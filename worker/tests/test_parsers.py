from datetime import datetime

from conftest import SETTINGS, TODAY
from fleet_worker.parsers import parse_fuel, parse_gps

GPS_MAP = {"vehicle": "Device Name", "date": "Start Time", "km": "Distance (Kms)"}
GPS_HEAD = ["Device Name", "Start Time", "Distance (Kms)"]


def gps(rows, mode="DAILY_TOTAL", index=None, mapping=None):
    return parse_gps([GPS_HEAD] + rows, 1, mapping or GPS_MAP, {"recordMode": mode}, index, SETTINGS, TODAY)


def by_row(result):
    return {r.row_number: r for r in result.rows}


def codes(row):
    return [m["code"] for m in row.messages]


def test_device_name_variations_match_one_vehicle(index):
    res = by_row(gps([["DL1RT1234 (CNG)", "01-10-2026", 100], ["dl 1rt 1234", "02-10-2026", 50],
                      ["Innova 7", "01-10-2026", 80]], index=index))
    assert res[2].vehicle_id == 1 and "VEHICLE_MATCHED_BY_PLATE" in codes(res[2])
    assert res[3].vehicle_id == 1 and res[3].status == "VALID"
    assert res[4].vehicle_id == 2 and "VEHICLE_MATCHED_BY_ALIAS" in codes(res[4])


def test_rejections_have_reasons(index):
    res = by_row(gps([
        ["XX99ZZ9999", "01-10-2026", 10],       # unmatched
        ["DL1RT1234", None, 10],                # blank date
        ["DL1RT1234", "31-02-2026", 10],        # invalid date
        ["DL1RT1234", "01-12-2026", 10],        # future
        ["DL1RT1234", "03-10-2026", -5],        # negative
        ["DL1RT1234", "04-10-2026", 2500],      # above daily limit
        ["DL1RT1234", "05-10-2026", None],      # missing km is not zero
        ["DL1RT1234", "06-10-2026", "abc"],     # junk
        [None, "07-10-2026", 10],               # blank vehicle
    ], index=index))
    expected = {2: "UNMATCHED_VEHICLE", 3: "BLANK_DATE", 4: "INVALID_DATE", 5: "FUTURE_DATE", 6: "NEGATIVE_KM", 7: "KM_ABOVE_LIMIT",
                8: "MISSING_KM", 9: "INVALID_NUMBER", 10: "MISSING_VEHICLE"}
    for row, code in expected.items():
        assert res[row].status == "REJECTED", row
        assert code in codes(res[row]), (row, codes(res[row]))
        assert res[row].errors()[0]["message"]


def test_zero_km_is_valid_measurement(index):
    r = gps([["DL1RT1234", "01-10-2026", 0]], index=index).rows[0]
    assert r.status == "VALID" and r.parsed["km"] == "0"


def test_daily_total_copies_marked_duplicate_not_summed(index):
    res = gps([["DL1RT1234", "01-10-2026", 120.5], ["DL1RT1234", "01-10-2026", "120.50"], ["DL1RT1234", "01-10-2026", 120.5]],
              index=index)
    statuses = [r.status for r in res.rows]
    assert statuses == ["VALID", "DUPLICATE", "DUPLICATE"]
    assert res.counts()["DUPLICATE"] == 2


def test_daily_total_conflict_flagged_for_all_rows(index):
    res = gps([["DL1RT1234", "01-10-2026", 120], ["DL1RT1234", "01-10-2026", 95]], index=index)
    assert all(r.status == "WARNING" and "DAILY_TOTAL_CONFLICT" in codes(r) for r in res.rows)


def test_trip_mode_sums_distinct_trips_and_drops_repeats(index):
    rows = [["DL1RT1234", datetime(2026, 10, 1, 8, 0), 40], ["DL1RT1234", datetime(2026, 10, 1, 12, 30), 35.5],
            ["DL1RT1234", datetime(2026, 10, 1, 8, 0), 40]]
    res = gps(rows, mode="TRIP", index=index)
    assert [r.status for r in res.rows] == ["VALID", "VALID", "DUPLICATE"]
    assert res.rows[0].parsed["tripStart"] == "2026-10-01T08:00:00"


def test_empty_rows_ignored(index):
    res = gps([["DL1RT1234", "01-10-2026", 10], [None, None, None], ["DL1RT1234", "02-10-2026", 10]], index=index)
    assert res.counts()["total"] == 2
    assert [r.row_number for r in res.rows] == [2, 4]


FUEL_HEAD = ["Vehicle No", "Request Date", "Fuel Type", "Quantity", "Value", "Discount", "Price", "OD Meter", "Invoice No",
             "Transaction No"]
