package com.lrtaxi.fleet.imports;

import com.lrtaxi.fleet.common.PageResult;
import com.lrtaxi.fleet.reports.Exporter;
import com.lrtaxi.fleet.security.Roles;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/imports")
public class ImportController {

    private final ImportService imports;

    public ImportController(ImportService imports) {
        this.imports = imports;
    }

    public record CommitRequest(String policy, @Size(max = 500) String reason) {
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(Roles.WRITE)
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file, @RequestParam("type") String type) {
        return imports.upload(file, type);
    }

    @GetMapping
    @PreAuthorize(Roles.READ)
    public PageResult<Map<String, Object>> list(@RequestParam(required = false) String type, @RequestParam(required = false) String status,
                                                @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return imports.list(type, status, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize(Roles.READ)
    public Map<String, Object> get(@PathVariable long id) {
        return imports.get(id);
    }

    @PostMapping("/{id}/mapping")
    @PreAuthorize(Roles.WRITE)
    public Map<String, Object> mapping(@PathVariable long id, @RequestBody ImportService.MappingRequest req) {
        return imports.setMapping(id, req);
    }

    @GetMapping("/{id}/rows")
    @PreAuthorize(Roles.READ)
    public PageResult<Map<String, Object>> rows(@PathVariable long id, @RequestParam(required = false) String status,
                                                @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return imports.staging(id, status, page, size);
    }

    /** Vehicle master dry run: new / updated (with changed fields) / unchanged vehicles. Saves nothing. */
    @GetMapping("/{id}/master-review")
    @PreAuthorize(Roles.READ)
    public Map<String, Object> masterReview(@PathVariable long id) {
        return imports.reviewMaster(id);
    }

    @GetMapping("/{id}/review")
    @PreAuthorize(Roles.READ)
    public Map<String, Object> review(@PathVariable long id) {
        return imports.reviewGps(id);
    }

    @PostMapping("/{id}/commit")
    @PreAuthorize(Roles.WRITE)
    public Map<String, Object> commit(@PathVariable long id, @RequestBody CommitRequest req) {
        return imports.commit(id, req.policy() == null ? "SKIP" : req.policy(), req.reason());
    }

    @PostMapping("/{id}/retry")
    @PreAuthorize(Roles.WRITE)
    public Map<String, Object> retry(@PathVariable long id) {
        return imports.retry(id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(Roles.WRITE)
    public void cancel(@PathVariable long id) {
        imports.cancel(id);
    }

    @GetMapping("/{id}/errors")
    @PreAuthorize(Roles.READ)
    public ResponseEntity<byte[]> errors(@PathVariable long id, @RequestParam(defaultValue = "xlsx") String format) {
        return Exporter.respond("import_" + id + "_errors", format, List.of(
                Exporter.integer("Row", "rowNumber"), Exporter.col("Field", "fieldName"), Exporter.col("Error code", "errorCode"),
                Exporter.col("Reason", "message"), Exporter.col("Value", "rawValue"), Exporter.col("Stage", "stage")), imports.errors(id));
    }
}
