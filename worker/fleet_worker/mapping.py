"""Column mapping: canonical target fields and the spreadsheet header spellings that suggest them."""
from __future__ import annotations

import re

GPS_FIELDS = ["vehicle", "date", "km", "tripStart"]
FUEL_FIELDS = ["vehicle", "date", "time", "energyType", "quantity", "unitPrice", "grossAmount", "discountAmount",
               "taxAmount", "invoiceAmount", "odometerKm", "isFullTank", "stationName", "invoiceNumber",
               "transactionRef", "paymentMethod", "cardNumber", "notes"]
MASTER_FIELDS = ["registration", "model", "fuelType", "modelYear", "operatorLabel", "driverName", "driverPhone", "department",
                 "registrationClass", "make", "category", "chassisNumber", "engineNumber", "gpsDeviceName", "soldDate", "soldTo", "remarks"]
SERVICE_FIELDS = ["registration", "serviceDate", "odometerKm", "nextServiceOdometer", "nextServiceDate", "recordStatus", "category",
                  "description", "workshopName", "totalCost", "invoiceNumber", "remarks"]
DOCUMENT_FIELDS = ["registration", "insuranceExpiry", "ownDamageExpiry", "pucExpiry", "fitnessExpiry", "roadTaxExpiry", "permitExpiry",
                   "permit1yExpiry", "registrationDate", "registrationClass", "remarks"]

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
        "date": ["date", "transactiondate", "txndate", "filldate", "requestdate", "billdate", "invoicedate"],
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
        "transactionRef": ["transactionno", "transactionid", "txnid", "txnno", "slipno", "reqnoslipno", "referenceno", "refno", "utr"],
        "paymentMethod": ["paymentmethod", "paymentmode", "payment", "paidby"],
        "cardNumber": ["cardnumber", "cardno", "fuelcard", "fleetcard", "smartcard"],
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
    # Next service date before next service odometer: "Next Service Date" must not be taken as the km column.
    "SERVICE": {
        "registration": ["registrationno", "registrationnumber", "vehicleno", "vehicalno", "vehiclenumber", "regno", "registerno", "carno"],
        "nextServiceDate": ["nextservicedate", "nextserviceduedate", "nextduedate"],
        "nextServiceOdometer": ["nextservicekm", "nextserviceodometer", "nextserviceat", "nextservicedue", "nextservice", "nextdue"],
        "serviceDate": ["servicedate", "lastservicedate", "dateofservice", "date"],
        "odometerKm": ["lastserviceodometerkm", "lastserviceodometer", "lastservicekm", "serviceodometer", "odometerkm", "odometer",
                       "kmreading", "odoreading", "kms", "km"],
        "recordStatus": ["servicerecord", "recordstatus", "servicestatus"],
        "category": ["servicetype", "typeofservice", "category", "worktype"],
        "description": ["description", "workdone", "jobdescription", "work"],
        "workshopName": ["workshop", "workshopname", "servicecenter", "servicecentre", "garage", "dealer"],
        "totalCost": ["totalcost", "amount", "cost", "billamount", "invoiceamount"],
        "invoiceNumber": ["invoiceno", "billno", "jobcardno", "jobcard"],
        "remarks": ["remarks", "remark", "notes"],
    },
    # Field order matters: specific columns (registration date, permit 1 year) claim their header before the general ones.
    "DOCUMENTS": {
        "registration": ["vehicleno", "vehicalno", "vehiclenumber", "vehicalnumber", "registrationno", "registrationnumber", "regno",
                         "carno"],
        "registrationDate": ["registrationdate", "regdate", "dateofregistration", "rcdate"],
        "permit1yExpiry": ["permit1yearenddate", "permit1yenddate", "permit1year", "permit1yr", "authorisationenddate",
                           "authorizationenddate", "authorisation", "authorization"],
        "permitExpiry": ["permit5yearsenddate", "permit5yearenddate", "permit5years", "permit5year", "permitenddate", "permitexpiry",
                         "permitvalidity", "permit"],
        "ownDamageExpiry": ["owndamageenddate", "owndamage", "odenddate", "odexpiry", "odinsurance"],
        "insuranceExpiry": ["insuranceenddate", "insuranceexpiry", "insurancevalidity", "insurancedate", "insurance"],
        "pucExpiry": ["pollutionenddate", "pullutionenddate", "pucenddate", "pucexpiry", "pollutionexpiry", "pollution", "pullution", "puc"],
        "fitnessExpiry": ["fitnessenddate", "fitnessexpiry", "fitnessvalidity", "fitness"],
        "roadTaxExpiry": ["hrroadtaxenddate", "roadtaxenddate", "hrtaxenddate", "taxenddate", "roadtaxexpiry", "roadtax", "hrtax"],
        "registrationClass": ["commercialorprivate", "privateorcommercial", "privatecommercial", "commercialprivate", "registrationtype"],
        "remarks": ["remarks", "remark", "notes"],
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
