package com.lrtaxi.fleet.calc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** Service-due calculation (docs/CALCULATION_RULES.md section 10). */
public final class ServiceDue {

    public enum Status { OVERDUE, DUE_SOON, MISSING_SERVICE_RECORD, MISSING_BASELINE, UNRELIABLE_BASELINE, OK }

    /**
     * @param lastServiceDate      date of the latest interval-resetting service (null = none on record)
     * @param lastServiceOdometer  odometer at that service (may be null)
     * @param explicitNextOdometer user-entered override of next service odometer (null = compute)
     * @param explicitNextDate     user-entered override of next service date (null = compute)
     * @param baseline             latest trusted odometer (null = none)
     * @param gpsKmAfterBaseline   sum of current GPS km for days strictly after baseline date
     */
    public record Input(LocalDate lastServiceDate, BigDecimal lastServiceOdometer,
                        BigDecimal explicitNextOdometer, LocalDate explicitNextDate,
                        int intervalKm, int intervalMonths,
                        Odometer.Baseline baseline, BigDecimal gpsKmAfterBaseline,
                        LocalDate today, int dueSoonKm, int dueSoonDays) {
    }

    public record Result(Status status, BigDecimal currentOdometer, BigDecimal nextServiceOdometer, BigDecimal remainingKm,
                         LocalDate nextServiceDate, Long daysRemaining, List<String> reasons) {
    }

    private ServiceDue() {
    }

    public static Result evaluate(Input in) {
        List<String> reasons = new ArrayList<>();

        BigDecimal current = null;
        if (in.baseline() != null) {
            current = in.baseline().km().add(Calc.nz(in.gpsKmAfterBaseline())).setScale(Calc.DISTANCE_SCALE, Calc.ROUNDING);
        }

        if (in.lastServiceDate() == null) {
            reasons.add("No service record that resets the service interval");
            return new Result(Status.MISSING_SERVICE_RECORD, current, null, null, null, null, reasons);
        }

        BigDecimal next = in.explicitNextOdometer();
        if (next == null && in.lastServiceOdometer() != null) {
            next = in.lastServiceOdometer().add(BigDecimal.valueOf(in.intervalKm()));
        }
        if (next != null) {
            next = next.setScale(Calc.DISTANCE_SCALE, Calc.ROUNDING);
        }

        LocalDate nextDate = in.explicitNextDate();
        if (nextDate == null && in.intervalMonths() > 0) {
            nextDate = in.lastServiceDate().plusMonths(in.intervalMonths());
        }
        Long daysRemaining = nextDate == null ? null : ChronoUnit.DAYS.between(in.today(), nextDate);

        Status dateStatus = Status.OK;
        if (daysRemaining != null) {
            if (daysRemaining < 0) {
                dateStatus = Status.OVERDUE;
                reasons.add("Service date " + IndianFormat.date(nextDate) + " has passed");
            } else if (daysRemaining <= in.dueSoonDays()) {
                dateStatus = Status.DUE_SOON;
                reasons.add("Service date " + IndianFormat.date(nextDate) + " is within " + in.dueSoonDays() + " days");
            }
        }

        BigDecimal remaining = null;
        Status kmStatus;
        if (in.baseline() == null) {
            kmStatus = Status.MISSING_BASELINE;
            reasons.add("No trusted odometer reading");
        } else if (!in.baseline().reliable()) {
            kmStatus = Status.UNRELIABLE_BASELINE;
            reasons.add("Latest odometer reading is lower than an earlier reading");
        } else if (next == null) {
            kmStatus = Status.MISSING_BASELINE;
            reasons.add("Last service has no odometer reading");
        } else {
            remaining = next.subtract(current);
            if (remaining.signum() < 0) {
                kmStatus = Status.OVERDUE;
                reasons.add("Overdue by " + remaining.negate() + " km");
            } else if (remaining.compareTo(BigDecimal.valueOf(in.dueSoonKm())) <= 0) {
                kmStatus = Status.DUE_SOON;
                reasons.add(remaining + " km remaining");
            } else {
                kmStatus = Status.OK;
            }
        }

        Status status;
        if (kmStatus == Status.OVERDUE || dateStatus == Status.OVERDUE) {
            status = Status.OVERDUE;
        } else if (kmStatus == Status.MISSING_BASELINE || kmStatus == Status.UNRELIABLE_BASELINE) {
            status = kmStatus;
        } else if (kmStatus == Status.DUE_SOON || dateStatus == Status.DUE_SOON) {
            status = Status.DUE_SOON;
        } else {
            status = Status.OK;
        }
        return new Result(status, current, next, remaining, nextDate, daysRemaining, reasons);
    }
}
