package com.lrtaxi.fleet.vehicle;

import com.lrtaxi.fleet.audit.AuditService;
import com.lrtaxi.fleet.common.ApiException;
import com.lrtaxi.fleet.common.Rows;
import com.lrtaxi.fleet.security.CurrentUser;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deleting vehicles (one or many). Delete = status DELETED: the vehicle disappears from every list, dashboard, report,
 * import match and alert, but its fuel, GPS, service and document history is kept and it can be restored. Permanent
 * removal (admin only) is allowed only for a deleted vehicle without any records, e.g. one added by mistake.
 */
@Service
public class VehicleDeleteService {

    public static final int MAX_BATCH = 500;
    /** Rows that belong only to the vehicle itself and are removed with it on permanent deletion. */
    private static final Set<String> OWN_TABLES = Set.of("vehicle_assignments", "vehicle_aliases", "alerts");
    /** References cleared (set to null) on permanent deletion. */
    private static final Set<String> CLEARED = Set.of("import_staging_rows");

    private final NamedParameterJdbcTemplate jdbc;
    private final VehicleService vehicles;
    private final AuditService audit;

    public VehicleDeleteService(NamedParameterJdbcTemplate jdbc, VehicleService vehicles, AuditService audit) {
        this.jdbc = jdbc;
        this.vehicles = vehicles;
        this.audit = audit;
    }

