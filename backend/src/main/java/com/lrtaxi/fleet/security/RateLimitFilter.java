package com.lrtaxi.fleet.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed one-minute window limiter per client IP: a tight limit on login attempts and a generous limit on the API.
 * In-memory per instance; for several instances put a gateway limit in front as described in docs/DEPLOYMENT.md.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private record Window(long minute, AtomicInteger count) {
    }

    private final int loginPerMinute;
    private final int apiPerMinute;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(int loginPerMinute, int apiPerMinute) {
        this.loginPerMinute = loginPerMinute > 0 ? loginPerMinute : 10;
        this.apiPerMinute = apiPerMinute > 0 ? apiPerMinute : 600;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if (!path.startsWith("/api/") || "OPTIONS".equals(req.getMethod())) {
            chain.doFilter(req, res);
            return;
        }
        boolean login = path.equals("/api/auth/login");
        String key = (login ? "L:" : "A:") + req.getRemoteAddr();
        int limit = login ? loginPerMinute : apiPerMinute;
        long minute = System.currentTimeMillis() / 60_000;
        Window w = windows.compute(key, (k, old) -> old == null || old.minute() != minute ? new Window(minute, new AtomicInteger()) : old);
        if (w.count().incrementAndGet() > limit) {
            res.setStatus(429);
            res.setContentType("application/json");
            res.setHeader("Retry-After", "60");
            res.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"Too many requests. Please wait a minute and retry.\"}");
            return;
        }
        if (windows.size() > 50_000) {
            windows.entrySet().removeIf(e -> e.getValue().minute() != minute);
        }
        chain.doFilter(req, res);
    }
}
