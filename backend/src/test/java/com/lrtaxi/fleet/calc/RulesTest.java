package com.lrtaxi.fleet.calc;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Fuel amounts, rounding, odometer progression, service due and GPS duplicate handling. */
class RulesTest {

    static BigDecimal b(String s) {
        return new BigDecimal(s);
    }

    static final BigDecimal TOL = b("1.00");
    static final LocalDate T = LocalDate.of(2026, 10, 1);

    // ---- Fuel amounts --------------------------------------------------------------------

    @Test
    void invoiceAmountIsAuthoritativeAndMismatchFlagged() {
        var r = FuelAmounts.resolve(new FuelAmounts.Input(b("20"), b("105"), null, null, null, b("2050.00")), TOL);
        assertThat(r.expenseAmount()).isEqualByComparingTo("2050.00");     // not overwritten with 2100
        assertThat(r.calculatedAmount()).isEqualByComparingTo("2100.00");
        assertThat(r.source()).isEqualTo(FuelAmounts.Source.INVOICE);
        assertThat(r.mismatch()).isTrue();
        assertThat(r.difference()).isEqualByComparingTo("-50.00");
    }

    @Test
    void discountAndTaxApplied() {
        var r = FuelAmounts.resolve(new FuelAmounts.Input(b("10"), b("90.50"), b("905.00"), b("25"), b("0"), null), TOL);
        assertThat(r.expenseAmount()).isEqualByComparingTo("880.00");
        assertThat(r.source()).isEqualTo(FuelAmounts.Source.GROSS_LESS_DISCOUNT);
        assertThat(r.mismatch()).isFalse();
    }

    @Test
    void withinToleranceIsNotMismatch() {
        var r = FuelAmounts.resolve(new FuelAmounts.Input(b("12.34"), b("94.72"), null, null, null, b("1169.00")), TOL);
        // 12.34 * 94.72 = 1168.8448 -> 1168.84 ; diff 0.16
        assertThat(r.mismatch()).isFalse();
    }

    @Test
    void unitPriceDerivedFromGross() {
        var r = FuelAmounts.resolve(new FuelAmounts.Input(b("7.5"), null, b("675.00"), null, null, null), TOL);
        assertThat(r.unitPrice()).isEqualByComparingTo("90.0000");
        assertThat(r.unitPriceDerived()).isTrue();
    }

    @Test
    void invalidQuantitiesRejected() {
        assertThatThrownBy(() -> FuelAmounts.resolve(new FuelAmounts.Input(BigDecimal.ZERO, b("100"), null, null, null, null), TOL))
                .hasMessageContaining("Quantity");
        assertThatThrownBy(() -> FuelAmounts.resolve(new FuelAmounts.Input(null, b("100"), null, null, null, null), TOL))
                .hasMessageContaining("Quantity");
        assertThatThrownBy(() -> FuelAmounts.resolve(new FuelAmounts.Input(b("-3"), b("100"), null, null, null, null), TOL))
                .hasMessageContaining("Quantity");
        assertThatThrownBy(() -> FuelAmounts.resolve(new FuelAmounts.Input(b("3"), null, null, null, null, null), TOL))
                .hasMessageContaining("No amount");
    }

    @Test
    void currencyRoundingHalfUp() {
        assertThat(Calc.money(b("10.005"))).isEqualByComparingTo("10.01");
        assertThat(Calc.money(b("10.004"))).isEqualByComparingTo("10.00");
        assertThat(Calc.money(b("-10.005"))).isEqualByComparingTo("-10.01");
        // 0.1 + 0.2 is exact with BigDecimal
        assertThat(b("0.1").add(b("0.2"))).isEqualByComparingTo("0.3");
    }

