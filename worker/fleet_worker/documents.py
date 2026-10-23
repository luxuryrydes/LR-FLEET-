"""Vehicle documents register (permit / pollution / insurance sheet): one row per vehicle with an end-date column per
document type. A workbook usually has master tabs ("Commercial cars", "Private cars") plus monthly to-do tabs with
repeated headers; every tab with a vehicle column and at least one end-date column is read, and for each vehicle and
document the latest end date wins (Java DocumentImportService decides what is saved)."""
from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date
from typing import Optional

from .dates import parse_datetime
from .excel import detect_header_row, headers_from, jsonable
from .mapping import SYNONYMS, header_key, suggest_mapping
from .normalize import extract_plate, normalize_registration
from .parsers import ParseResult, StagingRow, _iter_records, _text

# mapping field -> document type, in the order of the dashboard columns
DOC_FIELDS = {
    "insuranceExpiry": "INSURANCE", "ownDamageExpiry": "INSURANCE_OD", "pucExpiry": "PUC", "fitnessExpiry": "FITNESS",
    "roadTaxExpiry": "ROAD_TAX", "permitExpiry": "PERMIT", "permit1yExpiry": "PERMIT_1Y",
}
LABEL = {"INSURANCE": "Insurance", "INSURANCE_OD": "Own damage", "PUC": "Pollution", "FITNESS": "Fitness", "ROAD_TAX": "Road tax",
         "PERMIT": "Permit 5 years", "PERMIT_1Y": "Permit 1 year", "REGISTRATION": "Registration"}
_NOT_REQUIRED = {"NA", "NOTREQUIRED", "NOTAPPLICABLE", "NIL", "NILL", "NONE", "NO", "EXEMPT", "EXEMPTED"}
_NOT_REQUIRED_SYMBOLS = {"-", "--", "N/A", "N.A", "N.A."}
_LIFETIME = {"LTT", "LIFETIME", "LIFETIMETAX", "LIFETIMEROADTAX", "OTT", "ONETIMETAX"}
_REG_HEADERS = {k for k in SYNONYMS["DOCUMENTS"]["registration"]}


@dataclass
class DocValue:
    doc_type: str
    expiry: Optional[date] = None          # dated document
    issue: Optional[date] = None           # registration date
    not_applicable: bool = False
    note: Optional[str] = None

    def rank(self) -> tuple:
        """Higher is better: a dated document beats "not required"; a later end date beats an earlier one."""
        return (0, date.min) if self.not_applicable else (1, self.expiry or self.issue or date.min)

    def as_json(self) -> dict:
        out: dict = {"docType": self.doc_type}
        if self.expiry:
            out["expiryDate"] = self.expiry.isoformat()
        if self.issue:
            out["issueDate"] = self.issue.isoformat()
        if self.not_applicable:
            out["notApplicable"] = True
            out["note"] = self.note
        return out

    def text(self) -> str:
        if self.not_applicable:
            return self.note or "not required"
        d = self.expiry or self.issue
        return d.strftime("%d-%m-%Y") if d else "-"


def sheet_mapping(headers: list[str]) -> dict[str, str]:
    """Suggested mapping for a tab, or {} when it is not a documents table."""
    m = suggest_mapping(headers, "DOCUMENTS")
    if "registration" not in m or not any(f in m for f in [*DOC_FIELDS, "registrationDate"]):
        return {}
    return m


def registration_class(sheet: str, cell: object) -> Optional[str]:
    for text in (cell, sheet):
        k = re.sub(r"[^A-Z]", "", str(text or "").upper())
        if k.startswith("COMM") or "COMMERCIAL" in k:
            return "COMMERCIAL"
        if k.startswith("PRIV") or "PRIVATE" in k:
            return "PRIVATE"
    return None


def read_value(doc_type: str, raw: object, today: date) -> tuple[Optional[DocValue], Optional[str]]:
    """(value, warning). Blank -> (None, None)."""
    if raw is None or (isinstance(raw, str) and not raw.strip()):
        return None, None
    if isinstance(raw, str):
        k2 = re.sub(r"[^A-Z]", "", raw.upper())
        if raw.strip().upper() in _NOT_REQUIRED_SYMBOLS or k2 in _NOT_REQUIRED:
            return DocValue(doc_type, not_applicable=True, note="Not required" if "REQ" in k2 or "APPL" in k2 else "NA in register"), None
        if k2 in _LIFETIME:
            return DocValue(doc_type, not_applicable=True, note="Lifetime tax (LTT)"), None
    try:
        dt = parse_datetime(raw)
    except ValueError:
        return None, f"{LABEL[doc_type]} '{jsonable(raw)}' is not a date; ignored"
    if dt is None:
        return None, None
    d = dt.date()
    if d.year < 1990 or d.year > today.year + 30:
        return None, f"{LABEL[doc_type]} date {d:%d-%m-%Y} looks wrong; ignored"
    if doc_type == "REGISTRATION":
        return DocValue(doc_type, issue=d), None
    return DocValue(doc_type, expiry=d), None


