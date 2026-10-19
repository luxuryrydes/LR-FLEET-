package com.lrtaxi.fleet.reports;

import com.lrtaxi.fleet.common.FleetFilter;
import com.lrtaxi.fleet.jobs.JobService;
import com.lrtaxi.fleet.security.Roles;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private static final int PREVIEW_ROWS = 500;

    private final ReportService reports;
    private final JobService jobs;

    public ReportController(ReportService reports, JobService jobs) {
        this.reports = reports;
        this.jobs = jobs;
    }

    @GetMapping
    @PreAuthorize(Roles.READ)
    public List<Map<String, Object>> catalog() {
        return reports.catalog();
    }

    /** On-screen preview: first 500 rows plus total count and column definitions. */
    @GetMapping("/{key}")
    @PreAuthorize(Roles.READ)
    public Map<String, Object> preview(@PathVariable String key, @ModelAttribute FleetFilter filter) {
        filter.validate();
        ReportService.Report r = reports.get(key);
        List<Map<String, Object>> rows = r.rows().apply(filter);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", r.key());
        out.put("title", r.title());
        out.put("columns", r.columns());
        out.put("notes", reports.notes(r, filter));
        out.put("totalRows", rows.size());
        out.put("rows", rows.size() > PREVIEW_ROWS ? rows.subList(0, PREVIEW_ROWS) : rows);
        return out;
    }

    @GetMapping("/{key}/export")
    @PreAuthorize(Roles.READ)
    public ResponseEntity<byte[]> export(@PathVariable String key, @ModelAttribute FleetFilter filter,
                                         @RequestParam(defaultValue = "xlsx") String format) {
        filter.validate();
        ReportService.Report r = reports.get(key);
        return Exporter.respond(r.key().replace('-', '_'), format, r.columns(), r.rows().apply(filter), reports.notes(r, filter));
    }

    /**
     * Queues the Python GPS-vs-fuel reconciliation workbook (analytics only; it contains coverage gaps, never mileage
     * or money figures that compete with the Java engine). Poll /api/jobs/{id}; result.fileId is downloadable.
     */
    @PostMapping("/reconciliation-workbook")
    @PreAuthorize(Roles.READ)
    public Map<String, Object> reconciliationWorkbook(@ModelAttribute FleetFilter filter) {
        filter.validate();
        long id = jobs.enqueue("RECONCILIATION_REPORT", Map.of("from", filter.fromOrDefault().toString(), "to", filter.toOrDefault().toString()));
        return Map.of("jobId", id);
    }
}
