package com.lrtaxi.fleet.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** One structured log line per API request with a request id (also returned as X-Request-Id). */
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("http");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString().substring(0, 12);
        MDC.put("requestId", requestId);
        res.setHeader("X-Request-Id", requestId);
        long start = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            Long uid = CurrentUser.idOrNull();
            if (uid != null) {
                MDC.put("userId", uid.toString());
            }
            if (req.getRequestURI().startsWith("/api/")) {
                log.info("method={} path={} status={} durationMs={}", req.getMethod(), req.getRequestURI(), res.getStatus(),
                        (System.nanoTime() - start) / 1_000_000);
            }
            MDC.clear();
        }
    }
}
