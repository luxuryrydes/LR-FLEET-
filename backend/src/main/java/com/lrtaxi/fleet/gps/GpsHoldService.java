package com.lrtaxi.fleet.gps;

import com.lrtaxi.fleet.common.Rows;
import com.lrtaxi.fleet.fuel.EfficiencyService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GPS km that are not counted while a vehicle is not driven by an LR driver (vendor, idle, govt driver...) are kept in
 * gps_held_km. When the vehicle starts being tracked, the kept days from the change date are added to its GPS km; when it
 * stops, its counted days from the change date are moved to the kept list. So switching driver &lt;-&gt; vendor needs no re-import.
 */
@Service
public class GpsHoldService {

    private final NamedParameterJdbcTemplate jdbc;
    private final GpsService gps;
    private final EfficiencyService efficiency;

    public GpsHoldService(NamedParameterJdbcTemplate jdbc, GpsService gps, EfficiencyService efficiency) {
        this.jdbc = jdbc;
        this.gps = gps;
        this.efficiency = efficiency;
    }

    /** Keeps one daily figure for a vehicle that is not tracked (latest import wins). */
    public void hold(long vehicleId, LocalDate date, BigDecimal km, String mode, Integer trips, String device, Long batchId, String reason) {
        jdbc.update("""
                INSERT INTO gps_held_km (vehicle_id, km_date, distance_km, record_mode, trip_count, source_device_name, import_batch_id, held_reason)
                VALUES (:v, :d, :km, :mode, :trips, :dev, :b, :r)
                ON CONFLICT (vehicle_id, km_date) DO UPDATE SET distance_km = EXCLUDED.distance_km, record_mode = EXCLUDED.record_mode,
                    trip_count = EXCLUDED.trip_count, source_device_name = EXCLUDED.source_device_name, import_batch_id = EXCLUDED.import_batch_id,
                    held_reason = EXCLUDED.held_reason, created_at = now()""",
                new MapSqlParameterSource("v", vehicleId).addValue("d", date).addValue("km", km).addValue("mode", mode).addValue("trips", trips)
                        .addValue("dev", device).addValue("b", batchId).addValue("r", reason));
    }

    /**
     * Called after the vehicle's GPS tracking changed.
     * @return {direction: ADDED|HELD, days, km, from}
     */
    @Transactional
    public Map<String, Object> trackingChanged(long vehicleId, boolean nowTracked, LocalDate from) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from);
        int days = 0;
        BigDecimal km = BigDecimal.ZERO;
        if (nowTracked) {
            List<Map<String, Object>> held = jdbc.query("""
                    SELECT km_date, distance_km, record_mode, trip_count, source_device_name, import_batch_id
                    FROM gps_held_km WHERE vehicle_id = :v AND km_date >= :f ORDER BY km_date""",
                    Map.of("v", vehicleId, "f", from), Rows::map);
            for (Map<String, Object> h : held) {
                String mode = "TRIP_SUM".equals(h.get("recordMode")) ? "TRIP_SUM" : "DAILY_TOTAL";
                GpsService.Outcome o = gps.apply(new GpsService.Day(vehicleId, (LocalDate) h.get("kmDate"), (BigDecimal) h.get("distanceKm"), mode,
                        h.get("tripCount") == null ? null : ((Number) h.get("tripCount")).intValue(), "IMPORT", (String) h.get("sourceDeviceName"),
                        h.get("importBatchId") == null ? null : ((Number) h.get("importBatchId")).longValue(), null,
                        "Added when the vehicle moved to an LR driver"), false, null);
                if (o == GpsService.Outcome.INSERTED) {
                    days++;
                    km = km.add((BigDecimal) h.get("distanceKm"));
                }
            }
            jdbc.update("DELETE FROM gps_held_km WHERE vehicle_id = :v AND km_date >= :f", Map.of("v", vehicleId, "f", from));
            out.put("direction", "ADDED");
        } else {
            List<Map<String, Object>> counted = jdbc.query("""
                    SELECT id, km_date, distance_km, record_mode, trip_count, source_device_name, import_batch_id FROM gps_daily_km
                    WHERE vehicle_id = :v AND is_current AND km_date >= :f ORDER BY km_date""", Map.of("v", vehicleId, "f", from), Rows::map);
            for (Map<String, Object> c : counted) {
                hold(vehicleId, (LocalDate) c.get("kmDate"), (BigDecimal) c.get("distanceKm"), (String) c.get("recordMode"),
                        c.get("tripCount") == null ? null : ((Number) c.get("tripCount")).intValue(), (String) c.get("sourceDeviceName"),
                        c.get("importBatchId") == null ? null : ((Number) c.get("importBatchId")).longValue(), "Vehicle no longer with an LR driver");
                km = km.add((BigDecimal) c.get("distanceKm"));
                days++;
            }
            jdbc.update("""
                    UPDATE gps_daily_km SET is_current = FALSE, superseded_at = now(),
                        supersede_reason = 'Vehicle no longer with an LR driver from ' || to_char(CAST(:f AS date), 'DD-MM-YYYY')
                    WHERE vehicle_id = :v AND is_current AND km_date >= :f""", Map.of("v", vehicleId, "f", from));
            out.put("direction", "HELD");
        }
        if (days > 0) {
            efficiency.recomputeAll(Set.of(vehicleId));
        }
        out.put("days", days);
        out.put("km", km);
        return out;
    }

    /** Kept (not counted) GPS days of a vehicle: {days, km, firstDate, lastDate}. */
    public Map<String, Object> summary(long vehicleId) {
        return jdbc.query("""
                SELECT count(*) AS days, coalesce(sum(distance_km), 0) AS km, min(km_date) AS first_date, max(km_date) AS last_date
                FROM gps_held_km WHERE vehicle_id = :v""", Map.of("v", vehicleId), Rows::map).get(0);
    }
}
