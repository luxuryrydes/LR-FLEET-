package com.lrtaxi.fleet.files;

import com.lrtaxi.fleet.common.ApiException;
import com.lrtaxi.fleet.security.Roles;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private static final Set<String> PURPOSES = Set.of("FUEL_RECEIPT", "SERVICE_INVOICE", "VEHICLE_DOCUMENT", "OTHER");

    private final FileService files;

    public FileController(FileService files) {
        this.files = files;
    }

    /** Attachment upload (receipts, invoices, documents). Import files go through /api/imports. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(Roles.WRITE)
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file, @RequestParam("purpose") String purpose) {
        if (!PURPOSES.contains(purpose)) {
            throw ApiException.badRequest("INVALID_PURPOSE", "Purpose must be one of " + PURPOSES);
        }
        FileService.StoredFile f = files.store(file, purpose);
        return Map.of("id", f.id(), "originalFilename", f.originalFilename(), "sizeBytes", f.sizeBytes());
    }

    @GetMapping("/{id}")
    @PreAuthorize(Roles.READ)
    public ResponseEntity<InputStreamResource> download(@PathVariable long id) {
        FileService.StoredFile f = files.find(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(f.originalFilename()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(files.open(f)));
    }
}
