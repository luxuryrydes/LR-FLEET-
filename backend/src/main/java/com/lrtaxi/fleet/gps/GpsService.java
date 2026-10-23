package com.lrtaxi.fleet.gps;

import com.lrtaxi.fleet.audit.AuditService;
import com.lrtaxi.fleet.calc.Calc;
import com.lrtaxi.fleet.common.ApiException;
import com.lrtaxi.fleet.common.FleetFilter;
import com.lrtaxi.fleet.common.PageResult;
import com.lrtaxi.fleet.common.Paging;
import com.lrtaxi.fleet.common.Rows;
import com.lrtaxi.fleet.fuel.EfficiencyService;
import com.lrtaxi.fleet.security.CurrentUser;
import com.lrtaxi.fleet.settings.SettingsService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Daily GPS kilometres: exactly one current figure per vehicle per day, older figures kept as superseded history. */
@Service
public class GpsService {

    public enum Outcome { INSERTED, SKIPPED_SAME_VALUE, SKIPPED_EXISTING, REPLACED }

    public record Day(long vehicleId, LocalDate date, BigDecimal km, String recordMode, Integer tripCount, String source,
                      String deviceName, Long batchId, String rowNumbers, String notes) {
    }

    static final String SELECT = """
            SELECT g.id, g.vehicle_id, v.registration_number, g.km_date, g.distance_km, g.record_mode, g.trip_count, g.source,
                   g.source_device_name, g.import_batch_id, g.import_row_numbers, g.is_current, g.superseded_by_id, g.superseded_at,
                   su.full_name AS superseded_by_name, g.supersede_reason, g.notes, cu.full_name AS created_by_name, g.created_at
            FROM gps_daily_km g
            JOIN vehicles v ON v.id = g.vehicle_id
            LEFT JOIN users su ON su.id = g.superseded_by_user
            LEFT JOIN users cu ON cu.id = g.created_by
            """;

    static final Map<String, String> SORTABLE = Map.of("kmDate", "g.km_date", "registrationNumber", "v.registration_normalized",
            "distanceKm", "g.distance_km", "createdAt", "g.created_at");

    private final NamedParameterJdbcTemplate jdbc;
    private final SettingsService settings;
    private final EfficiencyService efficiency;
    private final AuditService audit;

