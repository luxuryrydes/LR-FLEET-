package com.lrtaxi.fleet.fuel;

import com.lrtaxi.fleet.calc.Calc;
import com.lrtaxi.fleet.calc.EnergyType;
import com.lrtaxi.fleet.calc.MileageEngine;
import com.lrtaxi.fleet.calc.VehicleFuelType;
import com.lrtaxi.fleet.common.ApiException;
import com.lrtaxi.fleet.common.FleetFilter;
import com.lrtaxi.fleet.common.Rows;
import com.lrtaxi.fleet.settings.SettingsService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists the Java engine's efficiency intervals (fuel_efficiency_intervals) and serves weighted summaries.
 * Dashboards and reports read these rows; they never recalculate intervals themselves.
 */
@Service
public class EfficiencyService {

    private final NamedParameterJdbcTemplate jdbc;
    private final SettingsService settings;

    public EfficiencyService(NamedParameterJdbcTemplate jdbc, SettingsService settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    @Transactional
    public void recomputeAll(Collection<Long> vehicleIds) {
        for (Long id : vehicleIds) {
            recompute(id);
        }
    }

    @Transactional
    public List<MileageEngine.Interval> recompute(long vehicleId) {
        List<String> ft = jdbc.queryForList("SELECT fuel_type FROM vehicles WHERE id = :v", Map.of("v", vehicleId), String.class);
        if (ft.isEmpty()) {
            throw ApiException.notFound("Vehicle");
        }
        VehicleFuelType vft = VehicleFuelType.valueOf(ft.get(0));
        // An odometer flagged untrusted by the progression check is treated as missing.
        List<MileageEngine.FuelFill> fills = jdbc.query("""
                SELECT f.id, f.txn_date, f.energy_type, f.quantity, f.is_full_tank,
                       CASE WHEN o.id IS NULL OR o.is_trusted THEN f.odometer_km END AS odometer
                FROM fuel_transactions f
                LEFT JOIN odometer_readings o ON o.source = 'FUEL' AND o.source_ref_id = f.id AND NOT o.is_void
                WHERE f.vehicle_id = :v AND NOT f.is_void""", Map.of("v", vehicleId),
                (rs, i) -> new MileageEngine.FuelFill(rs.getLong("id"), rs.getDate("txn_date").toLocalDate(),
                        EnergyType.valueOf(rs.getString("energy_type")), rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("odometer"), Rows.boolOrNull(rs, "is_full_tank")));
        Map<LocalDate, BigDecimal> gps = new HashMap<>();
        if (!fills.isEmpty()) {
            jdbc.query("SELECT km_date, distance_km FROM gps_daily_km WHERE vehicle_id = :v AND is_current",
                    Map.of("v", vehicleId), rs -> {
                        gps.put(rs.getDate(1).toLocalDate(), rs.getBigDecimal(2));
                    });
        }
        List<MileageEngine.Interval> intervals = MileageEngine.computeIntervals(vft, fills, gps, settings.mileagePolicy());

        jdbc.update("DELETE FROM fuel_efficiency_intervals WHERE vehicle_id = :v", Map.of("v", vehicleId));
        if (!intervals.isEmpty()) {
            List<SqlParameterSource> batch = new ArrayList<>(intervals.size());
            for (MileageEngine.Interval i : intervals) {
                batch.add(new MapSqlParameterSource()
                        .addValue("v", vehicleId).addValue("e", i.energy().name()).addValue("m", i.method().name())
                        .addValue("src", i.distanceSource().name()).addValue("s", i.startFillId()).addValue("end", i.endFillId())
                        .addValue("sd", i.startDate()).addValue("ed", i.endDate())
                        .addValue("so", i.startOdometer()).addValue("eo", i.endOdometer())
                        .addValue("d", i.distanceKm()).addValue("q", i.quantity()).addValue("mil", i.mileage())
                        .addValue("st", i.eligible() ? "ELIGIBLE" : "EXCLUDED")
                        .addValue("why", i.exclusion() == null ? null : i.exclusion().name()));
            }
            jdbc.batchUpdate("""
                    INSERT INTO fuel_efficiency_intervals (vehicle_id, energy_type, method, distance_source, start_txn_id, end_txn_id,
                        start_date, end_date, start_odometer, end_odometer, distance_km, quantity, mileage, status, exclusion_reason)
                    VALUES (:v, :e, :m, :src, :s, :end, :sd, :ed, :so, :eo, :d, :q, :mil, :st, :why)""",
                    batch.toArray(new SqlParameterSource[0]));
        }
        return intervals;
    }

    public List<Map<String, Object>> intervals(long vehicleId) {
        return jdbc.query("""
                SELECT i.id, i.energy_type, i.method, i.distance_source, i.start_txn_id, i.end_txn_id, i.start_date, i.end_date,
                       i.start_odometer, i.end_odometer, i.distance_km, i.quantity, i.mileage, i.status, i.exclusion_reason, i.computed_at
                FROM fuel_efficiency_intervals i WHERE i.vehicle_id = :v ORDER BY i.end_date DESC, i.energy_type""",
                Map.of("v", vehicleId), Rows::map);
    }

    /** Per energy for one vehicle: weighted mileage over intervals ending in the period, expected mileage and variance. */
    public List<Map<String, Object>> vehicleSummary(long vehicleId, LocalDate from, LocalDate to) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT i.energy_type,
                       sum(CASE WHEN i.status = 'ELIGIBLE' THEN i.distance_km ELSE 0 END) AS km,
                       sum(CASE WHEN i.status = 'ELIGIBLE' THEN i.quantity ELSE 0 END) AS qty,
                       count(*) FILTER (WHERE i.status = 'ELIGIBLE') AS eligible,
                       count(*) FILTER (WHERE i.status = 'EXCLUDED') AS excluded,
                       count(*) FILTER (WHERE i.exclusion_reason = 'MIXED_FUEL') AS mixed
                FROM fuel_efficiency_intervals i
                WHERE i.vehicle_id = :v AND i.end_date BETWEEN :from AND :to
                GROUP BY i.energy_type""", new MapSqlParameterSource("v", vehicleId).addValue("from", from).addValue("to", to));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            EnergyType e = EnergyType.valueOf((String) r.get("energy_type"));
            BigDecimal km = (BigDecimal) r.get("km");
            BigDecimal qty = (BigDecimal) r.get("qty");
            BigDecimal mileage = Calc.mileage(km, qty).orElse(null);
            long eligible = ((Number) r.get("eligible")).longValue();
            long mixed = ((Number) r.get("mixed")).longValue();
            Expected exp = expected(vehicleId, e);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("energyType", e.name());
            m.put("unit", e.efficiencyUnit());
            m.put("eligibleKm", km);
            m.put("eligibleQuantity", qty);
            m.put("mileage", mileage);
            m.put("eligibleIntervals", eligible);
            m.put("excludedIntervals", ((Number) r.get("excluded")).longValue());
            m.put("status", mileage != null ? "OK" : (mixed > 0 ? "MIXED_FUEL" : "INSUFFICIENT_DATA"));
            m.put("expectedMileage", exp == null ? null : exp.mileage());
            m.put("expectedSource", exp == null ? null : exp.source());
            m.put("variancePct", exp == null ? null : Calc.variancePct(mileage, exp.mileage()).orElse(null));
            m.put("lowThresholdPct", exp == null ? null : exp.lowThresholdPct());
            out.add(m);
        }
        return out;
    }

    public record Expected(BigDecimal mileage, String source, BigDecimal lowThresholdPct) {
    }

    /** Vehicle expected mileage (primary energy) -> model benchmark -> fuel-type benchmark. */
    public Expected expected(long vehicleId, EnergyType energy) {
        BigDecimal defaultPct = settings.decimal("mileage.low_threshold_pct", "15");
        List<Map<String, Object>> v = jdbc.queryForList("SELECT fuel_type, make, model, expected_mileage FROM vehicles WHERE id = :v",
                Map.of("v", vehicleId));
        if (v.isEmpty()) {
            return null;
        }
        Map<String, Object> vr = v.get(0);
        VehicleFuelType vft = VehicleFuelType.valueOf((String) vr.get("fuel_type"));
        if (vft.primaryEnergy() == energy && vr.get("expected_mileage") != null) {
            return new Expected((BigDecimal) vr.get("expected_mileage"), "VEHICLE", defaultPct);
        }
        List<Map<String, Object>> b = jdbc.queryForList("""
                SELECT scope, expected_mileage, low_threshold_pct FROM mileage_benchmarks
                WHERE energy_type = :e AND ((scope = 'MODEL' AND lower(make) = lower(:make) AND lower(model) = lower(:model)) OR scope = 'FUEL_TYPE')
                ORDER BY CASE scope WHEN 'MODEL' THEN 0 ELSE 1 END LIMIT 1""",
                new MapSqlParameterSource("e", energy.name()).addValue("make", vr.get("make")).addValue("model", vr.get("model")));
        if (b.isEmpty()) {
            return null;
        }
        BigDecimal pct = b.get(0).get("low_threshold_pct") == null ? defaultPct : (BigDecimal) b.get(0).get("low_threshold_pct");
        return new Expected((BigDecimal) b.get(0).get("expected_mileage"), (String) b.get(0).get("scope"), pct);
    }

    /** Fleet weighted mileage per energy: Σ eligible km ÷ Σ eligible quantity over intervals ending in the period. */
    public List<Map<String, Object>> fleetMileage(FleetFilter f) {
        MapSqlParameterSource p = f.dateParams();
        StringBuilder sql = new StringBuilder("""
                SELECT i.energy_type, sum(i.distance_km) AS km, sum(i.quantity) AS qty, count(DISTINCT i.vehicle_id) AS vehicles,
                       count(*) AS intervals
                FROM fuel_efficiency_intervals i JOIN vehicles v ON v.id = i.vehicle_id
                WHERE i.status = 'ELIGIBLE' AND i.end_date BETWEEN :from AND :to""");
        f.appendVehicleConditions(sql, p, "v");
        sql.append(" GROUP BY i.energy_type ORDER BY i.energy_type");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(sql.toString(), p)) {
            EnergyType e = EnergyType.valueOf((String) r.get("energy_type"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("energyType", e.name());
            m.put("unit", e.efficiencyUnit());
            m.put("eligibleKm", r.get("km"));
            m.put("eligibleQuantity", r.get("qty"));
            m.put("mileage", Calc.mileage((BigDecimal) r.get("km"), (BigDecimal) r.get("qty")).orElse(null));
            m.put("vehicles", r.get("vehicles"));
            m.put("intervals", r.get("intervals"));
            out.add(m);
        }
        return out;
    }

    /** Per vehicle and energy weighted mileage for the period, with expected mileage and variance (reports, alerts, KPIs). */
    public List<Map<String, Object>> vehicleMileageTable(FleetFilter f) {
        MapSqlParameterSource p = f.dateParams();
        StringBuilder sql = new StringBuilder("""
                SELECT v.id AS vehicle_id, v.registration_number, v.make, v.model, v.fuel_type, i.energy_type,
                       sum(CASE WHEN i.status = 'ELIGIBLE' THEN i.distance_km ELSE 0 END) AS km,
                       sum(CASE WHEN i.status = 'ELIGIBLE' THEN i.quantity ELSE 0 END) AS qty,
                       count(*) FILTER (WHERE i.status = 'ELIGIBLE') AS eligible,
                       count(*) FILTER (WHERE i.status = 'EXCLUDED') AS excluded,
                       string_agg(DISTINCT i.exclusion_reason, ', ') AS exclusion_reasons
                FROM fuel_efficiency_intervals i JOIN vehicles v ON v.id = i.vehicle_id
                WHERE i.end_date BETWEEN :from AND :to""");
        f.appendVehicleConditions(sql, p, "v");
        sql.append(" GROUP BY v.id, i.energy_type ORDER BY v.registration_normalized, i.energy_type");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(sql.toString(), p)) {
            long vid = ((Number) r.get("vehicle_id")).longValue();
            EnergyType e = EnergyType.valueOf((String) r.get("energy_type"));
            BigDecimal mileage = Calc.mileage((BigDecimal) r.get("km"), (BigDecimal) r.get("qty")).orElse(null);
            Expected exp = expected(vid, e);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("vehicleId", vid);
            m.put("registrationNumber", r.get("registration_number"));
            m.put("make", r.get("make"));
            m.put("model", r.get("model"));
            m.put("fuelType", r.get("fuel_type"));
            m.put("energyType", e.name());
            m.put("unit", e.efficiencyUnit());
            m.put("eligibleKm", r.get("km"));
            m.put("eligibleQuantity", r.get("qty"));
            m.put("mileage", mileage);
            m.put("expectedMileage", exp == null ? null : exp.mileage());
            BigDecimal variance = exp == null ? null : Calc.variancePct(mileage, exp.mileage()).orElse(null);
            m.put("variancePct", variance);
            m.put("lowThresholdPct", exp == null ? null : exp.lowThresholdPct());
            m.put("belowThreshold", variance != null && exp.lowThresholdPct() != null && variance.compareTo(exp.lowThresholdPct().negate()) <= 0);
            m.put("eligibleIntervals", r.get("eligible"));
            m.put("excludedIntervals", r.get("excluded"));
            m.put("exclusionReasons", r.get("exclusion_reasons"));
            m.put("dataQuality", mileage == null ? "INSUFFICIENT_DATA" : (((Number) r.get("excluded")).longValue() > 0 ? "PARTIAL" : "GOOD"));
            out.add(m);
        }
        return out;
    }
}
