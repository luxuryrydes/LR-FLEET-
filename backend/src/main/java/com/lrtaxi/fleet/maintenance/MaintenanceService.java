package com.lrtaxi.fleet.maintenance;

import com.lrtaxi.fleet.audit.AuditService;
import com.lrtaxi.fleet.calc.Calc;
import com.lrtaxi.fleet.calc.Odometer;
import com.lrtaxi.fleet.common.ApiException;
import com.lrtaxi.fleet.common.FleetFilter;
import com.lrtaxi.fleet.common.PageResult;
import com.lrtaxi.fleet.common.Paging;
import com.lrtaxi.fleet.common.Rows;
import com.lrtaxi.fleet.odometer.OdometerService;
import com.lrtaxi.fleet.security.CurrentUser;
import com.lrtaxi.fleet.settings.SettingsService;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MaintenanceService {

    public record MaintenanceInput(
            @NotNull Long vehicleId,
            @NotNull LocalDate serviceDate,
            @DecimalMin("0") BigDecimal odometerKm,
            @NotBlank String categoryCode,
            @NotBlank @Size(max = 2000) String description,
            @DecimalMin("0") BigDecimal partsCost,
            @DecimalMin("0") BigDecimal labourCost,
            @DecimalMin("0") BigDecimal otherCost,
            @DecimalMin("0") BigDecimal taxAmount,
            @DecimalMin("0") BigDecimal totalCost,
            @Size(max = 200) String workshopName,
            Long vendorId,
            @Size(max = 80) String invoiceNumber,
            @Size(max = 80) String jobCardNumber,
            Long attachmentFileId,
            LocalDate nextServiceDate,
            @DecimalMin("0") BigDecimal nextServiceOdometer,
            @Size(max = 1000) String remarks) {
    }

    static final String SELECT = """
            SELECT m.id, m.vehicle_id, v.registration_number, m.service_date, m.odometer_km, m.category_code, sc.name AS category_name,
                   sc.resets_service_interval, m.description, m.parts_cost, m.labour_cost, m.other_cost, m.tax_amount, m.calculated_total,
                   m.total_cost, m.cost_mismatch, m.workshop_name, m.vendor_id, vd.name AS vendor_name, m.invoice_number, m.job_card_number,
                   m.attachment_file_id, m.next_service_date, m.next_service_odometer, m.next_service_overridden, m.remarks,
                   m.is_void, m.void_reason, m.created_at, u.full_name AS created_by_name
            FROM maintenance_records m
            JOIN vehicles v ON v.id = m.vehicle_id
            JOIN service_categories sc ON sc.code = m.category_code
            LEFT JOIN vendors vd ON vd.id = m.vendor_id
            LEFT JOIN users u ON u.id = m.created_by
            """;

    static final Map<String, String> SORTABLE = Map.of("serviceDate", "m.service_date", "registrationNumber", "v.registration_normalized",
            "totalCost", "m.total_cost", "category", "sc.name");

    private final NamedParameterJdbcTemplate jdbc;
    private final SettingsService settings;
    private final OdometerService odometer;
    private final AuditService audit;

    public MaintenanceService(NamedParameterJdbcTemplate jdbc, SettingsService settings, OdometerService odometer, AuditService audit) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.odometer = odometer;
        this.audit = audit;
    }

    public PageResult<Map<String, Object>> list(FleetFilter f, String category, boolean includeVoid, Integer page, Integer size, String sort) {
        Paging paging = Paging.of(page, size, sort, SORTABLE, "m.service_date DESC, m.id DESC");
        MapSqlParameterSource p = f.dateParams();
        StringBuilder w = where(f, category, includeVoid, p);
        Long total = jdbc.queryForObject("SELECT count(*) FROM maintenance_records m JOIN vehicles v ON v.id = m.vehicle_id" + w, p, Long.class);
        return new PageResult<>(jdbc.query(SELECT + w + paging.sql(), p, Rows::map), total == null ? 0 : total, paging.page(), paging.size());
    }

    public List<Map<String, Object>> listForExport(FleetFilter f, String category) {
        MapSqlParameterSource p = f.dateParams();
        return jdbc.query(SELECT + where(f, category, false, p) + " ORDER BY v.registration_normalized, m.service_date LIMIT 500000", p, Rows::map);
    }

    private static StringBuilder where(FleetFilter f, String category, boolean includeVoid, MapSqlParameterSource p) {
        StringBuilder w = new StringBuilder(" WHERE m.service_date BETWEEN :from AND :to");
        if (!includeVoid) {
            w.append(" AND NOT m.is_void");
        }
        if (category != null && !category.isBlank()) {
            w.append(" AND m.category_code = :cat");
            p.addValue("cat", category);
        }
        f.appendVehicleConditions(w, p, "v");
        return w;
    }

    public Map<String, Object> get(long id) {
        List<Map<String, Object>> rows = jdbc.query(SELECT + " WHERE m.id = :id", Map.of("id", id), Rows::map);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Service record");
        }
        return rows.get(0);
    }

    @Transactional
    public Map<String, Object> create(MaintenanceInput in) {
        List<String> warnings = new ArrayList<>();
        if (in.serviceDate().isAfter(LocalDate.now())) {
            throw ApiException.badRequest("FUTURE_DATE", "Service date cannot be in the future");
        }
        List<Map<String, Object>> v = jdbc.queryForList("""
                SELECT v.id, coalesce(v.service_interval_km, c.service_interval_km) AS km, coalesce(v.service_interval_months, c.service_interval_months) AS months
                FROM vehicles v JOIN vehicle_categories c ON c.code = v.category_code WHERE v.id = :id""", Map.of("id", in.vehicleId()));
        if (v.isEmpty()) {
            throw ApiException.badRequest("UNKNOWN_VEHICLE", "Vehicle not found");
        }
        List<Boolean> resets = jdbc.queryForList("SELECT resets_service_interval FROM service_categories WHERE code = :c AND is_active",
                Map.of("c", in.categoryCode()), Boolean.class);
        if (resets.isEmpty()) {
            throw ApiException.badRequest("UNKNOWN_CATEGORY", "Unknown service category");
        }
        boolean resetsInterval = Boolean.TRUE.equals(resets.get(0));

        BigDecimal parts = Calc.nz(in.partsCost());
        BigDecimal labour = Calc.nz(in.labourCost());
        BigDecimal other = Calc.nz(in.otherCost());
        BigDecimal tax = Calc.nz(in.taxAmount());
        BigDecimal calculated = Calc.money(parts.add(labour).add(other).add(tax));
        BigDecimal total = in.totalCost() != null ? Calc.money(in.totalCost()) : calculated;
        BigDecimal tolerance = settings.decimal("fuel.amount_tolerance", "1.00");
        boolean mismatch = in.totalCost() != null && calculated.signum() > 0 && total.subtract(calculated).abs().compareTo(tolerance) > 0;
        if (mismatch) {
            warnings.add("Invoice total ₹" + total + " differs from parts + labour + other + tax ₹" + calculated + "; invoice total kept");
        }

        // Next service threshold: explicit override, else computed from this service for interval-resetting categories.
        boolean overridden = in.nextServiceOdometer() != null || in.nextServiceDate() != null;
        BigDecimal nextOdo = in.nextServiceOdometer();
        LocalDate nextDate = in.nextServiceDate();
        if (resetsInterval) {
            int km = v.get(0).get("km") == null ? settings.integer("service.default_interval_km", 10000) : ((Number) v.get(0).get("km")).intValue();
            int months = v.get(0).get("months") == null ? settings.integer("service.default_interval_months", 6) : ((Number) v.get(0).get("months")).intValue();
            if (nextOdo == null && in.odometerKm() != null) {
                nextOdo = in.odometerKm().add(BigDecimal.valueOf(km));
            }
            if (nextDate == null) {
                nextDate = in.serviceDate().plusMonths(months);
            }
            if (in.odometerKm() == null) {
                warnings.add("No odometer recorded: the km-based next service cannot be calculated");
            }
        }

        Long id = jdbc.queryForObject("""
                INSERT INTO maintenance_records (vehicle_id, service_date, odometer_km, category_code, description, parts_cost, labour_cost,
                    other_cost, tax_amount, calculated_total, total_cost, cost_mismatch, workshop_name, vendor_id, invoice_number,
                    job_card_number, attachment_file_id, next_service_date, next_service_odometer, next_service_overridden, remarks,
                    created_by, updated_by)
                VALUES (:v, :d, :odo, :cat, :desc, :parts, :labour, :other, :tax, :calc, :total, :mm, :workshop, :vendor, :inv, :job,
                    :file, :nextDate, :nextOdo, :overridden, :remarks, :uid, :uid) RETURNING id""",
                new MapSqlParameterSource("v", in.vehicleId()).addValue("d", in.serviceDate()).addValue("odo", in.odometerKm())
                        .addValue("cat", in.categoryCode()).addValue("desc", in.description().trim()).addValue("parts", parts)
                        .addValue("labour", labour).addValue("other", other).addValue("tax", tax).addValue("calc", calculated)
                        .addValue("total", total).addValue("mm", mismatch).addValue("workshop", in.workshopName())
                        .addValue("vendor", in.vendorId()).addValue("inv", in.invoiceNumber()).addValue("job", in.jobCardNumber())
                        .addValue("file", in.attachmentFileId()).addValue("nextDate", nextDate).addValue("nextOdo", nextOdo)
                        .addValue("overridden", overridden).addValue("remarks", in.remarks()).addValue("uid", CurrentUser.idOrNull()),
                Long.class);
        if (in.odometerKm() != null) {
            Odometer.Check c = odometer.addReading(in.vehicleId(), in.serviceDate(), in.odometerKm(), "SERVICE", id);
            if (!c.ok()) {
                warnings.add("Odometer not trusted: " + c.message());
            }
        }
        audit.record("CREATE", "MAINTENANCE_RECORD", id, null, get(id));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("warnings", warnings);
        out.put("nextServiceOdometer", nextOdo);
        out.put("nextServiceDate", nextDate);
        return out;
    }

    @Transactional
    public void voidRecord(long id, String reason) {
        Map<String, Object> before = get(id);
        if (Boolean.TRUE.equals(before.get("isVoid"))) {
            throw ApiException.badRequest("ALREADY_VOID", "Record is already void");
        }
        jdbc.update("UPDATE maintenance_records SET is_void = TRUE, void_reason = :r, voided_by = :u, voided_at = now(), updated_at = now() WHERE id = :id",
                new MapSqlParameterSource("r", reason).addValue("u", CurrentUser.idOrNull()).addValue("id", id));
        odometer.voidBySource("SERVICE", id);
        audit.record("VOID", "MAINTENANCE_RECORD", id, before, Map.of("reason", reason));
    }
}
