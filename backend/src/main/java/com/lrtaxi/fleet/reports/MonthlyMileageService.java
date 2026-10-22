package com.lrtaxi.fleet.reports;

import com.lrtaxi.fleet.calc.Calc;
import com.lrtaxi.fleet.calc.EnergyType;
import com.lrtaxi.fleet.calc.MonthlyMileage;
import com.lrtaxi.fleet.calc.VehicleFuelType;
import com.lrtaxi.fleet.common.ApiException;
import com.lrtaxi.fleet.common.FleetFilter;
import com.lrtaxi.fleet.settings.SettingsService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Monthly mileage calculator: fuel expense ÷ GPS km and km ÷ fuel per vehicle for a calendar month. */
@Service
public class MonthlyMileageService {

    private static final String SQL = """
            WITH km AS (
                SELECT vehicle_id, sum(distance_km) AS km, count(*) AS gps_days
                FROM gps_daily_km WHERE is_current AND km_date BETWEEN :from AND :to GROUP BY vehicle_id),
            fuel AS (
                SELECT vehicle_id,
                       sum(quantity) FILTER (WHERE energy_type = 'CNG') AS cng,
                       sum(quantity) FILTER (WHERE energy_type = 'PETROL') AS petrol,
                       sum(quantity) FILTER (WHERE energy_type = 'DIESEL') AS diesel,
                       sum(quantity) FILTER (WHERE energy_type = 'ELECTRICITY') AS electricity,
                       sum(expense_amount) AS expense, count(*) AS txns
                FROM fuel_transactions WHERE NOT is_void AND txn_date BETWEEN :from AND :to GROUP BY vehicle_id),
            iv AS (
                SELECT vehicle_id, energy_type, sum(distance_km) AS d, sum(quantity) AS q
                FROM fuel_efficiency_intervals WHERE status = 'ELIGIBLE' AND end_date BETWEEN :from AND :to
                GROUP BY vehicle_id, energy_type)
            SELECT v.id, v.registration_number, v.make, v.model, v.fuel_type, v.operator_type, v.status, v.expected_mileage,
                   dp.name AS department_name, km.km, km.gps_days, fuel.cng, fuel.petrol, fuel.diesel, fuel.electricity,
                   fuel.expense, fuel.txns,
                   (SELECT string_agg(iv.energy_type || ':' || iv.d || ':' || iv.q, ';') FROM iv WHERE iv.vehicle_id = v.id) AS intervals
            FROM vehicles v
            LEFT JOIN departments dp ON dp.id = v.current_department_id
            LEFT JOIN km ON km.vehicle_id = v.id
            LEFT JOIN fuel ON fuel.vehicle_id = v.id
            WHERE (km.km IS NOT NULL OR fuel.expense IS NOT NULL)""";

    private final NamedParameterJdbcTemplate jdbc;
    private final SettingsService settings;