    private static void check(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw ApiException.badRequest("NOTHING_SELECTED", "Select at least one vehicle");
        }
        if (ids.size() > MAX_BATCH) {
            throw ApiException.badRequest("TOO_MANY", "Delete at most " + MAX_BATCH + " vehicles at a time");
        }
    }

    @Transactional
    public Map<String, Object> delete(List<Long> ids, String reason) {
        check(ids);
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("REASON_REQUIRED", "Enter why the vehicle(s) are deleted");
        }
        String why = reason.trim().length() > 500 ? reason.trim().substring(0, 500) : reason.trim();
        int done = 0;
        List<String> skipped = new ArrayList<>();
        for (Long id : ids.stream().distinct().toList()) {
            Map<String, Object> before = vehicles.get(id);
            if ("DELETED".equals(before.get("status"))) {
                skipped.add(before.get("registrationNumber") + " (already deleted)");
                continue;
            }
            jdbc.update("""
                    UPDATE vehicles SET status_before_delete = status, status = 'DELETED', deleted_at = now(), deleted_by = :uid,
                        delete_reason = :r, gps_km_tracked = FALSE, updated_by = :uid, updated_at = now() WHERE id = :id""",
                    new MapSqlParameterSource("uid", CurrentUser.idOrNull()).addValue("r", why).addValue("id", id));
            vehicles.assign(id, "STATUS", null, null, null, "DELETED", LocalDate.now(), "Deleted: " + (why.length() > 280 ? why.substring(0, 280) : why));
            jdbc.update("""
                    UPDATE alerts SET status = 'DISMISSED', resolved_at = now(), resolved_by = :uid, resolution_note = 'Vehicle deleted: ' || :r
                    WHERE vehicle_id = :id AND status IN ('OPEN', 'ACKNOWLEDGED', 'IN_PROGRESS')""",
                    new MapSqlParameterSource("uid", CurrentUser.idOrNull()).addValue("r", why).addValue("id", id));
            audit.record("DELETE", "VEHICLE", id, before, Map.of("reason", why));
            done++;
        }
        return Map.of("deleted", done, "skipped", skipped);
    }

    @Transactional
    public Map<String, Object> restore(List<Long> ids) {
        check(ids);
        int done = 0;
        List<String> skipped = new ArrayList<>();
        for (Long id : ids.stream().distinct().toList()) {
            Map<String, Object> before = vehicles.get(id);
            if (!"DELETED".equals(before.get("status"))) {
                skipped.add(before.get("registrationNumber") + " (not deleted)");
                continue;
            }
            Integer clash = jdbc.queryForObject("""
                    SELECT count(*) FROM vehicles WHERE registration_normalized = :r AND id <> :id AND status <> 'DELETED'
                      AND duplicate_registration_reason IS NULL""",
                    new MapSqlParameterSource("r", before.get("registrationNormalized")).addValue("id", id), Integer.class);
            if (clash != null && clash > 0) {
                skipped.add(before.get("registrationNumber") + " (another vehicle with this number exists)");
                continue;
            }
            String status = before.get("statusBeforeDelete") == null ? "IDLE" : (String) before.get("statusBeforeDelete");
            jdbc.update("""
                    UPDATE vehicles SET status = :s, status_before_delete = NULL, deleted_at = NULL, deleted_by = NULL, delete_reason = NULL,
                        gps_km_tracked = (operator_type = 'OWN_DRIVER' AND current_driver_id IS NOT NULL AND :s NOT IN ('SOLD', 'SCRAPPED', 'INACTIVE')),
                        updated_by = :uid, updated_at = now() WHERE id = :id""",
                    new MapSqlParameterSource("s", status).addValue("uid", CurrentUser.idOrNull()).addValue("id", id));
            vehicles.assign(id, "STATUS", null, null, null, status, LocalDate.now(), "Restored after deletion");
            audit.record("RESTORE", "VEHICLE", id, before, Map.of("status", status));
            done++;
        }
        return Map.of("restored", done, "skipped", skipped);
    }

    /** Admin only. Removes deleted vehicles that have no records; vehicles with history are listed with what they still have. */
    @Transactional
    public Map<String, Object> purge(List<Long> ids) {
        check(ids);
        List<Map<String, Object>> refs = jdbc.query("""
                SELECT c.conrelid::regclass::text AS tbl, a.attname AS col
                FROM pg_constraint c
                JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
                WHERE c.contype = 'f' AND c.confrelid = 'vehicles'::regclass""", Map.of(), Rows::map);
        int removed = 0;
        List<String> kept = new ArrayList<>();
        for (Long id : ids.stream().distinct().toList()) {
            Map<String, Object> v = vehicles.get(id);
            if (!"DELETED".equals(v.get("status"))) {
                kept.add(v.get("registrationNumber") + ": delete it first");
                continue;
            }
            Map<String, Integer> has = new LinkedHashMap<>();
            for (Map<String, Object> r : refs) {
                String table = bare((String) r.get("tbl"));
                if (OWN_TABLES.contains(table) || CLEARED.contains(table) || "vehicles".equals(table)) {
                    continue;
                }
                Integer n = jdbc.queryForObject("SELECT count(*) FROM " + r.get("tbl") + " WHERE " + r.get("col") + " = :id", Map.of("id", id), Integer.class);
                if (n != null && n > 0) {
                    has.merge(table.replace('_', ' '), n, Integer::sum);
                }
            }
            if (!has.isEmpty()) {
                kept.add(v.get("registrationNumber") + ": has " + has.entrySet().stream().map(e -> e.getValue() + " " + e.getKey()).toList()
                        + " - kept as deleted so its history stays");
                continue;
            }
            for (Map<String, Object> r : refs) {
                String table = bare((String) r.get("tbl"));
                if (OWN_TABLES.contains(table)) {
                    jdbc.update("DELETE FROM " + r.get("tbl") + " WHERE " + r.get("col") + " = :id", Map.of("id", id));
                } else if (CLEARED.contains(table)) {
                    jdbc.update("UPDATE " + r.get("tbl") + " SET " + r.get("col") + " = NULL WHERE " + r.get("col") + " = :id", Map.of("id", id));
                }
            }
            jdbc.update("DELETE FROM vehicles WHERE id = :id", Map.of("id", id));
            audit.record("PURGE", "VEHICLE", id, v, Map.of("permanent", true));
            removed++;
        }
        return Map.of("removed", removed, "kept", kept);
    }

    private static String bare(String regclass) {
        int dot = regclass.lastIndexOf('.');
        return (dot < 0 ? regclass : regclass.substring(dot + 1)).replace("\"", "");
    }
}
