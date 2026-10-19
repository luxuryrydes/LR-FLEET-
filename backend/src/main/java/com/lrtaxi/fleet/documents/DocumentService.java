package com.lrtaxi.fleet.documents;

import com.lrtaxi.fleet.audit.AuditService;
import com.lrtaxi.fleet.common.ApiException;
import com.lrtaxi.fleet.common.FleetFilter;
import com.lrtaxi.fleet.common.Rows;
import com.lrtaxi.fleet.security.CurrentUser;
import com.lrtaxi.fleet.settings.SettingsService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compliance documents. The "current" document of a type for a vehicle is the non-void, non-rejected one with the
 * latest expiry. Buckets: EXPIRED, EXPIRING (within the largest reminder window), MISSING (required type absent),
 * PENDING_VERIFICATION.
 */
@Service
public class DocumentService {

    public record DocumentInput(Long vehicleId, Long driverId,
                                @NotBlank @Pattern(regexp = "INSURANCE|FITNESS|PUC|PERMIT|REGISTRATION|ROAD_TAX|DRIVING_LICENCE|OTHER") String docType,
                                @Size(max = 80) String documentNumber, @Size(max = 200) String issuer,
                                LocalDate issueDate, LocalDate expiryDate, Long fileId, @Size(max = 1000) String remarks) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final SettingsService settings;
    private final AuditService audit;