    public MonthlyMileageService(NamedParameterJdbcTemplate jdbc, SettingsService settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    public static YearMonth parseMonth(String month) {
        try {
            return month == null || month.isBlank() ? YearMonth.now() : YearMonth.parse(month);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("INVALID_MONTH", "Month must be YYYY-MM");
        }
    }

    /** Filter for one calendar month with the caller's vehicle filters. */
    public static FleetFilter forMonth(FleetFilter f, YearMonth m) {
        return new FleetFilter(m.atDay(1), m.atEndOfMonth(), f.vehicleId(), f.vehicle(), f.fuelType(), f.category(), f.ownership(),
                f.vendorId(), f.driverId(), f.departmentId(), f.status(), f.operatorType());
    }

    /** Per-vehicle rows for the filter's date range (normally one month). */
    public List<Map<String, Object>> rows(FleetFilter f) {
        MapSqlParameterSource p = f.dateParams();
        StringBuilder sql = new StringBuilder(SQL);
        f.appendVehicleConditions(sql, p, "v");
        sql.append(" ORDER BY v.registration_normalized");
        Map<String, BigDecimal> benchmarks = benchmarks();
        BigDecimal lowPct = settings.decimal("mileage.low_threshold_pct", "15");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(sql.toString(), p)) {
            Map<EnergyType, BigDecimal> qty = new EnumMap<>(EnergyType.class);
            put(qty, EnergyType.CNG, r.get("cng"));
            put(qty, EnergyType.PETROL, r.get("petrol"));
            put(qty, EnergyType.DIESEL, r.get("diesel"));
            put(qty, EnergyType.ELECTRICITY, r.get("electricity"));
            BigDecimal km = (BigDecimal) r.get("km");
            BigDecimal expense = (BigDecimal) r.get("expense");
            MonthlyMileage.Result res = MonthlyMileage.evaluate(new MonthlyMileage.VehicleMonth(km, qty, expense),
                    settings.mileagePolicy().plausible());

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("vehicleId", ((Number) r.get("id")).longValue());
            m.put("registrationNumber", r.get("registration_number"));
            m.put("vehicle", r.get("make") + " " + r.get("model"));
            m.put("fuelType", r.get("fuel_type"));
            m.put("operatorType", r.get("operator_type"));
            m.put("vehicleStatus", r.get("status"));
            m.put("departmentName", r.get("department_name"));
            m.put("km", km);
            m.put("gpsDays", r.get("gps_days"));
            m.put("cngKg", qty.get(EnergyType.CNG));
            m.put("petrolL", qty.get(EnergyType.PETROL));
            m.put("dieselL", qty.get(EnergyType.DIESEL));
            m.put("electricityKwh", qty.get(EnergyType.ELECTRICITY));
            m.put("fuelExpense", expense == null ? null : Calc.money(expense));
            m.put("fuelTransactions", r.get("txns"));
            m.put("costPerKm", res.costPerKm());
            m.put("mileage", res.mileage());
            m.put("mileageEnergy", res.mileageEnergy() == null ? null : res.mileageEnergy().name());
            m.put("mileageUnit", res.unit());
            // Mixed fuel: kg + L added into one quantity (kgL) as LR Taxi requested; single fuel: that fuel's quantity.
            m.put("combinedQuantity", res.status() == MonthlyMileage.Status.MIXED_FUEL ? res.quantity() : null);
            m.put("status", res.status().name());

            BigDecimal expected = null;
            if (res.mileageEnergy() != null) {
                expected = expected(r, res.mileageEnergy(), benchmarks);
            }
            BigDecimal variance = expected == null ? null : Calc.variancePct(res.mileage(), expected).orElse(null);
            m.put("expectedMileage", expected);
            m.put("variancePct", variance);
            m.put("belowExpected", variance != null && variance.compareTo(lowPct.negate()) <= 0);
            m.put("intervalMileage", intervalMileage((String) r.get("intervals"), res.mileageEnergy()));
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> month(FleetFilter base, YearMonth month) {
        List<Map<String, Object>> rows = rows(forMonth(base, month));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", month.toString());
        out.put("from", month.atDay(1));
        out.put("to", month.atEndOfMonth());
        out.put("totals", totals(rows, settings.mileagePolicy().plausible()));
        out.put("rows", rows);
        return out;
    }

    /** Fleet ₹/km and km/kg per month for the last {@code months} months ending at {@code end}. */
    public List<Map<String, Object>> trend(FleetFilter base, YearMonth end, int months) {
        int n = Math.max(1, Math.min(24, months));
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = n - 1; i >= 0; i--) {
            YearMonth m = end.minusMonths(i);
            Map<String, Object> t = new LinkedHashMap<>(totals(rows(forMonth(base, m)), settings.mileagePolicy().plausible()));
            t.put("month", m.toString());
            out.add(t);
        }
        return out;
    }

    private static Map<String, Object> totals(List<Map<String, Object>> rows, Map<EnergyType, com.lrtaxi.fleet.calc.MileageEngine.Range> plausible) {
        List<MonthlyMileage.VehicleMonth> vm = new ArrayList<>();
        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        BigDecimal allKm = BigDecimal.ZERO;
        BigDecimal allExpense = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            Map<EnergyType, BigDecimal> q = new EnumMap<>(EnergyType.class);
            put(q, EnergyType.CNG, r.get("cngKg"));
            put(q, EnergyType.PETROL, r.get("petrolL"));
            put(q, EnergyType.DIESEL, r.get("dieselL"));
            put(q, EnergyType.ELECTRICITY, r.get("electricityKwh"));
            vm.add(new MonthlyMileage.VehicleMonth((BigDecimal) r.get("km"), q, (BigDecimal) r.get("fuelExpense")));
            statusCounts.merge((String) r.get("status"), 1, Integer::sum);
            allKm = allKm.add(Calc.nz((BigDecimal) r.get("km")));
            allExpense = allExpense.add(Calc.nz((BigDecimal) r.get("fuelExpense")));
        }
        MonthlyMileage.FleetTotals t = MonthlyMileage.totals(vm, plausible);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vehicles", rows.size());
        m.put("vehiclesIncluded", t.vehiclesIncluded());
        m.put("statusCounts", statusCounts);
        m.put("totalKm", allKm);
        m.put("totalFuelExpense", Calc.money(allExpense));
        m.put("includedKm", t.km());
        m.put("includedFuelExpense", t.expense());
        m.put("costPerKm", t.costPerKm());
        Map<String, Object> mileage = new LinkedHashMap<>();
        for (Map.Entry<EnergyType, BigDecimal[]> e : t.byEnergy().entrySet()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("km", e.getValue()[0]);
            x.put("quantity", e.getValue()[1]);
            x.put("mileage", t.mileage().get(e.getKey()));
            x.put("unit", e.getKey().efficiencyUnit());
            mileage.put(e.getKey().name(), x);
        }
        m.put("mileageByEnergy", mileage);
        Map<String, Object> mixed = new LinkedHashMap<>();
        mixed.put("km", t.mixedKm());
        mixed.put("quantity", t.mixedQuantity());
        mixed.put("mileage", t.mixedMileage());
        mixed.put("unit", "km/kgL");
        mixed.put("vehicles", t.mixedVehicles());
        m.put("mixedFuel", mixed);
        return m;
    }

