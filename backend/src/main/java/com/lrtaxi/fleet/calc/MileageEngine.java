package com.lrtaxi.fleet.calc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Authoritative fuel-efficiency engine. Pure Java (no Spring, no database) so that every rule is unit-testable.
 * See docs/CALCULATION_RULES.md section 6.
 */
public final class MileageEngine {

    public enum AccountingPolicy { AUTO, FULL_TANK_ONLY }

    public enum Method { FULL_TANK, SUCCESSIVE_REFUEL }

    public enum DistanceSource { ODOMETER, GPS }

    public enum ExclusionReason {
        DECREASING_ODOMETER, ZERO_DISTANCE, ZERO_QUANTITY, INCOMPLETE_GPS, SAME_DAY_NO_ODOMETER, MIXED_FUEL, IMPLAUSIBLE_MILEAGE
    }

    /** One refuelling / charging event. odometer and fullTank are optional. */
    public record FuelFill(long id, LocalDate date, EnergyType energy, BigDecimal quantity, BigDecimal odometer, Boolean fullTank) {
    }

    public record Range(BigDecimal min, BigDecimal max) {
        boolean contains(BigDecimal v) {
            return (min == null || v.compareTo(min) >= 0) && (max == null || v.compareTo(max) <= 0);
        }
    }

    public record Policy(AccountingPolicy accounting, boolean bifuelAttribution, Map<EnergyType, Range> plausible) {
        public static Policy defaults() {
            Map<EnergyType, Range> p = new EnumMap<>(EnergyType.class);
            p.put(EnergyType.PETROL, new Range(new BigDecimal("3"), new BigDecimal("35")));
            p.put(EnergyType.DIESEL, new Range(new BigDecimal("2"), new BigDecimal("35")));
            p.put(EnergyType.CNG, new Range(new BigDecimal("5"), new BigDecimal("45")));
            p.put(EnergyType.ELECTRICITY, new Range(new BigDecimal("2"), new BigDecimal("15")));
            return new Policy(AccountingPolicy.AUTO, true, p);
        }
    }

    public record Interval(
            EnergyType energy, Method method, DistanceSource distanceSource,
            long startFillId, long endFillId, LocalDate startDate, LocalDate endDate,
            BigDecimal startOdometer, BigDecimal endOdometer,
            BigDecimal distanceKm, BigDecimal quantity, BigDecimal mileage,
            ExclusionReason exclusion) {

        public boolean eligible() {
            return exclusion == null;
        }
    }

    public enum SummaryStatus { OK, INSUFFICIENT_DATA, MIXED_FUEL }

    public record Summary(EnergyType energy, BigDecimal eligibleKm, BigDecimal eligibleQuantity, BigDecimal mileage,
                          int eligibleIntervals, int excludedIntervals, SummaryStatus status) {
    }

    private static final Comparator<FuelFill> FILL_ORDER = Comparator
            .<FuelFill, LocalDate>comparing(FuelFill::date)
            .thenComparing(FuelFill::odometer, Comparator.nullsLast(Comparator.<BigDecimal>naturalOrder()))
            .thenComparingLong(FuelFill::id);

    private MileageEngine() {
    }

    /**
     * Builds efficiency intervals for one vehicle.
     *
     * @param fills  all non-void fills of the vehicle (any energy, any order)
     * @param gpsKm  current GPS daily km by date (days without a record are absent, not zero)
     */
    public static List<Interval> computeIntervals(VehicleFuelType vehicleFuel, List<FuelFill> fills,
                                                  Map<LocalDate, BigDecimal> gpsKm, Policy policy) {
        List<Interval> out = new ArrayList<>();
        for (EnergyType energy : vehicleFuel.allowedEnergy()) {
            List<FuelFill> ef = fills.stream().filter(f -> f.energy() == energy).sorted(FILL_ORDER).toList();
            if (ef.size() < 2) {
                continue;
            }
            boolean useFullTank = policy.accounting() == AccountingPolicy.FULL_TANK_ONLY
                    || ef.stream().anyMatch(f -> Boolean.TRUE.equals(f.fullTank()));
            if (useFullTank) {
                int prevAnchor = -1;
                for (int i = 0; i < ef.size(); i++) {
                    if (!Boolean.TRUE.equals(ef.get(i).fullTank())) {
                        continue;
                    }
                    if (prevAnchor >= 0) {
                        out.add(build(vehicleFuel, energy, Method.FULL_TANK, ef.subList(prevAnchor, i + 1), fills, gpsKm, policy));
                    }
                    prevAnchor = i;
                }
            } else {
                for (int k = 1; k < ef.size(); k++) {
                    out.add(build(vehicleFuel, energy, Method.SUCCESSIVE_REFUEL, ef.subList(k - 1, k + 1), fills, gpsKm, policy));
                }
            }
        }
        return out;
    }

