package com.lrtaxi.fleet.users;

import com.lrtaxi.fleet.audit.AuditService;
import com.lrtaxi.fleet.common.ApiException;
import com.lrtaxi.fleet.common.Rows;
import com.lrtaxi.fleet.config.AppProperties;
import com.lrtaxi.fleet.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    public static final Set<String> ROLES = Set.of("ADMIN", "MANAGER", "OPERATOR", "VIEWER");
    private static final int MAX_FAILED = 5;

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final JwtEncoder jwt;
    private final AppProperties props;
    private final AuditService audit;
    /** Dummy hash so unknown emails take as long as wrong passwords (no user enumeration by timing). */
    private final String dummyHash;

    public UserService(JdbcTemplate jdbc, PasswordEncoder encoder, JwtEncoder jwt, AppProperties props, AuditService audit) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.jwt = jwt;
        this.props = props;
        this.audit = audit;
        this.dummyHash = encoder.encode("not-a-real-password-" + System.nanoTime());
    }

    /** Creates the first ADMIN from ADMIN_EMAIL / ADMIN_PASSWORD when the users table is empty. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void bootstrapAdmin() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM users", Long.class);
        if (count != null && count > 0) {
            return;
        }
        AppProperties.BootstrapAdmin a = props.bootstrapAdmin();
        if (a == null || a.email() == null || a.email().isBlank() || a.password() == null || a.password().length() < 10) {
            log.warn("No users exist. Set ADMIN_EMAIL and ADMIN_PASSWORD (min 10 chars) to create the first administrator.");
            return;
        }
        Long id = jdbc.queryForObject("INSERT INTO users (email, full_name, password_hash) VALUES (?, ?, ?) RETURNING id",
                Long.class, a.email().trim().toLowerCase(), a.name() == null ? "Administrator" : a.name(), encoder.encode(a.password()));
        jdbc.update("INSERT INTO user_roles (user_id, role_code) VALUES (?, 'ADMIN')", id);
        log.info("Bootstrap administrator created: {}", a.email());
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> login(String email, String password) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, email, full_name, password_hash, is_active, failed_logins, locked_until FROM users WHERE lower(email) = lower(?)",
                email.trim());
        ApiException invalid = new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
        if (rows.isEmpty()) {
            encoder.matches(password, dummyHash);
            throw invalid;
        }
        Map<String, Object> u = rows.get(0);
        long id = ((Number) u.get("id")).longValue();
        Timestamp locked = (Timestamp) u.get("locked_until");
        if (locked != null && locked.toInstant().isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_LOCKED", "Too many failed attempts. Try again in 15 minutes.");
        }
        if (!encoder.matches(password, (String) u.get("password_hash"))) {
            int failed = ((Number) u.get("failed_logins")).intValue() + 1;
            jdbc.update("UPDATE users SET failed_logins = ?, locked_until = ? WHERE id = ?", failed % MAX_FAILED,
                    failed >= MAX_FAILED ? Timestamp.from(Instant.now().plus(15, ChronoUnit.MINUTES)) : null, id);
            throw invalid;
        }
        if (!Boolean.TRUE.equals(u.get("is_active"))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED", "Your account has been deactivated");
        }
        jdbc.update("UPDATE users SET failed_logins = 0, locked_until = NULL, last_login_at = now() WHERE id = ?", id);
        List<String> roles = roles(id);
        Instant now = Instant.now();
        Instant exp = now.plus(props.jwtTtlMinutes() > 0 ? props.jwtTtlMinutes() : 480, ChronoUnit.MINUTES);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("lr-fleet")
                .subject(String.valueOf(id))
                .issuedAt(now)
                .expiresAt(exp)
                .claim("email", u.get("email"))
                .claim("name", u.get("full_name"))
                .claim("roles", roles)
                .build();
        String token = jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("expiresAt", exp.toString());
        out.put("user", profile(id));
        return out;
    }

    public List<String> roles(long userId) {
        return jdbc.queryForList("SELECT role_code FROM user_roles WHERE user_id = ? ORDER BY role_code", String.class, userId);
    }

    public Map<String, Object> profile(long id) {
        List<Map<String, Object>> rows = jdbc.query("SELECT id, email, full_name, is_active, last_login_at, created_at FROM users WHERE id = ?",
                Rows::map, id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("User");
        }
        Map<String, Object> m = new HashMap<>(rows.get(0));
        m.put("roles", roles(id));
        return m;
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT u.id, u.email, u.full_name, u.is_active, u.last_login_at, u.created_at,
                       coalesce(string_agg(r.role_code, ',' ORDER BY r.role_code), '') AS roles
                FROM users u LEFT JOIN user_roles r ON r.user_id = u.id
                GROUP BY u.id ORDER BY u.full_name""", Rows::map);
        for (Map<String, Object> r : rows) {
            String s = (String) r.get("roles");
            r.put("roles", s.isEmpty() ? List.of() : List.of(s.split(",")));
        }
        return rows;
    }

    @Transactional
    public long create(String email, String fullName, String password, List<String> roles) {
        validateRoles(roles);
        validatePassword(password);
        Long id = jdbc.queryForObject("INSERT INTO users (email, full_name, password_hash) VALUES (?, ?, ?) RETURNING id",
                Long.class, email.trim().toLowerCase(), fullName.trim(), encoder.encode(password));
        for (String r : roles) {
            jdbc.update("INSERT INTO user_roles (user_id, role_code) VALUES (?, ?)", id, r);
        }
        audit.record("CREATE", "USER", id, null, Map.of("email", email, "roles", roles));
        return id;
    }

    @Transactional
    public void update(long id, String fullName, Boolean active, List<String> roles) {
        Map<String, Object> before = profile(id);
        CurrentUser me = CurrentUser.require();
        if (me.id() == id && (Boolean.FALSE.equals(active) || (roles != null && !roles.contains("ADMIN")))) {
            throw ApiException.badRequest("SELF_LOCKOUT", "You cannot deactivate yourself or remove your own ADMIN role");
        }
        if (fullName != null && !fullName.isBlank()) {
            jdbc.update("UPDATE users SET full_name = ?, updated_at = now() WHERE id = ?", fullName.trim(), id);
        }
        if (active != null) {
            jdbc.update("UPDATE users SET is_active = ?, updated_at = now() WHERE id = ?", active, id);
        }
        if (roles != null) {
            validateRoles(roles);
            jdbc.update("DELETE FROM user_roles WHERE user_id = ?", id);
            for (String r : roles) {
                jdbc.update("INSERT INTO user_roles (user_id, role_code) VALUES (?, ?)", id, r);
            }
        }
        audit.record("UPDATE", "USER", id, before, profile(id));
    }

    @Transactional
    public void resetPassword(long id, String newPassword) {
        validatePassword(newPassword);
        int n = jdbc.update("UPDATE users SET password_hash = ?, failed_logins = 0, locked_until = NULL, updated_at = now() WHERE id = ?",
                encoder.encode(newPassword), id);
        if (n == 0) {
            throw ApiException.notFound("User");
        }
        audit.record("RESET_PASSWORD", "USER", id, null, null);
    }

    @Transactional
    public void changeOwnPassword(long id, String current, String next) {
        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, id);
        if (!encoder.matches(current, hash)) {
            throw ApiException.badRequest("INVALID_CREDENTIALS", "Current password is incorrect");
        }
        validatePassword(next);
        jdbc.update("UPDATE users SET password_hash = ?, updated_at = now() WHERE id = ?", encoder.encode(next), id);
        audit.record("CHANGE_PASSWORD", "USER", id, null, null);
    }

    private static void validateRoles(List<String> roles) {
        if (roles == null || roles.isEmpty() || !ROLES.containsAll(roles)) {
            throw ApiException.badRequest("INVALID_ROLES", "Choose at least one of " + ROLES);
        }
    }

    private static void validatePassword(String p) {
        if (p == null || p.length() < 10 || !p.matches(".*[A-Za-z].*") || !p.matches(".*\\d.*")) {
            throw ApiException.badRequest("WEAK_PASSWORD", "Password must be at least 10 characters with letters and digits");
        }
    }
}
