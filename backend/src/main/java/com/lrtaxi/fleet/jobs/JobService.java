package com.lrtaxi.fleet.jobs;

import com.lrtaxi.fleet.common.ApiException;
import com.lrtaxi.fleet.common.Json;
import com.lrtaxi.fleet.common.Rows;
import com.lrtaxi.fleet.security.CurrentUser;
import com.lrtaxi.fleet.security.Roles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Queue for the Python worker (background_jobs table). The worker claims jobs with
 * SELECT ... FOR UPDATE SKIP LOCKED, so several worker instances can run safely.
 */
@Service
public class JobService {

    private final JdbcTemplate jdbc;

    public JobService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long enqueue(String type, Map<String, Object> payload) {
        return jdbc.queryForObject("INSERT INTO background_jobs (job_type, payload, created_by) VALUES (?, CAST(? AS jsonb), ?) RETURNING id",
                Long.class, type, Json.write(payload), CurrentUser.idOrNull());
    }

    public Map<String, Object> get(long id) {
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT id, job_type, status, payload, result, attempts, max_attempts, last_error, created_at, started_at, finished_at
                FROM background_jobs WHERE id = ?""", Rows::map, id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Job");
        }
        return rows.get(0);
    }

    @RestController
    @RequestMapping("/api/jobs")
    public static class JobController {
        private final JobService jobs;

        public JobController(JobService jobs) {
            this.jobs = jobs;
        }

        @GetMapping("/{id}")
        @PreAuthorize(Roles.READ)
        public Map<String, Object> get(@PathVariable long id) {
            return jobs.get(id);
        }
    }
}