    /** segment = start fill, any intermediate fills, end fill (already sorted). Quantity = all fills after the start. */
    private static Interval build(VehicleFuelType vehicleFuel, EnergyType energy, Method method, List<FuelFill> segment,
                                  List<FuelFill> allFills, Map<LocalDate, BigDecimal> gpsKm, Policy policy) {
        FuelFill start = segment.get(0);
        FuelFill end = segment.get(segment.size() - 1);
        BigDecimal qty = BigDecimal.ZERO;
        for (int i = 1; i < segment.size(); i++) {
            qty = qty.add(Calc.nz(segment.get(i).quantity()));
        }

        ExclusionReason exclusion = null;
        DistanceSource source;
        BigDecimal distance = null;

        if (start.odometer() != null && end.odometer() != null) {
            source = DistanceSource.ODOMETER;
            distance = end.odometer().subtract(start.odometer());
            if (!odometersAscending(segment)) {
                exclusion = ExclusionReason.DECREASING_ODOMETER;
            }
        } else {
            source = DistanceSource.GPS;
            if (!start.date().isBefore(end.date())) {
                exclusion = ExclusionReason.SAME_DAY_NO_ODOMETER;
            } else {
                distance = gpsDistance(start.date(), end.date(), gpsKm);
                if (distance == null) {
                    exclusion = ExclusionReason.INCOMPLETE_GPS;
                }
            }
        }

        if (exclusion == null && distance.signum() < 0) {
            exclusion = ExclusionReason.DECREASING_ODOMETER;
        }
        if (exclusion == null && distance.signum() == 0) {
            exclusion = ExclusionReason.ZERO_DISTANCE;
        }
        if (exclusion == null && qty.signum() <= 0) {
            exclusion = ExclusionReason.ZERO_QUANTITY;
        }
        if (exclusion == null && vehicleFuel.isMultiEnergy()
                && (!policy.bifuelAttribution() || otherEnergyBoughtWithin(energy, start.date(), end.date(), allFills))) {
            exclusion = ExclusionReason.MIXED_FUEL;
        }

        BigDecimal mileage = null;
        if (distance != null && distance.signum() > 0) {
            mileage = Calc.mileage(distance, qty).orElse(null);
        }
        if (exclusion == null) {
            Range range = policy.plausible() == null ? null : policy.plausible().get(energy);
            if (mileage == null) {
                exclusion = ExclusionReason.ZERO_QUANTITY;
            } else if (range != null && !range.contains(mileage)) {
                exclusion = ExclusionReason.IMPLAUSIBLE_MILEAGE;
            }
        }

        return new Interval(energy, method, source, start.id(), end.id(), start.date(), end.date(),
                start.odometer(), end.odometer(),
                distance == null ? null : distance.setScale(2, Calc.ROUNDING),
                qty.setScale(3, Calc.ROUNDING), mileage, exclusion);
    }

    /** All non-null odometers in the segment must be non-decreasing. */
    private static boolean odometersAscending(List<FuelFill> segment) {
        BigDecimal last = null;
        for (FuelFill f : segment) {
            if (f.odometer() == null) {
                continue;
            }
            if (last != null && f.odometer().compareTo(last) < 0) {
                return false;
            }
            last = f.odometer();
        }
        return true;
    }

    /** Sum of GPS km for days in (startDate, endDate]; null if any day is missing. */
    static BigDecimal gpsDistance(LocalDate startDate, LocalDate endDate, Map<LocalDate, BigDecimal> gpsKm) {
        if (gpsKm == null) {
            return null;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (LocalDate d = startDate.plusDays(1); !d.isAfter(endDate); d = d.plusDays(1)) {
            BigDecimal km = gpsKm.get(d);
            if (km == null) {
                return null;
            }
            total = total.add(km);
        }
        return total;
    }

    private static boolean otherEnergyBoughtWithin(EnergyType energy, LocalDate from, LocalDate to, List<FuelFill> all) {
        for (FuelFill f : all) {
            if (f.energy() != energy && !f.date().isBefore(from) && !f.date().isAfter(to)) {
                return true;
            }
        }
        return false;
    }

    /** Weighted summary per energy: sum of eligible km / sum of eligible quantity. */
    public static Map<EnergyType, Summary> summarize(List<Interval> intervals) {
        Map<EnergyType, Summary> out = new EnumMap<>(EnergyType.class);
        for (EnergyType energy : EnergyType.values()) {
            List<Interval> mine = intervals.stream().filter(i -> i.energy() == energy).toList();
            if (mine.isEmpty()) {
                continue;
            }
            BigDecimal km = BigDecimal.ZERO;
            BigDecimal qty = BigDecimal.ZERO;
            int eligible = 0;
            int mixed = 0;
            for (Interval i : mine) {
                if (i.eligible()) {
                    km = km.add(i.distanceKm());
                    qty = qty.add(i.quantity());
                    eligible++;
                } else if (i.exclusion() == ExclusionReason.MIXED_FUEL) {
                    mixed++;
                }
            }
            Optional<BigDecimal> mileage = Calc.mileage(km, qty);
            SummaryStatus status;
            if (eligible > 0 && mileage.isPresent()) {
                status = SummaryStatus.OK;
            } else if (mixed > 0) {
                status = SummaryStatus.MIXED_FUEL;
            } else {
                status = SummaryStatus.INSUFFICIENT_DATA;
            }
            out.put(energy, new Summary(energy, km, qty, mileage.orElse(null), eligible, mine.size() - eligible, status));
        }
        return out;
    }

    /** Fleet mileage = total eligible km / total eligible quantity. Never a simple average of vehicle figures. */
    public static Optional<BigDecimal> weightedFleetMileage(List<BigDecimal[]> kmAndQuantity) {
        BigDecimal km = BigDecimal.ZERO;
        BigDecimal qty = BigDecimal.ZERO;
        for (BigDecimal[] pair : kmAndQuantity) {
            km = km.add(Calc.nz(pair[0]));
            qty = qty.add(Calc.nz(pair[1]));
        }
        return Calc.mileage(km, qty);
    }
}
