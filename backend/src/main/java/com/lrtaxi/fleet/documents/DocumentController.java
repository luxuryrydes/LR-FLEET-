package com.lrtaxi.fleet.documents;

import com.lrtaxi.fleet.common.FleetFilter;
import com.lrtaxi.fleet.security.Roles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
