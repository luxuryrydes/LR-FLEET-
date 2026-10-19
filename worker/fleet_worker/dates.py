"""Date parsing for Indian spreadsheets: day-first always (DD-MM-YYYY), Excel serial numbers, ISO and text months."""
from __future__ import annotations

import re
from datetime import date, datetime, time, timedelta

_EXCEL_EPOCH = datetime(1899, 12, 30)

_FORMATS = (
    "%d-%m-%Y %H:%M:%S", "%d-%m-%Y %H:%M", "%d-%m-%Y",
    "%d/%m/%Y %H:%M:%S", "%d/%m/%Y %H:%M", "%d/%m/%Y",
    "%d.%m.%Y %H:%M:%S", "%d.%m.%Y",
    "%d-%m-%y", "%d/%m/%y",
    "%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S", "%Y-%m-%d %H:%M", "%Y-%m-%dT%H:%M", "%Y-%m-%d",
    "%d %b %Y %H:%M:%S", "%d %b %Y %H:%M", "%d %b %Y", "%d-%b-%Y %H:%M:%S", "%d-%b-%Y", "%d-%b-%y",
    "%d %B %Y", "%b %d, %Y", "%d/%m/%Y %I:%M:%S %p", "%d-%m-%Y %I:%M:%S %p", "%d/%m/%Y %I:%M %p", "%d-%m-%Y %I:%M %p",
)


def parse_datetime(raw: object) -> datetime | None:
    """Returns None for blanks; raises ValueError when the value cannot be read as a date."""
    if raw is None:
        return None
    if isinstance(raw, datetime):
        return raw.replace(tzinfo=None)
    if isinstance(raw, date):
        return datetime(raw.year, raw.month, raw.day)
    if isinstance(raw, bool):
        raise ValueError(f"not a date: {raw!r}")
    if isinstance(raw, (int, float)):
        if raw != raw:
            return None
        if 20000 < raw < 80000:  # Excel serial date (1954..2119)
            return _EXCEL_EPOCH + timedelta(days=float(raw))
        raise ValueError(f"not a date: {raw!r}")
    text = re.sub(r"\s+", " ", str(raw).strip())
    if text == "" or text.lower() in {"nan", "none", "null", "-", "nat"}:
        return None
    text = re.sub(r"(?i)\s*(ist|utc|gmt)$", "", text)
    text = re.sub(r"(?<=:\d\d)\.\d+$", "", text)  # drop fractional seconds (only after HH:MM:SS)
    if re.fullmatch(r"\d{5}(\.\d+)?", text):
        return parse_datetime(float(text))
    for fmt in _FORMATS:
        try:
            return datetime.strptime(text, fmt)
        except ValueError:
            continue
    raise ValueError(f"unrecognised date {text!r} (use DD-MM-YYYY)")


def parse_date(raw: object) -> date | None:
    dt = parse_datetime(raw)
    return dt.date() if dt else None


def parse_time(raw: object) -> time | None:
    if raw is None or (isinstance(raw, str) and raw.strip() == ""):
        return None
    if isinstance(raw, time):
        return raw.replace(microsecond=0)
    if isinstance(raw, datetime):
        return raw.time().replace(microsecond=0)
    if isinstance(raw, float) and 0 <= raw < 1:  # Excel time fraction
        secs = round(raw * 86400)
        return time(secs // 3600 % 24, secs // 60 % 60, secs % 60)
    text = str(raw).strip()
    for fmt in ("%H:%M:%S", "%H:%M", "%I:%M %p", "%I:%M:%S %p"):
        try:
            return datetime.strptime(text, fmt).time()
        except ValueError:
            continue
    raise ValueError(f"unrecognised time {text!r}")
