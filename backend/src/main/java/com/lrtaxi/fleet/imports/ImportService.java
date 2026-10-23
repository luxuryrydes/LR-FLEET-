package com.lrtaxi.fleet.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrtaxi.fleet.alerts.AlertService;
import com.lrtaxi.fleet.audit.AuditService;
import com.lrtaxi.fleet.calc.GpsDailyDedup;
import com.lrtaxi.fleet.common.ApiException;
import com.lrtaxi.fleet.common.Json;
import com.lrtaxi.fleet.common.PageResult;
import com.lrtaxi.fleet.common.Paging;
import com.lrtaxi.fleet.common.Rows;
import com.lrtaxi.fleet.files.FileService;
import com.lrtaxi.fleet.fuel.EfficiencyService;
import com.lrtaxi.fleet.fuel.FuelInput;
import com.lrtaxi.fleet.fuel.FuelService;
import com.lrtaxi.fleet.gps.GpsService;
import com.lrtaxi.fleet.jobs.JobService;
import com.lrtaxi.fleet.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Import workflow:
 * upload -> [worker: DETECT_HEADERS] -> HEADERS_READY -> mapping -> [worker: PARSE_IMPORT] -> PARSED (preview/review)
 * -> commit (Java, single transaction, authoritative validation) -> COMMITTED.
 * A failed commit rolls back completely and the batch is marked FAILED with the reason; it can be retried.
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    public static final Set<String> GPS_FIELDS = Set.of("vehicle", "date", "km", "tripStart");
    public static final Set<String> FUEL_FIELDS = Set.of("vehicle", "date", "time", "energyType", "quantity", "unitPrice", "grossAmount",
            "discountAmount", "taxAmount", "invoiceAmount", "odometerKm", "isFullTank", "stationName", "invoiceNumber",
            "transactionRef", "paymentMethod", "cardNumber", "notes");
    public static final Set<String> MASTER_FIELDS = Set.of("registration", "model", "fuelType", "modelYear", "operatorLabel", "driverName",
            "driverPhone", "department", "registrationClass", "make", "category", "chassisNumber", "engineNumber", "gpsDeviceName",
            "soldDate", "soldTo", "remarks");
    /** Documents register: one row per vehicle with an end-date column per document type. */
    public static final Set<String> DOCUMENT_FIELDS = Set.of("registration", "registrationDate", "insuranceExpiry", "ownDamageExpiry",
            "pucExpiry", "fitnessExpiry", "roadTaxExpiry", "permitExpiry", "permit1yExpiry", "registrationClass", "remarks");
    /** Service register: last service odometer / date / next service per vehicle. */
    public static final Set<String> SERVICE_FIELDS = Set.of("registration", "serviceDate", "odometerKm", "nextServiceOdometer",
            "nextServiceDate", "recordStatus", "category", "description", "workshopName", "totalCost", "invoiceNumber", "remarks");
    private static final List<String> EXPIRY_FIELDS = List.of("insuranceExpiry", "ownDamageExpiry", "pucExpiry", "fitnessExpiry",
            "roadTaxExpiry", "permitExpiry", "permit1yExpiry", "registrationDate");

    private final NamedParameterJdbcTemplate jdbc;
    private final FileService files;
    private final JobService jobs;
    private final GpsService gps;
    private final FuelService fuel;
    private final EfficiencyService efficiency;
    private final AlertService alerts;
    private final AuditService audit;
    private final MasterImportService master;
    private final DocumentImportService documents;
    private final ServiceImportService services;
    private final com.lrtaxi.fleet.jobs.WorkerMonitor worker;
    private final TransactionTemplate tx;
    private final TransactionTemplate nested;
    private final TransactionTemplate requiresNew;

    public ImportService(NamedParameterJdbcTemplate jdbc, FileService files, JobService jobs, GpsService gps, FuelService fuel,
                         EfficiencyService efficiency, AlertService alerts, AuditService audit, MasterImportService master,
                         DocumentImportService documents, ServiceImportService services, com.lrtaxi.fleet.jobs.WorkerMonitor worker,
                         PlatformTransactionManager tm) {
        this.master = master;
        this.services = services;
        this.documents = documents;
        this.worker = worker;
        this.jdbc = jdbc;
        this.files = files;
        this.jobs = jobs;
        this.gps = gps;
        this.fuel = fuel;
        this.efficiency = efficiency;
        this.alerts = alerts;
        this.audit = audit;
        this.tx = new TransactionTemplate(tm);
        this.nested = new TransactionTemplate(tm);
        this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.requiresNew = new TransactionTemplate(tm);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------------ upload / mapping

    @Transactional
    public Map<String, Object> upload(MultipartFile file, String type) {
        if (!Set.of("GPS_KM", "FUEL", "VEHICLE_MASTER", "DOCUMENTS", "SERVICE").contains(type)) {
            throw ApiException.badRequest("INVALID_TYPE", "Import type must be GPS_KM, FUEL, VEHICLE_MASTER, DOCUMENTS or SERVICE");
        }
        FileService.StoredFile f = files.store(file, "IMPORT");
        Long id = jdbc.queryForObject("""
                INSERT INTO import_batches (import_type, file_id, original_filename, file_sha256, status, created_by)
                VALUES (:t, :f, :n, :sha, 'HEADERS_PENDING', :uid) RETURNING id""",
                new MapSqlParameterSource("t", type).addValue("f", f.id()).addValue("n", f.originalFilename())
                        .addValue("sha", f.sha256()).addValue("uid", CurrentUser.idOrNull()), Long.class);
        jobs.enqueue("DETECT_HEADERS", Map.of("batchId", id));
        audit.record("UPLOAD", "IMPORT_BATCH", id, null, Map.of("file", f.originalFilename(), "type", type, "sha256", f.sha256()));
        return get(id);
    }

    public Map<String, Object> get(long id) {
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT b.*, u.full_name AS created_by_name, cu.full_name AS committed_by_name
                FROM import_batches b LEFT JOIN users u ON u.id = b.created_by LEFT JOIN users cu ON cu.id = b.committed_by
                WHERE b.id = :id""", Map.of("id", id), Rows::map);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Import batch");
        }
        Map<String, Object> b = new LinkedHashMap<>(rows.get(0));
        b.put("sameFileImports", jdbc.query("""
                SELECT id, status, created_at, committed_at FROM import_batches
                WHERE file_sha256 = :sha AND id <> :id AND status IN ('COMMITTED', 'PARSED') ORDER BY id""",
                new MapSqlParameterSource("sha", b.get("fileSha256")).addValue("id", id), Rows::map));
        if (Set.of("HEADERS_PENDING", "PARSE_PENDING").contains(String.valueOf(b.get("status")))) {
            b.put("worker", worker.status());        // waiting on the Python worker: tell the screen whether one is running
        }
        return b;
    }

    public PageResult<Map<String, Object>> list(String type, String status, Integer page, Integer size) {
        Paging paging = Paging.of(page, size, null, Map.of(), "b.created_at DESC");
        MapSqlParameterSource p = new MapSqlParameterSource();
        StringBuilder w = new StringBuilder(" WHERE 1=1");
        if (type != null && !type.isBlank()) {
            w.append(" AND b.import_type = :t");
            p.addValue("t", type);
        }
        if (status != null && !status.isBlank()) {
            w.append(" AND b.status = :s");
            p.addValue("s", status);
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM import_batches b" + w, p, Long.class);
        List<Map<String, Object>> items = jdbc.query("""
                SELECT b.id, b.import_type, b.original_filename, b.status, b.total_rows, b.valid_rows, b.warning_rows, b.rejected_rows,
                       b.duplicate_rows, b.excluded_rows, b.inserted_rows, b.replaced_rows, b.skipped_rows, b.duplicate_policy, b.error_message,
                       b.created_at, b.committed_at, u.full_name AS created_by_name
                FROM import_batches b LEFT JOIN users u ON u.id = b.created_by""" + w + paging.sql(), p, Rows::map);
        return new PageResult<>(items, total == null ? 0 : total, paging.page(), paging.size());
    }

    public record MappingRequest(String sheet, Integer headerRow, Map<String, String> mapping, Map<String, Object> options) {
    }

    @Transactional
    public Map<String, Object> setMapping(long id, MappingRequest req) {
        Map<String, Object> b = lock(id);
        String status = (String) b.get("status");
        if (!"HEADERS_READY".equals(status) && !"PARSED".equals(status)) {
            throw ApiException.conflict("INVALID_STATE", "Mapping can only be set after headers are detected (current: " + status + ")");
        }
        String type = (String) b.get("import_type");
        Map<String, String> mapping = req.mapping() == null ? Map.of() : req.mapping();
        Set<String> allowed = switch (type) {
            case "GPS_KM" -> GPS_FIELDS;
            case "FUEL" -> FUEL_FIELDS;
            case "DOCUMENTS" -> DOCUMENT_FIELDS;
            case "SERVICE" -> SERVICE_FIELDS;
            default -> MASTER_FIELDS;
        };
        for (String k : mapping.keySet()) {
            if (!allowed.contains(k)) {
                throw ApiException.badRequest("UNKNOWN_FIELD", "Unknown target field '" + k + "'");
            }
        }
        Object defaultEnergy = req.options() == null ? null : req.options().get("defaultEnergyType");
        boolean hasDefaultEnergy = defaultEnergy != null && Set.of("PETROL", "DIESEL", "CNG", "ELECTRICITY").contains(String.valueOf(defaultEnergy));
        if (defaultEnergy != null && !String.valueOf(defaultEnergy).isBlank() && !hasDefaultEnergy) {
            throw ApiException.badRequest("INVALID_OPTION", "Fuel for all rows must be PETROL, DIESEL, CNG or ELECTRICITY");
        }
        List<String> required = switch (type) {
            case "GPS_KM" -> List.of("vehicle", "date", "km");
            case "FUEL" -> hasDefaultEnergy ? List.of("vehicle", "date", "quantity") : List.of("vehicle", "date", "energyType", "quantity");
            case "DOCUMENTS" -> List.of("registration");
            case "SERVICE" -> List.of("registration");
            default -> List.of("registration", "model", "fuelType");
        };
        for (String r : required) {
            if (mapping.get(r) == null || mapping.get(r).isBlank()) {
                throw ApiException.badRequest("MAPPING_INCOMPLETE", "Please map a column to '" + r + "'");
            }
        }
        Map<String, Object> options = new HashMap<>(req.options() == null ? Map.of() : req.options());
        if ("GPS_KM".equals(type)) {
            Object mode = options.getOrDefault("recordMode", "DAILY_TOTAL");
            if (!"DAILY_TOTAL".equals(mode) && !"TRIP".equals(mode)) {
                throw ApiException.badRequest("INVALID_MODE", "Record mode must be DAILY_TOTAL or TRIP");
            }
            if ("TRIP".equals(mode) && mapping.get("tripStart") == null && mapping.get("date") == null) {
                throw ApiException.badRequest("MAPPING_INCOMPLETE", "Trip files need the trip start timestamp column");
            }
            options.put("recordMode", mode);
        } else if ("SERVICE".equals(type) && (mapping.get("serviceDate") == null || mapping.get("serviceDate").isBlank())
                && (mapping.get("odometerKm") == null || mapping.get("odometerKm").isBlank())) {
            throw ApiException.badRequest("MAPPING_INCOMPLETE", "Map the service date or the service odometer column");
        } else if ("DOCUMENTS".equals(type) && EXPIRY_FIELDS.stream().allMatch(f -> mapping.get(f) == null || mapping.get(f).isBlank())) {
            throw ApiException.badRequest("MAPPING_INCOMPLETE", "Map at least one end-date column (insurance, pollution, fitness, permit, road tax)");
        } else if ("FUEL".equals(type) && mapping.get("invoiceAmount") == null && mapping.get("grossAmount") == null && mapping.get("unitPrice") == null) {
            throw ApiException.badRequest("MAPPING_INCOMPLETE", "Map at least one amount column: invoice amount, value/gross or unit price");
        }
        if (req.sheet() == null || req.sheet().isBlank()) {
            throw ApiException.badRequest("MAPPING_INCOMPLETE", "Please choose a worksheet");
        }
        jdbc.update("DELETE FROM import_staging_rows WHERE batch_id = :id", Map.of("id", id));
        jdbc.update("DELETE FROM import_row_errors WHERE batch_id = :id", Map.of("id", id));
        jdbc.update("""
                UPDATE import_batches SET selected_sheet = :sheet, header_row = :hr, column_mapping = CAST(:map AS jsonb),
                    options = CAST(:opt AS jsonb), status = 'PARSE_PENDING', total_rows = 0, valid_rows = 0, warning_rows = 0,
                    rejected_rows = 0, duplicate_rows = 0, existing_conflict_rows = 0, excluded_rows = 0, error_message = NULL, updated_at = now()
                WHERE id = :id""",
                new MapSqlParameterSource("sheet", req.sheet()).addValue("hr", req.headerRow() == null ? 1 : req.headerRow())
                        .addValue("map", Json.write(mapping)).addValue("opt", Json.write(options)).addValue("id", id));
        jobs.enqueue("PARSE_IMPORT", Map.of("batchId", id));
        return get(id);
    }

    /** Re-queues the worker step after a FAILED parse (safe: staging rows are rebuilt from scratch). */
    @Transactional
    public Map<String, Object> retry(long id) {
        Map<String, Object> b = lock(id);
        if (!"FAILED".equals(b.get("status"))) {
            throw ApiException.conflict("INVALID_STATE", "Only failed imports can be retried");
        }
        boolean mapped = b.get("column_mapping") != null;
        jdbc.update("UPDATE import_batches SET status = :s, error_message = NULL, updated_at = now() WHERE id = :id",
                new MapSqlParameterSource("s", mapped ? "PARSE_PENDING" : "HEADERS_PENDING").addValue("id", id));
        if (mapped) {
            jdbc.update("DELETE FROM import_staging_rows WHERE batch_id = :id", Map.of("id", id));
            jdbc.update("DELETE FROM import_row_errors WHERE batch_id = :id", Map.of("id", id));
        }
        jobs.enqueue(mapped ? "PARSE_IMPORT" : "DETECT_HEADERS", Map.of("batchId", id));
        return get(id);
    }

    @Transactional
    public void cancel(long id) {
        Map<String, Object> b = lock(id);
        if ("COMMITTED".equals(b.get("status")) || "COMMITTING".equals(b.get("status"))) {
            throw ApiException.conflict("INVALID_STATE", "A committed import cannot be cancelled");
        }
        jdbc.update("UPDATE import_batches SET status = 'CANCELLED', updated_at = now() WHERE id = :id", Map.of("id", id));
        jdbc.update("DELETE FROM import_staging_rows WHERE batch_id = :id", Map.of("id", id));
        audit.record("CANCEL", "IMPORT_BATCH", id, null, null);
    }

    public PageResult<Map<String, Object>> staging(long id, String status, Integer page, Integer size) {
        Paging paging = Paging.of(page, size, null, Map.of(), "s.row_number");
        MapSqlParameterSource p = new MapSqlParameterSource("id", id);
        String w = " WHERE s.batch_id = :id";
        if (status != null && !status.isBlank()) {
            w += " AND s.status = :st";
            p.addValue("st", status);
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM import_staging_rows s" + w, p, Long.class);
        List<Map<String, Object>> items = jdbc.query("""
                SELECT s.id, s.row_number, s.raw, s.parsed, s.vehicle_id, v.registration_number, s.record_date, s.status, s.messages
                FROM import_staging_rows s LEFT JOIN vehicles v ON v.id = s.vehicle_id""" + w + paging.sql(), p, Rows::map);
        return new PageResult<>(items, total == null ? 0 : total, paging.page(), paging.size());
    }

    public List<Map<String, Object>> errors(long id) {
        return jdbc.query("""
                SELECT row_number, field_name, error_code, message, raw_value, stage FROM import_row_errors
                WHERE batch_id = :id ORDER BY row_number, id LIMIT 200000""", Map.of("id", id), Rows::map);
    }

    // ------------------------------------------------------------------ review (dry run)

    /**
     * Dry run of the GPS commit rules against current data: what would be inserted, skipped as identical, kept as existing
     * conflict (SKIP) or replaced (REPLACE). Nothing is written.
     */
    public Map<String, Object> reviewGps(long id) {
        Map<String, Object> b = get(id);
        requireType(b, "GPS_KM");
        GpsDailyDedup.Mode mode = mode(b);
        List<GpsDailyDedup.DayResult> days = GpsDailyDedup.resolve(gpsRows(id), mode);
        int insert = 0;
        int same = 0;
        int conflicts = 0;
        int dayConflicts = 0;
        List<Map<String, Object>> conflictList = new ArrayList<>();
        Map<String, BigDecimal> existing = existingGps(id);
        for (GpsDailyDedup.DayResult d : days) {
            if (d.outcome() == GpsDailyDedup.Outcome.CONFLICT) {
                dayConflicts++;
                continue;
            }
            BigDecimal old = existing.get(d.vehicleId() + "|" + d.date());
            if (old == null) {
                insert++;
            } else if (old.compareTo(d.distanceKm()) == 0) {
                same++;
            } else {
                conflicts++;
                if (conflictList.size() < 500) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("vehicleId", d.vehicleId());
                    c.put("date", d.date());
                    c.put("existingKm", old);
                    c.put("newKm", d.distanceKm());
                    c.put("rows", d.usedRows());
                    conflictList.add(c);
                }
            }
        }
        jdbc.update("UPDATE import_batches SET existing_conflict_rows = :c WHERE id = :id", Map.of("c", conflicts, "id", id));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recordMode", mode.name());
        out.put("vehicleDays", days.size());
        out.put("newDays", insert);
        out.put("identicalExistingDays", same);
        out.put("existingConflictDays", conflicts);
        out.put("inFileConflictDays", dayConflicts);
        out.put("excludedRows", jdbc.queryForObject("""
                SELECT count(*) FROM import_staging_rows s JOIN vehicles v ON v.id = s.vehicle_id
                WHERE s.batch_id = :id AND s.status <> 'REJECTED' AND (s.status = 'EXCLUDED' OR NOT v.gps_km_tracked)""", Map.of("id", id), Integer.class));
        out.put("existingConflicts", conflictList);
        return out;
    }

    private Map<String, BigDecimal> existingGps(long batchId) {
        Map<String, BigDecimal> m = new HashMap<>();
        jdbc.query("""
                SELECT g.vehicle_id, g.km_date, g.distance_km FROM gps_daily_km g
                WHERE g.is_current AND (g.vehicle_id, g.km_date) IN (
                    SELECT DISTINCT s.vehicle_id, s.record_date FROM import_staging_rows s
                    WHERE s.batch_id = :id AND s.status NOT IN ('REJECTED', 'EXCLUDED') AND s.vehicle_id IS NOT NULL)""",
                Map.of("id", batchId), rs -> {
                    m.put(rs.getLong(1) + "|" + rs.getDate(2).toLocalDate(), rs.getBigDecimal(3));
                });
        return m;
    }

    // ------------------------------------------------------------------ commit

    public Map<String, Object> commit(long id, String policy, String reason) {
        boolean replace = "REPLACE".equals(policy);
        if (replace) {
            CurrentUser u = CurrentUser.require();
            if (!u.canManage()) {
                throw ApiException.forbidden("Only a manager can replace existing records");
            }
            if (reason == null || reason.isBlank()) {
                throw ApiException.badRequest("REASON_REQUIRED", "A reason is required to replace existing records");
            }
        } else if (policy != null && !"SKIP".equals(policy)) {
            throw ApiException.badRequest("INVALID_POLICY", "Policy must be SKIP or REPLACE");
        }
        try {
            tx.executeWithoutResult(status -> doCommit(id, replace, reason));
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("Import {} commit failed", id, e);
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            requiresNew.executeWithoutResult(s -> jdbc.update(
                    "UPDATE import_batches SET status = 'FAILED', error_message = :m, updated_at = now() WHERE id = :id AND status <> 'COMMITTED'",
                    new MapSqlParameterSource("m", "Commit failed and was fully rolled back: " + truncate(msg, 1500)).addValue("id", id)));
            throw ApiException.conflict("COMMIT_FAILED", "The import failed and nothing was saved. You can retry after fixing: " + truncate(msg, 300));
        }
        return get(id);
    }

    private void doCommit(long id, boolean replace, String reason) {
        Map<String, Object> b = lock(id);
        if (!"PARSED".equals(b.get("status"))) {
            throw ApiException.conflict("INVALID_STATE", "Only a parsed import can be committed (current: " + b.get("status") + ")");
        }
        Set<Long> touched = new HashSet<>();
        int[] counts;
        if ("GPS_KM".equals(b.get("import_type"))) {
            counts = commitGps(id, b, replace, reason, touched);
        } else if ("FUEL".equals(b.get("import_type"))) {
            counts = commitFuel(id, touched);
        } else if ("SERVICE".equals(b.get("import_type"))) {
            counts = services.commit(id, (row, field, code, message, raw) -> rowError(id, row, field, code, message, raw), touched);
        } else if ("DOCUMENTS".equals(b.get("import_type"))) {
            counts = documents.commit(id, replace, reason, (row, field, code, message, raw) -> rowError(id, row, field, code, message, raw));
        } else {
            counts = master.commit(id, replace, reason, (row, field, code, message, raw) -> rowError(id, row, field, code, message, raw));
        }
        efficiency.recomputeAll(touched);
        jdbc.update("""
                UPDATE import_batches SET status = 'COMMITTED', inserted_rows = :ins, replaced_rows = :rep, skipped_rows = :skip,
                    rejected_rows = (SELECT count(DISTINCT row_number) FROM import_row_errors WHERE batch_id = :id),
                    duplicate_policy = :pol, replace_reason = :reason, committed_by = :uid, committed_at = now(), updated_at = now()
                WHERE id = :id""",
                new MapSqlParameterSource("ins", counts[0]).addValue("rep", counts[1]).addValue("skip", counts[2])
                        .addValue("pol", replace ? "REPLACE" : "SKIP").addValue("reason", reason).addValue("uid", CurrentUser.idOrNull())
                        .addValue("id", id));
        jdbc.update("DELETE FROM import_staging_rows WHERE batch_id = :id", Map.of("id", id));
        audit.record("COMMIT", "IMPORT_BATCH", id, null, Map.of("inserted", counts[0], "replaced", counts[1], "skipped", counts[2],
                "policy", replace ? "REPLACE" : "SKIP", "reason", reason == null ? "" : reason));
    }

    private int[] commitGps(long id, Map<String, Object> b, boolean replace, String reason, Set<Long> touched) {
        GpsDailyDedup.Mode mode = mode(b);
        Map<Integer, String> deviceByRow = new HashMap<>();
        // GPS km are imported only for vehicles driven by LR's own drivers (vehicles.gps_km_tracked).
        Integer excluded = jdbc.queryForObject("""
                SELECT count(*) FROM import_staging_rows s JOIN vehicles v ON v.id = s.vehicle_id
                WHERE s.batch_id = :id AND s.status <> 'REJECTED' AND (s.status = 'EXCLUDED' OR NOT v.gps_km_tracked)""", Map.of("id", id), Integer.class);
        jdbc.update("UPDATE import_batches SET excluded_rows = :n WHERE id = :id", Map.of("n", excluded == null ? 0 : excluded, "id", id));
        jdbc.query("SELECT row_number, parsed->>'vehicleRaw' FROM import_staging_rows WHERE batch_id = :id AND status <> 'REJECTED'",
                Map.of("id", id), rs -> {
                    deviceByRow.put(rs.getInt(1), rs.getString(2));
                });
        int inserted = 0;
        int replaced = 0;
        int skipped = 0;
        for (GpsDailyDedup.DayResult d : GpsDailyDedup.resolve(gpsRows(id), mode)) {
            if (d.outcome() == GpsDailyDedup.Outcome.CONFLICT) {
                for (Integer row : d.conflictRows()) {
                    rowError(id, row, "km", "DAILY_TOTAL_CONFLICT", d.message(), null);
                }
                alerts.raise(new AlertService.AlertSpec("DUPLICATE_GPS", "MEDIUM", d.vehicleId(),
                        "DUPLICATE_GPS|" + d.vehicleId() + "|" + d.date(),
                        "Conflicting GPS daily totals on " + com.lrtaxi.fleet.calc.IndianFormat.date(d.date()),
                        d.message() + " (import batch " + id + ")",
                        Map.of("rule", "Daily-total rows for the same vehicle and date must agree", "rows", d.conflictRows()),
                        List.of(Map.of("type", "IMPORT_BATCH", "id", id, "label", "Batch " + id)), d.date(), d.date()));
                continue;
            }
            skipped += d.duplicateRows().size();
            String rows = d.usedRows().toString();
            GpsService.Outcome o;
            try {
                o = gps.apply(new GpsService.Day(d.vehicleId(), d.date(), d.distanceKm(), mode == GpsDailyDedup.Mode.TRIP ? "TRIP_SUM" : "DAILY_TOTAL",
                        mode == GpsDailyDedup.Mode.TRIP ? d.tripCount() : null, "IMPORT", deviceByRow.get(d.usedRows().get(0)), id,
                        truncate(rows, 500), null), replace, reason);
            } catch (ApiException e) {
                for (Integer row : d.usedRows()) {
                    rowError(id, row, "km", e.code(), e.getMessage(), d.distanceKm().toPlainString());
                }
                continue;
            }
            switch (o) {
                case INSERTED -> inserted++;
                case REPLACED -> replaced++;
                default -> skipped++;
            }
            if (o == GpsService.Outcome.INSERTED || o == GpsService.Outcome.REPLACED) {
                touched.add(d.vehicleId());
            }
        }
        return new int[]{inserted, replaced, skipped};
    }

    private int[] commitFuel(long id, Set<Long> touched) {
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT row_number, vehicle_id, parsed FROM import_staging_rows
                WHERE batch_id = :id AND status <> 'REJECTED' AND vehicle_id IS NOT NULL ORDER BY row_number""",
                Map.of("id", id), Rows::map);
        int inserted = 0;
        int skipped = 0;
        for (Map<String, Object> r : rows) {
            int rowNumber = ((Number) r.get("rowNumber")).intValue();
            JsonNode p = (JsonNode) r.get("parsed");
            FuelInput in;
            try {
                in = toFuelInput(((Number) r.get("vehicleId")).longValue(), p);
            } catch (RuntimeException e) {
                rowError(id, rowNumber, null, "INVALID_VALUE", e.getMessage(), null);
                continue;
            }
            try {
                // Each row runs in a savepoint: a rejected row cannot leave partial data behind.
                FuelService.SaveResult res = nested.execute(s -> fuel.save(in, "IMPORT", id, rowNumber));
                if (res != null && "INSERTED".equals(res.status())) {
                    inserted++;
                    touched.add(in.vehicleId());
                } else {
                    skipped++;
                }
            } catch (ApiException e) {
                rowError(id, rowNumber, null, e.code(), e.getMessage(), null);
            } catch (IllegalArgumentException e) {
                rowError(id, rowNumber, null, "INVALID_VALUE", e.getMessage(), null);
            }
        }
        return new int[]{inserted, 0, skipped};
    }

    static FuelInput toFuelInput(long vehicleId, JsonNode p) {
        return new FuelInput(vehicleId,
                LocalDate.parse(text(p, "date")),
                text(p, "time") == null ? null : LocalTime.parse(text(p, "time")),
                text(p, "energyType"),
                dec(p, "quantity"), dec(p, "unitPrice"), dec(p, "grossAmount"), dec(p, "discountAmount"), dec(p, "taxAmount"),
                dec(p, "invoiceAmount"), dec(p, "odometerKm"),
                p.hasNonNull("isFullTank") ? p.get("isFullTank").asBoolean() : null,
                text(p, "stationName"), null, text(p, "invoiceNumber"), text(p, "transactionRef"), text(p, "paymentMethod"), null,
                text(p, "notes"));
    }

    private List<GpsDailyDedup.Row> gpsRows(long batchId) {
        return jdbc.query("""
                SELECT row_number, vehicle_id, record_date, parsed->>'km' AS km, parsed->>'tripStart' AS trip_start
                FROM import_staging_rows s
                WHERE batch_id = :id AND status NOT IN ('REJECTED', 'EXCLUDED') AND vehicle_id IS NOT NULL AND record_date IS NOT NULL
                  AND vehicle_id IN (SELECT id FROM vehicles WHERE gps_km_tracked)
                ORDER BY row_number""", Map.of("id", batchId),
                (rs, i) -> new GpsDailyDedup.Row(rs.getInt("row_number"), rs.getLong("vehicle_id"), rs.getDate("record_date").toLocalDate(),
                        new BigDecimal(rs.getString("km")), rs.getString("trip_start")));
    }

    private void rowError(long batchId, int row, String field, String code, String message, String raw) {
        jdbc.update("""
                INSERT INTO import_row_errors (batch_id, row_number, field_name, error_code, message, raw_value, stage)
                VALUES (:b, :r, :f, :c, :m, :raw, 'COMMIT')""",
                new MapSqlParameterSource("b", batchId).addValue("r", row).addValue("f", field).addValue("c", code)
                        .addValue("m", truncate(message, 500)).addValue("raw", truncate(raw, 500)));
    }

    private Map<String, Object> lock(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, import_type, status, options, column_mapping FROM import_batches WHERE id = :id FOR UPDATE",
                Map.of("id", id));
        if (rows.isEmpty()) {
            throw ApiException.notFound("Import batch");
        }
        return rows.get(0);
    }

    private static GpsDailyDedup.Mode mode(Map<String, Object> b) {
        Object opts = b.get("options");
        JsonNode n = opts instanceof JsonNode j ? j : Json.read(String.valueOf(opts));
        return n != null && "TRIP".equals(n.path("recordMode").asText()) ? GpsDailyDedup.Mode.TRIP : GpsDailyDedup.Mode.DAILY_TOTAL;
    }

    private static void requireType(Map<String, Object> b, String type) {
        if (!type.equals(b.get("importType"))) {
            throw ApiException.badRequest("WRONG_TYPE", "This action applies to " + type + " imports only");
        }
    }

    private static String text(JsonNode p, String f) {
        return p.hasNonNull(f) && !p.get(f).asText().isBlank() ? p.get(f).asText() : null;
    }

    private static BigDecimal dec(JsonNode p, String f) {
        String t = text(p, f);
        return t == null ? null : new BigDecimal(t);
    }

    private static String truncate(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }
}
