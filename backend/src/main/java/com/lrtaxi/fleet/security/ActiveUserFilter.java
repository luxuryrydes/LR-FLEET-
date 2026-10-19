package com.lrtaxi.fleet.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Rejects tokens of users deactivated after the token was issued (status cached for 30 seconds). */
public class ActiveUserFilter extends OncePerRequestFilter {

    private record Cached(boolean active, long at) {
    }

    private final JdbcTemplate jdbc;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    public ActiveUserFilter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        CurrentUser user = CurrentUser.get();
        if (user != null && !isActive(user.id())) {
            res.setStatus(401);
            res.setContentType("application/json");
            res.getWriter().write("{\"code\":\"ACCOUNT_DISABLED\",\"message\":\"Your account has been deactivated\"}");
            return;
        }
        chain.doFilter(req, res);
    }

    private boolean isActive(long userId) {
        long now = System.currentTimeMillis();
        Cached c = cache.get(userId);
        if (c != null && now - c.at() < 30_000) {
            return c.active();
        }
        List<Boolean> rows = jdbc.queryForList("SELECT is_active FROM users WHERE id = ?", Boolean.class, userId);
        boolean active = !rows.isEmpty() && Boolean.TRUE.equals(rows.get(0));
        cache.put(userId, new Cached(active, now));
        return active;
    }
}
