package com.lrtaxi.fleet.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrtaxi.fleet.calc.RegistrationNormalizer;
import com.lrtaxi.fleet.common.ApiException;
import com.lrtaxi.fleet.common.Rows;
import com.lrtaxi.fleet.odometer.OdometerService;
import com.lrtaxi.fleet.security.CurrentUser;
import com.lrtaxi.fleet.settings.SettingsService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Commits a SERVICE import (the vehicle service register: last service odometer / date / next service). Each row becomes
 * an interval-resetting service record, so the Service due page counts the next service from it; a dated row with an
 * odometer also becomes the odometer baseline (GPS km after that date are added on top). A row already saved (same
 * vehicle, date and odometer) is skipped, so the register can be imported again after every update.
 */
@Service
public class ServiceImportService {

    private final NamedParameterJdbcTemplate jdbc;
    private final SettingsService settings;
    private final OdometerService odometer;
    private final TransactionTemplate nested;

    public ServiceImportService(NamedParameterJdbcTemplate jdbc, SettingsService settings, OdometerService odometer, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.odometer = odometer;
        this.nested = new TransactionTemplate(tm);
        this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    /** @return {services added, 0, already saved} */
    public int[] commit(long batchId, MasterImportService.RowErrors errors, Set<Long> touched) {
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT row_number, parsed FROM import_staging_rows
                WHERE batch_id = :id AND status IN ('VALID', 'WARNING') AND parsed IS NOT NULL ORDER BY row_number""",
                Map.of("id", batchId), Rows::map);
        int[] counts = new int[3];
        for (Map<String, Object> r : rows) {
            int row = ((Number) r.get("rowNumber")).intValue();
            JsonNode p = (JsonNode) r.get("parsed");
            try {
                Boolean added = nested.execute(s -> apply(batchId, p, touched));
                counts[Boolean.TRUE.equals(added) ? 0 : 2]++;
            } catch (ApiException e) {
                errors.add(row, "registration", e.code(), e.getMessage(), text(p, "registration"));
            } catch (IllegalArgumentException | org.springframework.dao.DataAccessException e) {
                Throwable t = e;
                while (t.getCause() != null && t.getCause() != t) {
                    t = t.getCause();
                }
                errors.add(row, null, "INVALID_ROW", String.valueOf(t.getMessage()), text(p, "registration"));
            }
        }
        return counts;
    }

    private boolean apply(long batchId, JsonNode p, Set<Long> touched) {
        String reg = RegistrationNormalizer.normalize(text(p, "registration"));
        List<Map<String, Object>> v = jdbc.query("""
                SELECT v.id, coalesce(v.service_interval_km, c.service_interval_km) AS km,
                       coalesce(v.service_interval_months, c.service_interval_months) AS months
                FROM vehicles v JOIN vehicle_categories c ON c.code = v.category_code
                WHERE v.registration_normalized = :r AND v.status NOT IN ('SOLD', 'SCRAPPED', 'DELETED')
                ORDER BY v.duplicate_registration_reason NULLS FIRST, v.id LIMIT 1""", Map.of("r", reg), Rows::map);
        if (v.isEmpty()) {
            throw ApiException.badRequest("VEHICLE_NOT_FOUND", reg + " is not in the vehicle master (or is sold). Add it under Vehicles first.");
        }
        long vehicleId = ((Number) v.get(0).get("id")).longValue();
        LocalDate date = p.hasNonNull("serviceDate") ? LocalDate.parse(p.get("serviceDate").asText()) : null;
        BigDecimal odo = p.hasNonNull("odometerKm") ? new BigDecimal(p.get("odometerKm").asText()) : null;
        if (date == null && odo == null) {
            throw ApiException.badRequest("NO_SERVICE", "Neither a service date nor an odometer reading");
        }
        if (date != null && date.isAfter(LocalDate.now())) {
            throw ApiException.badRequest("FUTURE_DATE", "Service date " + date + " is in the future");
        }
        String category = text(p, "category") == null ? "ROUTINE_SERVICE" : text(p, "category");
        List<Boolean> resets = jdbc.queryForList("SELECT resets_service_interval FROM service_categories WHERE code = :c AND is_active",
                Map.of("c", category), Boolean.class);
        if (resets.isEmpty()) {
            throw ApiException.badRequest("UNKNOWN_CATEGORY", "Unknown service category " + category);
        }
        Integer same = jdbc.queryForObject("""
                SELECT count(*) FROM maintenance_records WHERE vehicle_id = :v AND NOT is_void AND category_code = :c
                  AND odometer_km IS NOT DISTINCT FROM CAST(:odo AS numeric)
                  AND (service_date IS NOT DISTINCT FROM CAST(:d AS date) OR (CAST(:undated AS boolean) AND odometer_km IS NOT NULL))""",
                new MapSqlParameterSource("v", vehicleId).addValue("c", category).addValue("odo", odo).addValue("d", date)
                        .addValue("undated", date == null), Integer.class);
        if (same != null && same > 0) {
            return false;
        }

        int intervalKm = v.get(0).get("km") == null ? settings.integer("service.default_interval_km", 10000) : ((Number) v.get(0).get("km")).intValue();
        int months = v.get(0).get("months") == null ? settings.integer("service.default_interval_months", 6) : ((Number) v.get(0).get("months")).intValue();
        boolean resetsInterval = Boolean.TRUE.equals(resets.get(0));
        BigDecimal computed = resetsInterval && odo != null ? odo.add(BigDecimal.valueOf(intervalKm)) : null;
        BigDecimal sheetNext = p.hasNonNull("nextServiceOdometer") ? new BigDecimal(p.get("nextServiceOdometer").asText()) : null;
        BigDecimal nextOdo = sheetNext != null ? sheetNext : computed;
        LocalDate sheetNextDate = p.hasNonNull("nextServiceDate") ? LocalDate.parse(p.get("nextServiceDate").asText()) : null;
        LocalDate nextDate = sheetNextDate != null ? sheetNextDate : (resetsInterval && date != null ? date.plusMonths(months) : null);
        boolean overridden = (sheetNext != null && (computed == null || sheetNext.compareTo(computed) != 0)) || sheetNextDate != null;
        BigDecimal cost = p.hasNonNull("totalCost") ? new BigDecimal(p.get("totalCost").asText()) : BigDecimal.ZERO;
        String description = text(p, "description") != null ? text(p, "description")
                : "Periodic service (service register import" + (date == null ? ", date not recorded)" : ")");

        Long id = jdbc.queryForObject("""
                INSERT INTO maintenance_records (vehicle_id, service_date, odometer_km, category_code, description, calculated_total, total_cost,
                    workshop_name, invoice_number, next_service_date, next_service_odometer, next_service_overridden, remarks, source,
                    import_batch_id, created_by, updated_by)
                VALUES (:v, :d, :odo, :cat, :desc, :cost, :cost, :workshop, :inv, :nextDate, :nextOdo, :overridden, :remarks, 'IMPORT',
                    :b, :uid, :uid) RETURNING id""",
                new MapSqlParameterSource("v", vehicleId).addValue("d", date).addValue("odo", odo).addValue("cat", category)
                        .addValue("desc", description.length() > 2000 ? description.substring(0, 2000) : description).addValue("cost", cost)
                        .addValue("workshop", text(p, "workshopName")).addValue("inv", text(p, "invoiceNumber"))
                        .addValue("nextDate", nextDate).addValue("nextOdo", nextOdo).addValue("overridden", overridden)
                        .addValue("remarks", text(p, "remarks")).addValue("b", batchId).addValue("uid", CurrentUser.idOrNull()), Long.class);
        if (date != null && odo != null) {
            odometer.addReading(vehicleId, date, odo, "SERVICE", id);
        }
        touched.add(vehicleId);
        return true;
    }

    private static String text(JsonNode p, String f) {
        return p.hasNonNull(f) && !p.get(f).asText().isBlank() ? p.get(f).asText().trim() : null;
    }
}
