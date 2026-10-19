import json
from datetime import date, datetime, time
from decimal import Decimal

import pytest

from conftest import SPEC
from fleet_worker.dates import parse_date, parse_datetime, parse_time
from fleet_worker.normalize import (decimal_text, extract_plate, normalize_registration, parse_bool, parse_decimal, parse_energy,
                                    parse_payment)

CASES = json.loads((SPEC / "registration-cases.json").read_text(encoding="utf-8"))


@pytest.mark.parametrize("case", CASES["normalize"], ids=lambda c: c["input"])
def test_normalize_matches_shared_spec(case):
    assert normalize_registration(case["input"]) == case["expected"]


@pytest.mark.parametrize("case", CASES["extract"], ids=lambda c: c["input"])
def test_extract_matches_shared_spec(case):
    assert extract_plate(case["input"]) == case["expected"]


@pytest.mark.parametrize("raw,expected", [
    ("01-10-2026", date(2026, 10, 1)),
    ("01/10/2026", date(2026, 10, 1)),          # day-first, never 10 January
    ("1.10.2026", date(2026, 10, 1)),
    ("2026-10-01", date(2026, 10, 1)),
    ("01-Oct-2026", date(2026, 10, 1)),
    ("01 Oct 2026 08:15:00", date(2026, 10, 1)),
    ("01-10-2026 08:15:00 IST", date(2026, 10, 1)),
    ("01/10/2026 02:30:00 PM", date(2026, 10, 1)),
    (46296, date(2026, 10, 1)),                 # Excel serial number
    (46296.75, date(2026, 10, 1)),
    ("46296", date(2026, 10, 1)),
    (datetime(2026, 10, 1, 9, 30), date(2026, 10, 1)),
    (date(2026, 10, 1), date(2026, 10, 1)),
])
def test_date_conversion(raw, expected):
    assert parse_date(raw) == expected


@pytest.mark.parametrize("raw", [None, "", "  ", "nan", "-"])
def test_blank_dates_are_none(raw):
    assert parse_date(raw) is None


@pytest.mark.parametrize("raw", ["32-01-2026", "13/13/2026", "yesterday", "2026/31/12", 12, True])
def test_incorrect_dates_raise(raw):
    with pytest.raises(ValueError):
        parse_datetime(raw)


def test_time_parsing():
    assert parse_time("08:15") == time(8, 15)
    assert parse_time(0.5) == time(12, 0)
    assert parse_time("02:30 PM") == time(14, 30)


@pytest.mark.parametrize("raw,expected", [
    ("1,234.50", Decimal("1234.50")), ("₹ 950", Decimal("950")), ("120 km", Decimal("120")), ("12.5 Ltrs", Decimal("12.5")),
    (85.2, Decimal("85.2")), (0.1, Decimal("0.1")), (7, Decimal("7")), ("(50)", Decimal("-50")), ("", None), (None, None),
])
def test_decimal_parsing_is_exact(raw, expected):
    assert parse_decimal(raw) == expected


def test_decimal_junk_raises():
    with pytest.raises(ValueError):
        parse_decimal("twelve")


def test_decimal_text():
    assert decimal_text(Decimal("120.50")) == "120.5"
    assert decimal_text(Decimal("1E+2")) == "100"
    assert decimal_text(Decimal("0.00")) == "0"


def test_value_vocabularies():
    assert parse_energy("HSD") == "DIESEL"
    assert parse_energy("C.N.G") == "CNG"
    assert parse_energy("ev") == "ELECTRICITY"
    assert parse_energy("water") is None
    assert parse_bool("Yes") is True and parse_bool("partial") is False and parse_bool("?") is None
    assert parse_payment("PhonePe") == "UPI" and parse_payment("Fleet Card") == "FUEL_CARD" and parse_payment("cheque") == "OTHER"
