package com.lrtaxi.fleet.documents;

import com.lrtaxi.fleet.audit.AuditService;
import com.lrtaxi.fleet.calc.DocumentRules;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Compliance documents. The "current" document of a type for a vehicle is the non-void, non-rejected one with the
 * latest expiry. Buckets: EXPIRED, EXPIRING (within the largest reminder window), MISSING (required type absent, see
 * DocumentRules.required), PENDING_VERIFICATION (a scan was attached and nobody has verified it yet).
 */
@Service
public class DocumentService {

    public record DocumentInput(Long vehicleId, Long driverId,
                                @NotBlank @Pattern(regexp = "INSURANCE|INSURANCE_OD|FITNESS|PUC|PERMIT|PERMIT_1Y|REGISTRATION|ROAD_TAX|DRIVING_LICENCE|OTHER") String docType,
                                @Size(max = 80) String documentNumber, @Size(max = 200) String issuer,
                                LocalDate issueDate, LocalDate expiryDate, Long fileId, @Size(max = 1000) String remarks,
                                Boolean notApplicable) {
        boolean na() {
            return Boolean.TRUE.equals(notApplicable);
        }
    }

    /** The current document of each type for each vehicle. */
    static final String CURRENT = """
            SELECT DISTINCT ON (d.vehicle_id, d.doc_type) d.*
            FROM vehicle_documents d WHERE NOT d.is_void AND d.verification_status <> 'REJECTED' AND d.vehicle_id IS NOT NULL
            ORDER BY d.vehicle_id, d.doc_type, d.expiry_date DESC NULLS LAST, d.id DESC""";

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
                       d.file_id, f.original_filename AS file_name, d.remarks, d.not_applicable, d.source, d.created_at,
                       (d.expiry_date - CURRENT_DATE) AS days_to_expiry
                FROM vehicle_documents d
                LEFT JOIN vehicles v ON v.id = d.vehicle_id
                LEFT JOIN drivers dr ON dr.id = d.driver_id
                LEFT JOIN users vu ON vu.id = d.verified_by
                LEFT JOIN stored_files f ON f.id = d.file_id
                """;
    }

    /** Compliance buckets for active vehicles (and drivers' licences) matching the filter. */
    public Map<String, Object> compliance(FleetFilter f) {
        int window = reminderWindow();
        MapSqlParameterSource p = new MapSqlParameterSource("window", window);
        StringBuilder vw = new StringBuilder(" AND v.status IN ('ACTIVE','IDLE','WORKSHOP')");
        f.appendVehicleConditions(vw, p, "v");

        String current = CURRENT;
        List<Map<String, Object>> expired = jdbc.query("""
                SELECT c.id, c.vehicle_id, v.registration_number, c.doc_type, c.document_number, c.expiry_date,
                       (c.expiry_date - CURRENT_DATE) AS days_to_expiry, c.verification_status
                FROM (""" + current + ") c JOIN vehicles v ON v.id = c.vehicle_id WHERE NOT c.not_applicable AND c.expiry_date < CURRENT_DATE" + vw
                + " ORDER BY c.expiry_date", p, Rows::map);
        List<Map<String, Object>> expiring = jdbc.query("""
                SELECT c.id, c.vehicle_id, v.registration_number, c.doc_type, c.document_number, c.expiry_date,
                       (c.expiry_date - CURRENT_DATE) AS days_to_expiry, c.verification_status
                FROM (""" + current + ") c JOIN vehicles v ON v.id = c.vehicle_id"
                + " WHERE NOT c.not_applicable AND c.expiry_date BETWEEN CURRENT_DATE AND CURRENT_DATE + CAST(:window AS int)" + vw
                + " ORDER BY c.expiry_date", p, Rows::map);
        List<Map<String, Object>> pending = jdbc.query(select()
                + " WHERE NOT d.is_void AND d.verification_status = 'PENDING' AND d.file_id IS NOT NULL ORDER BY d.created_at LIMIT 2000", Rows::map);

        // Every vehicle needs docs.required_types; commercial vehicles also need DocumentRules.COMMERCIAL_EXTRA.
        List<String> base = settings.list("docs.required_types", "INSURANCE,PUC");
        MapSqlParameterSource mp = new MapSqlParameterSource().addValues(p.getValues())
                .addValue("types", base.isEmpty() ? List.of("-") : base).addValue("extra", DocumentRules.COMMERCIAL_EXTRA);
        List<Map<String, Object>> missing = jdbc.query("""
                SELECT v.id AS vehicle_id, v.registration_number, t.doc_type
                FROM vehicles v CROSS JOIN (SELECT CAST(x AS varchar) AS doc_type FROM unnest(ARRAY[:types]) AS x
                                            UNION SELECT CAST(x AS varchar) FROM unnest(ARRAY[:extra]) AS x) t
                WHERE (t.doc_type IN (:types) OR v.registration_class = 'COMMERCIAL')
                  AND NOT EXISTS (SELECT 1 FROM vehicle_documents d WHERE d.vehicle_id = v.id AND d.doc_type = t.doc_type
                                  AND NOT d.is_void AND d.verification_status <> 'REJECTED')""" + vw
                + " ORDER BY v.registration_normalized, t.doc_type LIMIT 20000", mp, Rows::map);
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

    /**
     * Documents dashboard: one row per active vehicle, one column per document type, each cell the current document's
     * status. {@code state} (EXPIRED, DUE_SOON, MISSING, NO_FILE, ATTENTION) keeps vehicles with that state in
     * {@code docType}, or in any column when docType is blank. The summary counts every column before that filter.
     */
    public Map<String, Object> matrix(String q, String registrationClass, String docType, String state) {
        int window = reminderWindow();
        LocalDate today = LocalDate.now();
        List<String> base = settings.list("docs.required_types", "INSURANCE,PUC");
        MapSqlParameterSource p = new MapSqlParameterSource();
        StringBuilder w = new StringBuilder(" WHERE v.status IN ('ACTIVE','IDLE','WORKSHOP')");
        if (q != null && !q.isBlank()) {
            w.append(" AND (v.registration_normalized LIKE :q OR upper(v.model) LIKE :q OR upper(coalesce(dp.name, '')) LIKE :q)");
            p.addValue("q", "%" + q.trim().toUpperCase(Locale.ROOT).replace(" ", "").replace("-", "") + "%");
        }
        if ("COMMERCIAL".equals(registrationClass) || "PRIVATE".equals(registrationClass)) {
            w.append(" AND v.registration_class = :rc");
            p.addValue("rc", registrationClass);
        } else if ("UNKNOWN".equals(registrationClass)) {
            w.append(" AND v.registration_class IS NULL");
        }
        List<Map<String, Object>> vehicles = jdbc.query("""
                SELECT v.id AS vehicle_id, v.registration_number, v.make, v.model, v.registration_class, v.status, v.operator_type,
                       dp.name AS department_name
                FROM vehicles v LEFT JOIN departments dp ON dp.id = v.current_department_id""" + w + " ORDER BY v.registration_normalized",
                p, Rows::map);
        Map<Long, Map<String, Map<String, Object>>> docs = new HashMap<>();
        for (Map<String, Object> d : jdbc.query("""
                SELECT c.id, c.vehicle_id, c.doc_type, c.document_number, c.issue_date, c.expiry_date, c.not_applicable, c.file_id,
                       f.original_filename AS file_name, c.verification_status, c.remarks, c.source
                FROM (""" + CURRENT + ") c LEFT JOIN stored_files f ON f.id = c.file_id", Map.of(), Rows::map)) {
            long vid = ((Number) d.remove("vehicleId")).longValue();
            docs.computeIfAbsent(vid, k -> new HashMap<>()).put((String) d.remove("docType"), d);
        }

        Set<String> types = DocumentRules.VEHICLE_TYPES.keySet();
        Map<String, Map<String, Integer>> summary = new LinkedHashMap<>();
        for (String t : types) {
            Map<String, Integer> s = new LinkedHashMap<>();
            for (DocumentRules.Status st : DocumentRules.Status.values()) {
                s.put(st.name(), 0);
            }
            s.put("WITH_FILE", 0);
            s.put("NO_FILE", 0);
            summary.put(t, s);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> v : vehicles) {
            long vid = ((Number) v.get("vehicleId")).longValue();
            List<String> required = DocumentRules.required((String) v.get("registrationClass"), base);
            Map<String, Map<String, Object>> mine = docs.getOrDefault(vid, Map.of());
            Map<String, Object> cells = new LinkedHashMap<>();
            boolean keep = state == null || state.isBlank();
            for (String t : types) {
                Map<String, Object> d = mine.get(t);
                LocalDate expiry = d == null ? null : (LocalDate) d.get("expiryDate");
                boolean na = d != null && Boolean.TRUE.equals(d.get("notApplicable"));
                DocumentRules.Status st = DocumentRules.status(d != null, na, expiry, required.contains(t), today, window);
                boolean noFile = d != null && !na && d.get("fileId") == null;
                Map<String, Object> cell = new LinkedHashMap<>(d == null ? Map.of() : d);
                cell.put("status", st.name());
                cell.put("required", required.contains(t));
                cell.put("daysToExpiry", expiry == null ? null : ChronoUnit.DAYS.between(today, expiry));
                cells.put(t, cell);
                Map<String, Integer> s = summary.get(t);
                s.merge(st.name(), 1, Integer::sum);
                if (d != null && d.get("fileId") != null) {
                    s.merge("WITH_FILE", 1, Integer::sum);
                }
                if (noFile) {
                    s.merge("NO_FILE", 1, Integer::sum);
                }
                if (!keep && (docType == null || docType.isBlank() || docType.equals(t))) {
                    keep = switch (state) {
                        case "EXPIRED" -> st == DocumentRules.Status.EXPIRED;
                        case "DUE_SOON" -> st == DocumentRules.Status.DUE_SOON;
                        case "MISSING" -> st == DocumentRules.Status.MISSING;
                        case "NO_FILE" -> noFile;
                        case "ATTENTION" -> st == DocumentRules.Status.EXPIRED || st == DocumentRules.Status.DUE_SOON
                                || st == DocumentRules.Status.MISSING;
                        default -> true;
                    };
                }
            }
            if (keep) {
                Map<String, Object> r = new LinkedHashMap<>(v);
                r.put("documents", cells);
                rows.add(r);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("types", DocumentRules.VEHICLE_TYPES.entrySet().stream().map(e -> Map.of("code", e.getKey(), "label", e.getValue())).toList());
        out.put("reminderDays", window);
        out.put("summary", summary);
        out.put("vehicles", vehicles.size());
        out.put("rows", rows);
        return out;
    }

    /** Attaches (or replaces) the scan of an existing document; a new scan needs verifying again. */
    @Transactional
    public void attachFile(long id, long fileId) {
        Integer ok = jdbc.queryForObject("SELECT count(*) FROM stored_files WHERE id = :f AND purpose = 'VEHICLE_DOCUMENT'",
                Map.of("f", fileId), Integer.class);
        if (ok == null || ok == 0) {
            throw ApiException.badRequest("INVALID_FILE", "Upload the scan as a vehicle document first");
        }
        List<Map<String, Object>> before = jdbc.query("SELECT file_id FROM vehicle_documents WHERE id = :id AND NOT is_void",
                Map.of("id", id), Rows::map);
        if (before.isEmpty()) {
            throw ApiException.notFound("Document");
        }
        jdbc.update("""
                UPDATE vehicle_documents SET file_id = :f, verification_status = 'PENDING', verified_by = NULL, verified_at = NULL,
                    updated_at = now() WHERE id = :id""", new MapSqlParameterSource("f", fileId).addValue("id", id));
        audit.record("ATTACH_FILE", "DOCUMENT", id, before.get(0), Map.of("fileId", fileId));
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
                    remarks, not_applicable, created_by)
                VALUES (:v, :d, :t, :num, :issuer, :issue, :expiry, :file, :remarks, :na, :uid) RETURNING id""",
                params(in).addValue("uid", CurrentUser.idOrNull()), Long.class);
        audit.record("CREATE", "DOCUMENT", id, null, in);
        return id;
    }

    @Transactional
    public void update(long id, DocumentInput in) {
        int n = jdbc.update("""
                UPDATE vehicle_documents SET vehicle_id = :v, driver_id = :d, doc_type = :t, document_number = :num, issuer = :issuer,
                    issue_date = :issue, expiry_date = :expiry, file_id = :file, remarks = :remarks, not_applicable = :na,
                    verification_status = 'PENDING',
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
                .addValue("expiry", in.na() ? null : in.expiryDate()).addValue("file", in.fileId()).addValue("remarks", in.remarks())
                .addValue("na", in.na());
    }
}
