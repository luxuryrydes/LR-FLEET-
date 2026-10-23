package com.lrtaxi.fleet.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrtaxi.fleet.calc.DocumentRules;
import com.lrtaxi.fleet.calc.RegistrationNormalizer;
import com.lrtaxi.fleet.common.ApiException;
import com.lrtaxi.fleet.common.Rows;
import com.lrtaxi.fleet.security.CurrentUser;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Commits a DOCUMENTS import (the permit / pollution / insurance register). Each row carries one vehicle's end dates.
 * A later end date than the current document is a renewal and is added as a new document (the old one stays as
 * history); the same date is unchanged; an earlier date is ignored unless policy REPLACE corrects it (the current one is
 * voided with the reason). NA / Not Required / LTT are recorded as "not required". Vehicles must already exist.
 */
@Service
public class DocumentImportService {

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate nested;

    public DocumentImportService(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.nested = new TransactionTemplate(tm);
        this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    /** @return {documents added, documents corrected, documents unchanged} */
    public int[] commit(long batchId, boolean replace, String reason, MasterImportService.RowErrors errors) {
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT row_number, parsed FROM import_staging_rows
                WHERE batch_id = :id AND status IN ('VALID', 'WARNING') AND parsed IS NOT NULL ORDER BY id""",
                Map.of("id", batchId), Rows::map);
        int[] counts = new int[3];
        String why = "Documents import #" + batchId + (reason == null || reason.isBlank() ? "" : ": " + reason);
        for (Map<String, Object> r : rows) {
            int row = ((Number) r.get("rowNumber")).intValue();
            JsonNode p = (JsonNode) r.get("parsed");
            try {
                int[] c = nested.execute(s -> apply(batchId, p, replace, why));
                for (int i = 0; i < 3; i++) {
                    counts[i] += c == null ? 0 : c[i];
                }
            } catch (ApiException e) {
                errors.add(row, "registration", e.code(), e.getMessage(), text(p, "registration"));
            } catch (IllegalArgumentException | org.springframework.dao.DataAccessException e) {
                errors.add(row, null, "INVALID_ROW", rootMessage(e), text(p, "registration"));
            }
        }
        return counts;
    }

    private int[] apply(long batchId, JsonNode p, boolean replace, String why) {
        String reg = RegistrationNormalizer.normalize(text(p, "registration"));
        List<Map<String, Object>> v = jdbc.query("""
                SELECT id, registration_class FROM vehicles WHERE registration_normalized = :r AND status NOT IN ('SOLD', 'SCRAPPED')
                ORDER BY duplicate_registration_reason NULLS FIRST, id LIMIT 1""", Map.of("r", reg), Rows::map);
        if (v.isEmpty()) {
            throw ApiException.badRequest("VEHICLE_NOT_FOUND", reg + " is not in the vehicle master (or is sold). Add it under Vehicles first.");
        }
        long vehicleId = ((Number) v.get(0).get("id")).longValue();
        String regClass = text(p, "registrationClass");
        if (v.get(0).get("registrationClass") == null && ("COMMERCIAL".equals(regClass) || "PRIVATE".equals(regClass))) {
            jdbc.update("UPDATE vehicles SET registration_class = :c, updated_at = now() WHERE id = :id", Map.of("c", regClass, "id", vehicleId));
        }
        int[] c = new int[3];
        for (JsonNode d : p.path("documents")) {
            String type = text(d, "docType");
            if (!DocumentRules.VEHICLE_TYPES.containsKey(type)) {
                throw new IllegalArgumentException("Unknown document type " + type);
            }
            boolean na = d.path("notApplicable").asBoolean(false);
            LocalDate expiry = d.hasNonNull("expiryDate") ? LocalDate.parse(d.get("expiryDate").asText()) : null;
            LocalDate issue = d.hasNonNull("issueDate") ? LocalDate.parse(d.get("issueDate").asText()) : null;
            List<Map<String, Object>> cur = jdbc.query("""
                    SELECT id, expiry_date, issue_date, not_applicable FROM vehicle_documents
                    WHERE vehicle_id = :v AND doc_type = :t AND NOT is_void AND verification_status <> 'REJECTED'
                    ORDER BY expiry_date DESC NULLS LAST, id DESC LIMIT 1""", Map.of("v", vehicleId, "t", type), Rows::map);
            Map<String, Object> current = cur.isEmpty() ? null : cur.get(0);
            if (current == null) {
                insert(batchId, vehicleId, type, issue, expiry, na, text(d, "note"));
                c[0]++;
            } else if (na || expiry == null) {
                c[2]++;                                     // a recorded document is never downgraded to "not required"
            } else {
                LocalDate have = (LocalDate) current.get("expiryDate");
                if (Objects.equals(have, expiry)) {
                    c[2]++;
                } else if (have == null || expiry.isAfter(have)) {
                    insert(batchId, vehicleId, type, issue, expiry, false, null);    // renewal
                    c[0]++;
                } else if (replace) {
                    jdbc.update("UPDATE vehicle_documents SET is_void = TRUE, remarks = :r, updated_at = now() WHERE id = :id",
                            new MapSqlParameterSource("r", "Corrected by " + why).addValue("id", current.get("id")));
                    insert(batchId, vehicleId, type, issue, expiry, false, null);
                    c[1]++;
                } else {
                    c[2]++;                                 // the app already holds a later end date
                }
            }
        }
        return c;
    }

    private void insert(long batchId, long vehicleId, String type, LocalDate issue, LocalDate expiry, boolean na, String note) {
        jdbc.update("""
                INSERT INTO vehicle_documents (vehicle_id, doc_type, issue_date, expiry_date, not_applicable, remarks, source, import_batch_id, created_by)
                VALUES (:v, :t, :issue, :expiry, :na, :remarks, 'IMPORT', :b, :uid)""",
                new MapSqlParameterSource("v", vehicleId).addValue("t", type).addValue("issue", issue).addValue("expiry", na ? null : expiry)
                        .addValue("na", na).addValue("remarks", note).addValue("b", batchId).addValue("uid", CurrentUser.idOrNull()));
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return String.valueOf(t.getMessage());
    }

    private static String text(JsonNode p, String f) {
        return p.hasNonNull(f) && !p.get(f).asText().isBlank() ? p.get(f).asText().trim() : null;
    }
}