def parse_documents(sheets: list[tuple[str, list[list[object]], int, dict]], index, today: date):
    """sheets = [(name, rows, header_row (1-based), mapping)]. Returns ParseResult with one staging row per source row."""
    multi = len(sheets) > 1
    out: list[StagingRow] = []
    values: list[tuple[StagingRow, str, dict[str, DocValue]]] = []
    best: dict[tuple[str, str], tuple[DocValue, StagingRow]] = {}
    for sheet, rows, header_row, mapping in sheets:
        for row_number, cells in _iter_records(rows, header_row, (mapping.get("registration"),)):
            raw_reg = cells.get(mapping["registration"])
            if header_key(raw_reg) in _REG_HEADERS:            # repeated header row inside a monthly tab
                continue
            row = StagingRow(row_number, {"Sheet": sheet, **{k: jsonable(v) for k, v in cells.items()}})
            where = f"{sheet} row {row_number}" if multi else f"row {row_number}"
            reg = normalize_registration(raw_reg)
            has_dates = any(cells.get(mapping.get(f)) not in (None, "") for f in [*DOC_FIELDS, "registrationDate"] if mapping.get(f))
            if not has_dates and extract_plate(reg) != reg:
                continue                                        # title line such as "April 2026 INSURANCE"
            if len(reg) < 4 or not re.search(r"\d", reg):
                row.error("MISSING_REGISTRATION", "registration", f"[{sheet}] vehicle number '{jsonable(raw_reg)}' is not valid", raw_reg)
                out.append(row)
                continue
            if extract_plate(reg) != reg:
                row.warn("UNUSUAL_REGISTRATION", "registration", f"'{raw_reg}' does not look like an Indian registration number; check it")
            vid, how = index.match(reg)
            if vid is None:
                row.error("VEHICLE_NOT_FOUND", "registration",
                          f"[{sheet}] {reg} is not in the vehicle master (or is sold); add it under Vehicles first", raw_reg)
                out.append(row)
                continue
            row.vehicle_id = vid
            row.group_key = reg
            docs: dict[str, DocValue] = {}
            fields = [*DOC_FIELDS.items(), ("registrationDate", "REGISTRATION")]
            for f, doc_type in fields:
                col = mapping.get(f)
                if not col:
                    continue
                v, warning = read_value(doc_type, cells.get(col), today)
                if warning:
                    row.warn("INVALID_DATE", f, warning)
                if v:
                    docs[doc_type] = v
            if not docs:
                row.status = "EXCLUDED"
                row.info("NO_DATES", "No end dates in this row")
                out.append(row)
                continue
            for doc_type, v in docs.items():
                key = (reg, doc_type)
                if key not in best or v.rank() > best[key][0].rank():
                    best[key] = (v, row)
            row.parsed = {"registration": reg, "registrationClass": registration_class(sheet, cells.get(mapping.get("registrationClass")))
                          if mapping.get("registrationClass") else registration_class(sheet, None),
                          "sheet": sheet, "remarks": _text(cells.get(mapping.get("remarks"))) if mapping.get("remarks") else None}
            values.append((row, where, docs))
            out.append(row)

    for row, where, docs in values:
        keep = []
        for doc_type, v in docs.items():
            winner, wrow = best[(row.parsed["registration"], doc_type)]
            if wrow is row:
                keep.append(v)
            else:
                row.info("SUPERSEDED", f"{LABEL[doc_type]} {v.text()} not used: {winner.text()} in "
                                       f"{wrow.raw.get('Sheet')} row {wrow.row_number}")
        row.parsed["documents"] = [v.as_json() for v in keep]
        if not keep and not row.rejected:
            row.status = "DUPLICATE"
        elif keep:
            row.info("DOCS_PREVIEW", ", ".join(f"{LABEL[v.doc_type]} {v.text()}" for v in keep))
    return ParseResult(out)


def documents_sheets(read_sheet, sheet_names: list[str], selected: str, header_row: int, mapping: dict, all_sheets: bool):
    """Collects (name, rows, header_row, mapping) for the selected tab and, with all_sheets, every other documents tab."""
    out = [(selected, read_sheet(selected), header_row, mapping)]
    if not all_sheets:
        return out
    for name in sheet_names:
        if name == selected:
            continue
        rows = read_sheet(name)
        if not rows:
            continue
        found = first_header(rows)
        if found:
            out.append((name, rows, found[0] + 1, found[1]))
    return out


def first_header(rows: list[list[object]], scan: int = 30) -> Optional[tuple[int, dict]]:
    """The first row that reads as a documents header. Monthly tabs repeat the header for every month block, sometimes
    with extra columns, so the best-scoring row can be a later block; the repeats below it are skipped while parsing."""
    for i, r in enumerate(rows[:scan]):
        m = sheet_mapping(headers_from(r))
        if m:
            return i, m
    h = detect_header_row(rows)
    m = sheet_mapping(headers_from(rows[h]))
    return (h, m) if m else None
