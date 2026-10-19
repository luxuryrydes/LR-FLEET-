"""Row-level parsing and pre-validation of GPS km and fuel spreadsheets into staging rows.

The Java backend re-validates every row at commit and is the only component that writes final records.
In-file duplicate handling here mirrors GpsDailyDedup so the preview shows what the commit will do.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime
from decimal import ROUND_HALF_UP, Decimal
from typing import Annotated, Literal, Optional

from pydantic import BaseModel, Field, ValidationError

from .dates import parse_datetime, parse_time
from .excel import headers_from, jsonable
from .normalize import (decimal_text, extract_plate, normalize_registration, parse_bool, parse_decimal, parse_energy,
                        parse_payment)

ALLOWED_ENERGY = {
    "PETROL": {"PETROL"}, "DIESEL": {"DIESEL"}, "CNG": {"CNG"}, "CNG_PETROL": {"CNG", "PETROL"},
    "ELECTRIC": {"ELECTRICITY"}, "HYBRID": {"PETROL"}, "PLUGIN_HYBRID": {"PETROL", "ELECTRICITY"},
}
UNIT = {"PETROL": "L", "DIESEL": "L", "CNG": "KG", "ELECTRICITY": "KWH"}
MIN_DATE = date(2000, 1, 1)
CENT = Decimal("0.01")


# ---------------------------------------------------------------------------------------------- vehicles

@dataclass
class VehicleIndex:
    by_reg: dict[str, int]
    fuel_type: dict[int, str]
    by_alias: dict[str, int] = field(default_factory=dict)
    ambiguous: set[str] = field(default_factory=set)

    def match(self, raw: object) -> tuple[Optional[int], Optional[str]]:
        """(vehicle_id, method) using: exact registration -> saved alias -> extracted plate. (None, reason) if unmatched."""
        norm = normalize_registration(raw)
        if not norm:
            return None, "blank"
        if norm in self.ambiguous:
            return None, "ambiguous"
        if norm in self.by_reg:
            return self.by_reg[norm], "registration"
        if norm in self.by_alias:
            return self.by_alias[norm], "alias"
        plate = extract_plate(raw)
        if plate and plate in self.by_reg and plate not in self.ambiguous:
            return self.by_reg[plate], "extracted"
        return None, "unmatched"


# ---------------------------------------------------------------------------------------------- models

class GpsRecord(BaseModel):
    vehicleRaw: str
    date: date
    km: Annotated[Decimal, Field(ge=0)]
    tripStart: Optional[str] = None


class FuelRecord(BaseModel):
    vehicleRaw: str
    date: date
    time: Optional[str] = None
    energyType: Literal["PETROL", "DIESEL", "CNG", "ELECTRICITY"]
    quantity: Annotated[Decimal, Field(gt=0)]
    unitPrice: Optional[Annotated[Decimal, Field(ge=0)]] = None
    grossAmount: Optional[Annotated[Decimal, Field(ge=0)]] = None
    discountAmount: Optional[Annotated[Decimal, Field(ge=0)]] = None
    taxAmount: Optional[Annotated[Decimal, Field(ge=0)]] = None
    invoiceAmount: Optional[Annotated[Decimal, Field(ge=0)]] = None
    odometerKm: Optional[Annotated[Decimal, Field(ge=0)]] = None
    isFullTank: Optional[bool] = None
    stationName: Optional[str] = None
    invoiceNumber: Optional[str] = None
    transactionRef: Optional[str] = None
    paymentMethod: Optional[str] = None
    notes: Optional[str] = None


def to_json(model: BaseModel) -> dict:
    out = {}
    for k, v in model.model_dump().items():
        if isinstance(v, Decimal):
            out[k] = decimal_text(v)
        elif isinstance(v, date):
            out[k] = v.isoformat()
        else:
            out[k] = v
    return out


# ---------------------------------------------------------------------------------------------- staging rows

@dataclass
class StagingRow:
    row_number: int                      # 1-based spreadsheet row
    raw: dict
    parsed: Optional[dict] = None
    vehicle_id: Optional[int] = None
    record_date: Optional[date] = None
    status: str = "VALID"                # VALID | WARNING | REJECTED | DUPLICATE
    group_key: Optional[str] = None
    messages: list[dict] = field(default_factory=list)

    def error(self, code: str, field_name: Optional[str], message: str, raw_value: object = None) -> None:
        self.status = "REJECTED"
        self.messages.append({"level": "error", "code": code, "field": field_name, "message": message,
                              "rawValue": None if raw_value is None else str(jsonable(raw_value))[:500]})

    def warn(self, code: str, field_name: Optional[str], message: str) -> None:
        if self.status == "VALID":
            self.status = "WARNING"
        self.messages.append({"level": "warning", "code": code, "field": field_name, "message": message})

    def info(self, code: str, message: str) -> None:
        self.messages.append({"level": "info", "code": code, "field": None, "message": message})

    @property
    def rejected(self) -> bool:
        return self.status == "REJECTED"

    def errors(self) -> list[dict]:
        return [m for m in self.messages if m["level"] == "error"]


@dataclass
class ParseResult:
    rows: list[StagingRow]

    def counts(self) -> dict[str, int]:
        c = {"total": len(self.rows), "VALID": 0, "WARNING": 0, "REJECTED": 0, "DUPLICATE": 0}
        for r in self.rows:
            c[r.status] += 1
        return c


def _cell(values: dict, mapping: dict, f: str) -> object:
    col = mapping.get(f)
    return values.get(col) if col else None


def _iter_records(rows: list[list[object]], header_row: int):
    """Yields (row_number, {header: value}) for non-empty data rows below the header row (1-based)."""
    if not rows or header_row < 1 or header_row > len(rows):
        return
    headers = headers_from(rows[header_row - 1])
    for i in range(header_row, len(rows)):
        r = rows[i]
        if all(c is None for c in r):
            continue
        yield i + 1, dict(zip(headers, r))


def _resolve_vehicle(row: StagingRow, raw_vehicle: object, index: VehicleIndex) -> Optional[int]:
    if raw_vehicle is None or str(raw_vehicle).strip() == "":
        row.error("MISSING_VEHICLE", "vehicle", "Vehicle number is blank")
        return None
    vid, method = index.match(raw_vehicle)
    if vid is None:
        msg = (f"Vehicle '{raw_vehicle}' matches more than one vehicle record" if method == "ambiguous"
               else f"Vehicle '{raw_vehicle}' is not in the vehicle register; add it or save the GPS name as an alias")
        row.error("UNMATCHED_VEHICLE", "vehicle", msg, raw_vehicle)
        return None
    if method == "extracted":
        row.info("VEHICLE_MATCHED_BY_PLATE", f"Matched '{raw_vehicle}' by its registration plate")
    elif method == "alias":
        row.info("VEHICLE_MATCHED_BY_ALIAS", f"Matched '{raw_vehicle}' using a saved GPS name")
    return vid


def _resolve_date(row: StagingRow, raw: object, today: date) -> Optional[datetime]:
    try:
        dt = parse_datetime(raw)
    except ValueError as e:
        row.error("INVALID_DATE", "date", str(e), raw)
        return None
    if dt is None:
        row.error("BLANK_DATE", "date", "Date is blank")
        return None
    if dt.date() > today:
        row.error("FUTURE_DATE", "date", f"Date {dt.date():%d-%m-%Y} is in the future", raw)
        return None
    if dt.date() < MIN_DATE:
        row.error("INVALID_DATE", "date", f"Date {dt.date():%d-%m-%Y} is too old", raw)
        return None
    return dt


def _decimal(row: StagingRow, raw: object, field_name: str) -> Optional[Decimal]:
    try:
        return parse_decimal(raw)
    except ValueError as e:
        row.error("INVALID_NUMBER", field_name, str(e), raw)
        return None


def _text(v: object) -> Optional[str]:
    if v is None:
        return None
    if isinstance(v, float) and v.is_integer():
        v = int(v)
    s = str(v).strip()
    return s[:200] if s else None


# ---------------------------------------------------------------------------------------------- GPS

def parse_gps(rows: list[list[object]], header_row: int, mapping: dict, options: dict, index: VehicleIndex,
              settings: dict, today: date) -> ParseResult:
    mode = options.get("recordMode", "DAILY_TOTAL")
    max_km = Decimal(str(settings.get("gps.max_daily_km", "1500")))
    out: list[StagingRow] = []
    for row_number, values in _iter_records(rows, header_row):
        row = StagingRow(row_number, {k: jsonable(v) for k, v in values.items()})
        raw_vehicle = _cell(values, mapping, "vehicle")
        vid = _resolve_vehicle(row, raw_vehicle, index)
        dt = _resolve_date(row, _cell(values, mapping, "date"), today)
        km_raw = _cell(values, mapping, "km")
        km = _decimal(row, km_raw, "km")
        if km is None and not row.rejected:
            row.error("MISSING_KM", "km", "Distance is blank (missing data is not treated as zero)")
        elif km is not None:
            if km < 0:
                row.error("NEGATIVE_KM", "km", f"Distance {km} km is negative", km_raw)
            elif km > max_km:
                row.error("KM_ABOVE_LIMIT", "km", f"Distance {km} km exceeds the daily limit of {max_km} km", km_raw)
            km = km.quantize(CENT, rounding=ROUND_HALF_UP)

        trip_start = None
        if mode == "TRIP" and not row.rejected:
            raw_start = _cell(values, mapping, "tripStart") if mapping.get("tripStart") else _cell(values, mapping, "date")
            try:
                ts = parse_datetime(raw_start)
            except ValueError as e:
                ts = None
                row.error("INVALID_TRIP_START", "tripStart", str(e), raw_start)
            if ts is None and not row.rejected:
                row.error("MISSING_TRIP_START", "tripStart", "Trip files need a trip start timestamp")
            elif ts is not None:
                trip_start = ts.isoformat(timespec="seconds")

        row.vehicle_id = vid
        if dt is not None:
            row.record_date = dt.date()
        if not row.rejected:
            try:
                rec = GpsRecord(vehicleRaw=str(raw_vehicle).strip(), date=dt.date(), km=km, tripStart=trip_start)
                row.parsed = to_json(rec)
            except ValidationError as e:
                row.error("INVALID_ROW", None, e.errors()[0]["msg"])
        if row.parsed is None and row.rejected:
            row.parsed = None
        out.append(row)
    _dedupe_gps(out, mode)
    return ParseResult(out)


def _dedupe_gps(rows: list[StagingRow], mode: str) -> None:
    groups: dict[tuple, list[StagingRow]] = {}
    for r in rows:
        if r.rejected or r.parsed is None:
            continue
        key = (r.vehicle_id, r.record_date)
        r.group_key = f"{r.vehicle_id}|{r.record_date}"
        groups.setdefault(key, []).append(r)
    for group in groups.values():
        if len(group) < 2:
            continue
        if mode == "TRIP":
            seen: dict[str, StagingRow] = {}
            for r in group:
                identity = f"{r.parsed['tripStart']}|{r.parsed['km']}"
                if identity in seen:
                    r.status = "DUPLICATE"
                    r.info("DUPLICATE_TRIP", f"Same trip as row {seen[identity].row_number}; it will not be added twice")
                else:
                    seen[identity] = r
            continue
        values = {Decimal(r.parsed["km"]) for r in group}
        if len(values) == 1:
            first = group[0]
            for r in group[1:]:
                r.status = "DUPLICATE"
                r.info("DUPLICATE_DAILY_TOTAL", f"Repeated copy of the daily total in row {first.row_number}; it will not be summed")
        else:
            rows_txt = ", ".join(str(r.row_number) for r in group)
            for r in group:
                r.warn("DAILY_TOTAL_CONFLICT", "km",
                       f"Rows {rows_txt} give different daily totals for the same vehicle and date; none of them will be imported")


# ---------------------------------------------------------------------------------------------- fuel

def parse_fuel(rows: list[list[object]], header_row: int, mapping: dict, options: dict, index: VehicleIndex,
               settings: dict, today: date) -> ParseResult:
    tolerance = Decimal(str(settings.get("fuel.amount_tolerance", "1.00")))
    out: list[StagingRow] = []
    for row_number, values in _iter_records(rows, header_row):
        row = StagingRow(row_number, {k: jsonable(v) for k, v in values.items()})
        raw_vehicle = _cell(values, mapping, "vehicle")
        vid = _resolve_vehicle(row, raw_vehicle, index)
        dt = _resolve_date(row, _cell(values, mapping, "date"), today)
        txn_time = None
        if mapping.get("time"):
            try:
                t = parse_time(_cell(values, mapping, "time"))
                txn_time = t.strftime("%H:%M:%S") if t else None
            except ValueError as e:
                row.warn("INVALID_TIME", "time", f"{e}; time ignored")
        elif dt is not None and (dt.hour or dt.minute or dt.second):
            txn_time = dt.strftime("%H:%M:%S")

        raw_energy = _cell(values, mapping, "energyType")
        energy = parse_energy(raw_energy)
        vehicle_fuel = index.fuel_type.get(vid) if vid else None
        if energy is None:
            allowed = ALLOWED_ENERGY.get(vehicle_fuel or "", set())
            if raw_energy is None and len(allowed) == 1:
                energy = next(iter(allowed))
                row.warn("ENERGY_INFERRED", "energyType", f"Fuel type blank; {energy} assumed from the vehicle register")
            else:
                row.error("INVALID_FUEL_TYPE", "energyType", f"Unknown fuel type '{raw_energy}'", raw_energy)
        elif vehicle_fuel and energy not in ALLOWED_ENERGY.get(vehicle_fuel, set()):
            row.error("INCOMPATIBLE_FUEL", "energyType", f"{energy} is not valid for a {vehicle_fuel} vehicle", raw_energy)

        qty = _decimal(row, _cell(values, mapping, "quantity"), "quantity")
        if qty is None and not any(m["field"] == "quantity" for m in row.errors()):
            row.error("MISSING_QUANTITY", "quantity", "Quantity is blank")
        elif qty is not None and qty <= 0:
            row.error("INVALID_QUANTITY", "quantity", f"Quantity {qty} must be greater than zero", qty)
        elif qty is not None and energy:
            limit = Decimal(str(settings.get(f"fuel.max_quantity.{energy}", "1000")))
            if qty > limit:
                row.warn("QUANTITY_ABOVE_LIMIT", "quantity", f"{qty} {UNIT[energy]} is above the configured limit of {limit}")

        amounts = {}
        for f in ("unitPrice", "grossAmount", "discountAmount", "taxAmount", "invoiceAmount", "odometerKm"):
            v = _decimal(row, _cell(values, mapping, f), f)
            if v is not None and v < 0:
                row.error("NEGATIVE_VALUE", f, f"{f} cannot be negative", v)
            amounts[f] = v
        if amounts["invoiceAmount"] is None and amounts["grossAmount"] is None and amounts["unitPrice"] is None:
            row.error("NO_AMOUNT", "invoiceAmount", "No amount: provide invoice amount, value or rate")

        if not row.rejected and qty is not None and amounts["unitPrice"] is not None:
            calc = (qty * amounts["unitPrice"]).quantize(CENT, rounding=ROUND_HALF_UP)
            disc = amounts["discountAmount"] or Decimal(0)
            tax = amounts["taxAmount"] or Decimal(0)
            for name, ref in (("invoice amount", amounts["invoiceAmount"]), ("value", amounts["grossAmount"])):
                if ref is None:
                    continue
                expected = calc - disc + tax if name == "invoice amount" else calc
                if abs(ref - expected) > tolerance:
                    row.warn("AMOUNT_MISMATCH", "invoiceAmount",
                             f"{name.capitalize()} ₹{ref} differs from quantity × rate (₹{expected}); the {name} will be kept")
                    break

        row.vehicle_id = vid
        if dt is not None:
            row.record_date = dt.date()
        if not row.rejected:
            try:
                rec = FuelRecord(
                    vehicleRaw=str(raw_vehicle).strip(), date=dt.date(), time=txn_time, energyType=energy, quantity=qty,
                    unitPrice=amounts["unitPrice"], grossAmount=amounts["grossAmount"], discountAmount=amounts["discountAmount"],
                    taxAmount=amounts["taxAmount"], invoiceAmount=amounts["invoiceAmount"], odometerKm=amounts["odometerKm"],
                    isFullTank=parse_bool(_cell(values, mapping, "isFullTank")),
                    stationName=_text(_cell(values, mapping, "stationName")),
                    invoiceNumber=_text(_cell(values, mapping, "invoiceNumber")),
                    transactionRef=_text(_cell(values, mapping, "transactionRef")),
                    paymentMethod=parse_payment(_cell(values, mapping, "paymentMethod")),
                    notes=_text(_cell(values, mapping, "notes")))
                row.parsed = to_json(rec)
            except ValidationError as e:
                err = e.errors()[0]
                row.error("INVALID_ROW", ".".join(str(x) for x in err.get("loc", [])) or None, err["msg"])
        out.append(row)
    _dedupe_fuel(out)
    return ParseResult(out)


def _fuel_amount_key(p: dict) -> str:
    if p.get("invoiceAmount") is not None:
        return p["invoiceAmount"]
    return f"{p.get('grossAmount')}|{p.get('unitPrice')}|{p.get('discountAmount')}"


def _dedupe_fuel(rows: list[StagingRow]) -> None:
    by_key: dict[str, StagingRow] = {}
    by_ref: dict[str, StagingRow] = {}
    by_invoice: dict[str, StagingRow] = {}
    for r in rows:
        if r.rejected or r.parsed is None:
            continue
        p = r.parsed
        key = "|".join(str(x) for x in (r.vehicle_id, p["date"], p["energyType"], p["quantity"], _fuel_amount_key(p),
                                         (p.get("invoiceNumber") or "").upper()))
        r.group_key = key[:200]
        if key in by_key:
            r.status = "DUPLICATE"
            r.info("DUPLICATE_TRANSACTION", f"Same transaction as row {by_key[key].row_number}; it will be saved once")
            continue
        by_key[key] = r
        ref = (p.get("transactionRef") or "").upper()
        if ref:
            if ref in by_ref:
                r.error("DUPLICATE_TRANSACTION_REF", "transactionRef",
                        f"Transaction number {p['transactionRef']} already used in row {by_ref[ref].row_number}", p["transactionRef"])
                continue
            by_ref[ref] = r
        inv = (p.get("invoiceNumber") or "").upper()
        if inv:
            if inv in by_invoice:
                r.warn("REPEATED_INVOICE", "invoiceNumber",
                       f"Invoice {p['invoiceNumber']} also appears in row {by_invoice[inv].row_number} with different details; "
                       "it will be rejected at import for review")
            else:
                by_invoice[inv] = r
