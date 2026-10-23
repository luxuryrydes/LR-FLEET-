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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Commits a VEHICLE_MASTER import (the company's vehicle / driver sheet). Status, operator type and GPS tracking are
 * decided by MasterRules. New vehicles are always added; existing vehicles whose details changed are updated with policy
 * REPLACE (the default for this import), and only counted as "changes not applied" with SKIP. Vehicles already marked
 * sold/disposed keep that status; deleted vehicles are never brought back.
 */
@Service
public class MasterImportService {

    public interface RowErrors {
        void add(int row, String field, String code, String message, String raw);
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final VehicleService vehicles;
    private final TransactionTemplate nested;
    private final TransactionTemplate dryRun;

    /** kind: 0 = added, 1 = updated, 2 = unchanged / skipped, 3 = changed in the sheet but kept (policy SKIP). */
    record Outcome(int kind, String registration, List<String> changes) {
    }

    public MasterImportService(NamedParameterJdbcTemplate jdbc, VehicleService vehicles, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.vehicles = vehicles;
        this.nested = new TransactionTemplate(tm);
        this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.dryRun = new TransactionTemplate(tm);
        this.dryRun.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * What a commit with updates would do, without saving anything (the whole run is rolled back): new vehicles,
     * vehicles that will be updated with the fields that change, unchanged ones, and vehicles in the app missing from the sheet.
     */
    public Map<String, Object> review(long batchId) {
        List<Map<String, Object>> rows = staged(batchId);
        List<Map<String, Object>> updates = new ArrayList<>();
        List<String> added = new ArrayList<>();
        int[] counts = new int[4];
        List<String> problems = new ArrayList<>();
        List<String> sheetRegs = new ArrayList<>();
        dryRun.executeWithoutResult(status -> {
            for (Map<String, Object> r : rows) {
                JsonNode p = (JsonNode) r.get("parsed");
                sheetRegs.add(RegistrationNormalizer.normalize(text(p, "registration")));
                try {
                    Outcome o = nested.execute(s -> apply(p, true, "review"));
                    counts[o.kind()]++;
                    if (o.kind() == 0) {
                        added.add(o.registration());
                    } else if (o.kind() == 1) {
                        Map<String, Object> u = new LinkedHashMap<>();
                        u.put("registration", o.registration());
                        u.put("changes", o.changes());
                        updates.add(u);
                    }
                } catch (RuntimeException e) {
                    problems.add(text(p, "registration") + ": " + e.getMessage());
                }
            }
            status.setRollbackOnly();
        });
        List<String> notInSheet = sheetRegs.isEmpty() ? List.of() : jdbc.queryForList("""
                SELECT registration_number FROM vehicles WHERE status NOT IN ('SOLD', 'SCRAPPED', 'DELETED')
                  AND registration_normalized NOT IN (:regs) ORDER BY registration_normalized""", Map.of("regs", sheetRegs), String.class);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("newVehicles", counts[0]);
        out.put("updatedVehicles", counts[1]);
        out.put("unchangedVehicles", counts[2]);
        out.put("added", added.size() > 200 ? added.subList(0, 200) : added);
        out.put("updates", updates.size() > 500 ? updates.subList(0, 500) : updates);
        out.put("problems", problems);
        out.put("notInSheet", notInSheet);
        return out;
    }

    private List<Map<String, Object>> staged(long batchId) {
        return jdbc.query("""
                SELECT row_number, parsed FROM import_staging_rows
                WHERE batch_id = :id AND status NOT IN ('REJECTED', 'EXCLUDED') AND parsed IS NOT NULL ORDER BY row_number""",
                Map.of("id", batchId), Rows::map);
    }

    /** @return {inserted, updated, unchanged, changed in the sheet but kept (SKIP)} */
    public int[] commit(long batchId, boolean replace, String reason, RowErrors errors) {
        List<Map<String, Object>> rows = staged(batchId);
        int[] counts = new int[4];
        String why = "Vehicle master import #" + batchId + (reason == null || reason.isBlank() ? "" : ": " + reason);
        for (Map<String, Object> r : rows) {
            int row = ((Number) r.get("rowNumber")).intValue();
            JsonNode p = (JsonNode) r.get("parsed");
            try {
                Outcome outcome = nested.execute(s -> apply(p, replace, why));
                counts[outcome == null ? 2 : outcome.kind()]++;
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

    private Outcome apply(JsonNode p, boolean replace, String why) {
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
            return new Outcome(0, reg, List.of());
        }
        long id = existing.get(0);
        Map<String, Object> v = vehicles.get(id);
        if ("DELETED".equals(v.get("status"))) {
            return new Outcome(2, reg, List.of());      // deleted on purpose: the sheet never brings it back (restore it instead)
        }
        boolean disposed = v.get("disposalType") != null;
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
        List<String> changes = changes(v, req);
        if (changes.isEmpty()) {
            disposeIfSold(id, soldDate, text(p, "soldTo"));
            return new Outcome(2, reg, changes);
        }
        if (!replace) {
            return new Outcome(3, reg, changes);
        }
        vehicles.update(id, req);
        disposeIfSold(id, soldDate, text(p, "soldTo"));
        return new Outcome(1, reg, changes);
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

    /** Which details differ between the saved vehicle and the sheet (empty = nothing to update). */
    static List<String> changes(Map<String, Object> v, VehicleRequest r) {
        List<String> c = new ArrayList<>();
        diff(c, "make", v.get("make"), r.make());
        diff(c, "model", v.get("model"), r.model());
        diff(c, "model year", num(v.get("modelYear")), num(r.modelYear()));
        diff(c, "fuel", v.get("fuelType"), r.fuelType());
        diff(c, "category", v.get("categoryCode"), r.categoryCode());
        diff(c, "ownership", v.get("ownershipType"), r.ownershipType());
        diff(c, "vendor", num(v.get("vendorId")), num(r.vendorId()));
        diff(c, "driver", num(v.get("currentDriverId")), num(r.currentDriverId()));
        diff(c, "department", num(v.get("currentDepartmentId")), num(r.currentDepartmentId()));
        diff(c, "status", v.get("status"), r.status());
        diff(c, "driver type", v.get("operatorType"), r.operatorType());
        diff(c, "GPS km tracking", v.get("gpsKmTracked"), r.gpsKmTracked());
        diff(c, "commercial / private", v.get("registrationClass"), r.registrationClass());
        diff(c, "Vendor / Driver label", v.get("assignmentLabel"), r.assignmentLabel());
        return c;
    }

    private static void diff(List<String> out, String label, Object a, Object b) {
        if (!Objects.equals(a, b)) {
            out.add(label);
        }
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