FUEL_MAP = {"vehicle": "Vehicle No", "date": "Request Date", "energyType": "Fuel Type", "quantity": "Quantity",
            "grossAmount": "Value", "discountAmount": "Discount", "unitPrice": "Price", "odometerKm": "OD Meter",
            "invoiceNumber": "Invoice No", "transactionRef": "Transaction No"}


def fuel(rows, index):
    return parse_fuel([FUEL_HEAD] + rows, 1, FUEL_MAP, {}, index, SETTINGS, TODAY)


def test_fuel_row_parsed_with_exact_decimals(index):
    r = fuel([["MH12AB1234", "01-10-2026", "Petrol", 20, 2100, 25, 105, 45210, "INV-9", 77881]], index).rows[0]
    assert r.status == "VALID", r.messages
    assert r.parsed["quantity"] == "20" and r.parsed["grossAmount"] == "2100" and r.parsed["discountAmount"] == "25"
    assert r.parsed["transactionRef"] == "77881"
    assert r.parsed["energyType"] == "PETROL"


def test_inconsistent_total_warns_but_keeps_row(index):
    r = fuel([["MH12AB1234", "01-10-2026", "Petrol", 20, 2000, 0, 105, None, "INV-10", None]], index).rows[0]
    assert r.status == "WARNING" and "AMOUNT_MISMATCH" in codes(r)


def test_fuel_rejections(index):
    res = by_row(fuel([
        ["MH12AB1234", "01-10-2026", "Diesel", 20, 2000, 0, 100, None, None, None],     # incompatible with petrol car
        ["MH12AB1234", "01-10-2026", "Petrol", 0, 0, 0, 100, None, None, None],          # zero quantity
        ["MH12AB1234", "01-10-2026", "Petrol", 10, None, None, None, None, None, None],  # no amount
        ["MH12AB1234", "01-10-2026", "Kerosene", 10, 900, 0, 90, None, None, None],      # unknown fuel
        ["NOPE1234", "01-10-2026", "Petrol", 10, 900, 0, 90, None, None, None],          # unmatched
    ], index))
    assert "INCOMPATIBLE_FUEL" in codes(res[2])
    assert "INVALID_QUANTITY" in codes(res[3])
    assert "NO_AMOUNT" in codes(res[4])
    assert "INVALID_FUEL_TYPE" in codes(res[5])
    assert "UNMATCHED_VEHICLE" in codes(res[6])
    assert all(r.status == "REJECTED" for r in res.values())


def test_abnormal_quantity_warns(index):
    r = fuel([["DL1RT1234", "01-10-2026", "CNG", 150, 13500, 0, 90, None, None, None]], index).rows[0]
    assert "QUANTITY_ABOVE_LIMIT" in codes(r) and r.status == "WARNING"


def test_bifuel_vehicle_accepts_both_fuels(index):
    res = fuel([["KA05MN4321", "01-10-2026", "CNG", 8, 720, 0, 90, None, None, None],
                ["KA05MN4321", "02-10-2026", "Petrol", 5, 525, 0, 105, None, None, None]], index)
    assert [r.status for r in res.rows] == ["VALID", "VALID"]


def test_duplicate_fuel_rows_in_same_file(index):
    row = ["MH12AB1234", "01-10-2026", "Petrol", 20, 2100, 0, 105, None, "INV-1", "T-1"]
    other = ["MH12AB1234", "02-10-2026", "Petrol", 10, 1050, 0, 105, None, "INV-1", "T-2"]
    reused_ref = ["MH12AB1234", "03-10-2026", "Petrol", 12, 1260, 0, 105, None, "INV-3", "T-1"]
    res = fuel([row, list(row), other, reused_ref], index)
    assert [r.status for r in res.rows] == ["VALID", "DUPLICATE", "WARNING", "REJECTED"]
    assert "REPEATED_INVOICE" in codes(res.rows[2])
    assert "DUPLICATE_TRANSACTION_REF" in codes(res.rows[3])


def test_energy_inferred_for_single_fuel_vehicle(index):
    res = parse_fuel([["Vehicle", "Date", "Qty", "Cost"], ["HR26DK0002", "01-10-2026", 25, 300]], 1,
                     {"vehicle": "Vehicle", "date": "Date", "quantity": "Qty", "invoiceAmount": "Cost"}, {}, index, SETTINGS, TODAY)
    r = res.rows[0]
    assert r.parsed["energyType"] == "ELECTRICITY" and "ENERGY_INFERRED" in codes(r)


def test_error_rows_generated_for_import_row_errors(index):
    res = gps([["XX00", "01-10-2026", 10], ["DL1RT1234", "bad", -1]], index=index)
    errs = [(r.row_number, e["code"]) for r in res.rows for e in r.errors()]
    assert (2, "UNMATCHED_VEHICLE") in errs
    assert (3, "INVALID_DATE") in errs and (3, "NEGATIVE_KM") in errs
    for r in res.rows:
        for e in r.errors():
            assert set(e) >= {"code", "field", "message"}