    private static void put(Map<EnergyType, BigDecimal> q, EnergyType e, Object v) {
        if (v != null) {
            q.put(e, (BigDecimal) v);
        }
    }

    /** "CNG:250.00:10.000;PETROL:..." -> engine interval mileage for the energy (refuelling-to-refuelling basis). */
    private static BigDecimal intervalMileage(String encoded, EnergyType energy) {
        if (encoded == null || energy == null) {
            return null;
        }
        for (String part : encoded.split(";")) {
            String[] f = part.split(":");
            if (f.length == 3 && f[0].equals(energy.name())) {
                return Calc.mileage(new BigDecimal(f[1]), new BigDecimal(f[2])).orElse(null);
            }
        }
        return null;
    }

    private Map<String, BigDecimal> benchmarks() {
        Map<String, BigDecimal> b = new HashMap<>();
        jdbc.query("SELECT scope, lower(coalesce(make, '')) AS make, lower(coalesce(model, '')) AS model, energy_type, expected_mileage FROM mileage_benchmarks",
                Map.of(), rs -> {
                    b.put(rs.getString("scope") + "|" + rs.getString("make") + "|" + rs.getString("model") + "|" + rs.getString("energy_type"),
                            rs.getBigDecimal("expected_mileage"));
                });
        return b;
    }

    /** Same order as EfficiencyService.expected: vehicle (primary fuel) -> model benchmark -> fuel-type benchmark. */
    private static BigDecimal expected(Map<String, Object> r, EnergyType energy, Map<String, BigDecimal> b) {
        VehicleFuelType vft = VehicleFuelType.valueOf((String) r.get("fuel_type"));
        if (vft.primaryEnergy() == energy && r.get("expected_mileage") != null) {
            return (BigDecimal) r.get("expected_mileage");
        }
        String make = String.valueOf(r.get("make")).toLowerCase();
        String model = String.valueOf(r.get("model")).toLowerCase();
        BigDecimal m = b.get("MODEL|" + make + "|" + model + "|" + energy.name());
        return m != null ? m : b.get("FUEL_TYPE|||" + energy.name());
    }

    public static LocalDate firstDay(YearMonth m) {
        return m.atDay(1);
    }
}
