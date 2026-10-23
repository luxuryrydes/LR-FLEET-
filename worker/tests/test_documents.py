from datetime import date, datetime

from conftest import TODAY
from fleet_worker.documents import documents_sheets, first_header, parse_documents, read_value, sheet_mapping
from fleet_worker.excel import headers_from
from fleet_worker.parsers import VehicleIndex

# Same layout as the company's "Permit- pollution- Insurance" register: a title line, then the header.
COMMERCIAL = [
    ["COMMERCIAL CARS", None, None, None, None, None, None, None, None],
    ["S. No.", "Vehicle No", "Vehicle Type", "Registration Date", "Fitness End Date", "HR Road Tax End Date", "Insurance End Date",
     "Pullution End Date", "Permit 5 Years End Date", "Permit 1 Year End Date"],
    [1, "DL52GD0247", "Tata Electrical", datetime(2021, 9, 13), datetime(2028, 3, 22), "NA", datetime(2027, 9, 13), "NA", "Not Required",
     datetime(2026, 9, 28)],
    [2, "HR38AE6518", "XL6", datetime(2023, 1, 5), datetime(2027, 1, 5), "LTT", datetime(2026, 11, 2), "New Number", datetime(2028, 1, 4),
     datetime(2026, 12, 1)],
    [3, "DL1ZZ9999", "Dzire", None, datetime(2027, 1, 1), None, None, None, None, None],
]
# A monthly to-do tab: older dates, a repeated header block and a month title in between.
MONTHLY = [
    ["March 2026 INSURANCE", None, None],
    ["S.NO", "Vehical No", "INSURANCE END DATE"],
    [1, "DL52GD0247", datetime(2026, 3, 1)],
    [None, "April 2026 INSURANCE", None],
    ["S.NO", "Vehical No", "INSURANCE END DATE"],
    [1, "HR38AE6518", datetime(2027, 4, 2)],
]
INDEX = VehicleIndex(by_reg={"DL52GD0247": 1, "HR38AE6518": 2}, fuel_type={})


def _sheets():
    h, m = first_header(COMMERCIAL)
    return documents_sheets(lambda n: {"Commercial cars": COMMERCIAL, "INSURANCE": MONTHLY}[n], ["Commercial cars", "INSURANCE"],
                            "Commercial cars", h + 1, m, True)


def test_register_headers_map_automatically():
    m = sheet_mapping(headers_from(COMMERCIAL[1]))
    assert m == {"registration": "Vehicle No", "registrationDate": "Registration Date", "fitnessExpiry": "Fitness End Date",
                 "roadTaxExpiry": "HR Road Tax End Date", "insuranceExpiry": "Insurance End Date", "pucExpiry": "Pullution End Date",
                 "permitExpiry": "Permit 5 Years End Date", "permit1yExpiry": "Permit 1 Year End Date"}
    assert sheet_mapping(["S.No", "Vehicle No", "Driver"]) == {}          # not a documents table


def test_values_na_lifetime_and_bad_dates():
    assert read_value("PUC", "NA", TODAY)[0].not_applicable
    assert read_value("PERMIT", "Not Required", TODAY)[0].note == "Not required"
    assert read_value("ROAD_TAX", "LTT", TODAY)[0].note == "Lifetime tax (LTT)"
    v, w = read_value("PUC", "New Number", TODAY)
    assert v is None and "not a date" in w
    assert read_value("INSURANCE", "13-09-2027", TODAY)[0].expiry == date(2027, 9, 13)
    assert read_value("INSURANCE", None, TODAY) == (None, None)


def test_every_tab_is_read_and_the_latest_end_date_wins():
    res = parse_documents(_sheets(), INDEX, TODAY)
    rows = {(r.raw["Sheet"], r.row_number): r for r in res.rows}
    first = rows[("Commercial cars", 3)]
    docs = {d["docType"]: d for d in first.parsed["documents"]}
    assert docs["INSURANCE"] == {"docType": "INSURANCE", "expiryDate": "2027-09-13"}
    assert docs["PUC"]["notApplicable"] and docs["PERMIT"]["notApplicable"]
    assert docs["REGISTRATION"] == {"docType": "REGISTRATION", "issueDate": "2021-09-13"}
    assert first.parsed["registrationClass"] == "COMMERCIAL"
    # the March to-do date is older than the master tab -> not used
    assert rows[("INSURANCE", 3)].status == "DUPLICATE"
    # the April to-do date is newer than the master tab -> it wins there, and the master tab drops its insurance date
    assert rows[("INSURANCE", 6)].parsed["documents"] == [{"docType": "INSURANCE", "expiryDate": "2027-04-02"}]
    second = rows[("Commercial cars", 4)]
    assert "INSURANCE" not in {d["docType"] for d in second.parsed["documents"]}
    assert second.status == "WARNING"                                 # 'New Number' in the pollution column
    assert {d["docType"]: d.get("note") for d in second.parsed["documents"]}["ROAD_TAX"] == "Lifetime tax (LTT)"
    # vehicle not in the master is rejected; title lines and repeated headers are skipped
    assert rows[("Commercial cars", 5)].status == "REJECTED"
    assert [r.raw["Sheet"] for r in res.rows].count("INSURANCE") == 2


def test_selected_tab_only():
    h, m = first_header(COMMERCIAL)
    sheets = documents_sheets(lambda n: COMMERCIAL, ["Commercial cars", "INSURANCE"], "Commercial cars", h + 1, m, False)
    assert len(sheets) == 1
