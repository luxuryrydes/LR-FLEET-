package com.lrtaxi.fleet.reports;

import com.lrtaxi.fleet.calc.Calc;
import com.lrtaxi.fleet.common.ApiException;
import com.lrtaxi.fleet.common.FleetFilter;
import com.lrtaxi.fleet.common.Rows;
import com.lrtaxi.fleet.documents.DocumentService;
import com.lrtaxi.fleet.fuel.EfficiencyService;
import com.lrtaxi.fleet.fuel.FuelService;
import com.lrtaxi.fleet.gps.GpsService;
import com.lrtaxi.fleet.maintenance.MaintenanceController;
import com.lrtaxi.fleet.maintenance.MaintenanceService;
import com.lrtaxi.fleet.maintenance.ServiceStatusService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.lrtaxi.fleet.reports.Exporter.col;
import static com.lrtaxi.fleet.reports.Exporter.date;
import static com.lrtaxi.fleet.reports.Exporter.integer;
import static com.lrtaxi.fleet.reports.Exporter.money;
import static com.lrtaxi.fleet.reports.Exporter.num;

/**
 * The 16 management reports. Each report is defined once (columns + query) and is used for the on-screen preview,
 * the XLSX/CSV export and, where the figures overlap, the dashboard, so the numbers always agree for the same filters.
 */
@Service
public class ReportService {

    public record Report(String key, String title, String description, List<Exporter.Col> columns, List<String> notes,
                         Function<FleetFilter, List<Map<String, Object>>> rows) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final Map<String, Report> reports = new LinkedHashMap<>();

