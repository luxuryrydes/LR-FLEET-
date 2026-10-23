"""Vehicle service register: one row per vehicle with the last service odometer, its date and the next service
(e.g. VEHICLE SERVICE RECORD & MAINTENANCE TRACKER). Java ServiceImportService decides what is saved."""
from __future__ import annotations

import re
from datetime import date
from decimal import Decimal
from typing import Optional

from .dates import parse_datetime
from .excel import jsonable
from .normalize import decimal_text, extract_plate, normalize_registration, parse_decimal
from .parsers import ParseResult, StagingRow, _cell, _iter_records, _text

MAX_ODOMETER = Decimal("2000000")
# "Service Record" column values meaning there is no service on record yet.
_NO_RECORD = {"MISCELLANEOUS", "NOTRECORDED", "NORECORD", "NA", "NIL", "NONE", "NEW", "NEWVEHICLE", "PENDING"}
_CATEGORIES = [
    (r"\bOIL\b", "OIL_CHANGE"), (r"TYRE|TIRE", "TYRES"), (r"BRAKE", "BRAKES"), (r"BATTER", "BATTERY"), (r"ACCIDENT", "ACCIDENT"),
    (r"BODY|PAINT|DENT", "BODY_WORK"), (r"FILTER", "FILTERS"), (r"\bEV\b", "EV_PERIODIC_SERVICE"), (r"REPAIR", "REPAIR"),
]


def service_category(raw: object) -> str:
    t = str(raw or "").upper()
    for pattern, code in _CATEGORIES:
        if re.search(pattern, t):
            return code
    return "ROUTINE_SERVICE"


def _odometer(row: StagingRow, field: str, raw: object, label: str) -> Optional[Decimal]:
    if raw is None or (isinstance(raw, str) and not raw.strip()):
        return None
    try:
        v = parse_decimal(raw)
    except (ValueError, ArithmeticError):
        v = None
    if v is None or v < 0 or v > MAX_ODOMETER:
        row.warn("INVALID_ODOMETER", field, f"{label} '{jsonable(raw)}' is not a valid odometer reading; ignored")
        return None
    return v


def parse_service(rows: list[list[object]], header_row: int, mapping: dict, options: dict, index, settings: dict,
                  today: date) -> ParseResult:
    interval = Decimal(str(settings.get("service.default_interval_km", "10000")))
    out: list[StagingRow] = []
    seen: dict[tuple, StagingRow] = {}
    for row_number, values in _iter_records(rows, header_row, (mapping.get("registration"),)):
        row = StagingRow(row_number, {k: jsonable(v) for k, v in values.items()})
        raw_reg = _cell(values, mapping, "registration")
        reg = normalize_registration(raw_reg)
        if len(reg) < 4 or not re.search(r"\d", reg):
            row.error("MISSING_REGISTRATION", "registration", f"Vehicle number '{jsonable(raw_reg)}' is not valid", raw_reg)
            out.append(row)
            continue
        if extract_plate(reg) != reg:
            row.warn("UNUSUAL_REGISTRATION", "registration", f"'{raw_reg}' does not look like an Indian registration number; check it")
        vid, _ = index.match(reg)
        if vid is None:
            row.error("VEHICLE_NOT_FOUND", "registration", f"{reg} is not in the vehicle master (or is sold); add it under Vehicles first", raw_reg)
            out.append(row)
            continue
        row.vehicle_id = vid
        row.group_key = reg

        service_date = None
        raw_date = _cell(values, mapping, "serviceDate")
        try:
            dt = parse_datetime(raw_date)
            service_date = dt.date() if dt else None
        except ValueError:
            row.warn("INVALID_DATE", "serviceDate", f"Service date '{jsonable(raw_date)}' is not a date; ignored")
        if service_date and service_date > today:
            row.error("FUTURE_DATE", "serviceDate", f"Service date {service_date:%d-%m-%Y} is in the future", raw_date)
        if service_date and service_date.year < 2000:
            row.warn("INVALID_DATE", "serviceDate", f"Service date {service_date:%d-%m-%Y} looks wrong; ignored")
            service_date = None
        odo = _odometer(row, "odometerKm", _cell(values, mapping, "odometerKm"), "Service odometer")
        next_odo = _odometer(row, "nextServiceOdometer", _cell(values, mapping, "nextServiceOdometer"), "Next service")
        next_date = None
        if mapping.get("nextServiceDate"):
            try:
                nd = parse_datetime(_cell(values, mapping, "nextServiceDate"))
                next_date = nd.date() if nd else None
            except ValueError:
                row.warn("INVALID_DATE", "nextServiceDate", "Next service date is not a date; ignored")
        record = re.sub(r"[^A-Z]", "", str(_cell(values, mapping, "recordStatus") or "").upper())

        if row.rejected:
            out.append(row)
            continue
        if odo is None and service_date is None:
            row.status = "EXCLUDED"
            why = "marked " + str(_cell(values, mapping, "recordStatus")).strip() if record in _NO_RECORD else "no service date or odometer"
            row.info("NO_SERVICE", f"No service on record ({why}); the vehicle stays under 'No service record'")
            out.append(row)
            continue
        if odo is not None and next_odo is not None and next_odo <= odo:
            row.warn("INVALID_NEXT", "nextServiceOdometer", f"Next service {decimal_text(next_odo)} km is not above the last service odometer; ignored")
            next_odo = None
        if service_date is None:
            row.warn("DATE_NOT_RECORDED", "serviceDate", "Service date not recorded: the km-based reminder is kept, no date-based reminder")
        if odo is None:
            row.warn("NO_ODOMETER", "odometerKm", "No service odometer: only the date-based reminder applies")

        key = (reg, service_date, odo)
        if key in seen:
            row.status = "DUPLICATE"
            row.info("DUPLICATE", f"Same service as row {seen[key].row_number}")
            out.append(row)
            continue
        seen[key] = row
        cost = None
        if mapping.get("totalCost"):
            try:
                cost = parse_decimal(_cell(values, mapping, "totalCost"))
            except (ValueError, ArithmeticError):
                row.warn("INVALID_AMOUNT", "totalCost", "Service cost is not a number; ignored")
        row.record_date = service_date
        row.parsed = {
            "registration": reg, "serviceDate": service_date.isoformat() if service_date else None,
            "odometerKm": decimal_text(odo) if odo is not None else None,
            "nextServiceOdometer": decimal_text(next_odo) if next_odo is not None else None,
            "nextServiceDate": next_date.isoformat() if next_date else None,
            "category": service_category(_cell(values, mapping, "category")) if mapping.get("category") else "ROUTINE_SERVICE",
            "description": _text(_cell(values, mapping, "description")) if mapping.get("description") else None,
            "workshopName": _text(_cell(values, mapping, "workshopName")) if mapping.get("workshopName") else None,
            "totalCost": decimal_text(cost) if cost is not None and cost >= 0 else None,
            "invoiceNumber": _text(_cell(values, mapping, "invoiceNumber")) if mapping.get("invoiceNumber") else None,
            "remarks": _text(_cell(values, mapping, "remarks")) if mapping.get("remarks") else None,
        }
        nxt = next_odo if next_odo is not None else (odo + interval if odo is not None else None)
        row.info("SERVICE_PREVIEW", ", ".join(filter(None, [
            f"Service {service_date:%d-%m-%Y}" if service_date else "Service (date not recorded)",
            f"at {decimal_text(odo)} km" if odo is not None else None,
            f"next at {decimal_text(nxt)} km" if nxt is not None else None])))
        out.append(row)
    return ParseResult(out)
