package com.lrtaxi.fleet.alerts;

import com.lrtaxi.fleet.calc.Calc;
import com.lrtaxi.fleet.calc.IndianFormat;
import com.lrtaxi.fleet.common.FleetFilter;
import com.lrtaxi.fleet.documents.DocumentService;
import com.lrtaxi.fleet.fuel.EfficiencyService;
import com.lrtaxi.fleet.maintenance.ServiceStatusService;
import com.lrtaxi.fleet.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rule-based alert generation from database records. Every alert stores the rule, threshold, observed value and the
 * contributing records, so users can investigate rather than assume wrongdoing. Thresholds come from app_settings.
 */
@Service
public class AlertEngine {

    private static final Logger log = LoggerFactory.getLogger(AlertEngine.class);

    /** Types this engine owns; open alerts of these types that are no longer detected are auto-resolved. */
    static final List<String> ENGINE_TYPES = List.of("GPS_NO_FUEL", "FUEL_NO_KM", "HIGH_COST_PER_KM", "FALLING_MILEAGE",
            "REPEATED_INVOICE", "INVALID_ODOMETER", "NO_GPS_REPORT", "SERVICE_DUE", "SERVICE_OVERDUE", "MISSING_SERVICE_BASELINE",
            "HIGH_MAINTENANCE_COST", "FUEL_QTY_OUT_OF_RANGE", "DOCUMENT_EXPIRY", "POSSIBLE_DOUBLE_CHARGE");

    private final NamedParameterJdbcTemplate jdbc;
    private final AlertService alerts;
    private final SettingsService settings;
    private final EfficiencyService efficiency;
    private final ServiceStatusService serviceStatus;
    private final DocumentService documents;

    public AlertEngine(NamedParameterJdbcTemplate jdbc, AlertService alerts, SettingsService settings, EfficiencyService efficiency,
                       ServiceStatusService serviceStatus, DocumentService documents) {
        this.jdbc = jdbc;
        this.alerts = alerts;
        this.settings = settings;
        this.efficiency = efficiency;
        this.serviceStatus = serviceStatus;
        this.documents = documents;
    }

    @Scheduled(cron = "${app.alerts-cron}", zone = "Asia/Kolkata")
    public void nightly() {
        try {
            Map<String, Integer> r = run();
            log.info("Nightly alert run: {}", r);
        } catch (RuntimeException e) {
            log.error("Nightly alert run failed", e);
        }
    }

