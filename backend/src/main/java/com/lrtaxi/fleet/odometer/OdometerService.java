package com.lrtaxi.fleet.odometer;

import com.lrtaxi.fleet.audit.AuditService;
import com.lrtaxi.fleet.calc.Odometer;
import com.lrtaxi.fleet.common.ApiException;
import com.lrtaxi.fleet.common.Rows;
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

/** Odometer readings from every source, validated for progression before being trusted. */
@Service
public class OdometerService {

    private final NamedParameterJdbcTemplate jdbc;
    private final SettingsService settings;
    private final AuditService audit;

    public OdometerService(NamedParameterJdbcTemplate jdbc, SettingsService settings, AuditService audit) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.audit = audit;
    }

    public List<Odometer.Reading> readings(long vehicleId) {
        return jdbc.query("SELECT id, reading_date, reading_km, is_trusted FROM odometer_readings WHERE vehicle_id = :v AND NOT is_void",
                Map.of("v", vehicleId),
                (rs, i) -> new Odometer.Reading(rs.getLong(1), rs.getDate(2).toLocalDate(), rs.getBigDecimal(3), rs.getBoolean(4)));
    }

    /**
     * Saves a reading. Readings that break progression are saved as untrusted (so the source record is kept)
     * and the returned check explains why; they are ignored for baselines and odometer mileage.
     */
    @Transactional
    public Odometer.Check addReading(long vehicleId, LocalDate date, BigDecimal km, String source, Long sourceRefId) {
        Odometer.Check check = Odometer.check(readings(vehicleId), date, km, settings.decimal("gps.max_daily_km", "1500"));
        jdbc.update("""
                INSERT INTO odometer_readings (vehicle_id, reading_date, reading_km, source, source_ref_id, is_trusted, untrusted_reason, created_by)
                VALUES (:v, :d, :km, :src, :ref, :trusted, :reason, :uid)""",
                new MapSqlParameterSource("v", vehicleId).addValue("d", date).addValue("km", km).addValue("src", source)
                        .addValue("ref", sourceRefId).addValue("trusted", check.ok()).addValue("reason", check.message())
                        .addValue("uid", CurrentUser.idOrNull()));
        return check;
    }

    /** Voids readings created from a source record that is itself voided. */
    public void voidBySource(String source, long sourceRefId) {
        jdbc.update("UPDATE odometer_readings SET is_void = TRUE WHERE source = :s AND source_ref_id = :r",
                Map.of("s", source, "r", sourceRefId));
    }

    public List<Map<String, Object>> list(long vehicleId) {
        return jdbc.query("""
                SELECT o.id, o.reading_date, o.reading_km, o.source, o.source_ref_id, o.is_trusted, o.untrusted_reason, o.is_void,
                       u.full_name AS created_by_name, o.created_at
                FROM odometer_readings o LEFT JOIN users u ON u.id = o.created_by
                WHERE o.vehicle_id = :v ORDER BY o.reading_date DESC, o.reading_km DESC""", Map.of("v", vehicleId), Rows::map);
    }

    @Transactional
    public Odometer.Check manual(long vehicleId, LocalDate date, BigDecimal km) {
        if (date.isAfter(LocalDate.now())) {
            throw ApiException.badRequest("FUTURE_DATE", "Reading date cannot be in the future");
        }
        Odometer.Check c = addReading(vehicleId, date, km, "MANUAL", null);
        audit.record("CREATE", "ODOMETER_READING", vehicleId, null, Map.of("date", date.toString(), "km", km, "trusted", c.ok()));
        return c;
    }

    /** Lets a manager mark an untrusted reading as trusted after investigation (or vice versa). */
    @Transactional
    public void setTrusted(long readingId, boolean trusted, String reason) {
        int n = jdbc.update("UPDATE odometer_readings SET is_trusted = :t, untrusted_reason = :r WHERE id = :id",
                new MapSqlParameterSource("t", trusted).addValue("r", trusted ? null : reason).addValue("id", readingId));
        if (n == 0) {
            throw ApiException.notFound("Odometer reading");
        }
        audit.record(trusted ? "TRUST" : "DISTRUST", "ODOMETER_READING", readingId, null, Map.of("reason", reason == null ? "" : reason));
    }
}
