package com.lrtaxi.fleet.jobs;

import com.lrtaxi.fleet.security.Roles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Is the Python import worker running? The worker writes worker_heartbeats every few seconds; no heartbeat in the last
 * {@link #ONLINE_SECONDS} seconds means queued Excel files will wait until a worker starts. With WORKER_WAKE_URL set
 * (a worker hosted as a free Render web service, which sleeps when idle) the API pings that URL to wake it.
 */
@Service
public class WorkerMonitor {

    private static final Logger log = LoggerFactory.getLogger(WorkerMonitor.class);
    static final int ONLINE_SECONDS = 90;

    private final JdbcTemplate jdbc;
    private final String wakeUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final AtomicLong lastWake = new AtomicLong();

    public WorkerMonitor(JdbcTemplate jdbc, @Value("${WORKER_WAKE_URL:}") String wakeUrl) {
        this.jdbc = jdbc;
        this.wakeUrl = wakeUrl == null ? "" : wakeUrl.trim();
    }

    public Map<String, Object> status() {
        List<OffsetDateTime> seen = jdbc.queryForList("SELECT max(last_seen) FROM worker_heartbeats", OffsetDateTime.class);
        OffsetDateTime last = seen.isEmpty() ? null : seen.get(0);
        Integer queued = jdbc.queryForObject("SELECT count(*) FROM background_jobs WHERE status = 'QUEUED'", Integer.class);
        Integer ageSeconds = jdbc.queryForObject(
                "SELECT CAST(extract(epoch FROM now() - min(created_at)) AS int) FROM background_jobs WHERE status = 'QUEUED'", Integer.class);
        boolean online = last != null && last.isAfter(OffsetDateTime.now().minusSeconds(ONLINE_SECONDS));
        if (!online) {
            wake();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("online", online);
        m.put("lastSeen", last);
        m.put("queuedJobs", queued == null ? 0 : queued);
        m.put("oldestQueuedSeconds", ageSeconds);
        m.put("wakeConfigured", !wakeUrl.isEmpty());
        return m;
    }

    /** Fire-and-forget ping to a sleeping worker; at most once every 30 seconds. */
    public void wake() {
        long now = System.currentTimeMillis();
        long prev = lastWake.get();
        if (wakeUrl.isEmpty() || now - prev < 30_000 || !lastWake.compareAndSet(prev, now)) {
            return;
        }
        try {
            http.sendAsync(HttpRequest.newBuilder(URI.create(wakeUrl)).timeout(Duration.ofSeconds(90)).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).exceptionally(e -> {
                        log.warn("Worker wake-up ping to {} failed: {}", wakeUrl, e.getMessage());
                        return null;
                    });
        } catch (IllegalArgumentException e) {
            log.warn("WORKER_WAKE_URL '{}' is not a valid URL", wakeUrl);
        }
    }

    @RestController
    @RequestMapping("/api/worker")
    public static class WorkerController {
        private final WorkerMonitor monitor;

        public WorkerController(WorkerMonitor monitor) {
            this.monitor = monitor;
        }

        @GetMapping("/status")
        @PreAuthorize(Roles.READ)
        public Map<String, Object> status() {
            return monitor.status();
        }
    }
}
