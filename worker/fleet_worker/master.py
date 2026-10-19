"""Vehicle master rules for the import preview.

Mirrors com.lrtaxi.fleet.calc.MasterRules (the Java backend decides what is saved). Both are tested against
spec/master-cases.json.
"""
from __future__ import annotations

import re

_SPLIT = re.compile(r"[^A-Z0-9]+")


def _key(s: object) -> str:
    return _SPLIT.sub(" ", str(s or "").upper()).strip()


def derive(label: object, name: object) -> dict:
    k = _key(label)
    has_name = name is not None and str(name).strip() != ""

    def d(op, status, ownership="OWNED", tracked=False):
        return {"operatorType": op, "status": status, "ownership": ownership, "gpsKmTracked": tracked}

    if "SOLD" in k:
        return d("NONE", "SOLD")
    if "SCRAP" in k:
        return d("NONE", "SCRAPPED")
    if k.startswith("WORKSHOP") or "ACCIDENT" in k:
        return d("NONE", "WORKSHOP")
    active = "ACTIVE" if has_name else "IDLE"
    if k in ("DRIVER", "OWN DRIVER", "LR DRIVER"):
        return d("OWN_DRIVER", active, tracked=has_name)
    if k == "VENDOR":
        return d("VENDOR", active, "VENDOR_ATTACHED")
    if k in ("GOVT DRIVER", "GOVERNMENT DRIVER"):
        return d("GOVT_DRIVER", active)
    if k == "OFFICER":
        return d("OFFICER", "ACTIVE")
    if k == "PERSONAL":
        return d("PERSONAL", "ACTIVE")
    if k == "":
        return d("OWN_DRIVER", "ACTIVE", tracked=True) if has_name else d("NONE", "IDLE")
    return d("NONE", "IDLE")


_FUEL = {
    "CNGPETROL": "CNG_PETROL", "PETROLCNG": "CNG_PETROL", "BIFUEL": "CNG_PETROL",
    "ELECTRIC": "ELECTRIC", "ELECTRICAL": "ELECTRIC", "EV": "ELECTRIC", "BEV": "ELECTRIC",
    "DIESEL": "DIESEL", "PETROL": "PETROL",
    "HYBRIDPETROL": "HYBRID", "HYBRID": "HYBRID", "STRONGHYBRID": "HYBRID", "PETROLHYBRID": "HYBRID",
    "PLUGINHYBRID": "PLUGIN_HYBRID", "PHEV": "PLUGIN_HYBRID", "CNG": "CNG",
}


def fuel_type(raw: object) -> str | None:
    return _FUEL.get(_key(raw).replace(" ", ""))


_BRANDS = {"TATA": "Tata", "TOYOTA": "Toyota", "MAHINDRA": "Mahindra", "HONDA": "Honda", "HYUNDAI": "Hyundai", "KIA": "Kia",
           "MG": "MG", "MARUTI": "Maruti Suzuki", "SUZUKI": "Maruti Suzuki"}
_MODEL_MAKE = {
    **{m: "Maruti Suzuki" for m in ("CIAZ", "DZIRE", "ERTIGA", "XL6", "ALTO", "EECO", "GRAND", "INVICTO", "SWIFT", "BALENO", "WAGONR")},
    **{m: "Toyota" for m in ("INNOVA", "RUMION", "HYRYDER", "COROLLA", "FORTUNER", "ETIOS", "GLANZA")},
    **{m: "Mahindra" for m in ("BOLERO", "SCORPIO", "XUV", "THAR", "XEV")},
    **{m: "Tata" for m in ("TIGOR", "NEXON", "HARRIER", "PUNCH", "TIAGO")},
    **{m: "Hyundai" for m in ("VERNA", "CRETA", "AURA")},
    **{m: "Honda" for m in ("CITY", "AMAZE")},
    **{m: "Kia" for m in ("SELTOS", "CARENS", "SONET")},
}
_CATEGORY = [
    ("LCV", {"CAMPER", "PICKUP"}),
    ("SUV", {"NEXON", "HARRIER", "HYRYDER", "GRAND", "SELTOS", "FORTUNER", "SCORPIO", "XEV", "ZS", "XUV", "THAR", "CRETA", "SONET", "PUNCH"}),
    ("MUV", {"ERTIGA", "XL6", "RUMION", "INNOVA", "INVICTO", "CARENS", "BOLERO", "EECO"}),
    ("SEDAN", {"CIAZ", "DZIRE", "TIGOR", "ELECTRICAL", "VERNA", "CITY", "COROLLA", "AURA", "AMAZE", "ETIOS"}),
    ("HATCHBACK", {"ALTO", "SWIFT", "WAGONR", "BALENO", "TIAGO", "GLANZA"}),
]
_KEEP_UPPER = {"EV", "XM", "XZ", "XE", "XEV", "B4", "B6", "B8", "9E", "ZS", "XL6", "EECO", "MG", "XUV", "CNG", "AMT", "AT", "MT", "VX",
               "ZX", "GX", "VXI", "ZXI", "LXI"}


def _alnum(w: str) -> str:
    return re.sub(r"[^A-Z0-9]", "", w.upper())


def _word(w: str) -> str:
    u = w.upper()
    if _alnum(u) in _KEEP_UPPER or any(c.isdigit() for c in u):
        return u
    return u[0] + u[1:].lower()


def model_info(raw: object) -> dict:
    words = str(raw or "").split()
    if not words:
        return {"make": "Other", "model": "Unknown", "category": "OTHER"}
    make = None
    rest = words
    if words[0].upper() in _BRANDS and len(words) > 1:
        make = _BRANDS[words[0].upper()]
        rest = words[1:]
    if make is None:
        make = _MODEL_MAKE.get(_alnum(rest[0]), "Other")
    tokens = {_alnum(w) for w in rest}
    category = next((c for c, keys in _CATEGORY if tokens & keys), "OTHER")
    return {"make": make, "model": " ".join(_word(w) for w in rest), "category": category}
