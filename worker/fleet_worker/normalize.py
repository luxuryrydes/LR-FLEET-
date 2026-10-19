"""Registration-number and value normalisation.

Mirrors com.lrtaxi.fleet.calc.RegistrationNormalizer and EnergyType.parse; both are tested against
spec/registration-cases.json so the implementations cannot drift apart.
"""
from __future__ import annotations

import re
from decimal import Decimal, InvalidOperation

_NON_ALNUM = re.compile(r"[^A-Z0-9]")
_PLATE = re.compile(r"(?<!\d)(?:\d{2}BH\d{4}[A-Z]{1,2}|[A-Z]{2}\d{1,2}[A-Z]{0,3}\d{4}(?!\d))")


def normalize_registration(raw: object) -> str:
    """Uppercase and strip everything except A-Z and 0-9."""
    if raw is None:
        return ""
    return _NON_ALNUM.sub("", str(raw).upper())


def extract_plate(raw: object) -> str | None:
    """Extract an Indian registration plate from a GPS device name such as 'DL1RT1234 (CNG)'."""
    n = normalize_registration(raw)
    if not n:
        return None
    m = _PLATE.search(n)
    return m.group(0) if m else None


_ENERGY = {
    "PETROL": "PETROL", "MS": "PETROL", "GASOLINE": "PETROL", "PET": "PETROL",
    "DIESEL": "DIESEL", "HSD": "DIESEL", "DSL": "DIESEL",
    "CNG": "CNG", "GAS": "CNG",
    "ELECTRICITY": "ELECTRICITY", "ELECTRIC": "ELECTRICITY", "EV": "ELECTRICITY", "KWH": "ELECTRICITY",
    "POWER": "ELECTRICITY", "CHARGING": "ELECTRICITY",
}


def parse_energy(raw: object) -> str | None:
    if raw is None:
        return None
    return _ENERGY.get(re.sub(r"[^A-Z]", "", str(raw).upper()))


_NUM_CLEAN = re.compile(r"[,\s₹]|rs\.?|inr|kms?|ltrs?|litres?|liters?|kgs?|kwh", re.IGNORECASE)


def parse_decimal(raw: object) -> Decimal | None:
    """Parse '1,234.50', '₹ 950', '120 km', 85.2 into Decimal. Returns None for blanks; raises ValueError for junk."""
    if raw is None:
        return None
    if isinstance(raw, bool):
        raise ValueError(f"not a number: {raw!r}")
    if isinstance(raw, (int, Decimal)):
        return Decimal(raw)
    if isinstance(raw, float):
        if raw != raw:  # NaN
            return None
        # repr gives the shortest round-trip text, so 120.5 -> '120.5' (no binary noise)
        return Decimal(repr(raw))
    text = str(raw).strip()
    if text == "" or text.lower() in {"nan", "none", "null", "-", "na", "n/a"}:
        return None
    cleaned = _NUM_CLEAN.sub("", text)
    if cleaned.startswith("(") and cleaned.endswith(")"):
        cleaned = "-" + cleaned[1:-1]
    try:
        return Decimal(cleaned)
    except InvalidOperation as e:
        raise ValueError(f"not a number: {text!r}") from e


def decimal_text(d: Decimal | None) -> str | None:
    """Canonical decimal text for JSON (no exponent, no trailing zeros)."""
    if d is None:
        return None
    t = format(d.normalize(), "f")
    return "0" if t in {"-0", ""} else t


_TRUE = {"Y", "YES", "TRUE", "1", "FULL", "FULLTANK", "F"}
_FALSE = {"N", "NO", "FALSE", "0", "PARTIAL", "P"}


def parse_bool(raw: object) -> bool | None:
    if raw is None:
        return None
    if isinstance(raw, bool):
        return raw
    s = re.sub(r"[^A-Z0-9]", "", str(raw).upper())
    if s in _TRUE:
        return True
    if s in _FALSE:
        return False
    return None


_PAYMENT = {
    "CASH": "CASH", "CARD": "CARD", "DEBITCARD": "CARD", "CREDITCARD": "CARD", "UPI": "UPI", "GPAY": "UPI", "PHONEPE": "UPI",
    "PAYTM": "UPI", "FUELCARD": "FUEL_CARD", "FLEETCARD": "FUEL_CARD", "CREDIT": "CREDIT", "NEFT": "BANK_TRANSFER",
    "RTGS": "BANK_TRANSFER", "IMPS": "BANK_TRANSFER", "BANKTRANSFER": "BANK_TRANSFER",
}


def parse_payment(raw: object) -> str | None:
    if raw is None or str(raw).strip() == "":
        return None
    return _PAYMENT.get(re.sub(r"[^A-Z]", "", str(raw).upper()), "OTHER")