    public DocumentService(NamedParameterJdbcTemplate jdbc, SettingsService settings, AuditService audit) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.audit = audit;
    }

    public int reminderWindow() {
        return settings.list("docs.reminder_days", "30,15,7").stream().mapToInt(s -> {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return 0;
            }
        }).max().orElse(30);
    }

    public List<Map<String, Object>> list(Long vehicleId, Long driverId, String docType) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        StringBuilder w = new StringBuilder(" WHERE NOT d.is_void");
        if (vehicleId != null) {
            w.append(" AND d.vehicle_id = :v");
            p.addValue("v", vehicleId);
        }
        if (driverId != null) {
            w.append(" AND d.driver_id = :dr");
            p.addValue("dr", driverId);
        }
        if (docType != null && !docType.isBlank()) {
            w.append(" AND d.doc_type = :t");
            p.addValue("t", docType);
        }
        return jdbc.query(select() + w + " ORDER BY d.expiry_date NULLS LAST LIMIT 5000", p, Rows::map);
    }

    private static String select() {
        return """
                SELECT d.id, d.vehicle_id, v.registration_number, d.driver_id, dr.full_name AS driver_name, d.doc_type, d.document_number,
                       d.issuer, d.issue_date, d.expiry_date, d.verification_status, d.verified_at, vu.full_name AS verified_by_name,
                       d.file_id, d.remarks, d.created_at,
                       (d.expiry_date - CURRENT_DATE) AS days_to_expiry
                FROM vehicle_documents d
                LEFT JOIN vehicles v ON v.id = d.vehicle_id
                LEFT JOIN drivers dr ON dr.id = d.driver_id
                LEFT JOIN users vu ON vu.id = d.verified_by
                """;
    }

    /** Compliance buckets for active vehicles (and drivers' licences) matching the filter. */
    public Map<String, Object> compliance(FleetFilter f) {
        int window = reminderWindow();
        MapSqlParameterSource p = new MapSqlParameterSource("window", window);
        StringBuilder vw = new StringBuilder(" AND v.status IN ('ACTIVE','IDLE','WORKSHOP')");
        f.appendVehicleConditions(vw, p, "v");

        String current = """
                SELECT DISTINCT ON (d.vehicle_id, d.doc_type) d.*
                FROM vehicle_documents d WHERE NOT d.is_void AND d.verification_status <> 'REJECTED' AND d.vehicle_id IS NOT NULL
                ORDER BY d.vehicle_id, d.doc_type, d.expiry_date DESC NULLS LAST, d.id DESC""";
        List<Map<String, Object>> expired = jdbc.query("""
                SELECT c.id, c.vehicle_id, v.registration_number, c.doc_type, c.document_number, c.expiry_date,
                       (c.expiry_date - CURRENT_DATE) AS days_to_expiry, c.verification_status
                FROM (""" + current + ") c JOIN vehicles v ON v.id = c.vehicle_id WHERE c.expiry_date < CURRENT_DATE" + vw
                + " ORDER BY c.expiry_date", p, Rows::map);
        List<Map<String, Object>> expiring = jdbc.query("""
                SELECT c.id, c.vehicle_id, v.registration_number, c.doc_type, c.document_number, c.expiry_date,
                       (c.expiry_date - CURRENT_DATE) AS days_to_expiry, c.verification_status
                FROM (""" + current + ") c JOIN vehicles v ON v.id = c.vehicle_id"
                + " WHERE c.expiry_date BETWEEN CURRENT_DATE AND CURRENT_DATE + CAST(:window AS int)" + vw
                + " ORDER BY c.expiry_date", p, Rows::map);
        List<Map<String, Object>> pending = jdbc.query(select() + " WHERE NOT d.is_void AND d.verification_status = 'PENDING' ORDER BY d.created_at LIMIT 2000",
                Rows::map);

        List<String> required = settings.list("docs.required_types", "INSURANCE,FITNESS,PUC,PERMIT,REGISTRATION");
        List<Map<String, Object>> missing = new ArrayList<>();
        if (!required.isEmpty()) {
            MapSqlParameterSource mp = new MapSqlParameterSource().addValues(p.getValues()).addValue("types", required);
            missing = jdbc.query("""
                    SELECT v.id AS vehicle_id, v.registration_number, t.doc_type
                    FROM vehicles v CROSS JOIN (SELECT CAST(x AS varchar) AS doc_type FROM unnest(ARRAY[:types]) AS x) t
                    WHERE NOT EXISTS (SELECT 1 FROM vehicle_documents d WHERE d.vehicle_id = v.id AND d.doc_type = t.doc_type
                                      AND NOT d.is_void AND d.verification_status <> 'REJECTED')""" + vw
                    + " ORDER BY v.registration_normalized, t.doc_type LIMIT 20000", mp, Rows::map);
        }
        List<Map<String, Object>> licences = jdbc.query("""
                SELECT id AS driver_id, full_name, licence_number, licence_expiry, (licence_expiry - CURRENT_DATE) AS days_to_expiry
                FROM drivers WHERE is_active AND (licence_expiry IS NULL OR licence_expiry <= CURRENT_DATE + CAST(:window AS int))
                ORDER BY licence_expiry NULLS FIRST""", p, Rows::map);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reminderDays", settings.list("docs.reminder_days", "30,15,7"));
        out.put("expired", expired);
        out.put("expiring", expiring);
        out.put("missing", missing);
        out.put("pendingVerification", pending);
        out.put("driverLicences", licences);
        return out;
    }

    @Transactional
    public long create(DocumentInput in) {
        if (in.vehicleId() == null && in.driverId() == null) {
            throw ApiException.badRequest("OWNER_REQUIRED", "Choose a vehicle or a driver");
        }
        if (in.issueDate() != null && in.expiryDate() != null && in.expiryDate().isBefore(in.issueDate())) {
            throw ApiException.badRequest("INVALID_DATES", "Expiry date must be on or after the issue date");
        }
        Long id = jdbc.queryForObject("""
                INSERT INTO vehicle_documents (vehicle_id, driver_id, doc_type, document_number, issuer, issue_date, expiry_date, file_id,
                    remarks, created_by)
                VALUES (:v, :d, :t, :num, :issuer, :issue, :expiry, :file, :remarks, :uid) RETURNING id""",
                params(in).addValue("uid", CurrentUser.idOrNull()), Long.class);
        audit.record("CREATE", "DOCUMENT", id, null, in);
        return id;
    }

    @Transactional
    public void update(long id, DocumentInput in) {
        int n = jdbc.update("""
                UPDATE vehicle_documents SET vehicle_id = :v, driver_id = :d, doc_type = :t, document_number = :num, issuer = :issuer,
                    issue_date = :issue, expiry_date = :expiry, file_id = :file, remarks = :remarks, verification_status = 'PENDING',
                    verified_by = NULL, verified_at = NULL, updated_at = now()
                WHERE id = :id AND NOT is_void""", params(in).addValue("id", id));
        if (n == 0) {
            throw ApiException.notFound("Document");
        }
        audit.record("UPDATE", "DOCUMENT", id, null, in);
    }

    @Transactional
    public void verify(long id, String status, String remarks) {
        if (!"VERIFIED".equals(status) && !"REJECTED".equals(status)) {
            throw ApiException.badRequest("INVALID_STATUS", "Status must be VERIFIED or REJECTED");
        }
        int n = jdbc.update("""
                UPDATE vehicle_documents SET verification_status = :s, verified_by = :u, verified_at = now(),
                    remarks = coalesce(:r, remarks), updated_at = now() WHERE id = :id AND NOT is_void""",
                new MapSqlParameterSource("s", status).addValue("u", CurrentUser.idOrNull()).addValue("r", remarks).addValue("id", id));
        if (n == 0) {
            throw ApiException.notFound("Document");
        }
        audit.record(status, "DOCUMENT", id, null, Map.of("remarks", remarks == null ? "" : remarks));
    }

    @Transactional
    public void voidDocument(long id, String reason) {
        int n = jdbc.update("UPDATE vehicle_documents SET is_void = TRUE, remarks = :r, updated_at = now() WHERE id = :id",
                new MapSqlParameterSource("r", reason).addValue("id", id));
        if (n == 0) {
            throw ApiException.notFound("Document");
        }
        audit.record("VOID", "DOCUMENT", id, null, Map.of("reason", reason));
    }

    private static MapSqlParameterSource params(DocumentInput in) {
        return new MapSqlParameterSource("v", in.vehicleId()).addValue("d", in.driverId()).addValue("t", in.docType())
                .addValue("num", in.documentNumber()).addValue("issuer", in.issuer()).addValue("issue", in.issueDate())
                .addValue("expiry", in.expiryDate()).addValue("file", in.fileId()).addValue("remarks", in.remarks());
    }
}
