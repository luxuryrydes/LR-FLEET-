package com.lrtaxi.fleet.calc;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Monthly mileage calculator (docs/CALCULATION_RULES.md section 14). Purchase basis for one calendar month:
 * <ul>
 *   <li>Cost per km = fuel expense in the month / GPS km in the month.</li>
 *   <li>Monthly mileage = GPS km in the month / fuel quantity bought in the month. Only when exactly one fuel was
 *       bought; km cannot be split between CNG and petrol, so mixed months show no km/kg figure.</li>
 * </ul>
 * Missing km or fuel is reported as a status, never as zero.
 */
public final class MonthlyMileage {

    /** IMPLAUSIBLE: km/fuel outside the configured plausible range - usually missing fuel bills or GPS days. */
    public enum Status { OK, NO_KM, NO_FUEL, MIXED_FUEL, IMPLAUSIBLE }

    public record VehicleMonth(BigDecimal km, Map<EnergyType, BigDecimal> quantity, BigDecimal expense) {
    }

    public record Result(Status status, BigDecimal costPerKm, EnergyType mileageEnergy, BigDecimal mileage) {
    }

    private MonthlyMileage() {
    }

    public static Result evaluate(VehicleMonth m) {
        return evaluate(m, Map.of());
    }

    public static Result evaluate(VehicleMonth m, Map<EnergyType, MileageEngine.Range> plausible) {
        boolean hasKm = Calc.isPositive(m.km());
        boolean hasFuel = Calc.isPositive(m.expense()) || m.quantity().values().stream().anyMatch(Calc::isPositive);
        if (!hasKm) {
            return new Result(Status.NO_KM, null, null, null);
        }
        if (!hasFuel) {
            return new Result(Status.NO_FUEL, null, null, null);
        }
        BigDecimal cpk = Calc.costPerKm(m.expense(), m.km()).orElse(null);
        List<EnergyType> bought = m.quantity().entrySet().stream().filter(e -> Calc.isPositive(e.getValue())).map(Map.Entry::getKey).toList();
        if (bought.size() != 1) {
            return new Result(Status.MIXED_FUEL, cpk, null, null);
        }
        EnergyType e = bought.get(0);
        BigDecimal mileage = Calc.mileage(m.km(), m.quantity().get(e)).orElse(null);
        MileageEngine.Range range = plausible == null ? null : plausible.get(e);
        boolean ok = mileage == null || range == null
                || ((range.min() == null || mileage.compareTo(range.min()) >= 0) && (range.max() == null || mileage.compareTo(range.max()) <= 0));
        return new Result(ok ? Status.OK : Status.IMPLAUSIBLE, cpk, e, mileage);
    }

    /** Fleet totals: Σ expense / Σ km over vehicles with both; per fuel Σ km / Σ qty over single-fuel vehicles. */
    public record FleetTotals(BigDecimal km, BigDecimal expense, BigDecimal costPerKm, Map<EnergyType, BigDecimal[]> byEnergy,
                              Map<EnergyType, BigDecimal> mileage, int vehiclesIncluded) {
    }

    public static FleetTotals totals(List<VehicleMonth> months) {
        return totals(months, Map.of());
    }

    /** Implausible vehicles count towards Rs/km but not towards fleet km/kg. */
    public static FleetTotals totals(List<VehicleMonth> months, Map<EnergyType, MileageEngine.Range> plausible) {
        BigDecimal km = BigDecimal.ZERO;
        BigDecimal exp = BigDecimal.ZERO;
        int included = 0;
        Map<EnergyType, BigDecimal[]> byEnergy = new EnumMap<>(EnergyType.class);
        for (VehicleMonth m : months) {
            Result r = evaluate(m, plausible);
            if (r.status() == Status.OK || r.status() == Status.MIXED_FUEL || r.status() == Status.IMPLAUSIBLE) {
                km = km.add(m.km());
                exp = exp.add(Calc.nz(m.expense()));
                included++;
            }
            if (r.status() == Status.OK) {
                BigDecimal[] t = byEnergy.computeIfAbsent(r.mileageEnergy(), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                t[0] = t[0].add(m.km());
                t[1] = t[1].add(m.quantity().get(r.mileageEnergy()));
            }
        }
        Map<EnergyType, BigDecimal> mileage = new EnumMap<>(EnergyType.class);
        for (Map.Entry<EnergyType, BigDecimal[]> e : byEnergy.entrySet()) {
            Optional<BigDecimal> v = Calc.mileage(e.getValue()[0], e.getValue()[1]);
            v.ifPresent(x -> mileage.put(e.getKey(), x));
        }
        return new FleetTotals(km, Calc.money(exp), Calc.costPerKm(exp, km).orElse(null), byEnergy, mileage, included);
    }
}
