package com.lrtaxi.fleet.calc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

/** Odometer progression checks and baseline resolution (docs/CALCULATION_RULES.md sections 10 and 11). */
public final class Odometer {

    public record Reading(long id, LocalDate date, BigDecimal km, boolean trusted) {
    }

    public enum Problem { DECREASES_FROM_PREVIOUS, EXCEEDS_LATER_READING, IMPLAUSIBLE_JUMP }

    public record Check(boolean ok, Problem problem, String message) {
        static Check pass() {
            return new Check(true, null, null);
        }
    }

    public record Baseline(LocalDate date, BigDecimal km, boolean reliable, long readingId) {
    }

    private static final Comparator<Reading> ORDER = Comparator
            .<Reading, LocalDate>comparing(Reading::date)
            .thenComparing(Reading::km)
            .thenComparingLong(Reading::id);

    private Odometer() {
    }

    /**
     * Validates a new reading against existing trusted readings of the same vehicle. Readings on the same day are not
     * compared with each other: statements without times (e.g. a CNG and a petrol fill on one BPCL date) cannot be
     * ordered within the day.
     */
    public static Check check(List<Reading> existing, LocalDate date, BigDecimal km, BigDecimal maxDailyKm) {
        Reading prev = null;
        Reading next = null;
        for (Reading r : existing) {
            if (!r.trusted() || r.date().isEqual(date)) {
                continue;
            }
            if (r.date().isBefore(date)) {
                if (prev == null || ORDER.compare(r, prev) > 0) {
                    prev = r;
                }
            } else if (next == null || ORDER.compare(r, next) < 0) {
                next = r;
            }
        }
        if (prev != null && km.compareTo(prev.km()) < 0) {
            return new Check(false, Problem.DECREASES_FROM_PREVIOUS,
                    "Odometer " + km + " is lower than " + prev.km() + " recorded on " + IndianFormat.date(prev.date()));
        }
        if (next != null && km.compareTo(next.km()) > 0) {
            return new Check(false, Problem.EXCEEDS_LATER_READING,
                    "Odometer " + km + " is higher than " + next.km() + " recorded later on " + IndianFormat.date(next.date()));
        }
        if (prev != null && maxDailyKm != null) {
            long days = ChronoUnit.DAYS.between(prev.date(), date) + 1;
            BigDecimal limit = maxDailyKm.multiply(BigDecimal.valueOf(days));
            if (km.subtract(prev.km()).compareTo(limit) > 0) {
                return new Check(false, Problem.IMPLAUSIBLE_JUMP,
                        "Odometer increased by " + km.subtract(prev.km()) + " km in " + days + " day(s); limit is " + limit + " km");
            }
        }
        return Check.pass();
    }

    /** Latest trusted reading. Unreliable if it is lower than any earlier trusted reading. Null when none. */
    public static Baseline baseline(List<Reading> readings) {
        List<Reading> trusted = readings.stream().filter(Reading::trusted).sorted(ORDER).toList();
        if (trusted.isEmpty()) {
            return null;
        }
        Reading latest = trusted.get(trusted.size() - 1);
        boolean reliable = true;
        for (Reading r : trusted) {
            if (r.date().isBefore(latest.date()) && r.km().compareTo(latest.km()) > 0) {
                reliable = false;
                break;
            }
        }
        return new Baseline(latest.date(), latest.km(), reliable, latest.id());
    }
}