    public ReportService(NamedParameterJdbcTemplate jdbc, GpsService gps, FuelService fuel, EfficiencyService efficiency,
                         MaintenanceService maintenance, ServiceStatusService serviceStatus, DocumentService documents) {
        this.jdbc = jdbc;
        String purchaseBasis = "Cost per km is on a purchase basis: expense dated in the period ÷ GPS km in the period.";
        String weighted = "Mileage is weighted: Σ eligible interval km ÷ Σ eligible interval quantity (never a simple average).";

        add(new Report("daily-gps-km", "Daily GPS kilometres", "Current daily km per vehicle", List.of(
                date("Date", "kmDate"), col("Vehicle", "registrationNumber"), num("Distance (km)", "distanceKm"),
                col("Record type", "recordMode"), integer("Trips", "tripCount"), col("Source", "source"),
                col("GPS device name", "sourceDeviceName"), integer("Import batch", "importBatchId")),
                List.of("Only the current figure per vehicle per day is shown; superseded corrections are kept in history."),
                f -> gps.listForExport(f, null)));

        add(new Report("monthly-utilisation", "Monthly vehicle utilisation", "Km, active days and utilisation per vehicle per month", List.of(
                col("Month", "month"), col("Vehicle", "registrationNumber"), col("Department", "departmentName"),
                num("Km", "km"), integer("Days with km", "activeDays"), integer("Days with GPS data", "gpsDays"),
                integer("Days in period", "periodDays"), num("Utilisation %", "utilisationPct")),
                List.of("Utilisation % = days with km > 0 ÷ days in the period within that month × 100.",
                        "Days without any GPS record are missing data, not idle days."), this::monthlyUtilisation));

        add(new Report("vehicle-mileage-history", "Vehicle-wise mileage history", "Every refuelling interval with its status", List.of(
                col("Vehicle", "registrationNumber"), col("Energy", "energyType"), col("Method", "method"),
                col("Distance source", "distanceSource"), date("Start", "startDate"), date("End", "endDate"),
                num("Start odometer", "startOdometer"), num("End odometer", "endOdometer"), num("Distance (km)", "distanceKm"),
                num("Quantity", "quantity"), num("Mileage", "mileage"), col("Status", "status"), col("Excluded because", "exclusionReason")),
                List.of(weighted, "Intervals are assigned to the period of their end date."), this::mileageHistory));

        add(new Report("fuel-consumption-by-type", "Fuel and energy consumption by type", "Monthly quantity, expense and weighted price per energy", List.of(
                col("Month", "month"), col("Energy", "energyType"), col("Unit", "unit"), num("Quantity", "quantity"),
                money("Expense", "expense"), num("Weighted unit price", "weightedUnitPrice"), integer("Transactions", "transactions"),
                integer("Vehicles", "vehicles")),
                List.of("Weighted unit price = Σ expense ÷ Σ quantity. Petrol, diesel, CNG and electricity are never combined."),
                this::fuelByEnergyMonth));

        add(new Report("fuel-expenditure-by-vehicle", "Fuel expenditure by vehicle", "Quantity and expense per vehicle and energy", List.of(
                col("Vehicle", "registrationNumber"), col("Fuel type", "fuelType"), col("Energy", "energyType"), col("Unit", "unit"),
                num("Quantity", "quantity"), money("Expense", "expense"), num("Weighted unit price", "weightedUnitPrice"),
                integer("Transactions", "transactions"), integer("Amount mismatches", "mismatches")),
                List.of(), this::fuelByVehicle));

        add(new Report("fleet-mileage-comparison", "Fleet mileage comparison", "Vehicle mileage vs expected and fleet average", List.of(
                col("Vehicle", "registrationNumber"), col("Make", "make"), col("Model", "model"), col("Energy", "energyType"),
                col("Unit", "unit"), num("Eligible km", "eligibleKm"), num("Eligible quantity", "eligibleQuantity"), num("Mileage", "mileage"),
                num("Fleet mileage", "fleetMileage"), num("Expected", "expectedMileage"), num("Variance %", "variancePct"),
                integer("Eligible intervals", "eligibleIntervals"), integer("Excluded intervals", "excludedIntervals"),
                col("Data quality", "dataQuality")),
                List.of(weighted), f -> fleetComparison(f, efficiency)));

        add(new Report("low-efficiency-vehicles", "Low-efficiency vehicles", "Vehicles below the configured mileage threshold", List.of(
                col("Vehicle", "registrationNumber"), col("Energy", "energyType"), num("Mileage", "mileage"),
                num("Expected", "expectedMileage"), num("Variance %", "variancePct"), num("Threshold %", "lowThresholdPct"),
                integer("Eligible intervals", "eligibleIntervals"), col("Excluded intervals reasons", "exclusionReasons"),
                col("Data quality", "dataQuality")),
                List.of("A low figure is a prompt to investigate (data quality, route, load), not proof of misuse."),
                f -> efficiency.vehicleMileageTable(f).stream().filter(r -> Boolean.TRUE.equals(r.get("belowThreshold"))).toList()));

        add(new Report("fuel-cost-per-km", "Fuel cost per kilometre", "Fuel and maintenance cost per km by vehicle", List.of(
                col("Vehicle", "registrationNumber"), col("Fuel type", "fuelType"), num("GPS km", "km"), money("Fuel expense", "fuelExpense"),
                money("Maintenance", "maintenanceExpense"), num("Fuel ₹/km", "fuelCostPerKm"), num("Maintenance ₹/km", "maintenanceCostPerKm"),
                num("Fuel + maintenance ₹/km", "combinedCostPerKm"), col("Coverage", "coverage")),
                List.of(purchaseBasis, "Fuel + maintenance excludes driver, insurance, tax and depreciation; it is not total operating cost.",
                        "Coverage NO_KM / NO_FUEL rows are excluded from fleet totals rather than treated as zero."), this::vehicleCostRows));

        add(new Report("maintenance-history", "Maintenance history", "Every service record", List.of(
                date("Date", "serviceDate"), col("Vehicle", "registrationNumber"), col("Category", "categoryName"),
                num("Odometer", "odometerKm"), col("Description", "description"), money("Parts", "partsCost"), money("Labour", "labourCost"),
                money("Other", "otherCost"), money("Tax", "taxAmount"), money("Total", "totalCost"), col("Workshop", "workshopName"),
                col("Invoice", "invoiceNumber"), col("Job card", "jobCardNumber"), num("Next service odometer", "nextServiceOdometer"),
                date("Next service date", "nextServiceDate")), List.of(), f -> maintenance.listForExport(f, null)));

        add(new Report("maintenance-cost-by-vehicle-month", "Maintenance costs by vehicle and month", "Monthly maintenance spend per vehicle", List.of(
                col("Month", "month"), col("Vehicle", "registrationNumber"), integer("Records", "records"), money("Parts", "parts"),
                money("Labour", "labour"), money("Total", "total")), List.of(), this::maintenanceByVehicleMonth));

        add(new Report("service-due", "Upcoming and overdue service", "Service status for active vehicles",
                MaintenanceController.SERVICE_COLUMNS,
                List.of("Current odometer = latest trusted odometer + GPS km after that date. Default interval 10,000 km; due soon at 500 km."),
                f -> serviceStatus.fleet(f, null)));

        add(new Report("document-expiry", "Compliance document expiry", "Expired, expiring and missing documents", List.of(
                col("Bucket", "bucket"), col("Vehicle", "registrationNumber"), col("Document", "docType"), col("Number", "documentNumber"),
                date("Expiry", "expiryDate"), integer("Days to expiry", "daysToExpiry"), col("Verification", "verificationStatus")),
                List.of(), f -> documentRows(documents.compliance(f))));

        add(new Report("missing-data-reconciliation", "Missing-data reconciliation", "GPS, fuel and service coverage per vehicle", List.of(
                col("Vehicle", "registrationNumber"), col("Status", "vehicleStatus"), integer("Days in period", "periodDays"),
                integer("GPS days", "gpsDays"), integer("Missing GPS days", "missingGpsDays"), num("GPS km", "km"),
                integer("Fuel transactions", "fuelTxns"), integer("Fuel without odometer", "fuelNoOdometer"),
                integer("Untrusted odometer readings", "untrustedReadings"), col("Issues", "issues")),
                List.of("GPS_NO_FUEL: km recorded but no fuel. FUEL_NO_KM: fuel recorded but no GPS km. MISSING_GPS_DAYS: days without any GPS record."),
                this::reconciliation));

        add(new Report("duplicate-rejected-imports", "Duplicate and rejected imports", "Row-level import errors for batches created in the period", List.of(
                integer("Batch", "batchId"), col("Type", "importType"), col("File", "originalFilename"), date("Uploaded", "uploadedOn"),
                integer("Row", "rowNumber"), col("Stage", "stage"), col("Error", "errorCode"), col("Reason", "message"), col("Value", "rawValue")),
                List.of(), this::importErrors));

        add(new Report("department-utilisation-expenditure", "Department-wise utilisation and expenditure",
                "Km and expense attributed to the department assigned on each date", List.of(
                col("Department", "departmentName"), integer("Vehicles", "vehicles"), num("GPS km", "km"), money("Fuel expense", "fuelExpense"),
                money("Maintenance", "maintenanceExpense"), money("Fuel + maintenance", "totalExpense"), num("Fuel ₹/km", "fuelCostPerKm")),
                List.of("Each GPS day, fuel transaction and service record is attributed to the department assigned to the vehicle on that date.",
                        purchaseBasis), this::departmentRows));

        add(new Report("vehicle-expense-history", "Vehicle-wise expense history", "Monthly fuel and maintenance spend per vehicle", List.of(
                col("Month", "month"), col("Vehicle", "registrationNumber"), num("GPS km", "km"), money("Fuel", "fuelExpense"),
                money("Maintenance", "maintenanceExpense"), money("Fuel + maintenance", "totalExpense"), num("Fuel + maintenance ₹/km", "costPerKm")),
                List.of(purchaseBasis), this::vehicleExpenseHistory));
    }

