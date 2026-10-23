import json

import pytest

from conftest import SETTINGS, SPEC, TODAY
from fleet_worker.excel import headers_from
from fleet_worker.mapping import suggest_mapping
from fleet_worker.master import derive, fuel_type, model_info
from fleet_worker.parsers import VehicleIndex, parse_gps, parse_master

CASES = json.loads((SPEC / "master-cases.json").read_text(encoding="utf-8"))


@pytest.mark.parametrize("c", CASES["operator"], ids=lambda c: f"{c['label']}/{c['name']}")
def test_operator_rules_match_java(c):
    d = derive(c["label"], c["name"])
    assert d == {k: c[k] for k in ("operatorType", "status", "ownership", "gpsKmTracked")}


@pytest.mark.parametrize("c", CASES["fuel"], ids=lambda c: c["raw"])
def test_fuel_rules_match_java(c):
    assert fuel_type(c["raw"]) == c["fuelType"]


@pytest.mark.parametrize("c", CASES["model"], ids=lambda c: c["raw"])
def test_model_rules_match_java(c):
    assert model_info(c["raw"]) == {"make": c["make"], "model": c["model"], "category": c["category"]}


# Same header layout as the company's NEW MASTER sheet (including line breaks inside header cells).
HEAD = ["S.no", "Register No.", "Vehicle Type", "Fuel Type", "Vehicle Model", "Vendor / Driver", "Vendor / Driver\nName",
        "Vendor / Driver \nContact", "Department", "Private/Commercial"]


def test_company_sheet_headers_map_automatically():
    m = suggest_mapping(headers_from(HEAD), "VEHICLE_MASTER")
    assert m == {"registration": "Register No.", "model": "Vehicle Type", "fuelType": "Fuel Type", "modelYear": "Vehicle Model",
                 "operatorLabel": "Vendor / Driver", "driverName": "Vendor / Driver Name", "driverPhone": "Vendor / Driver Contact",
                 "department": "Department", "registrationClass": "Private/Commercial"}


def parse(rows, index=None):
    headers = headers_from(HEAD)
    mapping = suggest_mapping(headers, "VEHICLE_MASTER")
    return parse_master([HEAD] + rows, 1, mapping, {}, index or VehicleIndex(by_reg={}, fuel_type={}), SETTINGS, TODAY)


def test_master_rows_parse_and_preview_status():
    res = parse([
        [1, "DL1AA0001", "Ciaz", "CNG / PETROL", 2019, "DRIVER", "TEST DRIVER", 9000000001, "LOOSE", "Private"],
        [2, "DL1AA0002", "Dzire", "CNG / PETROL", 2020, "DRIVER", None, None, None, "Commercial"],
        [3, "DL1AA0003", "Ciaz", "CNG / PETROL", 2020, "WORKSHOP CH", None, None, None, "Private"],
        [4, "DL1AA0004", "Bolero B6", "DIESEL", 2026, "VENDOR", "Test Vendor", 9000000004, "MCF", "Commercial"],
        [5, "DL1AA0005", "Corolla Altis", "PETROL", 2025, "GOVT DRIVER", "GOVT TEST", 9000000005, "DOT", "Private"],
        [6, "DL1AA0006", "Ciaz", "CNG / PETROL", 2021, "OFFICER", "OFFICER", "OFFICER", "MHA", "Private"],
    ])
    p = {r.row_number: r.parsed["preview"] for r in res.rows}
    assert p[2]["status"] == "ACTIVE" and p[2]["gpsKmTracked"] is True and p[2]["operatorType"] == "OWN_DRIVER"
    assert p[3]["status"] == "IDLE" and p[3]["gpsKmTracked"] is False
    assert p[4]["status"] == "WORKSHOP"
    assert p[5]["operatorType"] == "VENDOR" and p[5]["ownership"] == "VENDOR_ATTACHED" and p[5]["gpsKmTracked"] is False
    assert p[6]["operatorType"] == "GOVT_DRIVER" and p[6]["gpsKmTracked"] is False
    row2 = res.rows[0]
    assert row2.parsed["driverPhone"] == "9000000001" and row2.parsed["modelYear"] == 2019 and row2.parsed["department"] == "LOOSE"
    officer = res.rows[5]
    assert officer.parsed["driverPhone"] is None and "INVALID_PHONE" in [m["code"] for m in officer.messages]
    assert all(r.status in ("VALID", "WARNING") for r in res.rows)


def test_master_rejections_and_duplicates():
    res = parse([
        [1, None, "Ciaz", "CNG / PETROL", 2019, "DRIVER", "A", None, None, None],
        [2, "DL1AA0010", "Ciaz", "KEROSENE", 2019, "DRIVER", "A", None, None, None],
        [3, "DL1AA0011", None, "PETROL", 2019, "DRIVER", "A", None, None, None],
        [4, "DL 1AA 0012", "Ciaz", "PETROL", 2019, "DRIVER", "A", None, None, None],
        [5, "DL1AA0012", "Ciaz", "PETROL", 2019, "DRIVER", "B", None, None, None],
        [6, "CH - 5250", "Innova Crysta", "DIESEL", 2026, "DRIVER", "C", None, None, None],
    ])
    codes = {r.row_number: [m["code"] for m in r.messages] for r in res.rows}
    assert "MISSING_REGISTRATION" in codes[2]
    assert "INVALID_FUEL_TYPE" in codes[3]
    assert "MISSING_MODEL" in codes[4]
    assert res.rows[3].status != "REJECTED" and res.rows[3].parsed["registration"] == "DL1AA0012"
    assert "DUPLICATE_REGISTRATION" in codes[6]
    assert "UNUSUAL_REGISTRATION" in codes[7] and res.rows[5].status == "WARNING"


def test_gps_km_excluded_for_vehicles_not_driven_by_own_drivers():
    index = VehicleIndex(by_reg={"DL1AA0001": 1, "DL1AA0004": 4}, fuel_type={1: "CNG_PETROL", 4: "DIESEL"}, gps_tracked={1})
    res = parse_gps([["Device Name", "Start Time", "Distance (Kms)"], ["DL1AA0001", "01-10-2026", 120], ["DL1AA0004", "01-10-2026", 80]],
                    1, {"vehicle": "Device Name", "date": "Start Time", "km": "Distance (Kms)"}, {"recordMode": "DAILY_TOTAL"},
                    index, SETTINGS, TODAY)
    assert [r.status for r in res.rows] == ["VALID", "EXCLUDED"]
    assert "NOT_OWN_DRIVER" in [m["code"] for m in res.rows[1].messages]
    assert res.counts()["EXCLUDED"] == 1


def test_unknown_headers_use_the_columns_chosen_last_time():
    from fleet_worker.jobs import remember_mapping
    headers = ["Sr", "Gaadi Number", "Model", "Fuel", "Chalak"]
    info = {"sheets": [{"name": "Sheet1", "headers": headers,
                        "suggestedMapping": {"VEHICLE_MASTER": suggest_mapping(headers, "VEHICLE_MASTER")}}]}
    assert "registration" not in info["sheets"][0]["suggestedMapping"]["VEHICLE_MASTER"]
    remember_mapping(info, [{"registration": "Gaadi Number", "driverName": "Chalak", "department": "Not in this file"}], "VEHICLE_MASTER")
    m = info["sheets"][0]["suggestedMapping"]["VEHICLE_MASTER"]
    assert m["registration"] == "Gaadi Number" and m["driverName"] == "Chalak" and "department" not in m
    assert info["sheets"][0]["remembered"]["VEHICLE_MASTER"] == ["registration", "driverName"]
