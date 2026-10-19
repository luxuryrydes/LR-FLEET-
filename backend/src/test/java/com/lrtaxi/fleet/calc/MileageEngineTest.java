package com.lrtaxi.fleet.calc;

import com.lrtaxi.fleet.calc.MileageEngine.DistanceSource;
import com.lrtaxi.fleet.calc.MileageEngine.ExclusionReason;
import com.lrtaxi.fleet.calc.MileageEngine.FuelFill;
import com.lrtaxi.fleet.calc.MileageEngine.Interval;
import com.lrtaxi.fleet.calc.MileageEngine.Method;
import com.lrtaxi.fleet.calc.MileageEngine.Policy;
import com.lrtaxi.fleet.calc.MileageEngine.SummaryStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MileageEngineTest {

    static final LocalDate D = LocalDate.of(2026, 9, 1);

    static BigDecimal b(String s) {
        return new BigDecimal(s);
    }

    static FuelFill fill(long id, int day, EnergyType e, String qty, String odo, Boolean full) {
        return new FuelFill(id, D.plusDays(day), e, b(qty), odo == null ? null : b(odo), full);
    }

    @Test
    void successiveRefuelUsesClosingFillQuantity() {
        List<FuelFill> fills = List.of(
                fill(1, 0, EnergyType.CNG, "8", "10000", null),
                fill(2, 2, EnergyType.CNG, "10", "10250", null),
                fill(3, 4, EnergyType.CNG, "9", "10475", null));
        List<Interval> iv = MileageEngine.computeIntervals(VehicleFuelType.CNG, fills, Map.of(), Policy.defaults());
        assertThat(iv).hasSize(2);
        assertThat(iv.get(0).method()).isEqualTo(Method.SUCCESSIVE_REFUEL);
        assertThat(iv.get(0).distanceSource()).isEqualTo(DistanceSource.ODOMETER);
        assertThat(iv.get(0).mileage()).isEqualByComparingTo("25");          // 250 km / 10 kg
        assertThat(iv.get(1).mileage()).isEqualByComparingTo("25");          // 225 km / 9 kg
        var summary = MileageEngine.summarize(iv).get(EnergyType.CNG);
        assertThat(summary.mileage()).isEqualByComparingTo("25.000");       // 475 / 19
        assertThat(summary.status()).isEqualTo(SummaryStatus.OK);
    }

    @Test
    void fullTankToFullTankSumsIntermediatePartialFills() {
        List<FuelFill> fills = List.of(
                fill(1, 0, EnergyType.PETROL, "30", "20000", true),
                fill(2, 3, EnergyType.PETROL, "10", "20150", false),
                fill(3, 6, EnergyType.PETROL, "15", "20375", true),
                fill(4, 8, EnergyType.PETROL, "5", "20450", false));   // after last full tank: unaccounted
        List<Interval> iv = MileageEngine.computeIntervals(VehicleFuelType.PETROL, fills, Map.of(), Policy.defaults());
        assertThat(iv).hasSize(1);
        Interval i = iv.get(0);
        assertThat(i.method()).isEqualTo(Method.FULL_TANK);
        assertThat(i.quantity()).isEqualByComparingTo("25");               // 10 + 15, start fill excluded
        assertThat(i.distanceKm()).isEqualByComparingTo("375");
        assertThat(i.mileage()).isEqualByComparingTo("15");
    }

    @Test
    void decreasingOdometerIsExcludedNotCounted() {
        List<FuelFill> fills = List.of(
                fill(1, 0, EnergyType.DIESEL, "40", "50000", null),
                fill(2, 5, EnergyType.DIESEL, "40", "49900", null));
        Interval i = MileageEngine.computeIntervals(VehicleFuelType.DIESEL, fills, Map.of(), Policy.defaults()).get(0);
        assertThat(i.eligible()).isFalse();
        assertThat(i.exclusion()).isEqualTo(ExclusionReason.DECREASING_ODOMETER);
        assertThat(MileageEngine.summarize(List.of(i)).get(EnergyType.DIESEL).status()).isEqualTo(SummaryStatus.INSUFFICIENT_DATA);
    }

    @Test
    void zeroDistanceExcluded() {
        List<FuelFill> fills = List.of(
                fill(1, 0, EnergyType.PETROL, "20", "1000", null),
                fill(2, 1, EnergyType.PETROL, "20", "1000", null));
        Interval i = MileageEngine.computeIntervals(VehicleFuelType.PETROL, fills, Map.of(), Policy.defaults()).get(0);
        assertThat(i.exclusion()).isEqualTo(ExclusionReason.ZERO_DISTANCE);
    }

    @Test
    void implausibleMileageExcludedButValueKeptForInvestigation() {
        List<FuelFill> fills = List.of(
                fill(1, 0, EnergyType.PETROL, "20", "1000", null),
                fill(2, 1, EnergyType.PETROL, "2", "1500", null));          // 250 km/L
        Interval i = MileageEngine.computeIntervals(VehicleFuelType.PETROL, fills, Map.of(), Policy.defaults()).get(0);
        assertThat(i.exclusion()).isEqualTo(ExclusionReason.IMPLAUSIBLE_MILEAGE);
        assertThat(i.mileage()).isEqualByComparingTo("250");
    }

    @Test
    void gpsDistanceUsedWhenOdometerMissingAndRequiresEveryDay() {
        Map<LocalDate, BigDecimal> gps = new HashMap<>();
        gps.put(D.plusDays(1), b("100"));
        gps.put(D.plusDays(2), b("150"));
        List<FuelFill> fills = List.of(
                fill(1, 0, EnergyType.CNG, "9", null, null),
                fill(2, 2, EnergyType.CNG, "10", null, null));
        Interval i = MileageEngine.computeIntervals(VehicleFuelType.CNG, fills, gps, Policy.defaults()).get(0);
        assertThat(i.distanceSource()).isEqualTo(DistanceSource.GPS);
        assertThat(i.distanceKm()).isEqualByComparingTo("250");             // day 0 excluded, days 1..2 included
        assertThat(i.mileage()).isEqualByComparingTo("25");

        gps.remove(D.plusDays(1));
        Interval missing = MileageEngine.computeIntervals(VehicleFuelType.CNG, fills, gps, Policy.defaults()).get(0);
        assertThat(missing.exclusion()).isEqualTo(ExclusionReason.INCOMPLETE_GPS);
    }

    @Test
    void sameDayWithoutOdometerExcluded() {
        List<FuelFill> fills = List.of(
                fill(1, 0, EnergyType.CNG, "9", null, null),
                fill(2, 0, EnergyType.CNG, "3", null, null));
        Interval i = MileageEngine.computeIntervals(VehicleFuelType.CNG, fills, Map.of(), Policy.defaults()).get(0);
        assertThat(i.exclusion()).isEqualTo(ExclusionReason.SAME_DAY_NO_ODOMETER);
    }

    @Test
    void biFuelIntervalWithOtherFuelInsideIsMixed() {
        List<FuelFill> fills = List.of(
                fill(1, 0, EnergyType.CNG, "8", "10000", null),
                fill(2, 1, EnergyType.PETROL, "5", "10100", null),
                fill(3, 3, EnergyType.CNG, "10", "10300", null),
                fill(4, 6, EnergyType.CNG, "10", "10550", null));
        List<Interval> iv = MileageEngine.computeIntervals(VehicleFuelType.CNG_PETROL, fills, Map.of(), Policy.defaults());
        List<Interval> cng = iv.stream().filter(i -> i.energy() == EnergyType.CNG).toList();
        assertThat(cng).hasSize(2);
        assertThat(cng.get(0).exclusion()).isEqualTo(ExclusionReason.MIXED_FUEL);   // petrol bought on day 1
        assertThat(cng.get(1).eligible()).isTrue();                                  // pure CNG interval
        assertThat(cng.get(1).mileage()).isEqualByComparingTo("25");
        // Only one petrol fill: no petrol interval and petrol is never added to the CNG denominator
        assertThat(iv.stream().filter(i -> i.energy() == EnergyType.PETROL)).isEmpty();
        assertThat(MileageEngine.summarize(iv).get(EnergyType.CNG).eligibleQuantity()).isEqualByComparingTo("10");
    }

    @Test
    void biFuelWithAttributionDisabledIsAlwaysMixed() {
        Policy p = new Policy(MileageEngine.AccountingPolicy.AUTO, false, Policy.defaults().plausible());
        List<FuelFill> fills = List.of(
                fill(1, 0, EnergyType.CNG, "8", "10000", null),
                fill(2, 3, EnergyType.CNG, "10", "10250", null));
        List<Interval> iv = MileageEngine.computeIntervals(VehicleFuelType.CNG_PETROL, fills, Map.of(), p);
        assertThat(iv.get(0).exclusion()).isEqualTo(ExclusionReason.MIXED_FUEL);
        assertThat(MileageEngine.summarize(iv).get(EnergyType.CNG).status()).isEqualTo(SummaryStatus.MIXED_FUEL);
    }

    @Test
    void fullTankOnlyPolicyWithoutFlagsProducesNoIntervals() {
        Policy p = new Policy(MileageEngine.AccountingPolicy.FULL_TANK_ONLY, true, Policy.defaults().plausible());
        List<FuelFill> fills = List.of(
                fill(1, 0, EnergyType.PETROL, "20", "1000", null),
                fill(2, 3, EnergyType.PETROL, "20", "1250", null));
        assertThat(MileageEngine.computeIntervals(VehicleFuelType.PETROL, fills, Map.of(), p)).isEmpty();
    }

    @Test
    void weightedFleetMileageIsNotSimpleAverage() {
        // vehicle A 500 km / 20 kg = 25; vehicle B 100 km / 10 kg = 10; simple average 17.5; weighted 20
        assertThat(MileageEngine.weightedFleetMileage(List.of(
                new BigDecimal[]{b("500"), b("20")}, new BigDecimal[]{b("100"), b("10")})))
                .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("20"));
    }

    @Test
    void zeroDenominatorsGiveEmptyNotZero() {
        assertThat(Calc.mileage(b("100"), BigDecimal.ZERO)).isEmpty();
        assertThat(Calc.mileage(b("100"), null)).isEmpty();
        assertThat(Calc.costPerKm(b("100"), BigDecimal.ZERO)).isEmpty();
        assertThat(Calc.weightedUnitPrice(b("100"), BigDecimal.ZERO)).isEmpty();
        assertThat(MileageEngine.weightedFleetMileage(List.of())).isEmpty();
    }
}
