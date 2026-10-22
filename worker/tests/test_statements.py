"""Fuel-company statement layouts used by LR Taxi (synthetic rows in the real column layouts)."""
import json
from decimal import Decimal

import pytest
from openpyxl import Workbook

from conftest import SETTINGS, SPEC, TODAY
from fleet_worker.excel import describe_file, headers_from, read_rows
from fleet_worker.mapping import suggest_mapping
from fleet_worker.normalize import parse_energy
from fleet_worker.parsers import VehicleIndex, expense_of, fill_key, mark_already_recorded, parse_fuel

ENERGY = json.loads((SPEC / "energy-cases.json").read_text(encoding="utf-8"))["cases"]


@pytest.mark.parametrize("c", ENERGY, ids=lambda c: c["raw"] or "blank")
def test_statement_fuel_names_match_java(c):
    assert parse_energy(c["raw"]) == c["energy"]


BPCL_HEAD = ["#", "Customer Name", "Vehicle No", "POD", "Alt Vehicle No", "Fuel Type", "Slip No", "Transaction Date", "Quantity",
             "Price", "Value", "Discount", "Shift Manager", "Group", "Trans Mode", "Req.No./Slip No.", "Bill No.", "Payment Mode",
             "Invoice No.", "Request Date", "OD Meter"]


def bpcl(vehicle, fuel, slip, date, qty, price, value, disc, inv, odo=None, req_date=None):
    return [1, "L.R.TAXI SERVICES", vehicle, None, vehicle, fuel, slip, date, qty, price, value, disc, "TEST", "FUEL", "E-SLIPS",
            slip, None, None, inv, req_date or date, odo]


INDEX = VehicleIndex(by_reg={"HR51AA0001": 1, "DL10AA0002": 2, "HR51AA0003": 3},
                     fuel_type={1: "CNG_PETROL", 2: "PETROL", 3: "CNG_PETROL"})


def test_bpcl_statement_maps_and_parses():
    m = suggest_mapping(headers_from(BPCL_HEAD), "FUEL")
    assert m["vehicle"] == "Vehicle No" and m["date"] == "Transaction Date" and m["energyType"] == "Fuel Type"
    assert m["quantity"] == "Quantity" and m["unitPrice"] == "Price" and m["grossAmount"] == "Value"
    assert m["discountAmount"] == "Discount" and m["invoiceNumber"] == "Invoice No." and m["transactionRef"] == "Slip No"
    assert m["odometerKm"] == "OD Meter"
    rows = [BPCL_HEAD,
            bpcl("HR51AA0001", "AUTO CNG - BPCL", "RQ1", "18/10/2026", 7.054, 86.98, 613.56, 0.0, "SLP/1", 86661, "19/10/2026"),
            bpcl("DL10AA0002", "PETROL - BPCL", "RQ2", "18/10/2026", 10.0, 102.12, 1021.2, 10.0, "SLP/2"),
            [100000000, "", "", "Total", "", "", "", "", 17.054, "", 1634.76, "", "", "", "", "", "", "", "", "", ""]]
    res = parse_fuel(rows, 1, m, {}, INDEX, SETTINGS, TODAY)
    assert res.counts()["total"] == 2                         # Total line skipped, not rejected
    cng, petrol = res.rows
    assert cng.status == "VALID" and cng.parsed["energyType"] == "CNG" and cng.parsed["date"] == "2026-10-18"
    assert cng.parsed["odometerKm"] == "86661" and cng.parsed["transactionRef"] == "RQ1"
    assert petrol.parsed["energyType"] == "PETROL" and expense_of(petrol.parsed) == Decimal("1011.20")   # value - Rs 1/L discount


IGL_HEAD = ["Txn Date", "Outlet Name", "Card Number", "Vehicle No.", "Txn Type", "Txn Id", "Amount", "Quantity"]