    private void add(Report r) {
        reports.put(r.key(), r);
    }

    public List<Map<String, Object>> catalog() {
        List<Map<String, Object>> out = new ArrayList<>();
        int i = 1;
        for (Report r : reports.values()) {
            out.add(Map.of("number", i++, "key", r.key(), "title", r.title(), "description", r.description()));
        }
        return out;
    }

    public Report get(String key) {
        Report r = reports.get(key);
        if (r == null) {
            throw ApiException.notFound("Report " + key);
        }
        return r;
    }

    public List<String> notes(Report r, FleetFilter f) {
        List<String> n = new ArrayList<>();
        n.add(r.title());
        n.add("Period: " + com.lrtaxi.fleet.calc.IndianFormat.date(f.fromOrDefault()) + " to " + com.lrtaxi.fleet.calc.IndianFormat.date(f.toOrDefault()));
        n.add("Filters: " + describe(f));
        n.addAll(r.notes());
        n.add("Generated: " + java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Kolkata")).withNano(0));
        return n;
    }

    private static String describe(FleetFilter f) {
        List<String> parts = new ArrayList<>();
        if (f.vehicle() != null && !f.vehicle().isBlank()) parts.add("vehicle " + f.vehicle());
        if (f.vehicleId() != null) parts.add("vehicle id " + f.vehicleId());
        if (f.fuelType() != null && !f.fuelType().isBlank()) parts.add("fuel " + f.fuelType());
        if (f.category() != null && !f.category().isBlank()) parts.add("category " + f.category());
        if (f.ownership() != null && !f.ownership().isBlank()) parts.add("ownership " + f.ownership());
        if (f.vendorId() != null) parts.add("vendor " + f.vendorId());
        if (f.driverId() != null) parts.add("driver " + f.driverId());
        if (f.departmentId() != null) parts.add("department " + f.departmentId());
        if (f.status() != null && !f.status().isBlank()) parts.add("status " + f.status());
        return parts.isEmpty() ? "none" : String.join(", ", parts);
    }

    // ------------------------------------------------------------------ shared queries

    private StringBuilder vehicleWhere(FleetFilter f, MapSqlParameterSource p) {
        StringBuilder w = new StringBuilder(" WHERE 1=1");
        f.appendVehicleConditions(w, p, "v");
        return w;
    }

    public List<Map<String, Object>> monthlyUtilisation(FleetFilter f) {
        MapSqlParameterSource p = f.dateParams();
        String sql = """
                WITH months AS (
                    SELECT greatest(date_trunc('month', d)::date, CAST(:from AS date)) AS m_start,
                           least((date_trunc('month', d) + interval '1 month - 1 day')::date, CAST(:to AS date)) AS m_end
                    FROM generate_series(date_trunc('month', CAST(:from AS date)), CAST(:to AS date), interval '1 month') d
                )
                SELECT to_char(m.m_start, 'YYYY-MM') AS month, v.registration_number, dp.name AS department_name,
                       coalesce(sum(g.distance_km), 0) AS km,
                       count(g.id) FILTER (WHERE g.distance_km > 0) AS active_days,
                       count(g.id) AS gps_days,
                       (m.m_end - m.m_start + 1) AS period_days
                FROM months m CROSS JOIN vehicles v
                LEFT JOIN departments dp ON dp.id = v.current_department_id
                LEFT JOIN gps_daily_km g ON g.vehicle_id = v.id AND g.is_current AND g.km_date BETWEEN m.m_start AND m.m_end
                """ + vehicleWhere(f, p) + " AND v.status NOT IN ('SOLD','SCRAPPED')"
                + " GROUP BY m.m_start, m.m_end, v.id, dp.name ORDER BY m.m_start, v.registration_normalized";
        List<Map<String, Object>> rows = jdbc.query(sql, p, Rows::map);
        for (Map<String, Object> r : rows) {
            BigDecimal active = BigDecimal.valueOf(((Number) r.get("activeDays")).longValue() * 100);
            r.put("utilisationPct", Calc.ratio(active, BigDecimal.valueOf(((Number) r.get("periodDays")).longValue()), 2).orElse(null));
        }
        return rows;
    }

    private List<Map<String, Object>> mileageHistory(FleetFilter f) {
        MapSqlParameterSource p = f.dateParams();
        return jdbc.query("""
                SELECT v.registration_number, i.energy_type, i.method, i.distance_source, i.start_date, i.end_date, i.start_odometer,
                       i.end_odometer, i.distance_km, i.quantity, i.mileage, i.status, i.exclusion_reason
                FROM fuel_efficiency_intervals i JOIN vehicles v ON v.id = i.vehicle_id""" + vehicleWhere(f, p)
                + " AND i.end_date BETWEEN :from AND :to ORDER BY v.registration_normalized, i.end_date LIMIT 500000", p, Rows::map);
    }

    /** Monthly totals per energy. Dashboard fuel KPIs are the sum of these rows. */
    public List<Map<String, Object>> fuelByEnergyMonth(FleetFilter f) {
        MapSqlParameterSource p = f.dateParams();
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT to_char(date_trunc('month', t.txn_date), 'YYYY-MM') AS month, t.energy_type, t.unit, sum(t.quantity) AS quantity,
                       sum(t.expense_amount) AS expense, count(*) AS transactions, count(DISTINCT t.vehicle_id) AS vehicles
                FROM fuel_transactions t JOIN vehicles v ON v.id = t.vehicle_id""" + vehicleWhere(f, p)
                + " AND NOT t.is_void AND t.txn_date BETWEEN :from AND :to GROUP BY 1, t.energy_type, t.unit ORDER BY 1, t.energy_type", p, Rows::map);
        for (Map<String, Object> r : rows) {
            r.put("weightedUnitPrice", Calc.weightedUnitPrice((BigDecimal) r.get("expense"), (BigDecimal) r.get("quantity")).orElse(null));
        }
        return rows;
    }

    private List<Map<String, Object>> fuelByVehicle(FleetFilter f) {
        MapSqlParameterSource p = f.dateParams();
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT v.registration_number, v.fuel_type, t.energy_type, t.unit, sum(t.quantity) AS quantity, sum(t.expense_amount) AS expense,
                       count(*) AS transactions, count(*) FILTER (WHERE t.amount_mismatch) AS mismatches
                FROM fuel_transactions t JOIN vehicles v ON v.id = t.vehicle_id""" + vehicleWhere(f, p)
                + " AND NOT t.is_void AND t.txn_date BETWEEN :from AND :to GROUP BY v.id, t.energy_type, t.unit ORDER BY v.registration_normalized, t.energy_type",
                p, Rows::map);
        for (Map<String, Object> r : rows) {
            r.put("weightedUnitPrice", Calc.weightedUnitPrice((BigDecimal) r.get("expense"), (BigDecimal) r.get("quantity")).orElse(null));
        }
        return rows;
    }

