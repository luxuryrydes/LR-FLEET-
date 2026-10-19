package com.lrtaxi.fleet.dashboard;

import com.lrtaxi.fleet.calc.Calc;
import com.lrtaxi.fleet.common.FleetFilter;
import com.lrtaxi.fleet.common.Rows;
import com.lrtaxi.fleet.fuel.EfficiencyService;
import com.lrtaxi.fleet.maintenance.ServiceStatusService;
import com.lrtaxi.fleet.reports.ReportService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Dashboard figures. Fuel totals and cost-per-km KPIs are aggregated from the same ReportService rows used by the
 * exports, and mileage from the Java engine's intervals, so dashboard and reports agree for the same filters.
 */
@Service
public class DashboardService {

    private final NamedParameterJdbcTemplate jdbc;
    private final ReportService reports;
    private final EfficiencyService efficiency;
    private final ServiceStatusService serviceStatus;

    public DashboardService(NamedParameterJdbcTemplate jdbc, ReportService reports, EfficiencyService efficiency,
                            ServiceStatusService serviceStatus) {
        this.jdbc = jdbc;
        this.reports = reports;
        this.efficiency = efficiency;
        this.serviceStatus = serviceStatus;
    }

    public Map<String, Object> kpis(FleetFilter f) {
        MapSqlParameterSource p = f.dateParams();
        StringBuilder vw = new StringBuilder(" WHERE 1=1");
        f.appendVehicleConditions(vw, p, "v");

        Map<String, Object> fleet = jdbc.queryForMap("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE v.status = 'ACTIVE') AS active,
                       count(*) FILTER (WHERE v.status IN ('INACTIVE','SOLD','SCRAPPED')) AS inactive,
                       count(*) FILTER (WHERE v.status = 'IDLE') AS idle,
                       count(*) FILTER (WHERE v.status = 'WORKSHOP') AS workshop
                FROM vehicles v""" + vw, p);

        Map<String, Object> gps = jdbc.queryForMap("""
                SELECT coalesce(sum(g.distance_km), 0) AS km, count(DISTINCT g.vehicle_id) AS vehicles_with_gps
                FROM gps_daily_km g JOIN vehicles v ON v.id = g.vehicle_id""" + vw + " AND g.is_current AND g.km_date BETWEEN :from AND :to", p);
        Long missingGps = jdbc.queryForObject("""
                SELECT count(*) FROM vehicles v""" + vw + " " + """
                 AND v.status = 'ACTIVE' AND NOT EXISTS (
                    SELECT 1 FROM gps_daily_km g WHERE g.vehicle_id = v.id AND g.is_current AND g.km_date BETWEEN :from AND :to)""", p, Long.class);
        Long missingFuel = jdbc.queryForObject("""
                SELECT count(*) FROM vehicles v""" + vw + " " + """
                 AND v.status = 'ACTIVE'
                 AND EXISTS (SELECT 1 FROM gps_daily_km g WHERE g.vehicle_id = v.id AND g.is_current AND g.distance_km > 0 AND g.km_date BETWEEN :from AND :to)
                 AND NOT EXISTS (SELECT 1 FROM fuel_transactions t WHERE t.vehicle_id = v.id AND NOT t.is_void AND t.txn_date BETWEEN :from AND :to)""",
                p, Long.class);
        BigDecimal maintenance = jdbc.queryForObject("""
                SELECT coalesce(sum(m.total_cost), 0) FROM maintenance_records m JOIN vehicles v ON v.id = m.vehicle_id""" + vw
                + " AND NOT m.is_void AND m.service_date BETWEEN :from AND :to", p, BigDecimal.class);

        // Fuel by energy: sum of report "fuel-consumption-by-type" rows.
        Map<String, BigDecimal[]> energy = new TreeMap<>();
        for (Map<String, Object> r : reports.fuelByEnergyMonth(f)) {
            BigDecimal[] t = energy.computeIfAbsent((String) r.get("energyType"), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            t[0] = t[0].add((BigDecimal) r.get("quantity"));
            t[1] = t[1].add((BigDecimal) r.get("expense"));
        }
        BigDecimal totalFuelExpense = BigDecimal.ZERO;
        Map<String, Object> fuelByEnergy = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal[]> e : energy.entrySet()) {
            totalFuelExpense = totalFuelExpense.add(e.getValue()[1]);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("quantity", e.getValue()[0]);
            m.put("expense", Calc.money(e.getValue()[1]));
            m.put("weightedUnitPrice", Calc.weightedUnitPrice(e.getValue()[1], e.getValue()[0]).orElse(null));
            fuelByEnergy.put(e.getKey(), m);
        }

        // Cost per km: aggregate of report "fuel-cost-per-km" rows with COMPLETE coverage.
        BigDecimal cpkKm = BigDecimal.ZERO;
        BigDecimal cpkFuel = BigDecimal.ZERO;
        BigDecimal cpkMaint = BigDecimal.ZERO;
        BigDecimal maintKm = BigDecimal.ZERO;
        BigDecimal maintAmt = BigDecimal.ZERO;
        int complete = 0;
        int noKm = 0;
        int noFuel = 0;
        for (Map<String, Object> r : reports.vehicleCostRows(f)) {
            BigDecimal km = Calc.nz((BigDecimal) r.get("km"));
            BigDecimal maint = Calc.nz((BigDecimal) r.get("maintenanceExpense"));
            switch ((String) r.get("coverage")) {
                case "COMPLETE" -> {
                    complete++;
                    cpkKm = cpkKm.add(km);
                    cpkFuel = cpkFuel.add((BigDecimal) r.get("fuelExpense"));
                    cpkMaint = cpkMaint.add(maint);
                }
                case "NO_KM" -> noKm++;
                default -> noFuel++;
            }
            if (km.signum() > 0) {
                maintKm = maintKm.add(km);
                maintAmt = maintAmt.add(maint);
            }
        }
        Map<String, Object> cost = new LinkedHashMap<>();
        cost.put("fuelCostPerKm", Calc.costPerKm(cpkFuel, cpkKm).orElse(null));
        cost.put("maintenanceCostPerKm", Calc.costPerKm(maintAmt, maintKm).orElse(null));
        cost.put("fuelPlusMaintenanceCostPerKm", Calc.costPerKm(cpkFuel.add(cpkMaint), cpkKm).orElse(null));
        cost.put("vehiclesIncluded", complete);
        cost.put("vehiclesWithoutKm", noKm);
        cost.put("vehiclesWithoutFuel", noFuel);
        cost.put("basis", "Purchase basis: expense dated in the period ÷ GPS km in the period, vehicles with both km and fuel only. "
                + "Excludes driver, insurance, tax and depreciation.");

        List<Map<String, Object>> service = serviceStatus.fleet(f, null);
        long dueSoon = service.stream().filter(s -> "DUE_SOON".equals(s.get("status"))).count();
        long overdue = service.stream().filter(s -> "OVERDUE".equals(s.get("status"))).count();
        long missingService = service.stream().filter(s -> "MISSING_SERVICE_RECORD".equals(s.get("status"))).count();
        long missingBaseline = service.stream().filter(s -> List.of("MISSING_BASELINE", "UNRELIABLE_BASELINE").contains(s.get("status"))).count();

        long deteriorating = efficiency.vehicleMileageTable(f).stream().filter(r -> Boolean.TRUE.equals(r.get("belowThreshold")))
                .map(r -> r.get("vehicleId")).distinct().count();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", Map.of("from", f.fromOrDefault(), "to", f.toOrDefault()));
        out.put("vehicles", fleet);
        out.put("totalKm", gps.get("km"));
        out.put("vehiclesWithGps", gps.get("vehicles_with_gps"));
        out.put("vehiclesMissingGps", missingGps);
        out.put("serviceDueSoon", dueSoon);
        out.put("serviceOverdue", overdue);
        out.put("missingServiceRecords", missingService);
        out.put("missingOdometerBaseline", missingBaseline);
        out.put("fuelByEnergy", fuelByEnergy);
        out.put("totalFuelExpense", Calc.money(totalFuelExpense));
        out.put("fleetMileage", efficiency.fleetMileage(f));
        out.put("costPerKm", cost);
        out.put("maintenanceExpense", Calc.money(maintenance));
        out.put("vehiclesDeterioratingMileage", deteriorating);
        out.put("vehiclesMissingFuel", missingFuel);
        return out;
    }

    public Map<String, Object> charts(FleetFilter f) {
        MapSqlParameterSource p = f.dateParams();
        StringBuilder vw = new StringBuilder(" WHERE 1=1");
        f.appendVehicleConditions(vw, p, "v");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("monthlyKm", jdbc.query("""
                SELECT to_char(date_trunc('month', g.km_date), 'YYYY-MM') AS month, sum(g.distance_km) AS km
                FROM gps_daily_km g JOIN vehicles v ON v.id = g.vehicle_id""" + vw
                + " AND g.is_current AND g.km_date BETWEEN :from AND :to GROUP BY 1 ORDER BY 1", p, Rows::map));
        out.put("fuelExpenditure", reports.fuelByEnergyMonth(f));
        List<Map<String, Object>> mileage = jdbc.query("""
                SELECT to_char(date_trunc('month', i.end_date), 'YYYY-MM') AS month, i.energy_type, sum(i.distance_km) AS km, sum(i.quantity) AS qty
                FROM fuel_efficiency_intervals i JOIN vehicles v ON v.id = i.vehicle_id""" + vw
                + " AND i.status = 'ELIGIBLE' AND i.end_date BETWEEN :from AND :to GROUP BY 1, i.energy_type ORDER BY 1", p, Rows::map);
        for (Map<String, Object> r : mileage) {
            r.put("mileage", Calc.mileage((BigDecimal) r.get("km"), (BigDecimal) r.get("qty")).orElse(null));
        }
        out.put("mileageEfficiency", mileage);
        out.put("maintenanceExpenditure", jdbc.query("""
                SELECT to_char(date_trunc('month', m.service_date), 'YYYY-MM') AS month, sum(m.total_cost) AS amount
                FROM maintenance_records m JOIN vehicles v ON v.id = m.vehicle_id""" + vw
                + " AND NOT m.is_void AND m.service_date BETWEEN :from AND :to GROUP BY 1 ORDER BY 1", p, Rows::map));
        // Utilisation per month: Σ days with km > 0 ÷ Σ days in period (same rows as the monthly-utilisation report).
        Map<String, long[]> util = new TreeMap<>();
        for (Map<String, Object> r : reports.monthlyUtilisation(f)) {
            long[] t = util.computeIfAbsent((String) r.get("month"), k -> new long[2]);
            t[0] += ((Number) r.get("activeDays")).longValue();
            t[1] += ((Number) r.get("periodDays")).longValue();
        }
        List<Map<String, Object>> utilisation = util.entrySet().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month", e.getKey());
            m.put("activeVehicleDays", e.getValue()[0]);
            m.put("vehicleDays", e.getValue()[1]);
            m.put("utilisationPct", Calc.ratio(BigDecimal.valueOf(e.getValue()[0] * 100), BigDecimal.valueOf(e.getValue()[1]), 2).orElse(null));
            return m;
        }).toList();
        out.put("utilisation", utilisation);
        return out;
    }
}