    public GpsService(NamedParameterJdbcTemplate jdbc, SettingsService settings, EfficiencyService efficiency, AuditService audit) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.efficiency = efficiency;
        this.audit = audit;
    }

    public PageResult<Map<String, Object>> list(FleetFilter f, boolean includeHistory, String source, Integer page, Integer size, String sort) {
        Paging paging = Paging.of(page, size, sort, SORTABLE, "g.km_date DESC, v.registration_normalized");
        MapSqlParameterSource p = f.dateParams();
        StringBuilder where = where(f, includeHistory, source, p);
        Long total = jdbc.queryForObject("SELECT count(*) FROM gps_daily_km g JOIN vehicles v ON v.id = g.vehicle_id" + where, p, Long.class);
        return new PageResult<>(jdbc.query(SELECT + where + paging.sql(), p, Rows::map), total == null ? 0 : total, paging.page(), paging.size());
    }

    public List<Map<String, Object>> listForExport(FleetFilter f, String source) {
        MapSqlParameterSource p = f.dateParams();
        return jdbc.query(SELECT + where(f, false, source, p) + " ORDER BY g.km_date, v.registration_normalized LIMIT 1000000", p, Rows::map);
    }

    private static StringBuilder where(FleetFilter f, boolean includeHistory, String source, MapSqlParameterSource p) {
        StringBuilder w = new StringBuilder(" WHERE g.km_date BETWEEN :from AND :to");
        if (!includeHistory) {
            w.append(" AND g.is_current");
        }
        if (source != null && !source.isBlank()) {
            w.append(" AND g.source = :source");
            p.addValue("source", source);
        }
        f.appendVehicleConditions(w, p, "v");
        return w;
    }

    public void validateKm(BigDecimal km) {
        if (km == null) {
            throw ApiException.badRequest("MISSING_KM", "Distance is required");
        }
        if (km.signum() < 0) {
            throw ApiException.badRequest("NEGATIVE_KM", "Distance cannot be negative");
        }
        BigDecimal max = settings.decimal("gps.max_daily_km", "1500");
        if (km.compareTo(max) > 0) {
            throw ApiException.badRequest("KM_ABOVE_LIMIT", "Distance " + km + " km exceeds the daily limit of " + max + " km");
        }
    }

    /**
     * Applies one vehicle-day. Same value already saved: skipped. Different value saved: kept (SKIP) or superseded (REPLACE)
     * with who/when/why recorded on the old row and in the audit log. Does not recompute efficiency (callers batch that).
     */
    public Outcome apply(Day d, boolean replace, String reason) {
        validateKm(d.km());
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id, distance_km FROM gps_daily_km WHERE vehicle_id = :v AND km_date = :d AND is_current FOR UPDATE",
                new MapSqlParameterSource("v", d.vehicleId()).addValue("d", d.date()));
        Long oldId = null;
        if (!existing.isEmpty()) {
            BigDecimal old = (BigDecimal) existing.get(0).get("distance_km");
            if (Calc.sameValue(old, d.km())) {
                return Outcome.SKIPPED_SAME_VALUE;
            }
            if (!replace) {
                return Outcome.SKIPPED_EXISTING;
            }
            oldId = ((Number) existing.get(0).get("id")).longValue();
            jdbc.update("UPDATE gps_daily_km SET is_current = FALSE WHERE id = :id", Map.of("id", oldId));
        }
        Long newId = jdbc.queryForObject("""
                INSERT INTO gps_daily_km (vehicle_id, km_date, distance_km, record_mode, trip_count, source, source_device_name,
                    import_batch_id, import_row_numbers, notes, created_by)
                VALUES (:v, :d, :km, :mode, :trips, :src, :device, :batch, :rows, :notes, :uid) RETURNING id""",
                new MapSqlParameterSource("v", d.vehicleId()).addValue("d", d.date()).addValue("km", d.km())
                        .addValue("mode", d.recordMode()).addValue("trips", d.tripCount()).addValue("src", d.source())
                        .addValue("device", d.deviceName()).addValue("batch", d.batchId()).addValue("rows", d.rowNumbers())
                        .addValue("notes", d.notes()).addValue("uid", CurrentUser.idOrNull()), Long.class);
        if (oldId != null) {
            jdbc.update("""
                    UPDATE gps_daily_km SET superseded_by_id = :n, superseded_at = now(), superseded_by_user = :uid, supersede_reason = :r
                    WHERE id = :o""",
                    new MapSqlParameterSource("n", newId).addValue("uid", CurrentUser.idOrNull()).addValue("r", reason).addValue("o", oldId));
            audit.record("REPLACE", "GPS_DAILY_KM", oldId,
                    Map.of("id", oldId, "km", existing.get(0).get("distance_km")),
                    Map.of("id", newId, "km", d.km(), "reason", reason == null ? "" : reason, "batchId", d.batchId() == null ? "" : d.batchId()));
            return Outcome.REPLACED;
        }
        return Outcome.INSERTED;
    }

    /** Manual entry when GPS data is unavailable, or a correction of an existing day (requires a reason). */
    @Transactional
    public Map<String, Object> manual(long vehicleId, LocalDate date, BigDecimal km, String notes, String correctionReason) {
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT status = 'DELETED' FROM vehicles WHERE id = :v", Map.of("v", vehicleId), Boolean.class))) {
            throw ApiException.badRequest("VEHICLE_DELETED", "This vehicle is deleted. Restore it under Vehicles to record km.");
        }
        if (date.isAfter(LocalDate.now())) {
            throw ApiException.badRequest("FUTURE_DATE", "Date cannot be in the future");
        }
        boolean exists = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM gps_daily_km WHERE vehicle_id = :v AND km_date = :d AND is_current)",
                new MapSqlParameterSource("v", vehicleId).addValue("d", date), Boolean.class));
        if (exists && (correctionReason == null || correctionReason.isBlank())) {
            throw ApiException.conflict("GPS_EXISTS", "A km figure already exists for this vehicle and date. Enter a correction reason to replace it.");
        }
        if (exists && !CurrentUser.require().canManage()) {
            throw ApiException.forbidden("Only a manager can correct an existing GPS km figure");
        }
        Outcome o = apply(new Day(vehicleId, date, km, "MANUAL", null, exists ? "CORRECTION" : "MANUAL", null, null, null, notes),
                exists, correctionReason);
        if (o == Outcome.INSERTED) {
            audit.record("CREATE", "GPS_DAILY_KM", vehicleId + "@" + date, null, Map.of("km", km));
        }
        efficiency.recompute(vehicleId);
        return Map.of("outcome", o.name());
    }
}