    private static List<Map<String, Object>> fleetComparison(FleetFilter f, EfficiencyService efficiency) {
        Map<String, Object> fleet = new LinkedHashMap<>();
        for (Map<String, Object> m : efficiency.fleetMileage(f)) {
            fleet.put((String) m.get("energyType"), m.get("mileage"));
        }
        List<Map<String, Object>> rows = new ArrayList<>(efficiency.vehicleMileageTable(f));
        for (Map<String, Object> r : rows) {
            r.put("fleetMileage", fleet.get((String) r.get("energyType")));
        }
        return rows;
    }

    /**
     * Per vehicle km, fuel and maintenance spend in the period. The dashboard's cost-per-km KPIs aggregate these
     * same rows, so the export and the dashboard agree.
     */
    public List<Map<String, Object>> vehicleCostRows(FleetFilter f) {
        MapSqlParameterSource p = f.dateParams();
        List<Map<String, Object>> rows = jdbc.query("""
                WITH km AS (SELECT vehicle_id, sum(distance_km) AS km FROM gps_daily_km WHERE is_current AND km_date BETWEEN :from AND :to GROUP BY vehicle_id),
                     fuel AS (SELECT vehicle_id, sum(expense_amount) AS amt FROM fuel_transactions WHERE NOT is_void AND txn_date BETWEEN :from AND :to GROUP BY vehicle_id),
                     maint AS (SELECT vehicle_id, sum(total_cost) AS amt FROM maintenance_records WHERE NOT is_void AND service_date BETWEEN :from AND :to GROUP BY vehicle_id)
                SELECT v.id AS vehicle_id, v.registration_number, v.fuel_type, km.km, fuel.amt AS fuel_expense, maint.amt AS maintenance_expense
                FROM vehicles v LEFT JOIN km ON km.vehicle_id = v.id LEFT JOIN fuel ON fuel.vehicle_id = v.id LEFT JOIN maint ON maint.vehicle_id = v.id"""
                + vehicleWhere(f, p) + " AND (km.km IS NOT NULL OR fuel.amt IS NOT NULL OR maint.amt IS NOT NULL) ORDER BY v.registration_normalized",
                p, Rows::map);
        for (Map<String, Object> r : rows) {
            BigDecimal km = (BigDecimal) r.get("km");
            BigDecimal fuelAmt = (BigDecimal) r.get("fuelExpense");
            BigDecimal maint = (BigDecimal) r.get("maintenanceExpense");
            boolean hasKm = Calc.isPositive(km);
            boolean hasFuel = Calc.isPositive(fuelAmt);
            r.put("coverage", hasKm && hasFuel ? "COMPLETE" : !hasKm ? "NO_KM" : "NO_FUEL");
            r.put("fuelCostPerKm", hasKm && hasFuel ? Calc.costPerKm(fuelAmt, km).orElse(null) : null);
            r.put("maintenanceCostPerKm", hasKm ? Calc.costPerKm(Calc.nz(maint), km).orElse(null) : null);
            r.put("combinedCostPerKm", hasKm && hasFuel ? Calc.costPerKm(fuelAmt.add(Calc.nz(maint)), km).orElse(null) : null);
        }
        return rows;
    }

