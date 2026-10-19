package com.lrtaxi.fleet.calc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collapses GPS rows into one figure per vehicle per day without double counting
 * (docs/CALCULATION_RULES.md section 4). The Python worker mirrors this logic only for the preview;
 * this class decides what is saved.
 */
public final class GpsDailyDedup {

    public enum Mode { DAILY_TOTAL, TRIP }

    public enum Outcome { ACCEPTED, CONFLICT }

    /** tripStart is the full start timestamp text (required in TRIP mode). */
    public record Row(int rowNumber, long vehicleId, LocalDate date, BigDecimal distanceKm, String tripStart) {
    }

    public record DayResult(long vehicleId, LocalDate date, Outcome outcome, BigDecimal distanceKm, int tripCount,
                            List<Integer> usedRows, List<Integer> duplicateRows, List<Integer> conflictRows, String message) {
    }

    private record Key(long vehicleId, LocalDate date) {
    }

    private GpsDailyDedup() {
    }

    public static List<DayResult> resolve(List<Row> rows, Mode mode) {
        Map<Key, List<Row>> groups = new LinkedHashMap<>();
        for (Row r : rows) {
            groups.computeIfAbsent(new Key(r.vehicleId(), r.date()), k -> new ArrayList<>()).add(r);
        }
        List<DayResult> out = new ArrayList<>(groups.size());
        for (Map.Entry<Key, List<Row>> e : groups.entrySet()) {
            out.add(mode == Mode.DAILY_TOTAL ? dailyTotal(e.getKey(), e.getValue()) : trips(e.getKey(), e.getValue()));
        }
        return out;
    }

    private static DayResult dailyTotal(Key key, List<Row> group) {
        Set<String> distinct = new LinkedHashSet<>();
        for (Row r : group) {
            distinct.add(r.distanceKm().stripTrailingZeros().toPlainString());
        }
        List<Integer> rowNumbers = group.stream().map(Row::rowNumber).toList();
        if (distinct.size() > 1) {
            return new DayResult(key.vehicleId(), key.date(), Outcome.CONFLICT, null, 0, List.of(), List.of(), rowNumbers,
                    "Daily total has conflicting values " + distinct + " for the same vehicle and date; none imported");
        }
        return new DayResult(key.vehicleId(), key.date(), Outcome.ACCEPTED, group.get(0).distanceKm(), 0,
                List.of(rowNumbers.get(0)), rowNumbers.subList(1, rowNumbers.size()), List.of(),
                group.size() > 1 ? (group.size() - 1) + " repeated copy/copies of the daily total ignored" : null);
    }

    private static DayResult trips(Key key, List<Row> group) {
        Map<String, Row> unique = new LinkedHashMap<>();
        List<Integer> duplicates = new ArrayList<>();
        for (Row r : group) {
            String identity = r.tripStart() + "|" + r.distanceKm().stripTrailingZeros().toPlainString();
            if (unique.putIfAbsent(identity, r) != null) {
                duplicates.add(r.rowNumber());
            }
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Row r : unique.values()) {
            total = total.add(r.distanceKm());
        }
        return new DayResult(key.vehicleId(), key.date(), Outcome.ACCEPTED, total, unique.size(),
                unique.values().stream().map(Row::rowNumber).toList(), duplicates, List.of(),
                duplicates.isEmpty() ? null : duplicates.size() + " repeated trip row(s) ignored");
    }
}