    @Test
    void costPerKmComponents() {
        BigDecimal fuel = b("12500.00");
        BigDecimal maint = b("2500.00");
        BigDecimal km = b("5000");
        assertThat(Calc.costPerKm(fuel, km)).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("2.50"));
        assertThat(Calc.costPerKm(maint, km)).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("0.50"));
        assertThat(Calc.costPerKm(fuel.add(maint), km)).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("3.00"));
    }

    // ---- Odometer -------------------------------------------------------------------------

    static Odometer.Reading r(long id, int day, String km) {
        return new Odometer.Reading(id, T.plusDays(day), b(km), true);
    }

    @Test
    void odometerProgression() {
        List<Odometer.Reading> existing = List.of(r(1, 0, "10000"), r(2, 10, "11000"));
        BigDecimal max = b("1500");
        assertThat(Odometer.check(existing, T.plusDays(5), b("10500"), max).ok()).isTrue();
        assertThat(Odometer.check(existing, T.plusDays(5), b("9990"), max).problem()).isEqualTo(Odometer.Problem.DECREASES_FROM_PREVIOUS);
        assertThat(Odometer.check(existing, T.plusDays(5), b("11200"), max).problem()).isEqualTo(Odometer.Problem.EXCEEDS_LATER_READING);
        assertThat(Odometer.check(existing, T.plusDays(11), b("20000"), max).problem()).isEqualTo(Odometer.Problem.IMPLAUSIBLE_JUMP);
    }

    @Test
    void baselineIsLatestTrustedAndUnreliableWhenItGoesBackwards() {
        Odometer.Baseline ok = Odometer.baseline(List.of(r(1, 0, "10000"), r(2, 10, "11000")));
        assertThat(ok.km()).isEqualByComparingTo("11000");
        assertThat(ok.reliable()).isTrue();

        Odometer.Baseline bad = Odometer.baseline(List.of(r(1, 0, "10000"), r(2, 10, "9000")));
        assertThat(bad.reliable()).isFalse();

        Odometer.Baseline ignoresUntrusted = Odometer.baseline(List.of(r(1, 0, "10000"),
                new Odometer.Reading(2, T.plusDays(10), b("9000"), false)));
        assertThat(ignoresUntrusted.km()).isEqualByComparingTo("10000");
        assertThat(ignoresUntrusted.reliable()).isTrue();
        assertThat(Odometer.baseline(List.of())).isNull();
    }

    // ---- Service due ----------------------------------------------------------------------

    static ServiceDue.Input svc(LocalDate lastDate, String lastOdo, Odometer.Baseline base, String gpsAfter, int months) {
        return new ServiceDue.Input(lastDate, lastOdo == null ? null : b(lastOdo), null, null, 10000, months,
                base, gpsAfter == null ? null : b(gpsAfter), T, 500, 15);
    }

    @Test
    void serviceBaselineResetDoesNotDoubleCount() {
        // Old baseline 40,000 on day -60 with 12,000 GPS km since would give 52,000.
        // A new service on day -10 at 50,000 becomes the baseline; only GPS km after day -10 (800) count.
        Odometer.Baseline afterService = Odometer.baseline(List.of(
                new Odometer.Reading(1, T.minusDays(60), b("40000"), true),
                new Odometer.Reading(2, T.minusDays(10), b("50000"), true)));
        var res = ServiceDue.evaluate(svc(T.minusDays(10), "50000", afterService, "800", 6));
        assertThat(res.currentOdometer()).isEqualByComparingTo("50800");
        assertThat(res.nextServiceOdometer()).isEqualByComparingTo("60000");
        assertThat(res.remainingKm()).isEqualByComparingTo("9200");
        assertThat(res.status()).isEqualTo(ServiceDue.Status.OK);
    }

    @Test
    void dateBasedReminderOverridesLowKm() {
        Odometer.Baseline base = new Odometer.Baseline(T.minusDays(1), b("51000"), true, 1);
        var res = ServiceDue.evaluate(svc(T.minusMonths(7), "50000", base, "0", 6));
        assertThat(res.status()).isEqualTo(ServiceDue.Status.OVERDUE);
        assertThat(res.remainingKm()).isEqualByComparingTo("9000");
    }

    @Test
    void missingServiceRecordAndBaseline() {
        assertThat(ServiceDue.evaluate(svc(null, null, null, null, 6)).status()).isEqualTo(ServiceDue.Status.MISSING_SERVICE_RECORD);
        assertThat(ServiceDue.evaluate(svc(T.minusDays(5), "50000", null, null, 6)).status()).isEqualTo(ServiceDue.Status.MISSING_BASELINE);
        Odometer.Baseline unreliable = new Odometer.Baseline(T, b("100"), false, 1);
        assertThat(ServiceDue.evaluate(svc(T.minusDays(5), "50000", unreliable, "0", 6)).status())
                .isEqualTo(ServiceDue.Status.UNRELIABLE_BASELINE);
    }

    @Test
    void explicitNextServiceOverride() {
        Odometer.Baseline base = new Odometer.Baseline(T, b("54800"), true, 1);
        var in = new ServiceDue.Input(T.minusDays(5), b("50000"), b("55000"), null, 10000, 0, base, b("0"), T, 500, 15);
        var res = ServiceDue.evaluate(in);
        assertThat(res.nextServiceOdometer()).isEqualByComparingTo("55000");
        assertThat(res.status()).isEqualTo(ServiceDue.Status.DUE_SOON);
    }

    // ---- GPS duplicate handling -----------------------------------------------------------

    static GpsDailyDedup.Row g(int row, long v, String km, String start) {
        return new GpsDailyDedup.Row(row, v, T, b(km), start);
    }

    @Test
    void dailyTotalCopiesAreNeverSummed() {
        var res = GpsDailyDedup.resolve(List.of(g(2, 1, "120.5", null), g(3, 1, "120.50", null), g(4, 1, "120.5", null)),
                GpsDailyDedup.Mode.DAILY_TOTAL);
        assertThat(res).hasSize(1);
        assertThat(res.get(0).outcome()).isEqualTo(GpsDailyDedup.Outcome.ACCEPTED);
        assertThat(res.get(0).distanceKm()).isEqualByComparingTo("120.5");
        assertThat(res.get(0).duplicateRows()).containsExactly(3, 4);
    }

    @Test
    void dailyTotalConflictImportsNothing() {
        var res = GpsDailyDedup.resolve(List.of(g(2, 1, "120", null), g(3, 1, "95", null)), GpsDailyDedup.Mode.DAILY_TOTAL);
        assertThat(res.get(0).outcome()).isEqualTo(GpsDailyDedup.Outcome.CONFLICT);
        assertThat(res.get(0).conflictRows()).containsExactly(2, 3);
        assertThat(res.get(0).distanceKm()).isNull();
    }

    @Test
    void tripsAreSummedButRepeatedTripsDropped() {
        var res = GpsDailyDedup.resolve(List.of(
                g(2, 1, "40", "2026-10-01T08:00"),
                g(3, 1, "35.5", "2026-10-01T12:30"),
                g(4, 1, "40", "2026-10-01T08:00"),          // same trip uploaded twice
                g(5, 2, "10", "2026-10-01T09:00")), GpsDailyDedup.Mode.TRIP);
        assertThat(res).hasSize(2);
        assertThat(res.get(0).distanceKm()).isEqualByComparingTo("75.5");
        assertThat(res.get(0).tripCount()).isEqualTo(2);
        assertThat(res.get(0).duplicateRows()).containsExactly(4);
        assertThat(res.get(1).distanceKm()).isEqualByComparingTo("10");
    }

    @Test
    void energyParsingAcceptsIndianSpellings() {
        assertThat(EnergyType.parse("HSD")).isEqualTo(EnergyType.DIESEL);
        assertThat(EnergyType.parse("ms")).isEqualTo(EnergyType.PETROL);
        assertThat(EnergyType.parse("C.N.G.")).isEqualTo(EnergyType.CNG);
        assertThat(EnergyType.parse("EV")).isEqualTo(EnergyType.ELECTRICITY);
        assertThat(EnergyType.parse("water")).isNull();
        assertThat(VehicleFuelType.CNG_PETROL.allows(EnergyType.PETROL)).isTrue();
        assertThat(VehicleFuelType.CNG.allows(EnergyType.PETROL)).isFalse();
    }
}
