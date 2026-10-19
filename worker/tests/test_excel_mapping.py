from datetime import datetime

from openpyxl import Workbook

from fleet_worker.excel import describe_file, detect_header_row, headers_from, read_rows
from fleet_worker.mapping import suggest_mapping


def make_xlsx(path, sheets: dict[str, list[list]]):
    wb = Workbook()
    wb.remove(wb.active)
    for name, rows in sheets.items():
        ws = wb.create_sheet(name)
        for r in rows:
            ws.append(r)
    wb.save(path)


def test_gps_export_headers_are_mapped():
    m = suggest_mapping(["Device Name", "Start Time", "End Time", "Distance (Kms)", "Max Speed"], "GPS_KM")
    assert m["vehicle"] == "Device Name"
    assert m["date"] == "Start Time"
    assert m["km"] == "Distance (Kms)"
    assert m["tripStart"] == "Start Time"


def test_fuel_format_one():
    m = suggest_mapping(["Date", "Vehicle", "Fuel Type", "Quantity", "Rate", "Cost", "Odometer", "Invoice No."], "FUEL")
    assert m == {"vehicle": "Vehicle", "date": "Date", "energyType": "Fuel Type", "quantity": "Quantity", "unitPrice": "Rate",
                 "invoiceAmount": "Cost", "odometerKm": "Odometer", "invoiceNumber": "Invoice No."}


def test_fuel_format_two():
    m = suggest_mapping(["Vehicle No", "Request Date", "Fuel Type", "Quantity", "Value", "Discount", "Price", "OD Meter",
                         "Invoice No", "Transaction No"], "FUEL")
    assert m["vehicle"] == "Vehicle No"
    assert m["date"] == "Request Date"
    assert m["grossAmount"] == "Value"
    assert m["discountAmount"] == "Discount"
    assert m["unitPrice"] == "Price"
    assert m["odometerKm"] == "OD Meter"
    assert m["invoiceNumber"] == "Invoice No"
    assert m["transactionRef"] == "Transaction No"
    assert "invoiceAmount" not in m


def test_header_row_detected_below_title_rows(tmp_path):
    p = tmp_path / "gps.xlsx"
    make_xlsx(p, {"Report": [
        ["Distance Report - LR Taxi"],
        ["Generated 19-10-2026"],
        [],
        ["Device Name", "Start Time", "Distance (Kms)"],
        ["DL1RT1234 (CNG)", datetime(2026, 10, 1, 8, 0), 120.5],
    ], "Other": [["x"]]})
    info = describe_file(p)
    assert [s["name"] for s in info["sheets"]] == ["Report", "Other"]
    rep = info["sheets"][0]
    assert rep["headerRow"] == 4
    assert rep["headers"] == ["Device Name", "Start Time", "Distance (Kms)"]
    assert rep["sample"][0]["Device Name"] == "DL1RT1234 (CNG)"
    assert rep["sample"][0]["Start Time"] == "01-10-2026 08:00:00"
    assert rep["suggestedMapping"]["GPS_KM"]["km"] == "Distance (Kms)"


def test_csv_with_semicolons_and_bom(tmp_path):
    p = tmp_path / "fuel.csv"
    p.write_bytes("﻿Vehicle No;Date;Qty\nMH12AB1234;01-10-2026;20\n\n".encode("utf-8"))
    rows = read_rows(p)
    assert rows == [["Vehicle No", "Date", "Qty"], ["MH12AB1234", "01-10-2026", "20"]]


def test_blank_and_duplicate_headers_get_unique_names():
    assert headers_from(["Date", None, "Date"]) == ["Date", "Column 2", "Date (2)"]


def test_detect_header_prefers_known_columns():
    rows = [["Fleet summary", None], ["Vehicle No", "Fuel Type"], ["MH12AB1234", "Petrol"]]
    assert detect_header_row(rows) == 1


def test_large_sheet_streams(tmp_path):
    p = tmp_path / "big.xlsx"
    rows = [["Device Name", "Start Time", "Distance (Kms)"]] + [[f"DL1RT{1000 + i % 500}", "01-10-2026", 10] for i in range(20000)]
    make_xlsx(p, {"S": rows})
    assert len(read_rows(p, "S")) == 20001