    @Transactional
    public Map<String, Integer> run() {
        Boolean locked = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(424242)", Map.of(), Boolean.class);
        if (!Boolean.TRUE.equals(locked)) {
            return Map.of("skipped", 1);
        }
        Instant start = jdbc.queryForObject("SELECT now()", Map.of(), Timestamp.class).toInstant();
        LocalDate today = LocalDate.now();
        int window = settings.integer("alert.window_days", 30);
        LocalDate from = today.minusDays(window - 1L);
        MapSqlParameterSource p = new MapSqlParameterSource("from", from).addValue("to", today);
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Object> period = Map.of("from", from.toString(), "to", today.toString(), "windowDays", window);

        // 1. GPS km without fuel records
        int minKm = settings.integer("alert.gps_no_fuel_min_km", 300);
        p.addValue("minKm", minKm);
        count(counts, "GPS_NO_FUEL", each(jdbc.queryForList("""
                SELECT v.id, v.registration_number, sum(g.distance_km) AS km, count(*) AS days
                FROM gps_daily_km g JOIN vehicles v ON v.id = g.vehicle_id
                WHERE g.is_current AND g.km_date BETWEEN :from AND :to
                  AND NOT EXISTS (SELECT 1 FROM fuel_transactions f WHERE f.vehicle_id = v.id AND NOT f.is_void AND f.txn_date BETWEEN :from AND :to)
                GROUP BY v.id HAVING sum(g.distance_km) >= :minKm""", p), r -> alerts.raise(new AlertService.AlertSpec(
                "GPS_NO_FUEL", "MEDIUM", id(r), "GPS_NO_FUEL|" + id(r),
                r.get("registration_number") + ": " + r.get("km") + " km with no fuel record",
                "GPS reported " + r.get("km") + " km over " + r.get("days") + " day(s) between " + IndianFormat.date(from) + " and "
                        + IndianFormat.date(today) + " but no fuel or charging transaction is recorded.",
                rule("GPS km in window ≥ threshold and no fuel transactions in window", minKm + " km", r.get("km"), period),
                List.of(link("GPS_KM", id(r), "GPS km " + IndianFormat.date(from) + " to " + IndianFormat.date(today))), from, today))));

        // 2. Fuel without usable km data
        count(counts, "FUEL_NO_KM", each(jdbc.queryForList("""
                SELECT v.id, v.registration_number, count(*) AS txns, string_agg(f.id::text, ',') AS ids
                FROM fuel_transactions f JOIN vehicles v ON v.id = f.vehicle_id
                WHERE NOT f.is_void AND f.txn_date BETWEEN :from AND :to AND f.odometer_km IS NULL
                  AND NOT EXISTS (SELECT 1 FROM gps_daily_km g WHERE g.vehicle_id = v.id AND g.is_current AND g.km_date BETWEEN :from AND :to)
                GROUP BY v.id""", p), r -> alerts.raise(new AlertService.AlertSpec(
                "FUEL_NO_KM", "MEDIUM", id(r), "FUEL_NO_KM|" + id(r),
                r.get("registration_number") + ": fuel bought but no km data",
                r.get("txns") + " fuel transaction(s) without odometer and no GPS km in the window; mileage cannot be calculated.",
                rule("Fuel transactions in window without odometer and no GPS km in window", "0 GPS days", r.get("txns") + " transactions", period),
                ids("FUEL_TRANSACTION", (String) r.get("ids")), from, today))));

        // 3. High fuel cost per km vs fleet average for the same vehicle fuel type (purchase basis)
        BigDecimal pct = settings.decimal("alert.high_cpk_pct", "30");
        List<Map<String, Object>> cpk = jdbc.queryForList("""
                WITH km AS (SELECT vehicle_id, sum(distance_km) AS km FROM gps_daily_km WHERE is_current AND km_date BETWEEN :from AND :to GROUP BY vehicle_id),
                     fuel AS (SELECT vehicle_id, sum(expense_amount) AS amt FROM fuel_transactions WHERE NOT is_void AND txn_date BETWEEN :from AND :to GROUP BY vehicle_id)
                SELECT v.id, v.registration_number, v.fuel_type, km.km, fuel.amt
                FROM vehicles v JOIN km ON km.vehicle_id = v.id JOIN fuel ON fuel.vehicle_id = v.id
                WHERE km.km > 0 AND fuel.amt > 0""", p);
        Map<String, BigDecimal[]> byType = new HashMap<>();
        for (Map<String, Object> r : cpk) {
            BigDecimal[] t = byType.computeIfAbsent((String) r.get("fuel_type"), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            t[0] = t[0].add((BigDecimal) r.get("amt"));
            t[1] = t[1].add((BigDecimal) r.get("km"));
        }
        int high = 0;
        for (Map<String, Object> r : cpk) {
            BigDecimal[] t = byType.get((String) r.get("fuel_type"));
            BigDecimal avg = Calc.costPerKm(t[0], t[1]).orElse(null);
            BigDecimal mine = Calc.costPerKm((BigDecimal) r.get("amt"), (BigDecimal) r.get("km")).orElse(null);
            if (avg == null || mine == null || avg.signum() == 0) {
                continue;
            }
            BigDecimal limit = avg.multiply(BigDecimal.ONE.add(pct.movePointLeft(2)));
            if (mine.compareTo(limit) > 0) {
                high++;
                alerts.raise(new AlertService.AlertSpec("HIGH_COST_PER_KM", "MEDIUM", id(r), "HIGH_COST_PER_KM|" + id(r),
                        r.get("registration_number") + ": fuel cost ₹" + mine + "/km vs fleet ₹" + avg + "/km",
                        "Fuel expense " + IndianFormat.rupees((BigDecimal) r.get("amt")) + " over " + r.get("km") + " GPS km (purchase basis) is more than "
                                + pct + "% above the " + r.get("fuel_type") + " fleet average.",
                        rule("Vehicle fuel cost/km > fleet average for its fuel type × (1 + threshold)", pct + "% above ₹" + avg, "₹" + mine, period),
                        List.of(link("FUEL_TRANSACTION", id(r), "Fuel transactions in window"), link("GPS_KM", id(r), "GPS km in window")),
                        from, today));
            }
        }
        counts.put("HIGH_COST_PER_KM", high);

        // 4. Falling mileage vs expected
        FleetFilter wf = new FleetFilter(from, today, null, null, null, null, null, null, null, null, null, null);
        int falling = 0;
        for (Map<String, Object> r : efficiency.vehicleMileageTable(wf)) {
            if (!Boolean.TRUE.equals(r.get("belowThreshold"))) {
                continue;
            }
            falling++;
            long vid = ((Number) r.get("vehicleId")).longValue();
            List<Map<String, Object>> contrib = jdbc.queryForList("""
                    SELECT id FROM fuel_efficiency_intervals WHERE vehicle_id = :v AND energy_type = :e AND end_date BETWEEN :from AND :to
                    ORDER BY end_date LIMIT 50""", new MapSqlParameterSource(p.getValues()).addValue("v", vid).addValue("e", r.get("energyType")));
            List<Map<String, Object>> links = new ArrayList<>();
            for (Map<String, Object> c : contrib) {
                links.add(link("EFFICIENCY_INTERVAL", ((Number) c.get("id")).longValue(), "Interval " + c.get("id")));
            }
            alerts.raise(new AlertService.AlertSpec("FALLING_MILEAGE", "HIGH", vid, "FALLING_MILEAGE|" + vid + "|" + r.get("energyType"),
                    r.get("registrationNumber") + ": " + r.get("energyType") + " mileage " + r.get("mileage") + " " + r.get("unit")
                            + " is " + r.get("variancePct") + "% vs expected",
                    "Weighted mileage over " + r.get("eligibleIntervals") + " eligible refuelling interval(s) is " + r.get("mileage") + " "
                            + r.get("unit") + " against an expected " + r.get("expectedMileage") + ". Data quality: " + r.get("dataQuality")
                            + (r.get("exclusionReasons") != null ? " (excluded intervals: " + r.get("exclusionReasons") + ")" : "")
                            + ". Check the contributing intervals before drawing conclusions.",
                    rule("Mileage variance ≤ −threshold", "−" + r.get("lowThresholdPct") + "%", r.get("variancePct") + "%", period),
                    links, from, today));
        }
        counts.put("FALLING_MILEAGE", falling);

        // 5. Repeated invoice numbers
        count(counts, "REPEATED_INVOICE", each(jdbc.queryForList("""
                SELECT upper(invoice_number) AS inv, count(*) AS n, string_agg(id::text, ',') AS ids, min(vehicle_id) AS vehicle_id
                FROM fuel_transactions WHERE NOT is_void AND invoice_number IS NOT NULL
                GROUP BY upper(invoice_number) HAVING count(*) > 1""", p), r -> alerts.raise(new AlertService.AlertSpec(
                "REPEATED_INVOICE", "HIGH", ((Number) r.get("vehicle_id")).longValue(), "REPEATED_INVOICE|" + r.get("inv"),
                "Invoice " + r.get("inv") + " used on " + r.get("n") + " fuel transactions",
                "The same invoice number appears on several transactions. Verify the invoices and void any duplicate.",
                rule("Invoice number must be unique", "1", r.get("n"), period),
                ids("FUEL_TRANSACTION", (String) r.get("ids")), null, null))));

        // 5b. Possible double charges: same vehicle, fuel, quantity and amount within 10 minutes (different transactions)
        count(counts, "POSSIBLE_DOUBLE_CHARGE", each(jdbc.queryForList("""
                SELECT a.id AS a_id, b.id AS b_id, a.vehicle_id, v.registration_number, a.txn_date, a.quantity, a.unit, a.expense_amount
                FROM fuel_transactions a
                JOIN fuel_transactions b ON b.vehicle_id = a.vehicle_id AND b.txn_date = a.txn_date AND b.energy_type = a.energy_type
                     AND b.quantity = a.quantity AND b.expense_amount = a.expense_amount AND b.id > a.id AND NOT b.is_void
                     AND a.txn_time IS NOT NULL AND b.txn_time IS NOT NULL
                     AND abs(extract(epoch FROM (b.txn_time - a.txn_time))) <= 600
                JOIN vehicles v ON v.id = a.vehicle_id
                WHERE NOT a.is_void AND a.txn_date BETWEEN :from AND :to""", p), r -> alerts.raise(new AlertService.AlertSpec(
                "POSSIBLE_DOUBLE_CHARGE", "HIGH", ((Number) r.get("vehicle_id")).longValue(), "DOUBLE_CHARGE|" + r.get("a_id") + "|" + r.get("b_id"),
                r.get("registration_number") + ": possible double charge of " + IndianFormat.rupees((BigDecimal) r.get("expense_amount")),
                "Two transactions on " + IndianFormat.date(((java.sql.Date) r.get("txn_date")).toLocalDate()) + " for the same quantity ("
                        + r.get("quantity") + " " + r.get("unit") + ") and amount within 10 minutes. Both are counted; check with the fuel"
                        + " company whether the card was charged twice.",
                rule("Same vehicle, fuel, quantity and amount within 10 minutes", "10 minutes", "2 transactions", period),
                List.of(link("FUEL_TRANSACTION", ((Number) r.get("a_id")).longValue(), "Transaction " + r.get("a_id")),
                        link("FUEL_TRANSACTION", ((Number) r.get("b_id")).longValue(), "Transaction " + r.get("b_id"))), null, null))));

        // 6. Invalid / decreasing odometer readings
        count(counts, "INVALID_ODOMETER", each(jdbc.queryForList("""
                SELECT o.id, o.vehicle_id, v.registration_number, o.reading_date, o.reading_km, o.source, o.source_ref_id, o.untrusted_reason
                FROM odometer_readings o JOIN vehicles v ON v.id = o.vehicle_id
                WHERE NOT o.is_trusted AND NOT o.is_void AND o.reading_date BETWEEN :from AND :to""", p), r -> {
            long vid = ((Number) r.get("vehicle_id")).longValue();
            List<Map<String, Object>> links = new ArrayList<>();
            links.add(link("ODOMETER_READING", ((Number) r.get("id")).longValue(), "Reading " + r.get("reading_km") + " km"));
            if (r.get("source_ref_id") != null) {
                links.add(link("FUEL".equals(r.get("source")) ? "FUEL_TRANSACTION" : "SERVICE".equals(r.get("source")) ? "MAINTENANCE_RECORD" : "VEHICLE",
                        ((Number) r.get("source_ref_id")).longValue(), r.get("source") + " record"));
            }
            alerts.raise(new AlertService.AlertSpec("INVALID_ODOMETER", "MEDIUM", vid, "INVALID_ODOMETER|" + r.get("id"),
                    r.get("registration_number") + ": odometer reading not trusted",
                    String.valueOf(r.get("untrusted_reason")),
                    rule("Odometer must not decrease, exceed later readings or jump more than the daily limit", "progressive", r.get("reading_km"), period),
                    links, null, null));
        }));

        // 7. Vehicles not reporting GPS
        int noDays = settings.integer("gps.no_report_days", 3);
        p.addValue("since", today.minusDays(noDays));
        count(counts, "NO_GPS_REPORT", each(jdbc.queryForList("""
                SELECT v.id, v.registration_number, (SELECT max(km_date) FROM gps_daily_km g WHERE g.vehicle_id = v.id AND g.is_current) AS last_date
                FROM vehicles v WHERE v.status = 'ACTIVE'
                  AND NOT EXISTS (SELECT 1 FROM gps_daily_km g WHERE g.vehicle_id = v.id AND g.is_current AND g.km_date > :since)""", p), r -> alerts.raise(new AlertService.AlertSpec("NO_GPS_REPORT", "LOW", id(r), "NO_GPS_REPORT|" + id(r),
                        r.get("registration_number") + ": no GPS km for " + noDays + "+ days",
                        "Last GPS record: " + (r.get("last_date") == null ? "never" : IndianFormat.date(((java.sql.Date) r.get("last_date")).toLocalDate())),
                        rule("Active vehicle must report GPS km", noDays + " days", r.get("last_date") == null ? "never" : String.valueOf(r.get("last_date")), period),
                        List.of(link("VEHICLE", id(r), "Vehicle")), null, null))));

        // 8. Service status
        int due = 0;
        int overdue = 0;
        int baseline = 0;
        for (Map<String, Object> s : serviceStatus.fleet(FleetFilter.empty(), null)) {
            String st = (String) s.get("status");
            long vid = ((Number) s.get("vehicleId")).longValue();
            String reg = (String) s.get("registrationNumber");
            String reasons = String.join("; ", castList(s.get("reasons")));
            Map<String, Object> r = rule("Service due calculation", "due soon ≤ " + settings.integer("service.due_soon_km", 500) + " km",
                    s.get("remainingKm") == null ? st : s.get("remainingKm") + " km remaining", period);
            List<Map<String, Object>> links = s.get("lastServiceId") == null ? List.of(link("VEHICLE", vid, "Vehicle"))
                    : List.of(link("MAINTENANCE_RECORD", ((Number) s.get("lastServiceId")).longValue(), "Last service"));
            switch (st) {
                case "OVERDUE" -> {
                    overdue++;
                    alerts.raise(new AlertService.AlertSpec("SERVICE_OVERDUE", "HIGH", vid, "SERVICE|" + vid, reg + ": service overdue", reasons, r, links, null, null));
                }
                case "DUE_SOON" -> {
                    due++;
                    alerts.raise(new AlertService.AlertSpec("SERVICE_DUE", "MEDIUM", vid, "SERVICE|" + vid, reg + ": service due soon", reasons, r, links, null, null));
                }
                case "MISSING_SERVICE_RECORD", "MISSING_BASELINE", "UNRELIABLE_BASELINE" -> {
                    baseline++;
                    alerts.raise(new AlertService.AlertSpec("MISSING_SERVICE_BASELINE", "LOW", vid, "SERVICE_BASELINE|" + vid,
                            reg + ": " + st.replace('_', ' ').toLowerCase(), reasons, r, links, null, null));
                }
                default -> {
                }
            }
        }
        counts.put("SERVICE_OVERDUE", overdue);
        counts.put("SERVICE_DUE", due);
        counts.put("MISSING_SERVICE_BASELINE", baseline);

        // 9. High maintenance expenditure
        BigDecimal maxMaint = settings.decimal("alert.high_maintenance_amount", "50000");
        p.addValue("maxMaint", maxMaint);
        count(counts, "HIGH_MAINTENANCE_COST", each(jdbc.queryForList("""
                SELECT v.id, v.registration_number, sum(m.total_cost) AS amt, string_agg(m.id::text, ',') AS ids
                FROM maintenance_records m JOIN vehicles v ON v.id = m.vehicle_id
                WHERE NOT m.is_void AND m.service_date BETWEEN :from AND :to
                GROUP BY v.id HAVING sum(m.total_cost) > :maxMaint""", p), r -> alerts.raise(new AlertService.AlertSpec(
                "HIGH_MAINTENANCE_COST", "MEDIUM", id(r), "HIGH_MAINTENANCE_COST|" + id(r),
                r.get("registration_number") + ": maintenance " + IndianFormat.rupees((BigDecimal) r.get("amt")) + " in " + window + " days",
                "Maintenance spend in the window exceeds the configured threshold.",
                rule("Maintenance spend in window > threshold", IndianFormat.rupees(maxMaint), IndianFormat.rupees((BigDecimal) r.get("amt")), period),
                ids("MAINTENANCE_RECORD", (String) r.get("ids")), from, today))));

        // 10. Fuel quantity outside limits
        count(counts, "FUEL_QTY_OUT_OF_RANGE", each(jdbc.queryForList("""
                SELECT f.id, f.vehicle_id, v.registration_number, f.quantity, f.unit, f.quantity_flag, f.txn_date
                FROM fuel_transactions f JOIN vehicles v ON v.id = f.vehicle_id
                WHERE NOT f.is_void AND f.quantity_flag IS NOT NULL AND f.txn_date BETWEEN :from AND :to""", p), r -> alerts.raise(
                new AlertService.AlertSpec("FUEL_QTY_OUT_OF_RANGE", "MEDIUM", ((Number) r.get("vehicle_id")).longValue(), "FUEL_QTY|" + r.get("id"),
                        r.get("registration_number") + ": " + r.get("quantity") + " " + r.get("unit") + " in one transaction",
                        "Quantity exceeds " + ("EXCEEDS_CAPACITY".equals(r.get("quantity_flag")) ? "the vehicle's tank/battery capacity" : "the configured maximum"),
                        rule("Quantity within capacity and configured maximum", String.valueOf(r.get("quantity_flag")), r.get("quantity"), period),
                        List.of(link("FUEL_TRANSACTION", ((Number) r.get("id")).longValue(), "Transaction")), null, null))));

        // 11. Document expiry
        Map<String, Object> compliance = documents.compliance(FleetFilter.empty());
        int docs = 0;
        for (String bucket : List.of("expired", "expiring")) {
            for (Map<String, Object> d : castMaps(compliance.get(bucket))) {
                docs++;
                long days = d.get("daysToExpiry") == null ? 0 : ((Number) d.get("daysToExpiry")).longValue();
                String sev = days < 0 ? "HIGH" : days <= 7 ? "MEDIUM" : "LOW";
                alerts.raise(new AlertService.AlertSpec("DOCUMENT_EXPIRY", sev, ((Number) d.get("vehicleId")).longValue(),
                        "DOC|" + d.get("id"), d.get("registrationNumber") + ": " + d.get("docType") + (days < 0 ? " expired" : " expires in " + days + " day(s)"),
                        d.get("docType") + " " + (d.get("documentNumber") == null ? "" : d.get("documentNumber") + " ") + "expiry "
                                + IndianFormat.date((LocalDate) d.get("expiryDate")),
                        rule("Document expiry reminders", String.valueOf(compliance.get("reminderDays")), days + " days", period),
                        List.of(link("DOCUMENT", ((Number) d.get("id")).longValue(), String.valueOf(d.get("docType")))), null, null));
            }
        }
        counts.put("DOCUMENT_EXPIRY", docs);

        // Auto-resolve OPEN engine alerts whose condition was not detected in this run.
        List<Long> stale = jdbc.queryForList("""
                SELECT id FROM alerts WHERE status = 'OPEN' AND alert_type IN (:types) AND last_detected_at < :start""",
                new MapSqlParameterSource("types", ENGINE_TYPES).addValue("start", Timestamp.from(start)), Long.class);
        for (Long id : stale) {
            jdbc.update("UPDATE alerts SET status = 'RESOLVED', resolved_at = now(), resolution_note = 'Condition no longer detected (automatic)' WHERE id = :id",
                    Map.of("id", id));
            jdbc.update("INSERT INTO alert_events (alert_id, event_type, note) VALUES (:id, 'RESOLVED', 'Condition no longer detected (automatic)')",
                    Map.of("id", id));
        }
        counts.put("autoResolved", stale.size());
        return counts;
    }

    /** Runs the consumer for every row (not Stream.peek, which count() may skip) and returns the row count. */
    private static long each(List<Map<String, Object>> rows, java.util.function.Consumer<Map<String, Object>> action) {
        rows.forEach(action);
        return rows.size();
    }

    private static void count(Map<String, Integer> counts, String type, long n) {
        counts.put(type, (int) n);
    }

    private static long id(Map<String, Object> r) {
        return ((Number) r.get("id")).longValue();
    }

    static Map<String, Object> rule(String rule, Object threshold, Object observed, Map<String, Object> period) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rule", rule);
        m.put("threshold", String.valueOf(threshold));
        m.put("observed", String.valueOf(observed));
        m.put("period", period);
        return m;
    }

    static Map<String, Object> link(String type, long id, String label) {
        return Map.of("type", type, "id", id, "label", label);
    }

    private static List<Map<String, Object>> ids(String type, String csv) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (csv == null) {
            return out;
        }
        for (String s : csv.split(",")) {
            if (out.size() >= 100) {
                break;
            }
            out.add(link(type, Long.parseLong(s.trim()), type.replace('_', ' ').toLowerCase() + " #" + s.trim()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object o) {
        return o == null ? List.of() : (List<String>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castMaps(Object o) {
        return o == null ? List.of() : (List<Map<String, Object>>) o;
    }
}