    private List<Map<String, Object>> maintenanceByVehicleMonth(FleetFilter f) {
        MapSqlParameterSource p = f.dateParams();
        return jdbc.query("""
                SELECT to_char(date_trunc('month', m.service_date), 'YYYY-MM') AS month, v.registration_number, count(*) AS records,
                       sum(m.parts_cost) AS parts, sum(m.labour_cost) AS labour, sum(m.total_cost) AS total
                FROM maintenance_records m JOIN vehicles v ON v.id = m.vehicle_id""" + vehicleWhere(f, p)
                + " AND NOT m.is_void AND m.service_date BETWEEN :from AND :to GROUP BY 1, v.id ORDER BY 1, v.registration_normalized", p, Rows::map);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> documentRows(Map<String, Object> c) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String bucket : List.of("expired", "expiring", "missing", "pendingVerification")) {
            for (Map<String, Object> d : (List<Map<String, Object>>) c.get(bucket)) {
                Map<String, Object> m = new LinkedHashMap<>(d);
                m.put("bucket", bucket.toUpperCase());
                out.add(m);
            }
        }
        return out;
    }

    private List<Map<String, Object>> reconciliation(FleetFilter f) {
        MapSqlParameterSource p = f.dateParams();
        long days = ChronoUnit.DAYS.between(f.fromOrDefault(), f.toOrDefault()) + 1;
        p.addValue("days", days);
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT v.registration_number, v.status AS vehicle_status, CAST(:days AS int) AS period_days,
                       (SELECT count(*) FROM gps_daily_km g WHERE g.vehicle_id = v.id AND g.is_current AND g.km_date BETWEEN :from AND :to) AS gps_days,
                       (SELECT coalesce(sum(distance_km), 0) FROM gps_daily_km g WHERE g.vehicle_id = v.id AND g.is_current AND g.km_date BETWEEN :from AND :to) AS km,
                       (SELECT count(*) FROM fuel_transactions t WHERE t.vehicle_id = v.id AND NOT t.is_void AND t.txn_date BETWEEN :from AND :to) AS fuel_txns,
                       (SELECT count(*) FROM fuel_transactions t WHERE t.vehicle_id = v.id AND NOT t.is_void AND t.odometer_km IS NULL AND t.txn_date BETWEEN :from AND :to) AS fuel_no_odometer,
                       (SELECT count(*) FROM odometer_readings o WHERE o.vehicle_id = v.id AND NOT o.is_trusted AND NOT o.is_void AND o.reading_date BETWEEN :from AND :to) AS untrusted_readings
                FROM vehicles v""" + vehicleWhere(f, p) + " AND v.status IN ('ACTIVE','IDLE','WORKSHOP') ORDER BY v.registration_normalized", p, Rows::map);
        for (Map<String, Object> r : rows) {
            long gpsDays = ((Number) r.get("gpsDays")).longValue();
            long fuelTxns = ((Number) r.get("fuelTxns")).longValue();
            BigDecimal km = (BigDecimal) r.get("km");
            r.put("missingGpsDays", days - gpsDays);
            List<String> issues = new ArrayList<>();
            if (Calc.isPositive(km) && fuelTxns == 0) issues.add("GPS_NO_FUEL");
            if (fuelTxns > 0 && gpsDays == 0) issues.add("FUEL_NO_KM");
            if (days - gpsDays > 0) issues.add("MISSING_GPS_DAYS");
            if (((Number) r.get("untrustedReadings")).longValue() > 0) issues.add("UNTRUSTED_ODOMETER");
            r.put("issues", String.join(", ", issues));
        }
        return rows;
    }

    private List<Map<String, Object>> importErrors(FleetFilter f) {
        MapSqlParameterSource p = f.dateParams();
        return jdbc.query("""
                SELECT b.id AS batch_id, b.import_type, b.original_filename, CAST(b.created_at AS date) AS uploaded_on, e.row_number, e.stage,
                       e.error_code, e.message, e.raw_value
                FROM import_row_errors e JOIN import_batches b ON b.id = e.batch_id
                WHERE CAST(b.created_at AS date) BETWEEN :from AND :to ORDER BY b.id, e.row_number LIMIT 500000""", p, Rows::map);
    }

    private List<Map<String, Object>> departmentRows(FleetFilter f) {
        MapSqlParameterSource p = f.dateParams();
        String dept = """
                (SELECT a.department_id FROM vehicle_assignments a WHERE a.vehicle_id = v.id AND a.assignment_type = 'DEPARTMENT'
                  AND a.effective_from <= %s AND (a.effective_to IS NULL OR a.effective_to >= %s) ORDER BY a.effective_from DESC LIMIT 1)""";
        String sql = """
                WITH facts AS (
                    SELECT %s AS dept_id, v.id AS vehicle_id, g.distance_km AS km, 0::numeric AS fuel, 0::numeric AS maint
                    FROM gps_daily_km g JOIN vehicles v ON v.id = g.vehicle_id %s AND g.is_current AND g.km_date BETWEEN :from AND :to
                    UNION ALL
                    SELECT %s, v.id, 0, t.expense_amount, 0
                    FROM fuel_transactions t JOIN vehicles v ON v.id = t.vehicle_id %s AND NOT t.is_void AND t.txn_date BETWEEN :from AND :to
                    UNION ALL
                    SELECT %s, v.id, 0, 0, m.total_cost
                    FROM maintenance_records m JOIN vehicles v ON v.id = m.vehicle_id %s AND NOT m.is_void AND m.service_date BETWEEN :from AND :to
                )
                SELECT coalesce(d.name, 'Unassigned') AS department_name, count(DISTINCT facts.vehicle_id) AS vehicles, sum(facts.km) AS km,
                       sum(facts.fuel) AS fuel_expense, sum(facts.maint) AS maintenance_expense, sum(facts.fuel) + sum(facts.maint) AS total_expense
                FROM facts LEFT JOIN departments d ON d.id = facts.dept_id
                GROUP BY d.name ORDER BY d.name NULLS LAST""";
        StringBuilder w = new StringBuilder(" WHERE 1=1");
        f.appendVehicleConditions(w, p, "v");
        String filled = String.format(sql,
                String.format(dept, "g.km_date", "g.km_date"), w,
                String.format(dept, "t.txn_date", "t.txn_date"), w,
                String.format(dept, "m.service_date", "m.service_date"), w);
        List<Map<String, Object>> rows = jdbc.query(filled, p, Rows::map);
        for (Map<String, Object> r : rows) {
            r.put("fuelCostPerKm", Calc.costPerKm((BigDecimal) r.get("fuelExpense"), (BigDecimal) r.get("km")).orElse(null));
        }
        return rows;
    }

    private List<Map<String, Object>> vehicleExpenseHistory(FleetFilter f) {
        MapSqlParameterSource p = f.dateParams();
        StringBuilder w = new StringBuilder(" WHERE 1=1");
        f.appendVehicleConditions(w, p, "v");
        List<Map<String, Object>> rows = jdbc.query("""
                WITH facts AS (
                    SELECT g.vehicle_id, date_trunc('month', g.km_date) AS m, g.distance_km AS km, 0::numeric AS fuel, 0::numeric AS maint
                    FROM gps_daily_km g WHERE g.is_current AND g.km_date BETWEEN :from AND :to
                    UNION ALL
                    SELECT t.vehicle_id, date_trunc('month', t.txn_date), 0, t.expense_amount, 0
                    FROM fuel_transactions t WHERE NOT t.is_void AND t.txn_date BETWEEN :from AND :to
                    UNION ALL
                    SELECT m.vehicle_id, date_trunc('month', m.service_date), 0, 0, m.total_cost
                    FROM maintenance_records m WHERE NOT m.is_void AND m.service_date BETWEEN :from AND :to
                )
                SELECT to_char(facts.m, 'YYYY-MM') AS month, v.registration_number, sum(facts.km) AS km, sum(facts.fuel) AS fuel_expense,
                       sum(facts.maint) AS maintenance_expense, sum(facts.fuel) + sum(facts.maint) AS total_expense
                FROM facts JOIN vehicles v ON v.id = facts.vehicle_id""" + w
                + " GROUP BY facts.m, v.id ORDER BY v.registration_normalized, facts.m", p, Rows::map);
        for (Map<String, Object> r : rows) {
            BigDecimal km = (BigDecimal) r.get("km");
            boolean complete = Calc.isPositive(km) && Calc.isPositive((BigDecimal) r.get("fuelExpense"));
            r.put("costPerKm", complete ? Calc.costPerKm((BigDecimal) r.get("totalExpense"), km).orElse(null) : null);
        }
        return rows;
    }
}
