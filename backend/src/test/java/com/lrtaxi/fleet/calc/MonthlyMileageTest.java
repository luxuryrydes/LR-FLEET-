package com.lrtaxi.fleet.calc;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MonthlyMileageTest {

    static BigDecimal b(String s) {
        return new BigDecimal(s);
    }

    static MonthlyMileage.VehicleMonth vm(String km, String expense, Object... energyQty) {
        Map<EnergyType, BigDecimal> q = new EnumMap<>(EnergyType.class);
        for (int i = 0; i < energyQty.length; i += 2) {
            q.put((EnergyType) energyQty[i], b((String) energyQty[i + 1]));
        }
        return new MonthlyMileage.VehicleMonth(km == null ? null : b(km), q, expense == null ? null : b(expense));
    }

    @Test
    void costPerKmAndMileageForASingleFuelMonth() {
        // 4,200 km, 180 kg CNG for Rs 15,800.40 -> Rs 3.76/km, 23.333 km/kg
        var r = MonthlyMileage.evaluate(vm("4200", "15800.40", EnergyType.CNG, "180"));
        assertThat(r.status()).isEqualTo(MonthlyMileage.Status.OK);
        assertThat(r.costPerKm()).isEqualByComparingTo("3.76");
        assertThat(r.mileage()).isEqualByComparingTo("23.333");
        assertThat(r.mileageEnergy()).isEqualTo(EnergyType.CNG);
    }

    @Test
    void mixedFuelMonthAddsKgAndLitresIntoKgL() {
        // HR38AG0004 example: 246 km, 27.66 kg CNG + 23.64 L petrol = 51.30 kgL -> 4.795 km/kgL
        var r = MonthlyMileage.evaluate(vm("246", "5333.85", EnergyType.CNG, "27.66", EnergyType.PETROL, "23.64"));
        assertThat(r.status()).isEqualTo(MonthlyMileage.Status.MIXED_FUEL);
        assertThat(r.costPerKm()).isEqualByComparingTo("21.68");
        assertThat(r.quantity()).isEqualByComparingTo("51.30");
        assertThat(r.mileage()).isEqualByComparingTo("4.795");
        assertThat(r.unit()).isEqualTo("km/kgL");
        assertThat(r.mileageEnergy()).isNull();
        assertThat(MonthlyMileage.evaluate(vm("3000", "13000", EnergyType.PETROL, "10", EnergyType.ELECTRICITY, "50")).unit()).isEqualTo("km/LkWh");
    }

    @Test
    void missingKmOrFuelIsAStatusNotZero() {
        assertThat(MonthlyMileage.evaluate(vm(null, "5000", EnergyType.CNG, "55")).status()).isEqualTo(MonthlyMileage.Status.NO_KM);
        assertThat(MonthlyMileage.evaluate(vm("0", "5000", EnergyType.CNG, "55")).status()).isEqualTo(MonthlyMileage.Status.NO_KM);
        var noFuel = MonthlyMileage.evaluate(vm("800", null));
        assertThat(noFuel.status()).isEqualTo(MonthlyMileage.Status.NO_FUEL);
        assertThat(noFuel.costPerKm()).isNull();
    }

    @Test
    void implausibleMonthIsFlaggedAndKeptOutOfFleetMileage() {
        Map<EnergyType, MileageEngine.Range> limits = MileageEngine.Policy.defaults().plausible();   // CNG 5-45 km/kg
        var bad = vm("5000", "6000", EnergyType.CNG, "60");                 // 83 km/kg: fuel bills missing
        var r = MonthlyMileage.evaluate(bad, limits);
        assertThat(r.status()).isEqualTo(MonthlyMileage.Status.IMPLAUSIBLE);
        assertThat(r.mileage()).isEqualByComparingTo("83.333");             // shown for investigation
        var t = MonthlyMileage.totals(List.of(bad, vm("4000", "12000", EnergyType.CNG, "160")), limits);
        assertThat(t.mileage().get(EnergyType.CNG)).isEqualByComparingTo("25");   // only the plausible vehicle
        assertThat(t.km()).isEqualByComparingTo("9000");                     // Rs/km still covers both
    }

    @Test
    void fleetTotalsAreWeightedAndExcludeIncompleteVehicles() {
        var t = MonthlyMileage.totals(List.of(
                vm("4000", "12000", EnergyType.CNG, "160"),                         // 25 km/kg, Rs 3/km
                vm("1000", "6000", EnergyType.CNG, "80"),                          // 12.5 km/kg, Rs 6/km
                vm("2000", "9000", EnergyType.CNG, "70", EnergyType.PETROL, "15"), // mixed: 2000 / 85 kgL
                vm(null, "7000", EnergyType.DIESEL, "75"),                         // vendor vehicle, no km
                vm("900", null)));                                                 // no fuel
        assertThat(t.vehiclesIncluded()).isEqualTo(3);
        assertThat(t.km()).isEqualByComparingTo("7000");
        assertThat(t.expense()).isEqualByComparingTo("27000");
        assertThat(t.costPerKm()).isEqualByComparingTo("3.86");                 // 27000 / 7000
        // weighted 5000 km / 240 kg = 20.833, not the simple average (25 + 12.5) / 2 = 18.75
        assertThat(t.mileage().get(EnergyType.CNG)).isEqualByComparingTo("20.833");
        assertThat(t.mileage()).doesNotContainKey(EnergyType.PETROL);
        // mixed-fuel vehicles kept separate: 2000 km / (70 kg + 15 L) = 23.529 km/kgL
        assertThat(t.mixedVehicles()).isEqualTo(1);
        assertThat(t.mixedQuantity()).isEqualByComparingTo("85");
        assertThat(t.mixedMileage()).isEqualByComparingTo("23.529");
    }
}
