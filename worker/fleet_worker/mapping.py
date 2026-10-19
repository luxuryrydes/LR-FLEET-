"""Column mapping: canonical target fields and the spreadsheet header spellings that suggest them."""
from __future__ import annotations

import re

GPS_FIELDS = ["vehicle", "date", "km", "tripStart"]
FUEL_FIELDS = ["vehicle", "date", "time", "energyType", "quantity", "unitPrice", "grossAmount", "discountAmount",
               "taxAmount", "invoiceAmount", "odometerKm", "isFullTank", "stationName", "invoiceNumber",
               "transactionRef", "paymentMethod", "notes"]
MASTER_FIELDS = ["registration", "model", "fuelType", "modelYear", "operatorLabel", "driverName", "driverPhone", "department",
                 "registrationClass", "make", "category", "chassisNumber", "engineNumber", "gpsDeviceName", "soldDate", "soldTo", "remarks"]

# Lower-case, alphanumeric-only header keys. Order matters: earlier = stronger match.
SYNONYMS: dict[str, dict[str, list[str]]] = {
    "GPS_KM": {
        "vehicle": ["devicename", "device", "vehicleno", "vehiclenumber", "vehicle", "registrationno", "registration", "regno",
                    "vehiclename", "carno", "asset", "assetname", "unitname"],
        "date": ["date", "starttime", "reportdate", "day", "tripdate", "startdate", "datetime"],
        "km": ["distancekms", "distancekm", "distance", "kms", "km", "totaldistance", "totalkm", "kmtravelled", "distancetravelled",
               "mileagekm", "odometerdistance"],
        "tripStart": ["starttime", "tripstart", "tripstarttime", "startdatetime"],
    },
    "FUEL": {
        "vehicle": ["vehicleno", "vehiclenumber", "vehicle", "registrationno", "regno", "carno"],
        "date": ["date", "requestdate", "transactiondate", "txndate", "filldate", "billdate", "invoicedate"],
        "time": ["time", "transactiontime", "txntime"],
        "energyType": ["fueltype", "fuel", "product", "energytype", "energy"],
        "quantity": ["quantity", "qty", "litres", "liters", "volume", "kg", "kwh", "units"],
        "unitPrice": ["rate", "price", "unitprice", "rateperunit", "priceperlitre", "rateperlitre"],
        "grossAmount": ["value", "grossamount", "gross", "amountbeforediscount"],
        "discountAmount": ["discount", "discountamount", "cashback"],
        "taxAmount": ["tax", "gst", "taxamount", "vat"],
        "invoiceAmount": ["cost", "amount", "totalamount", "invoiceamount", "netamount", "billamount", "total", "amountrs"],
        "odometerKm": ["odometer", "odmeter", "odometerreading", "odo", "odreading", "kmreading", "meterreading"],
        "isFullTank": ["fulltank", "tankfull", "full", "isfulltank"],
        "stationName": ["station", "fuelstation", "pump", "outlet", "supplier", "vendor", "dealer", "ro", "roname"],
        "invoiceNumber": ["invoiceno", "invoice", "invoicenumber", "billno", "billnumber", "receiptno"],
        "transactionRef": ["transactionno", "transactionid", "txnid", "txnno", "referenceno", "refno", "utr"],
        "paymentMethod": ["paymentmethod", "paymentmode", "payment", "mode", "paidby"],
        "notes": ["notes", "remarks", "comment", "comments", "narration"],
    },
    "VEHICLE_MASTER": {
        "registration": ["registerno", "registrationno", "registrationnumber", "vehicleno", "vehiclenumber", "regno", "registration",
                         "vehicle"],
        "model": ["vehicletype", "model", "carmodel", "makemodel", "modelname"],
        "fuelType": ["fueltype", "fuel"],
        "modelYear": ["vehiclemodel", "modelyear", "year", "mfgyear", "manufacturingyear", "yearofmanufacture"],
        "operatorLabel": ["vendordriver", "drivervendor", "drivertype", "operator", "assignment"],
        "driverName": ["vendordrivername", "drivervendorname", "drivername", "name", "vendorname"],
        "driverPhone": ["vendordrivercontact", "drivervendorcontact", "drivercontact", "contact", "mobile", "phone", "contactno",
                        "mobileno"],
        "department": ["department", "dept", "client", "customer"],
        "registrationClass": ["privatecommercial", "commercialprivate", "registrationtype", "permittype"],
        "make": ["make", "brand", "manufacturer"],
        "category": ["category", "vehiclecategory", "bodytype"],
        "chassisNumber": ["chassisno", "chassisnumber", "chassis"],
        "engineNumber": ["engineno", "enginenumber", "engine"],
        "gpsDeviceName": ["gpsdevicename", "gpsname", "devicename", "imei"],
        "soldDate": ["solddate", "dateofsale", "saledate"],
        "soldTo": ["soldto", "dealer", "buyer", "dealername"],
        "remarks": ["remarks", "notes", "comment"],
    },
}

ALL_KEYS = {k for fields in SYNONYMS.values() for syns in fields.values() for k in syns}


def header_key(h: object) -> str:
    return re.sub(r"[^a-z0-9]", "", str(h or "").lower())


def suggest_mapping(headers: list[str], import_type: str) -> dict[str, str]:
    """Best header for each field. A header is used for at most one field, except GPS 'Start Time'
    which may serve as both the date and the trip start timestamp."""
    keys = {h: header_key(h) for h in headers if h is not None and str(h).strip() != ""}
    result: dict[str, str] = {}
    used: set[str] = set()
    for field, synonyms in SYNONYMS[import_type].items():
        best = None
        best_rank = None
        for h, k in keys.items():
            if h in used and not (import_type == "GPS_KM" and field == "tripStart"):
                continue
            for rank, syn in enumerate(synonyms):
                if k == syn or (len(syn) >= 4 and k.startswith(syn)):
                    score = rank * 2 + (0 if k == syn else 1)
                    if best_rank is None or score < best_rank:
                        best, best_rank = h, score
                    break
        if best is not None:
            result[field] = best
            if field != "tripStart":
                used.add(best)
    # 'Value' with a separate 'Price' column: Value is the gross amount; with 'Cost' present, Cost is the invoice total.
    return result


def header_score(row: list[object]) -> int:
    """How much a row looks like a header row."""
    score = 0
    for cell in row:
        if cell is None:
            continue
        if isinstance(cell, str) and cell.strip():
            score += 1
            if header_key(cell) in ALL_KEYS:
                score += 3
    return score
