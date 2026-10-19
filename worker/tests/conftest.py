import sys
from datetime import date
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from fleet_worker.parsers import VehicleIndex  # noqa: E402

SPEC = Path(__file__).resolve().parents[2] / "spec"
TODAY = date(2026, 10, 19)
SETTINGS = {"gps.max_daily_km": "1500", "fuel.amount_tolerance": "1.00", "fuel.max_quantity.CNG": "100",
            "fuel.max_quantity.PETROL": "120", "fuel.max_quantity.DIESEL": "400", "fuel.max_quantity.ELECTRICITY": "200"}


@pytest.fixture
def index() -> VehicleIndex:
    return VehicleIndex(
        by_reg={"DL1RT1234": 1, "MH12AB1234": 2, "KA05MN4321": 3, "HR26DK0002": 4},
        fuel_type={1: "CNG", 2: "PETROL", 3: "CNG_PETROL", 4: "ELECTRIC"},
        by_alias={"INNOVA7": 2},
    )