def test_igl_card_statement_needs_default_cng_and_records_card():
    m = suggest_mapping(headers_from(IGL_HEAD), "FUEL")
    assert m["date"] == "Txn Date" and m["vehicle"] == "Vehicle No." and m["invoiceAmount"] == "Amount"
    assert m["quantity"] == "Quantity" and m["transactionRef"] == "Txn Id" and m["stationName"] == "Outlet Name"
    assert m["cardNumber"] == "Card Number" and "energyType" not in m
    rows = [IGL_HEAD, ["05/10/2026 12:54:12 AM", "NH-09  ", "7010213872910106", "HR51AA0003", "Smart Card Sale", 65977613, 355.76, "4.090"],
            ["14/10/2026 10:26:58 AM", "R S SERVICE STAT", "7010211032717023", "HR51AA0003", "Smart Card Sale", 66281375, 2.4, ".025"],
            [None, None, None, None, None, None, None, 0]]
    without = parse_fuel(rows, 1, m, {}, INDEX, SETTINGS, TODAY)
    assert without.rows[0].status == "REJECTED"               # bi-fuel vehicle and no fuel column: cannot guess
    res = parse_fuel(rows, 1, m, {"defaultEnergyType": "CNG"}, INDEX, SETTINGS, TODAY)
    assert res.counts()["total"] == 2                         # trailing blank/zero row skipped
    r = res.rows[0]
    assert r.status == "VALID" and r.parsed["energyType"] == "CNG" and r.parsed["time"] == "00:54:12"
    assert r.parsed["paymentMethod"] == "FUEL_CARD" and r.parsed["notes"] == "Fuel card 7010213872910106"
    assert r.parsed["transactionRef"] == "65977613" and r.parsed["quantity"] == "4.09"
    assert res.rows[1].parsed["quantity"] == "0.025"


def test_same_fill_from_another_statement_is_marked_duplicate():
    m = suggest_mapping(headers_from(BPCL_HEAD), "FUEL")
    rows = [BPCL_HEAD, bpcl("HR51AA0003", "AUTO CNG - BPCL", "IGL1317", "05/10/2026 12:54:12 AM", "4.090", 86.98, 355.76, None, "IGL/SLP/1317"),
            bpcl("HR51AA0003", "AUTO CNG - BPCL", "IGL1318", "06/10/2026 11:46:23 AM", "6.340", 86.98, 551.45, None, "IGL/SLP/1318")]
    res = parse_fuel(rows, 1, m, {}, INDEX, SETTINGS, TODAY)
    existing = {fill_key(3, "2026-10-05", "CNG", "4.090", "355.76"): ["00:54:12"]}   # already saved from the IGL card file
    mark_already_recorded(res.rows, existing)
    assert [r.status for r in res.rows] == ["DUPLICATE", "VALID"]
    assert "ALREADY_RECORDED" in [x["code"] for x in res.rows[0].messages]
    # a different time on the same day is a different fill
    res2 = parse_fuel(rows, 1, m, {}, INDEX, SETTINGS, TODAY)
    mark_already_recorded(res2.rows, {fill_key(3, "2026-10-05", "CNG", "4.090", "355.76"): ["18:00:00"]})
    assert res2.rows[0].status == "VALID"


def test_workbook_with_broken_style_sheet_still_reads(tmp_path):
    """BPCL portal exports have a malformed styles.xml that openpyxl rejects; the values must still be read."""
    import zipfile
    good = tmp_path / "good.xlsx"
    wb = Workbook()
    ws = wb.active
    ws.append(BPCL_HEAD)
    ws.append(bpcl("HR51AA0001", "AUTO CNG - BPCL", "RQ1", "18/10/2026", 7.054, 86.98, 613.56, 0.0, "SLP/1"))
    wb.save(good)
    bad = tmp_path / "bad.xlsx"
    with zipfile.ZipFile(good) as src, zipfile.ZipFile(bad, "w") as dst:
        for item in src.infolist():
            data = src.read(item.filename)
            if item.filename == "xl/styles.xml":
                data = data.replace(b"<fills count=\"2\">", b"<fills count=\"2\"><fill/>")   # invalid fill element
            dst.writestr(item, data)
    info = describe_file(bad)
    assert info["sheets"][0]["suggestedMapping"]["FUEL"]["vehicle"] == "Vehicle No"
    assert read_rows(bad)[1][2] == "HR51AA0001"


def test_two_card_charges_seconds_apart_are_both_kept_and_flagged():
    m = suggest_mapping(headers_from(IGL_HEAD), "FUEL")
    rows = [IGL_HEAD,
            ["16/10/2026 05:33:01 PM", "OUTLET", "7010", "HR51AA0003", "Smart Card Sale", 66366788, 242.23, "2.785"],
            ["16/10/2026 05:32:59 PM", "OUTLET", "7010", "HR51AA0003", "Smart Card Sale", 66366785, 242.23, "2.785"],
            ["16/10/2026 05:32:59 PM", "OUTLET", "7010", "HR51AA0003", "Smart Card Sale", 66366785, 242.23, "2.785"]]
    res = parse_fuel(rows, 1, m, {"defaultEnergyType": "CNG"}, INDEX, SETTINGS, TODAY)
    assert [r.status for r in res.rows] == ["WARNING", "WARNING", "DUPLICATE"]   # two real charges kept, exact copy dropped
    assert all("POSSIBLE_DOUBLE_CHARGE" in [x["code"] for x in r.messages] for r in res.rows[:2])
