package com.lrtaxi.fleet.audit;

import com.lrtaxi.fleet.common.Json;
import com.lrtaxi.fleet.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Writes audit_logs in the caller's transaction, so an audit row exists exactly when the change is committed. */
@Service
public class AuditService {

    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String action, String entityType, Object entityId, Object before, Object after) {
        CurrentUser u = CurrentUser.get();
        jdbc.update("""
                        INSERT INTO audit_logs (user_id, user_email, action, entity_type, entity_id, before_data, after_data, ip_address)
                        VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?)""",
                u == null ? null : u.id(), u == null ? "system" : u.email(), action, entityType,
                entityId == null ? null : String.valueOf(entityId), Json.write(before), Json.write(after), clientIp());
    }

    private static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest req = attrs.getRequest();
            return req.getRemoteAddr();
        }
        return null;
    }
}
