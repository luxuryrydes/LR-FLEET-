package com.lrtaxi.fleet.vehicle;

import com.lrtaxi.fleet.audit.AuditService;
import com.lrtaxi.fleet.calc.RegistrationNormalizer;
import com.lrtaxi.fleet.common.ApiException;
import com.lrtaxi.fleet.common.FleetFilter;
import com.lrtaxi.fleet.common.PageResult;
import com.lrtaxi.fleet.common.Paging;
import com.lrtaxi.fleet.common.Rows;
import com.lrtaxi.fleet.odometer.OdometerService;
import com.lrtaxi.fleet.security.CurrentUser;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class VehicleService {

    static final String SELECT = """
            SELECT v.id, v.registration_number, v.registration_normalized, v.duplicate_registration_reason, v.make, v.model,
                   v.model_year, v.fuel_type, v.category_code, v.ownership_type, v.vendor_id, vd.name AS vendor_name,
                   v.current_driver_id, dr.full_name AS driver_name, dr.phone AS driver_phone,
                   v.current_department_id, dp.name AS department_name, v.customer_name, v.status,
                   v.expected_mileage, v.service_interval_km, v.service_interval_months, v.tank_capacity, v.battery_capacity_kwh,
                   v.purchase_date, v.purchase_cost, v.chassis_number, v.engine_number, v.gps_device_name, v.remarks,
                   v.deactivated_at, v.created_at, v.updated_at
            FROM vehicles v
            LEFT JOIN vendors vd ON vd.id = v.vendor_id
            LEFT JOIN drivers dr ON dr.id = v.current_driver_id
            LEFT JOIN departments dp ON dp.id = v.current_department_id
            """;

    static final Map<String, String> SORTABLE = Map.of(
            "registrationNumber", "v.registration_normalized",
            "make", "v.make",
            "model", "v.model",
            "status", "v.status",
            "fuelType", "v.fuel_type",
            "department", "dp.name",
            "createdAt", "v.created_at");

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;
    private final OdometerService odometer;

    public VehicleService(NamedParameterJdbcTemplate jdbc, AuditService audit, OdometerService odometer) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.odometer = odometer;
    }

    /** Export rows (capped at 100,000) using exactly the same filters as the list. */
    public List<Map<String, Object>> listForExport(FleetFilter f, String q) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        StringBuilder where = where(f, q, p);
        return jdbc.query(SELECT + where + " ORDER BY v.registration_normalized LIMIT 100000", p, Rows::map);
    }

    private static StringBuilder where(FleetFilter f, String q, MapSqlParameterSource p) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        f.appendVehicleConditions(where, p, "v");
        if (q != null && !q.isBlank()) {
            where.append(" AND (v.registration_normalized LIKE :q OR lower(v.make) LIKE :ql OR lower(v.model) LIKE :ql OR lower(dr.full_name) LIKE :ql)");
            p.addValue("q", "%" + RegistrationNormalizer.normalize(q) + "%").addValue("ql", "%" + q.trim().toLowerCase() + "%");
        }
        return where;
    }

    public PageResult<Map<String, Object>> list(FleetFilter f, String q, Integer page, Integer size, String sort) {
        Paging paging = Paging.of(page, size, sort, SORTABLE, "v.registration_normalized");
        MapSqlParameterSource p = new MapSqlParameterSource();
        StringBuilder where = where(f, q, p);
        Long total = jdbc.queryForObject("SELECT count(*) FROM vehicles v LEFT JOIN drivers dr ON dr.id = v.current_driver_id" + where, p, Long.class);
        List<Map<String, Object>> items = jdbc.query(SELECT + where + paging.sql(), p, Rows::map);
        return new PageResult<>(items, total == null ? 0 : total, paging.page(), paging.size());
    }

    public Map<String, Object> get(long id) {
        List<Map<String, Object>> rows = jdbc.query(SELECT + " WHERE v.id = :id", Map.of("id", id), Rows::map);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Vehicle");
        }
        return rows.get(0);
    }

    @Transactional
    public long create(VehicleRequest r) {
        String norm = normalizeOrFail(r.registrationNumber());
        checkDuplicate(norm, r.duplicateRegistrationReason(), null);
        Long uid = CurrentUser.idOrNull();
        MapSqlParameterSource p = params(r, norm).addValue("uid", uid).addValue("status", r.status() == null ? "ACTIVE" : r.status());
        Long id = jdbc.queryForObject("""
                INSERT INTO vehicles (registration_number, registration_normalized, duplicate_registration_reason, make, model, model_year,
                    fuel_type, category_code, ownership_type, vendor_id, current_driver_id, current_department_id, customer_name, status,
                    expected_mileage, service_interval_km, service_interval_months, tank_capacity, battery_capacity_kwh, purchase_date,
                    purchase_cost, chassis_number, engine_number, gps_device_name, remarks, created_by, updated_by)
                VALUES (:reg, :norm, :dupReason, :make, :model, :year, :fuel, :category, :ownership, :vendor, :driver, :dept, :customer,
                    :status, :expected, :intervalKm, :intervalMonths, :tank, :battery, :purchaseDate, :purchaseCost, :chassis, :engine,
                    :gps, :remarks, :uid, :uid)
                RETURNING id""", p, Long.class);
        LocalDate eff = r.effectiveDate() != null ? r.effectiveDate() : LocalDate.now();
        String reason = r.changeReason() == null ? "Vehicle created" : r.changeReason();
        assign(id, "STATUS", null, null, null, r.status() == null ? "ACTIVE" : r.status(), eff, reason);
        assign(id, "OWNERSHIP", null, null, null, r.ownershipType(), eff, reason);
        if (r.currentDriverId() != null) {
            assign(id, "DRIVER", r.currentDriverId(), null, null, null, eff, reason);
        }
        if (r.currentDepartmentId() != null) {
            assign(id, "DEPARTMENT", null, r.currentDepartmentId(), null, null, eff, reason);
        }
        if (r.vendorId() != null) {
            assign(id, "VENDOR", null, null, r.vendorId(), null, eff, reason);
        }
        if (r.customerName() != null && !r.customerName().isBlank()) {
            assign(id, "CUSTOMER", null, null, null, r.customerName(), eff, reason);
        }
        if (r.gpsDeviceName() != null && !r.gpsDeviceName().isBlank()) {
            addAliasInternal(id, r.gpsDeviceName());
        }
        if (r.openingOdometerKm() != null) {
            odometer.addReading(id, r.openingOdometerDate() != null ? r.openingOdometerDate() : LocalDate.now(),
                    r.openingOdometerKm(), "VEHICLE_MASTER", id);
        }
        audit.record("CREATE", "VEHICLE", id, null, get(id));
        return id;
    }

    @Transactional
    public void update(long id, VehicleRequest r) {
        Map<String, Object> before = get(id);
        String norm = normalizeOrFail(r.registrationNumber());
        checkDuplicate(norm, r.duplicateRegistrationReason(), id);
        String status = r.status() == null ? (String) before.get("status") : r.status();
        if (!Objects.equals(status, before.get("status")) && !"ACTIVE".equals(status) && !"IDLE".equals(status)
                && !"WORKSHOP".equals(status) && !CurrentUser.require().canManage()) {
            throw ApiException.forbidden("Only a manager can deactivate, sell or scrap a vehicle");
        }
        MapSqlParameterSource p = params(r, norm).addValue("id", id).addValue("uid", CurrentUser.idOrNull()).addValue("status", status);
        jdbc.update("""
                UPDATE vehicles SET registration_number = :reg, registration_normalized = :norm, duplicate_registration_reason = :dupReason,
                    make = :make, model = :model, model_year = :year, fuel_type = :fuel, category_code = :category, ownership_type = :ownership,
                    vendor_id = :vendor, current_driver_id = :driver, current_department_id = :dept, customer_name = :customer, status = :status,
                    expected_mileage = :expected, service_interval_km = :intervalKm, service_interval_months = :intervalMonths,
                    tank_capacity = :tank, battery_capacity_kwh = :battery, purchase_date = :purchaseDate, purchase_cost = :purchaseCost,
                    chassis_number = :chassis, engine_number = :engine, gps_device_name = :gps, remarks = :remarks,
                    deactivated_at = CASE WHEN :status IN ('INACTIVE','SOLD','SCRAPPED') THEN coalesce(deactivated_at, now()) ELSE NULL END,
                    updated_by = :uid, updated_at = now()
                WHERE id = :id""", p);

        LocalDate eff = r.effectiveDate() != null ? r.effectiveDate() : LocalDate.now();
        String reason = r.changeReason();
        if (!Objects.equals(status, before.get("status"))) {
            assign(id, "STATUS", null, null, null, status, eff, reason);
        }
        if (!Objects.equals(r.ownershipType(), before.get("ownershipType"))) {
            assign(id, "OWNERSHIP", null, null, null, r.ownershipType(), eff, reason);
        }
        if (!Objects.equals(r.currentDriverId(), asLong(before.get("currentDriverId")))) {
            assign(id, "DRIVER", r.currentDriverId(), null, null, null, eff, reason);
        }
        if (!Objects.equals(r.currentDepartmentId(), asLong(before.get("currentDepartmentId")))) {
            assign(id, "DEPARTMENT", null, r.currentDepartmentId(), null, null, eff, reason);
        }
        if (!Objects.equals(r.vendorId(), asLong(before.get("vendorId")))) {
            assign(id, "VENDOR", null, null, r.vendorId(), null, eff, reason);
        }
        if (!Objects.equals(blankToNull(r.customerName()), before.get("customerName"))) {
            assign(id, "CUSTOMER", null, null, null, blankToNull(r.customerName()), eff, reason);
        }
        if (r.gpsDeviceName() != null && !r.gpsDeviceName().isBlank() && !Objects.equals(r.gpsDeviceName(), before.get("gpsDeviceName"))) {
            addAliasInternal(id, r.gpsDeviceName());
        }
        audit.record("UPDATE", "VEHICLE", id, before, get(id));
    }

    @Transactional
    public void deactivate(long id, String reason) {
        Map<String, Object> before = get(id);
        jdbc.update("UPDATE vehicles SET status = 'INACTIVE', deactivated_at = now(), updated_by = :uid, updated_at = now() WHERE id = :id",
                new MapSqlParameterSource("id", id).addValue("uid", CurrentUser.idOrNull()));
        assign(id, "STATUS", null, null, null, "INACTIVE", LocalDate.now(), reason);
        audit.record("DEACTIVATE", "VEHICLE", id, before, get(id));
    }

    /** Closes the open assignment of this type and opens a new one (effective-dated history). Null value = unassigned. */
    void assign(long vehicleId, String type, Long driverId, Long deptId, Long vendorId, String valueText, LocalDate eff, String reason) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("v", vehicleId).addValue("t", type).addValue("eff", eff)
                .addValue("driver", driverId).addValue("dept", deptId).addValue("vendor", vendorId).addValue("txt", valueText)
                .addValue("reason", reason).addValue("uid", CurrentUser.idOrNull());
        // An open row that started on the same day is replaced; an older one is closed the day before.
        jdbc.update("DELETE FROM vehicle_assignments WHERE vehicle_id = :v AND assignment_type = :t AND effective_to IS NULL AND effective_from >= :eff", p);
        jdbc.update("UPDATE vehicle_assignments SET effective_to = CAST(:eff AS date) - 1 WHERE vehicle_id = :v AND assignment_type = :t AND effective_to IS NULL", p);
        if (driverId != null || deptId != null || vendorId != null || valueText != null) {
            jdbc.update("""
                    INSERT INTO vehicle_assignments (vehicle_id, assignment_type, driver_id, department_id, vendor_id, value_text,
                        effective_from, reason, changed_by)
                    VALUES (:v, :t, :driver, :dept, :vendor, :txt, :eff, :reason, :uid)""", p);
        }
    }

    public List<Map<String, Object>> assignments(long vehicleId) {
        return jdbc.query("""
                SELECT a.id, a.assignment_type, a.driver_id, d.full_name AS driver_name, a.department_id, dp.name AS department_name,
                       a.vendor_id, vd.name AS vendor_name, a.value_text, a.effective_from, a.effective_to, a.reason,
                       u.full_name AS changed_by_name, a.created_at
                FROM vehicle_assignments a
                LEFT JOIN drivers d ON d.id = a.driver_id
                LEFT JOIN departments dp ON dp.id = a.department_id
                LEFT JOIN vendors vd ON vd.id = a.vendor_id
                LEFT JOIN users u ON u.id = a.changed_by
                WHERE a.vehicle_id = :v ORDER BY a.assignment_type, a.effective_from DESC""", Map.of("v", vehicleId), Rows::map);
    }

    public List<Map<String, Object>> aliases(long vehicleId) {
        return jdbc.query("SELECT id, alias_raw, alias_normalized, created_at FROM vehicle_aliases WHERE vehicle_id = :v ORDER BY alias_raw",
                Map.of("v", vehicleId), Rows::map);
    }

    @Transactional
    public long addAlias(long vehicleId, String raw) {
        get(vehicleId);
        long id = addAliasInternal(vehicleId, raw);
        audit.record("CREATE", "VEHICLE_ALIAS", id, null, Map.of("vehicleId", vehicleId, "alias", raw));
        return id;
    }

    private long addAliasInternal(long vehicleId, String raw) {
        String norm = RegistrationNormalizer.normalize(raw);
        if (norm.isEmpty()) {
            throw ApiException.badRequest("INVALID_ALIAS", "Alias must contain letters or digits");
        }
        List<Long> existing = jdbc.queryForList("SELECT vehicle_id FROM vehicle_aliases WHERE alias_normalized = :n", Map.of("n", norm), Long.class);
        if (!existing.isEmpty()) {
            if (existing.get(0) == vehicleId) {
                return jdbc.queryForObject("SELECT id FROM vehicle_aliases WHERE alias_normalized = :n", Map.of("n", norm), Long.class);
            }
            throw ApiException.conflict("ALIAS_IN_USE", "GPS name '" + raw + "' is already mapped to another vehicle");
        }
        return jdbc.queryForObject("""
                INSERT INTO vehicle_aliases (vehicle_id, alias_normalized, alias_raw, created_by) VALUES (:v, :n, :raw, :uid) RETURNING id""",
                new MapSqlParameterSource("v", vehicleId).addValue("n", norm).addValue("raw", raw.trim()).addValue("uid", CurrentUser.idOrNull()),
                Long.class);
    }

    @Transactional
    public void deleteAlias(long vehicleId, long aliasId) {
        int n = jdbc.update("DELETE FROM vehicle_aliases WHERE id = :id AND vehicle_id = :v", Map.of("id", aliasId, "v", vehicleId));
        if (n == 0) {
            throw ApiException.notFound("Alias");
        }
        audit.record("DELETE", "VEHICLE_ALIAS", aliasId, Map.of("vehicleId", vehicleId), null);
    }

    private void checkDuplicate(String norm, String reason, Long selfId) {
        if (reason != null && !reason.isBlank()) {
            return;   // documented exception
        }
        MapSqlParameterSource p = new MapSqlParameterSource("n", norm).addValue("self", selfId == null ? -1L : selfId);
        Long n = jdbc.queryForObject("SELECT count(*) FROM vehicles WHERE registration_normalized = :n AND id <> :self AND duplicate_registration_reason IS NULL", p, Long.class);
        if (n != null && n > 0) {
            throw ApiException.conflict("DUPLICATE_REGISTRATION", "Registration " + norm + " already exists. Record a documented reason to allow a duplicate.");
        }
    }

    private static String normalizeOrFail(String raw) {
        String n = RegistrationNormalizer.normalize(raw);
        if (n.length() < 4) {
            throw ApiException.badRequest("INVALID_REGISTRATION", "Registration number must contain at least 4 letters/digits");
        }
        return n;
    }

    private static MapSqlParameterSource params(VehicleRequest r, String norm) {
        return new MapSqlParameterSource()
                .addValue("reg", norm)
                .addValue("norm", norm)
                .addValue("dupReason", blankToNull(r.duplicateRegistrationReason()))
                .addValue("make", r.make().trim())
                .addValue("model", r.model().trim())
                .addValue("year", r.modelYear())
                .addValue("fuel", r.fuelType())
                .addValue("category", r.categoryCode())
                .addValue("ownership", r.ownershipType())
                .addValue("vendor", r.vendorId())
                .addValue("driver", r.currentDriverId())
                .addValue("dept", r.currentDepartmentId())
                .addValue("customer", blankToNull(r.customerName()))
                .addValue("expected", r.expectedMileage())
                .addValue("intervalKm", r.serviceIntervalKm())
                .addValue("intervalMonths", r.serviceIntervalMonths())
                .addValue("tank", r.tankCapacity())
                .addValue("battery", r.batteryCapacityKwh())
                .addValue("purchaseDate", r.purchaseDate())
                .addValue("purchaseCost", r.purchaseCost())
                .addValue("chassis", blankToNull(r.chassisNumber()))
                .addValue("engine", blankToNull(r.engineNumber()))
                .addValue("gps", blankToNull(r.gpsDeviceName()))
                .addValue("remarks", blankToNull(r.remarks()));
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static Long asLong(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }
}
