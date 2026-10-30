package com.lrtaxi.fleet.users;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;

/**
 * Used by share-online.cmd before it opens a Cloudflare Tunnel: is any active admin still using a published default
 * password? Answers only requests made directly on this PC (loopback, not forwarded by a proxy or tunnel).
 */
@RestController
public class ShareCheckController {

    /** Passwords printed in the README / launcher; an admin with one of these must not be reachable from the internet. */
    static final List<String> PUBLISHED = List.of("Admin@12345", "AdminPass123", "ChangeMe12345");

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public ShareCheckController(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    @GetMapping("/api/system/share-check")
    public ResponseEntity<Map<String, Object>> check(HttpServletRequest req) throws Exception {
        boolean direct = InetAddress.getByName(req.getRemoteAddr()).isLoopbackAddress()
                && req.getHeader("X-Forwarded-For") == null && req.getHeader("Cf-Connecting-Ip") == null && req.getHeader("Forwarded") == null;
        if (!direct) {
            return ResponseEntity.notFound().build();
        }
        List<String> hashes = jdbc.queryForList("""
                SELECT u.password_hash FROM users u JOIN user_roles r ON r.user_id = u.id
                WHERE u.is_active AND r.role_code = 'ADMIN'""", String.class);
        boolean weak = hashes.stream().anyMatch(h -> PUBLISHED.stream().anyMatch(p -> encoder.matches(p, h)));
        return ResponseEntity.ok(Map.of("defaultAdminPassword", weak, "admins", hashes.size()));
    }
}
