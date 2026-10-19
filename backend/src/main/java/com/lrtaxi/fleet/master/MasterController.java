package com.lrtaxi.fleet.master;

import com.lrtaxi.fleet.audit.AuditService;
import com.lrtaxi.fleet.common.ApiException;
import com.lrtaxi.fleet.common.Rows;
import com.lrtaxi.fleet.security.Roles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Departments, vendors (fuel stations, workshops, vehicle vendors) and drivers. Records are deactivated, never deleted. */
@RestController
@RequestMapping("/api")
public class MasterController {

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;

    public MasterController(NamedParameterJdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record DepartmentRequest(@NotBlank @Size(max = 30) String code, @NotBlank @Size(max = 150) String name, Boolean active) {
    }

    public record VendorRequest(@NotBlank @Size(max = 200) String name,
                                @NotBlank @Pattern(regexp = "FUEL_STATION|WORKSHOP|VEHICLE_VENDOR|OTHER") String vendorType,
                                @Size(max = 150) String contactPerson, @Size(max = 30) String phone, @Email @Size(max = 200) String email,
                                @Pattern(regexp = "^$|^[0-9A-Z]{15}$", message = "GSTIN must be 15 characters") String gstin,
                                @Size(max = 500) String address, Boolean active) {
    }

    public record DriverRequest(@NotBlank @Size(max = 150) String fullName, @Size(max = 30) String phone,
                                @Size(max = 40) String licenceNumber, LocalDate licenceExpiry, Long vendorId,
                                @Size(max = 500) String remarks, Boolean active) {
    }

    // ---- Departments ----
    @GetMapping("/departments")
    @PreAuthorize(Roles.READ)
    public List<Map<String, Object>> departments(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return jdbc.query("SELECT id, code, name, is_active, created_at FROM departments"
                + (includeInactive ? "" : " WHERE is_active") + " ORDER BY name", Rows::map);
    }

    @PostMapping("/departments")
    @PreAuthorize(Roles.MANAGE)
    @Transactional
    public Map<String, Object> createDepartment(@Valid @RequestBody DepartmentRequest r) {
        Long id = jdbc.queryForObject("INSERT INTO departments (code, name) VALUES (:c, :n) RETURNING id",
                new MapSqlParameterSource("c", r.code().trim().toUpperCase()).addValue("n", r.name().trim()), Long.class);
        audit.record("CREATE", "DEPARTMENT", id, null, r);
        return Map.of("id", id);
    }

    @PutMapping("/departments/{id}")
    @PreAuthorize(Roles.MANAGE)
    @Transactional
    public void updateDepartment(@PathVariable long id, @Valid @RequestBody DepartmentRequest r) {
        must(jdbc.update("UPDATE departments SET code = :c, name = :n, is_active = :a WHERE id = :id",
                new MapSqlParameterSource("c", r.code().trim().toUpperCase()).addValue("n", r.name().trim())
                        .addValue("a", r.active() == null || r.active()).addValue("id", id)), "Department");
        audit.record("UPDATE", "DEPARTMENT", id, null, r);
    }

    // ---- Vendors ----
    @GetMapping("/vendors")
    @PreAuthorize(Roles.READ)
    public List<Map<String, Object>> vendors(@RequestParam(required = false) String type,
                                             @RequestParam(defaultValue = "false") boolean includeInactive) {
        StringBuilder sql = new StringBuilder("SELECT id, name, vendor_type, contact_person, phone, email, gstin, address, is_active, created_at FROM vendors WHERE 1=1");
        MapSqlParameterSource p = new MapSqlParameterSource();
        if (!includeInactive) {
            sql.append(" AND is_active");
        }
        if (type != null && !type.isBlank()) {
            sql.append(" AND vendor_type = :t");
            p.addValue("t", type);
        }
        return jdbc.query(sql + " ORDER BY name", p, Rows::map);
    }

    @PostMapping("/vendors")
    @PreAuthorize(Roles.WRITE)
    @Transactional
    public Map<String, Object> createVendor(@Valid @RequestBody VendorRequest r) {
        Long id = jdbc.queryForObject("""
                INSERT INTO vendors (name, vendor_type, contact_person, phone, email, gstin, address)
                VALUES (:name, :type, :contact, :phone, :email, :gstin, :address) RETURNING id""", vendorParams(r), Long.class);
        audit.record("CREATE", "VENDOR", id, null, r);
        return Map.of("id", id);
    }

    @PutMapping("/vendors/{id}")
    @PreAuthorize(Roles.MANAGE)
    @Transactional
    public void updateVendor(@PathVariable long id, @Valid @RequestBody VendorRequest r) {
        must(jdbc.update("""
                UPDATE vendors SET name = :name, vendor_type = :type, contact_person = :contact, phone = :phone, email = :email,
                    gstin = :gstin, address = :address, is_active = :active WHERE id = :id""",
                vendorParams(r).addValue("id", id).addValue("active", r.active() == null || r.active())), "Vendor");
        audit.record("UPDATE", "VENDOR", id, null, r);
    }

    private static MapSqlParameterSource vendorParams(VendorRequest r) {
        return new MapSqlParameterSource("name", r.name().trim()).addValue("type", r.vendorType())
                .addValue("contact", r.contactPerson()).addValue("phone", r.phone()).addValue("email", r.email())
                .addValue("gstin", r.gstin() == null || r.gstin().isBlank() ? null : r.gstin()).addValue("address", r.address());
    }

    // ---- Drivers ----
    @GetMapping("/drivers")
    @PreAuthorize(Roles.READ)
    public List<Map<String, Object>> drivers(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return jdbc.query("""
                SELECT d.id, d.full_name, d.phone, d.licence_number, d.licence_expiry, d.vendor_id, v.name AS vendor_name,
                       d.remarks, d.is_active, d.created_at,
                       (SELECT string_agg(x.registration_number, ', ') FROM vehicles x WHERE x.current_driver_id = d.id) AS vehicles
                FROM drivers d LEFT JOIN vendors v ON v.id = d.vendor_id""" + (includeInactive ? "" : " WHERE d.is_active") + " ORDER BY d.full_name",
                Rows::map);
    }

    @PostMapping("/drivers")
    @PreAuthorize(Roles.WRITE)
    @Transactional
    public Map<String, Object> createDriver(@Valid @RequestBody DriverRequest r) {
        Long id = jdbc.queryForObject("""
                INSERT INTO drivers (full_name, phone, licence_number, licence_expiry, vendor_id, remarks)
                VALUES (:name, :phone, :lic, :exp, :vendor, :remarks) RETURNING id""", driverParams(r), Long.class);
        audit.record("CREATE", "DRIVER", id, null, r);
        return Map.of("id", id);
    }

    @PutMapping("/drivers/{id}")
    @PreAuthorize(Roles.WRITE)
    @Transactional
    public void updateDriver(@PathVariable long id, @Valid @RequestBody DriverRequest r) {
        must(jdbc.update("""
                UPDATE drivers SET full_name = :name, phone = :phone, licence_number = :lic, licence_expiry = :exp, vendor_id = :vendor,
                    remarks = :remarks, is_active = :active WHERE id = :id""",
                driverParams(r).addValue("id", id).addValue("active", r.active() == null || r.active())), "Driver");
        audit.record("UPDATE", "DRIVER", id, null, r);
    }

    private static MapSqlParameterSource driverParams(DriverRequest r) {
        return new MapSqlParameterSource("name", r.fullName().trim()).addValue("phone", r.phone())
                .addValue("lic", r.licenceNumber() == null || r.licenceNumber().isBlank() ? null : r.licenceNumber().trim().toUpperCase())
                .addValue("exp", r.licenceExpiry()).addValue("vendor", r.vendorId()).addValue("remarks", r.remarks());
    }

    private static void must(int updated, String what) {
        if (updated == 0) {
            throw ApiException.notFound(what);
        }
    }
}
