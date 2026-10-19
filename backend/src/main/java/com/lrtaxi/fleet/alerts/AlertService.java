package com.lrtaxi.fleet.alerts;

import com.lrtaxi.fleet.common.ApiException;
import com.lrtaxi.fleet.common.Json;
import com.lrtaxi.fleet.common.PageResult;
import com.lrtaxi.fleet.common.Paging;
import com.lrtaxi.fleet.common.Rows;
import com.lrtaxi.fleet.security.CurrentUser;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Alert storage and workflow: open -> acknowledged -> in progress -> resolved / dismissed, with a full event history. */
@Service
public class AlertService {

    public record AlertSpec(String type, String severity, Long vehicleId, String dedupeKey, String title, String message,
                            Map<String, Object> rule, List<Map<String, Object>> contributing, LocalDate periodStart, LocalDate periodEnd) {
    }

    private static final Set<String> STATUSES = Set.of("OPEN", "ACKNOWLEDGED", "IN_PROGRESS", "RESOLVED", "DISMISSED");

    private final NamedParameterJdbcTemplate jdbc;

    public AlertService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates an alert, or refreshes the open alert with the same dedupe key (never duplicates). Returns the alert id. */
    @Transactional
    public long raise(AlertSpec s) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("type", s.type()).addValue("sev", s.severity()).addValue("v", s.vehicleId()).addValue("key", s.dedupeKey())
                .addValue("title", s.title()).addValue("msg", s.message()).addValue("rule", Json.write(s.rule()))
                .addValue("contrib", Json.write(s.contributing() == null ? List.of() : s.contributing()))
                .addValue("ps", s.periodStart()).addValue("pe", s.periodEnd());
        List<Long> open = jdbc.queryForList("""
                SELECT id FROM alerts WHERE dedupe_key = :key AND status IN ('OPEN','ACKNOWLEDGED','IN_PROGRESS') FOR UPDATE""", p, Long.class);
        if (!open.isEmpty()) {
            long id = open.get(0);
            jdbc.update("""
                    UPDATE alerts SET severity = :sev, title = :title, message = :msg, rule_explanation = CAST(:rule AS jsonb),
                        contributing_records = CAST(:contrib AS jsonb), period_start = :ps, period_end = :pe, last_detected_at = now()
                    WHERE id = :id""", p.addValue("id", id));
            return id;
        }
        long id = jdbc.queryForObject("""
                INSERT INTO alerts (alert_type, severity, vehicle_id, dedupe_key, title, message, rule_explanation, contributing_records,
                    period_start, period_end)
                VALUES (:type, :sev, :v, :key, :title, :msg, CAST(:rule AS jsonb), CAST(:contrib AS jsonb), :ps, :pe) RETURNING id""",
                p, Long.class);
        event(id, "CREATED", null);
        return id;
    }

    public PageResult<Map<String, Object>> list(String type, String status, String severity, Long vehicleId, Long assignedTo,
                                                Integer page, Integer size) {
        Paging paging = Paging.of(page, size, null, Map.of(),
                "CASE a.severity WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END, a.last_detected_at DESC");
        MapSqlParameterSource p = new MapSqlParameterSource();
        StringBuilder w = new StringBuilder(" WHERE 1=1");
        if (type != null && !type.isBlank()) {
            w.append(" AND a.alert_type = :type");
            p.addValue("type", type);
        }
        if ("ACTIVE".equals(status)) {
            w.append(" AND a.status IN ('OPEN','ACKNOWLEDGED','IN_PROGRESS')");
        } else if (status != null && !status.isBlank()) {
            w.append(" AND a.status = :status");
            p.addValue("status", status);
        }
        if (severity != null && !severity.isBlank()) {
            w.append(" AND a.severity = :sev");
            p.addValue("sev", severity);
        }
        if (vehicleId != null) {
            w.append(" AND a.vehicle_id = :v");
            p.addValue("v", vehicleId);
        }
        if (assignedTo != null) {
            w.append(" AND a.assigned_to = :u");
            p.addValue("u", assignedTo);
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM alerts a" + w, p, Long.class);
        List<Map<String, Object>> items = jdbc.query("""
                SELECT a.id, a.alert_type, a.severity, a.vehicle_id, v.registration_number, a.title, a.message, a.status,
                       a.assigned_to, u.full_name AS assigned_to_name, a.period_start, a.period_end, a.first_detected_at, a.last_detected_at
                FROM alerts a LEFT JOIN vehicles v ON v.id = a.vehicle_id LEFT JOIN users u ON u.id = a.assigned_to""" + w + paging.sql(),
                p, Rows::map);
        return new PageResult<>(items, total == null ? 0 : total, paging.page(), paging.size());
    }

    public List<Map<String, Object>> summary() {
        return jdbc.query("""
                SELECT alert_type, severity, count(*) AS open_count FROM alerts
                WHERE status IN ('OPEN','ACKNOWLEDGED','IN_PROGRESS') GROUP BY alert_type, severity ORDER BY alert_type""", Rows::map);
    }

    public Map<String, Object> get(long id) {
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT a.*, v.registration_number, u.full_name AS assigned_to_name, r.full_name AS resolved_by_name
                FROM alerts a LEFT JOIN vehicles v ON v.id = a.vehicle_id LEFT JOIN users u ON u.id = a.assigned_to
                LEFT JOIN users r ON r.id = a.resolved_by WHERE a.id = :id""", Map.of("id", id), Rows::map);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Alert");
        }
        Map<String, Object> a = new LinkedHashMap<>(rows.get(0));
        a.put("events", jdbc.query("""
                SELECT e.id, e.event_type, e.note, e.created_at, u.full_name AS user_name FROM alert_events e
                LEFT JOIN users u ON u.id = e.user_id WHERE e.alert_id = :id ORDER BY e.created_at""", Map.of("id", id), Rows::map));
        return a;
    }

    @Transactional
    public void acknowledge(long id, String note) {
        changeStatus(id, "ACKNOWLEDGED", "ACKNOWLEDGED", note);
    }

    @Transactional
    public void assign(long id, Long userId, String note) {
        get(id);
        jdbc.update("UPDATE alerts SET assigned_to = :u, status = CASE WHEN status = 'OPEN' THEN 'IN_PROGRESS' ELSE status END WHERE id = :id",
                new MapSqlParameterSource("u", userId).addValue("id", id));
        event(id, "ASSIGNED", note);
    }

    @Transactional
    public void note(long id, String note) {
        get(id);
        event(id, "NOTE", note);
    }

    @Transactional
    public void setStatus(long id, String status, String note) {
        if (!STATUSES.contains(status)) {
            throw ApiException.badRequest("INVALID_STATUS", "Unknown status " + status);
        }
        if ((status.equals("RESOLVED") || status.equals("DISMISSED")) && (note == null || note.isBlank())) {
            throw ApiException.badRequest("NOTE_REQUIRED", "Please record how the alert was resolved");
        }
        String event = switch (status) {
            case "RESOLVED", "DISMISSED" -> "RESOLVED";
            case "OPEN" -> "REOPENED";
            case "ACKNOWLEDGED" -> "ACKNOWLEDGED";
            default -> "STATUS_CHANGED";
        };
        changeStatus(id, status, event, note);
    }

    private void changeStatus(long id, String status, String event, String note) {
        Map<String, Object> a = get(id);
        if (status.equals("OPEN") || status.equals("ACKNOWLEDGED") || status.equals("IN_PROGRESS")) {
            // re-opening must not collide with a newer open alert of the same key
            Long clash = jdbc.queryForObject("""
                    SELECT count(*) FROM alerts WHERE dedupe_key = :k AND id <> :id AND status IN ('OPEN','ACKNOWLEDGED','IN_PROGRESS')""",
                    new MapSqlParameterSource("k", a.get("dedupeKey")).addValue("id", id), Long.class);
            if (clash != null && clash > 0) {
                throw ApiException.conflict("ALERT_SUPERSEDED", "A newer open alert exists for the same issue");
            }
        }
        boolean closing = status.equals("RESOLVED") || status.equals("DISMISSED");
        jdbc.update("""
                UPDATE alerts SET status = :s, resolved_at = CASE WHEN :closing THEN now() ELSE NULL END,
                    resolved_by = CASE WHEN :closing THEN CAST(:u AS bigint) ELSE NULL END,
                    resolution_note = CASE WHEN :closing THEN :note ELSE resolution_note END
                WHERE id = :id""",
                new MapSqlParameterSource("s", status).addValue("closing", closing).addValue("u", CurrentUser.idOrNull())
                        .addValue("note", note).addValue("id", id));
        event(id, event, note);
    }

    private void event(long alertId, String type, String note) {
        jdbc.update("INSERT INTO alert_events (alert_id, event_type, note, user_id) VALUES (:a, :t, :n, :u)",
                new MapSqlParameterSource("a", alertId).addValue("t", type).addValue("n", note).addValue("u", CurrentUser.idOrNull()));
    }
}
