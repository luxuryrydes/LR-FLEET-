package com.lrtaxi.fleet.maintenance;

import com.lrtaxi.fleet.calc.Odometer;
import com.lrtaxi.fleet.calc.ServiceDue;
import com.lrtaxi.fleet.common.FleetFilter;
import com.lrtaxi.fleet.common.Rows;
import com.lrtaxi.fleet.settings.SettingsService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service-due status for one vehicle or the whole fleet. Bulk SQL gathers the inputs; the decision is made by
 * the Java engine (Odometer.baseline + ServiceDue.evaluate) for every vehicle, so list, dashboard and report agree.
 */
@Service
public class ServiceStatusService {

    private static final String SQL = """
            WITH v AS (
                SELECT v.id, v.registration_number, v.registration_normalized, v.make, v.model, v.fuel_type, v.status AS vehicle_status,
                       v.current_department_id, dp.name AS department_name,
                       coalesce(v.service_interval_km, c.service_interval_km) AS interval_km,
                       coalesce(v.service_interval_months, c.service_interval_months) AS interval_months
                FROM vehicles v
                JOIN vehicle_categories c ON c.code = v.category_code
                LEFT JOIN departments dp ON dp.id = v.current_department_id
                WHERE %s
            ),
            last_service AS (
                SELECT DISTINCT ON (m.vehicle_id) m.vehicle_id, m.id AS service_id, m.service_date, m.odometer_km,
                       m.next_service_odometer, m.next_service_date, m.next_service_overridden
                FROM maintenance_records m JOIN service_categories sc ON sc.code = m.category_code
                WHERE NOT m.is_void AND sc.resets_service_interval AND m.vehicle_id IN (SELECT id FROM v)
                ORDER BY m.vehicle_id, m.service_date DESC, m.id DESC
            ),
            latest AS (
                SELECT DISTINCT ON (o.vehicle_id) o.vehicle_id, o.id AS reading_id, o.reading_date, o.reading_km
                FROM odometer_readings o
                WHERE o.is_trusted AND NOT o.is_void AND o.vehicle_id IN (SELECT id FROM v)
                ORDER BY o.vehicle_id, o.reading_date DESC, o.reading_km DESC, o.id DESC
            )
            SELECT v.*, ls.service_id, ls.service_date, ls.odometer_km AS service_odometer, ls.next_service_odometer,
                   ls.next_service_date, ls.next_service_overridden,
                   l.reading_id, l.reading_date, l.reading_km,
                   (SELECT max(o.reading_km) FROM odometer_readings o
                     WHERE o.vehicle_id = v.id AND o.is_trusted AND NOT o.is_void AND o.reading_date < l.reading_date) AS prior_max_km,
                   (SELECT sum(g.distance_km) FROM gps_daily_km g
                     WHERE g.vehicle_id = v.id AND g.is_current AND g.km_date > l.reading_date AND g.km_date <= :today) AS gps_after
            FROM v
            LEFT JOIN last_service ls ON ls.vehicle_id = v.id
            LEFT JOIN latest l ON l.vehicle_id = v.id
            ORDER BY v.registration_normalized
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final SettingsService settings;

    public ServiceStatusService(NamedParameterJdbcTemplate jdbc, SettingsService settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    public Map<String, Object> forVehicle(long vehicleId) {
        MapSqlParameterSource p = new MapSqlParameterSource("vid", vehicleId).addValue("today", LocalDate.now());
        List<Map<String, Object>> rows = run("v.id = :vid", p);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    /** All vehicles matching the filter (active statuses only unless a status filter is given), optionally by due status. */
    public List<Map<String, Object>> fleet(FleetFilter f, String dueStatus) {
        MapSqlParameterSource p = new MapSqlParameterSource("today", LocalDate.now());
        StringBuilder where = new StringBuilder("1=1");
        if (f.status() == null || f.status().isBlank()) {
            where.append(" AND v.status IN ('ACTIVE','IDLE','WORKSHOP')");
        }
        f.appendVehicleConditions(where, p, "v");
        List<Map<String, Object>> rows = run(where.toString(), p);
        if (dueStatus == null || dueStatus.isBlank()) {
            return rows;
        }
        List<String> wanted = List.of(dueStatus.split(","));
        return rows.stream().filter(r -> wanted.contains((String) r.get("status"))).toList();
    }

    private List<Map<String, Object>> run(String where, MapSqlParameterSource p) {
        int defKm = settings.integer("service.default_interval_km", 10000);
        int defMonths = settings.integer("service.default_interval_months", 6);
        int dueSoonKm = settings.integer("service.due_soon_km", 500);
        int dueSoonDays = settings.integer("service.due_soon_days", 15);
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> out = new ArrayList<>();
        jdbc.query(String.format(SQL, where), p, (ResultSet rs) -> {
            out.add(evaluate(rs, defKm, defMonths, dueSoonKm, dueSoonDays, today));
        });
        return out;
    }

    private static Map<String, Object> evaluate(ResultSet rs, int defKm, int defMonths, int dueSoonKm, int dueSoonDays, LocalDate today)
            throws SQLException {
        Integer intervalKm = Rows.intOrNull(rs, "interval_km");
        Integer intervalMonths = Rows.intOrNull(rs, "interval_months");
        int km = intervalKm != null ? intervalKm : defKm;
        int months = intervalMonths != null ? intervalMonths : defMonths;

        Odometer.Baseline baseline = null;
        Long readingId = Rows.longOrNull(rs, "reading_id");
        if (readingId != null) {
            LocalDate rd = Rows.date(rs, "reading_date");
            BigDecimal rkm = rs.getBigDecimal("reading_km");
            BigDecimal prior = rs.getBigDecimal("prior_max_km");
            List<Odometer.Reading> readings = new ArrayList<>();
            readings.add(new Odometer.Reading(readingId, rd, rkm, true));
            if (prior != null) {
                readings.add(new Odometer.Reading(-1, rd.minusDays(1), prior, true));
            }
            baseline = Odometer.baseline(readings);
        }
        boolean overridden = Boolean.TRUE.equals(Rows.boolOrNull(rs, "next_service_overridden"));
        ServiceDue.Result r = ServiceDue.evaluate(new ServiceDue.Input(
                Rows.date(rs, "service_date"), rs.getBigDecimal("service_odometer"),
                overridden ? rs.getBigDecimal("next_service_odometer") : null,
                overridden ? Rows.date(rs, "next_service_date") : null,
                km, months, baseline, rs.getBigDecimal("gps_after"), today, dueSoonKm, dueSoonDays));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vehicleId", rs.getLong("id"));
        m.put("registrationNumber", rs.getString("registration_number"));
        m.put("make", rs.getString("make"));
        m.put("model", rs.getString("model"));
        m.put("fuelType", rs.getString("fuel_type"));
        m.put("vehicleStatus", rs.getString("vehicle_status"));
        m.put("departmentName", rs.getString("department_name"));
        m.put("intervalKm", km);
        m.put("intervalMonths", months);
        m.put("lastServiceId", Rows.longOrNull(rs, "service_id"));
        m.put("lastServiceDate", Rows.date(rs, "service_date"));
        m.put("lastServiceOdometer", rs.getBigDecimal("service_odometer"));
        m.put("baselineDate", baseline == null ? null : baseline.date());
        m.put("baselineKm", baseline == null ? null : baseline.km());
        m.put("gpsKmAfterBaseline", rs.getBigDecimal("gps_after"));
        m.put("currentOdometer", r.currentOdometer());
        m.put("nextServiceOdometer", r.nextServiceOdometer());
        m.put("remainingKm", r.remainingKm());
        m.put("nextServiceDate", r.nextServiceDate());
        m.put("daysRemaining", r.daysRemaining());
        m.put("nextServiceOverridden", overridden);
        m.put("status", r.status().name());
        m.put("reasons", r.reasons());
        return m;
    }
}
