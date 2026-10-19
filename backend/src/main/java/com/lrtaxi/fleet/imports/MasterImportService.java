package com.lrtaxi.fleet.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrtaxi.fleet.calc.MasterRules;
import com.lrtaxi.fleet.calc.RegistrationNormalizer;
import com.lrtaxi.fleet.common.ApiException;
import com.lrtaxi.fleet.common.Rows;
import com.lrtaxi.fleet.vehicle.VehicleRequest;
import com.lrtaxi.fleet.vehicle.VehicleService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Commits a VEHICLE_MASTER import (the company's vehicle / driver sheet). Status, operator type and GPS tracking are
 * decided by MasterRules. New vehicles are always added; existing vehicles are updated only with policy REPLACE.
 * Vehicles already marked sold/disposed keep that status.
 */
@Service
public class MasterImportService {

    public interface RowErrors {
        void add(int row, String field, String code, String message, String raw);
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final VehicleService vehicles;
    private final TransactionTemplate nested;

    public MasterImportService(NamedParameterJdbcTemplate jdbc, VehicleService vehicles, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.vehicles = vehicles;
        this.nested = new TransactionTemplate(tm);
        this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    /** @return {inserted, updated, unchanged} */
    public int[] commit(long batchId, boolean replace, String reason, RowErrors errors) {
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT row_number, parsed FROM import_staging_rows
                WHERE batch_id = :id AND status NOT IN ('REJECTED', 'EXCLUDED') AND parsed IS NOT NULL ORDER BY row_number""",
                Map.of("id", batchId), Rows::map);
        int[] counts = new int[3];
        String why = "Vehicle master import #" + batchId + (reason == null || reason.isBlank() ? "" : ": " + reason);
        for (Map<String, Object> r : rows) {
            int row = ((Number) r.get("rowNumber")).intValue();
            JsonNode p = (JsonNode) r.get("parsed");
            try {
                Integer outcome = nested.execute(s -> apply(p, replace, why));
                counts[outcome == null ? 2 : outcome]++;
            } catch (ApiException e) {
                errors.add(row, null, e.code(), e.getMessage(), text(p, "registration"));
            } catch (IllegalArgumentException | org.springframework.dao.DataAccessException e) {
                errors.add(row, null, "INVALID_ROW", String.valueOf(rootMessage(e)), text(p, "registration"));
            }
        }
        return counts;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage();
    }

    /** 0 = inserted, 1 = updated, 2 = unchanged/skipped. */
    private int apply(JsonNode p, boolean replace, String why) {
        String reg = RegistrationNormalizer.normalize(text(p, "registration"));
        if (reg.length() < 4) {
            throw ApiException.badRequest("INVALID_REGISTRATION", "Registration number is missing or too short");
        }
        MasterRules.ModelInfo mi = MasterRules.model(text(p, "model"));
        String make = firstNonBlank(text(p, "make"), mi.make());
        String category = categoryOrOther(firstNonBlank(text(p, "category"), mi.category()));
        String fuel = MasterRules.fuelType(text(p, "fuelType"));
        if (fuel == null) {
            throw ApiException.badRequest("INVALID_FUEL_TYPE", "Unknown fuel type '" + text(p, "fuelType") + "'");
        }
        String name = text(p, "driverName");
        String phone = text(p, "driverPhone");
        MasterRules.Derived d = MasterRules.derive(text(p, "operatorLabel"), name);
        Long deptId = department(text(p, "department"));
        Long driverId = null;
        Long vendorId = null;
        if (name != null) {
            if (d.operatorType() == MasterRules.OperatorType.VENDOR) {
                vendorId = vendor(name, phone);
            } else {
                driverId = driver(name, phone);
            }
        }
        String regClass = regClass(text(p, "registrationClass"));
        Integer year = p.hasNonNull("modelYear") ? Integer.valueOf(p.get("modelYear").asInt()) : null;
        LocalDate soldDate = p.hasNonNull("soldDate") ? LocalDate.parse(p.get("soldDate").asText()) : null;
        String label = text(p, "operatorLabel");

        List<Long> existing = jdbc.queryForList(
                "SELECT id FROM vehicles WHERE registration_normalized = :r AND duplicate_registration_reason IS NULL", Map.of("r", reg), Long.class);
        if (existing.isEmpty()) {
            long id = vehicles.create(new VehicleRequest(reg, null, make, mi.model(), year, fuel, category, d.ownership(), vendorId, driverId,
                    deptId, null, d.status(), null, null, null, null, null, null, null, text(p, "chassisNumber"), text(p, "engineNumber"),
                    text(p, "gpsDeviceName"), text(p, "remarks"), null, null, null, why, d.operatorType().name(), d.gpsKmTracked(), regClass,
                    trim(label, 60)));
            disposeIfSold(id, soldDate, text(p, "soldTo"));
            return 0;
        }
        long id = existing.get(0);
        Map<String, Object> v = vehicles.get(id);
        boolean disposed = v.get("disposalType") != null;
        if (!replace) {
            return 2;
        }
        String status = disposed ? (String) v.get("status") : d.status();
        boolean tracked = !disposed && d.gpsKmTracked();
        VehicleRequest req = new VehicleRequest(reg, null, make, mi.model(), year != null ? year : (v.get("modelYear") == null ? null : Integer.valueOf(((Number) v.get("modelYear")).intValue())), fuel, category,
                d.ownership(), vendorId, driverId, deptId, (String) v.get("customerName"), status, (BigDecimal) v.get("expectedMileage"),
                intOrNull(v.get("serviceIntervalKm")), intOrNull(v.get("serviceIntervalMonths")), (BigDecimal) v.get("tankCapacity"),
                (BigDecimal) v.get("batteryCapacityKwh"), (LocalDate) v.get("purchaseDate"), (BigDecimal) v.get("purchaseCost"),
                firstNonBlank(text(p, "chassisNumber"), (String) v.get("chassisNumber")),
                firstNonBlank(text(p, "engineNumber"), (String) v.get("engineNumber")),
                firstNonBlank(text(p, "gpsDeviceName"), (String) v.get("gpsDeviceName")),
                firstNonBlank(text(p, "remarks"), (String) v.get("remarks")), null, null, null, why, d.operatorType().name(), tracked,
                regClass != null ? regClass : (String) v.get("registrationClass"), trim(label, 60));
        if (same(v, req)) {
            disposeIfSold(id, soldDate, text(p, "soldTo"));
            return 2;
        }
        vehicles.update(id, req);
        disposeIfSold(id, soldDate, text(p, "soldTo"));
        return 1;
    }

    private void disposeIfSold(long id, LocalDate soldDate, String soldTo) {
        if (soldDate == null) {
            return;
        }
        Map<String, Object> v = vehicles.get(id);
        if (v.get("disposalType") == null) {
            vehicles.dispose(id, new VehicleService.Disposal("SOLD", soldDate, soldTo == null ? "Not recorded" : soldTo, null,
                    "From vehicle master import"));
        }
    }

    private static boolean same(Map<String, Object> v, VehicleRequest r) {
        return Objects.equals(v.get("make"), r.make()) && Objects.equals(v.get("model"), r.model())
                && Objects.equals(num(v.get("modelYear")), num(r.modelYear())) && Objects.equals(v.get("fuelType"), r.fuelType())
                && Objects.equals(v.get("categoryCode"), r.categoryCode()) && Objects.equals(v.get("ownershipType"), r.ownershipType())
                && Objects.equals(num(v.get("vendorId")), num(r.vendorId())) && Objects.equals(num(v.get("currentDriverId")), num(r.currentDriverId()))
                && Objects.equals(num(v.get("currentDepartmentId")), num(r.currentDepartmentId())) && Objects.equals(v.get("status"), r.status())
                && Objects.equals(v.get("operatorType"), r.operatorType()) && Objects.equals(v.get("gpsKmTracked"), r.gpsKmTracked())
                && Objects.equals(v.get("registrationClass"), r.registrationClass()) && Objects.equals(v.get("assignmentLabel"), r.assignmentLabel());
    }

    private static Integer intOrNull(Object o) {
        return o == null ? null : ((Number) o).intValue();
    }

    private static Long num(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }

    private Long department(String name) {
        if (name == null) {
            return null;
        }
        List<Long> found = jdbc.queryForList("SELECT id FROM departments WHERE lower(name) = lower(:n)", Map.of("n", name), Long.class);
        if (!found.isEmpty()) {
            return found.get(0);
        }
        String base = name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
        base = base.isEmpty() ? "DEPT" : base.substring(0, Math.min(26, base.length()));
        String code = base;
        for (int i = 2; Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM departments WHERE code = :c)",
                Map.of("c", code), Boolean.class)); i++) {
            code = base + "_" + i;
        }
        return jdbc.queryForObject("INSERT INTO departments (code, name) VALUES (:c, :n) RETURNING id",
                new MapSqlParameterSource("c", code).addValue("n", name), Long.class);
    }

    private Long driver(String name, String phone) {
        String n = name.trim();
        List<Long> found = phone != null
                ? jdbc.queryForList("SELECT id FROM drivers WHERE phone = :p AND upper(full_name) = upper(:n) ORDER BY id", Map.of("p", phone, "n", n), Long.class)
                : jdbc.queryForList("SELECT id FROM drivers WHERE phone IS NULL AND upper(full_name) = upper(:n) ORDER BY id", Map.of("n", n), Long.class);
        if (!found.isEmpty()) {
            return found.get(0);
        }
        return jdbc.queryForObject("INSERT INTO drivers (full_name, phone) VALUES (:n, :p) RETURNING id",
                new MapSqlParameterSource("n", n).addValue("p", phone), Long.class);
    }

    private Long vendor(String name, String phone) {
        String n = name.trim();
        List<Long> found = jdbc.queryForList("SELECT id FROM vendors WHERE lower(name) = lower(:n) AND vendor_type = 'VEHICLE_VENDOR'",
                Map.of("n", n), Long.class);
        if (!found.isEmpty()) {
            return found.get(0);
        }
        return jdbc.queryForObject("INSERT INTO vendors (name, vendor_type, phone) VALUES (:n, 'VEHICLE_VENDOR', :p) RETURNING id",
                new MapSqlParameterSource("n", n).addValue("p", phone), Long.class);
    }

    private String categoryOrOther(String code) {
        Boolean ok = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM vehicle_categories WHERE code = :c)", Map.of("c", code), Boolean.class);
        return Boolean.TRUE.equals(ok) ? code : "OTHER";
    }

    private static String regClass(String raw) {
        if (raw == null) {
            return null;
        }
        String k = raw.trim().toUpperCase(Locale.ROOT);
        return k.startsWith("PRIV") ? "PRIVATE" : k.startsWith("COMM") ? "COMMERCIAL" : null;
    }

    private static String text(JsonNode p, String f) {
        return p.hasNonNull(f) && !p.get(f).asText().isBlank() ? p.get(f).asText().trim() : null;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static String trim(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }
}
