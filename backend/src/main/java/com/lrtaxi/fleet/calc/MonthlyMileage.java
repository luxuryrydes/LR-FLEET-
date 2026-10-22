package com.lrtaxi.fleet.calc;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Monthly mileage calculator (docs/CALCULATION_RULES.md section 14). Purchase basis for one calendar month:
 * <ul>
 *   <li>Cost per km = fuel expense in the month / GPS km in the month.</li>
 *   <li>Monthly mileage = GPS km in the month / fuel quantity bought in the month.</li>
 *   <li>Mixed fuel (e.g. CNG + petrol): GPS km / (kg + L) in the combined unit "km/kgL", as requested by LR Taxi.
 *       It is a comparison figure between mixed-fuel vehicles only and is never mixed into fleet km/kg or km/L.</li>
 * </ul>
 * Missing km or fuel is reported as a status, never as zero.
 */
public final class MonthlyMileage {

    /** IMPLAUSIBLE: km/fuel outside the configured plausible range - usually missing fuel bills or GPS days. */
    public enum Status { OK, NO_KM, NO_FUEL, MIXED_FUEL, IMPLAUSIBLE }

    public record VehicleMonth(BigDecimal km, Map<EnergyType, BigDecimal> quantity, BigDecimal expense) {
    }

    /**
     * @param mileageEnergy the single fuel the mileage refers to (null for mixed fuel)
     * @param quantity      the denominator used: one fuel's quantity, or the combined kg + L for mixed fuel
     * @param unit          "km/kg", "km/L", "km/kWh" or the combined "km/kgL"
     */
    public record Result(Status status, BigDecimal costPerKm, EnergyType mileageEnergy, BigDecimal mileage,
                         BigDecimal quantity, String unit) {
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
            return new Result(Status.NO_KM, null, null, null, null, null);
        }
        if (!hasFuel) {
            return new Result(Status.NO_FUEL, null, null, null, null, null);
        }
        BigDecimal cpk = Calc.costPerKm(m.expense(), m.km()).orElse(null);
        List<EnergyType> bought = m.quantity().entrySet().stream().filter(e -> Calc.isPositive(e.getValue())).map(Map.Entry::getKey).toList();
        if (bought.isEmpty()) {
            return new Result(Status.MIXED_FUEL, cpk, null, null, null, null);   // expense without quantities
        }
        if (bought.size() > 1) {
            BigDecimal combined = bought.stream().map(e -> m.quantity().get(e)).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new Result(Status.MIXED_FUEL, cpk, null, Calc.mileage(m.km(), combined).orElse(null), combined, combinedUnit(bought));
        }
        EnergyType e = bought.get(0);
        BigDecimal mileage = Calc.mileage(m.km(), m.quantity().get(e)).orElse(null);
        MileageEngine.Range range = plausible == null ? null : plausible.get(e);
        boolean ok = mileage == null || range == null
                || ((range.min() == null || mileage.compareTo(range.min()) >= 0) && (range.max() == null || mileage.compareTo(range.max()) <= 0));
        return new Result(ok ? Status.OK : Status.IMPLAUSIBLE, cpk, e, mileage, m.quantity().get(e), e.efficiencyUnit());
    }

    /** CNG + petrol -> "km/kgL"; petrol + electricity -> "km/LkWh". */
    static String combinedUnit(List<EnergyType> energies) {
        Set<String> units = new LinkedHashSet<>();
        for (EnergyType e : EnergyType.values()) {           // stable order: kg (CNG) before L before kWh
            if (energies.contains(e)) {
                units.add(e.efficiencyUnit().substring(3));      // "km/kg" -> "kg"
            }
        }
        return "km/" + String.join("", units.stream().sorted((a, b) -> order(a) - order(b)).toList());
    }

    private static int order(String unit) {
        return switch (unit) {
            case "kg" -> 0;
            case "L" -> 1;
            default -> 2;
        };
    }

    /**
     * Fleet totals: Σ expense / Σ km over vehicles with both; per fuel Σ km / Σ qty over single-fuel vehicles;
     * mixed-fuel vehicles separately as Σ km / Σ (kg + L).
     */
    public record FleetTotals(BigDecimal km, BigDecimal expense, BigDecimal costPerKm, Map<EnergyType, BigDecimal[]> byEnergy,
                              Map<EnergyType, BigDecimal> mileage, int vehiclesIncluded,
                              BigDecimal mixedKm, BigDecimal mixedQuantity, BigDecimal mixedMileage, int mixedVehicles) {
    }

    public static FleetTotals totals(List<VehicleMonth> months) {
        return totals(months, Map.of());
    }

    /** Implausible vehicles count towards Rs/km but not towards fleet km/kg. */
    public static FleetTotals totals(List<VehicleMonth> months, Map<EnergyType, MileageEngine.Range> plausible) {
        BigDecimal km = BigDecimal.ZERO;
        BigDecimal exp = BigDecimal.ZERO;
        BigDecimal mixedKm = BigDecimal.ZERO;
        BigDecimal mixedQty = BigDecimal.ZERO;
        int included = 0;
        int mixed = 0;
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
                t[1] = t[1].add(r.quantity());
            }
            if (r.status() == Status.MIXED_FUEL && r.quantity() != null) {
                mixedKm = mixedKm.add(m.km());
                mixedQty = mixedQty.add(r.quantity());
                mixed++;
            }
        }
        Map<EnergyType, BigDecimal> mileage = new EnumMap<>(EnergyType.class);
        for (Map.Entry<EnergyType, BigDecimal[]> e : byEnergy.entrySet()) {
            Optional<BigDecimal> v = Calc.mileage(e.getValue()[0], e.getValue()[1]);
            v.ifPresent(x -> mileage.put(e.getKey(), x));
        }
        return new FleetTotals(km, Calc.money(exp), Calc.costPerKm(exp, km).orElse(null), byEnergy, mileage, included,
                mixedKm, mixedQty, Calc.mileage(mixedKm, mixedQty).orElse(null), mixed);
    }
}
