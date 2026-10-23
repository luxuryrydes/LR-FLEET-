from datetime import datetime

from conftest import TODAY
from fleet_worker.excel import detect_header_row, headers_from
from fleet_worker.mapping import suggest_mapping
from fleet_worker.parsers import VehicleIndex
from fleet_worker.service import parse_service, service_category

# Same layout as the company's VEHICLE SERVICE RECORD & MAINTENANCE TRACKER (summary block above the table).
SHEET = [
    ["VEHICLE SERVICE RECORD & MAINTENANCE TRACKER", None, None, None, None, None, None, None, None],
    ["Service history master register | Report Date: 08-Oct-2026", None, None, None, None, None, None, None, None],
    [None] * 9,
    ["TOTAL VEHICLES", None, "SERVICE DATES RECORDED", None, "SERVICE DATES MISSING", None, "REPORT DATE", None, None],
    [5, None, 2, None, 3, None, datetime(2026, 10, 8), None, None],
    [None] * 9,
    ["S.No.", "Registration No.", "Vehicle Type", "Model Year", "Last Service Odometer (KM)", "Service Date", "Days Since Service",
     "Service Record", "Next Service"],
    [1, "DL1CAH7243", "DZIRE", 2019, 178024, datetime(2026, 9, 10), 28, "RECORDED", 188024],
    [2, "HR51CS9447", "CIAZ", 2021, 132806, None, None, "DATE NOT RECORDED", 142806],
    [3, "DL1CAK4856", "DZIRE", 2022, None, None, None, "MISCELLANEOUS", 10000],
    [4, "CH - 4844", "INNOVA CRYSTA", 2026, 5000, datetime(2026, 12, 1), None, "RECORDED", 15000],
    [5, "DL9ZZ0001", "ALTO", 2019, 1000, datetime(2026, 1, 1), None, "RECORDED", 11000],
    [6, "DL1CAH7243", "DZIRE", 2019, 178024, datetime(2026, 9, 10), 28, "RECORDED", 188024],
]
INDEX = VehicleIndex(by_reg={"DL1CAH7243": 1, "HR51CS9447": 2, "DL1CAK4856": 3, "CH4844": 4}, fuel_type={})


def _mapping():
    h = detect_header_row(SHEET)
    return h + 1, suggest_mapping(headers_from(SHEET[h]), "SERVICE")


def test_register_headers_map_automatically():
    h, m = _mapping()
    assert h == 7
    assert m == {"registration": "Registration No.", "nextServiceOdometer": "Next Service", "serviceDate": "Service Date",
                 "odometerKm": "Last Service Odometer (KM)", "recordStatus": "Service Record"}


def test_rows_become_services_or_stay_without_record():
    h, m = _mapping()
    res = parse_service(SHEET, h, m, {}, INDEX, {}, TODAY)
    rows = {r.row_number: r for r in res.rows}
    assert rows[8].status == "VALID"
    assert rows[8].parsed["serviceDate"] == "2026-09-10" and rows[8].parsed["odometerKm"] == "178024"
    assert rows[8].parsed["nextServiceOdometer"] == "188024" and rows[8].parsed["category"] == "ROUTINE_SERVICE"
    assert rows[9].status == "WARNING" and rows[9].parsed["serviceDate"] is None          # date not recorded: km reminder only
    assert rows[10].status == "EXCLUDED" and "MISCELLANEOUS" in rows[10].messages[0]["message"]
    assert rows[11].status == "REJECTED"                                                   # future service date
    assert rows[12].status == "REJECTED"                                                   # not in the vehicle master
    assert rows[13].status == "DUPLICATE"                                                  # same service twice in the file


def test_category_from_text():
    assert service_category("Periodic service") == "ROUTINE_SERVICE"
    assert service_category("Engine OIL change") == "OIL_CHANGE"
    assert service_category("Front tyres") == "TYRES"
    assert service_category(None) == "ROUTINE_SERVICE"
