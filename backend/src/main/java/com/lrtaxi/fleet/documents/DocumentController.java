package com.lrtaxi.fleet.documents;

import com.lrtaxi.fleet.calc.DocumentRules;
import com.lrtaxi.fleet.common.FleetFilter;
import com.lrtaxi.fleet.reports.Exporter;
import com.lrtaxi.fleet.security.Roles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService docs;

    public DocumentController(DocumentService docs) {
        this.docs = docs;
    }

    public record VerifyRequest(@NotBlank String status, @Size(max = 1000) String remarks) {
    }

    public record VoidRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record AttachRequest(@NotNull Long fileId) {
    }

    private static final Map<String, String> STATUS_TEXT = Map.of("VALID", "Valid", "DUE_SOON", "Due soon", "EXPIRED", "Expired",
            "NOT_REQUIRED", "Not required", "NO_EXPIRY", "No expiry date", "MISSING", "Missing", "OPTIONAL", "");

    @GetMapping
    @PreAuthorize(Roles.READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) Long vehicleId, @RequestParam(required = false) Long driverId,
                                          @RequestParam(required = false) String docType) {
        return docs.list(vehicleId, driverId, docType);
    }

    @GetMapping("/compliance")
    @PreAuthorize(Roles.READ)
    public Map<String, Object> compliance(@ModelAttribute FleetFilter filter) {
        return docs.compliance(filter);
    }

    /** Documents dashboard: vehicles x document types. Filters: q, registrationClass, docType + state. */
    @GetMapping("/matrix")
    @PreAuthorize(Roles.READ)
    public Map<String, Object> matrix(@RequestParam(required = false) String q, @RequestParam(required = false) String registrationClass,
                                      @RequestParam(required = false) String docType, @RequestParam(required = false) String state) {
        return docs.matrix(q, registrationClass, docType, state);
    }

    @GetMapping("/matrix/export")
    @PreAuthorize(Roles.READ)
    @SuppressWarnings("unchecked")
    public ResponseEntity<byte[]> exportMatrix(@RequestParam(required = false) String q, @RequestParam(required = false) String registrationClass,
                                               @RequestParam(required = false) String docType, @RequestParam(required = false) String state,
                                               @RequestParam(defaultValue = "xlsx") String format) {
        Map<String, Object> m = docs.matrix(q, registrationClass, docType, state);
        List<Exporter.Col> cols = new ArrayList<>(List.of(Exporter.col("Vehicle", "registrationNumber"), Exporter.col("Model", "model"),
                Exporter.col("Commercial / private", "registrationClass"), Exporter.col("Department", "departmentName")));
        DocumentRules.VEHICLE_TYPES.forEach((code, label) -> {
            cols.add(Exporter.col(label + " end date", code + "_date"));
            cols.add(Exporter.col(label + " status", code + "_status"));
            cols.add(Exporter.col(label + " scan", code + "_file"));
        });
        DateTimeFormatter dmy = DateTimeFormatter.ofPattern("dd-MM-yyyy");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : (List<Map<String, Object>>) m.get("rows")) {
            Map<String, Object> x = new LinkedHashMap<>(r);
            Map<String, Map<String, Object>> cells = (Map<String, Map<String, Object>>) r.get("documents");
            cells.forEach((code, c) -> {
                Object d = c.get("expiryDate");
                x.put(code + "_date", d == null ? (Boolean.TRUE.equals(c.get("notApplicable")) ? "Not required" : "") : ((java.time.LocalDate) d).format(dmy));
                x.put(code + "_status", STATUS_TEXT.getOrDefault(String.valueOf(c.get("status")), ""));
                x.put(code + "_file", c.get("fileName") == null ? "" : "Uploaded");
            });
            rows.add(x);
        }
        return Exporter.respond("vehicle_documents", format, cols, rows, List.of(
                "Vehicle documents register - current document of each type per active vehicle.",
                "Due soon = expires within " + m.get("reminderDays") + " days. Missing = required and not recorded.",
                "Every vehicle needs the documents in Settings > docs.required_types; commercial vehicles also need fitness and both permits."));
    }

    /** Attach or replace the scan (PDF / photo) of a document. Upload it first with POST /api/files purpose=VEHICLE_DOCUMENT. */
    @PostMapping("/{id}/file")
    @PreAuthorize(Roles.WRITE)
    public void attach(@PathVariable long id, @Valid @RequestBody AttachRequest req) {
        docs.attachFile(id, req.fileId());
    }

    @PostMapping
    @PreAuthorize(Roles.WRITE)
    public Map<String, Object> create(@Valid @RequestBody DocumentService.DocumentInput in) {
        return Map.of("id", docs.create(in));
    }

    @PutMapping("/{id}")
    @PreAuthorize(Roles.WRITE)
    public void update(@PathVariable long id, @Valid @RequestBody DocumentService.DocumentInput in) {
        docs.update(id, in);
    }

    @PostMapping("/{id}/verify")
    @PreAuthorize(Roles.MANAGE)
    public void verify(@PathVariable long id, @Valid @RequestBody VerifyRequest req) {
        docs.verify(id, req.status(), req.remarks());
    }

    @PostMapping("/{id}/void")
    @PreAuthorize(Roles.MANAGE)
    public void voidDoc(@PathVariable long id, @Valid @RequestBody VoidRequest req) {
        docs.voidDocument(id, req.reason());
    }
}
