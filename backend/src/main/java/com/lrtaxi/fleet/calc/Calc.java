package com.lrtaxi.fleet.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/** Decimal helpers. All business arithmetic goes through BigDecimal with HALF_UP rounding. */
public final class Calc {

    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    public static final int MONEY_SCALE = 2;
    public static final int MILEAGE_SCALE = 3;
    public static final int UNIT_PRICE_SCALE = 4;
    public static final int COST_PER_KM_SCALE = 2;
    public static final int PERCENT_SCALE = 2;
    public static final int DISTANCE_SCALE = 1;
    /** Internal precision for intermediate division before final rounding. */
    private static final int WORK_SCALE = 10;

    private Calc() {
    }

    public static BigDecimal money(BigDecimal v) {
        return v == null ? null : v.setScale(MONEY_SCALE, ROUNDING);
    }

    public static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    public static boolean isPositive(BigDecimal v) {
        return v != null && v.signum() > 0;
    }

    /**
     * numerator / denominator rounded to {@code scale}. Empty when either side is missing or the
     * denominator is zero or negative: a missing ratio is reported as "insufficient data", never as 0.
     */
    public static Optional<BigDecimal> ratio(BigDecimal numerator, BigDecimal denominator, int scale) {
        if (numerator == null || !isPositive(denominator)) {
            return Optional.empty();
        }
        return Optional.of(numerator.divide(denominator, WORK_SCALE, ROUNDING).setScale(scale, ROUNDING));
    }

    public static Optional<BigDecimal> mileage(BigDecimal km, BigDecimal quantity) {
        return ratio(km, quantity, MILEAGE_SCALE);
    }

    public static Optional<BigDecimal> weightedUnitPrice(BigDecimal totalAmount, BigDecimal totalQuantity) {
        return ratio(totalAmount, totalQuantity, UNIT_PRICE_SCALE);
    }

    public static Optional<BigDecimal> costPerKm(BigDecimal amount, BigDecimal km) {
        return ratio(amount, km, COST_PER_KM_SCALE);
    }

    /** ((actual - expected) / expected) * 100. */
    public static Optional<BigDecimal> variancePct(BigDecimal actual, BigDecimal expected) {
        if (actual == null || !isPositive(expected)) {
            return Optional.empty();
        }
        BigDecimal diff = actual.subtract(expected);
        return Optional.of(diff.multiply(BigDecimal.valueOf(100))
                .divide(expected, WORK_SCALE, ROUNDING)
                .setScale(PERCENT_SCALE, ROUNDING));
    }

    public static boolean sameValue(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.compareTo(b) == 0;
    }
}
