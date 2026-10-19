package com.lrtaxi.fleet.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Import transactions, repeat uploads and correction history. */
class ImportCommitIT extends AbstractIT {

    static String gps(String km) {
        return "{\"vehicleRaw\":\"x\",\"km\":\"" + km + "\"}";
    }

    BigDecimal currentKm(long vehicleId) {
        return jdbc.queryForObject("SELECT sum(distance_km) FROM gps_daily_km WHERE vehicle_id = ? AND is_current", BigDecimal.class, vehicleId);
    }

    @Test
    void repeatedGpsImportNeverDoubleCountsAndReplacementIsAudited() throws Exception {
        long v = createVehicle("DL1RT1001", "CNG");
        Object[][] rows = {
                {2, v, D0, gps("120.5")},
                {3, v, D0, gps("120.5"), "DUPLICATE"},      // repeated copy of the same daily total
                {4, v, D0.plusDays(1), gps("80")}};
        long b1 = parsedBatch("GPS_KM", "{\"recordMode\":\"DAILY_TOTAL\"}", rows);
        JsonNode r1 = call(post("/api/imports/" + b1 + "/commit"), adminToken, Map.of("policy", "SKIP"), status().isOk());
        assertThat(r1.get("status").asText()).isEqualTo("COMMITTED");
        assertThat(r1.get("insertedRows").asInt()).isEqualTo(2);
        assertThat(currentKm(v)).isEqualByComparingTo("200.5");

        // Same file again: nothing added
        long b2 = parsedBatch("GPS_KM", "{\"recordMode\":\"DAILY_TOTAL\"}", rows);
        JsonNode r2 = call(post("/api/imports/" + b2 + "/commit"), adminToken, Map.of("policy", "SKIP"), status().isOk());
        assertThat(r2.get("insertedRows").asInt()).isZero();
        assertThat(currentKm(v)).isEqualByComparingTo("200.5");

        // Different value, SKIP: existing kept
        long b3 = parsedBatch("GPS_KM", "{\"recordMode\":\"DAILY_TOTAL\"}", new Object[][]{{2, v, D0, gps("130")}});
        JsonNode review = getJson("/api/imports/" + b3 + "/review", adminToken);
        assertThat(review.get("existingConflictDays").asInt()).isEqualTo(1);
        call(post("/api/imports/" + b3 + "/commit"), adminToken, Map.of("policy", "SKIP"), status().isOk());
        assertThat(currentKm(v)).isEqualByComparingTo("200.5");

        // REPLACE without reason is refused; with reason replaces and keeps history
        long b4 = parsedBatch("GPS_KM", "{\"recordMode\":\"DAILY_TOTAL\"}", new Object[][]{{2, v, D0, gps("130")}});
        call(post("/api/imports/" + b4 + "/commit"), adminToken, Map.of("policy", "REPLACE"), status().isBadRequest());
        JsonNode r4 = call(post("/api/imports/" + b4 + "/commit"), adminToken,
                Map.of("policy", "REPLACE", "reason", "GPS vendor re-issued corrected report"), status().isOk());
        assertThat(r4.get("replacedRows").asInt()).isEqualTo(1);
        assertThat(currentKm(v)).isEqualByComparingTo("210");
        Map<String, Object> old = jdbc.queryForMap(
                "SELECT distance_km, supersede_reason, superseded_by_user FROM gps_daily_km WHERE vehicle_id = ? AND km_date = ? AND NOT is_current",
                v, D0);
        assertThat((BigDecimal) old.get("distance_km")).isEqualByComparingTo("120.5");
        assertThat(old.get("supersede_reason")).isEqualTo("GPS vendor re-issued corrected report");
        assertThat(old.get("superseded_by_user")).isNotNull();
        Long audits = jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE entity_type = 'GPS_DAILY_KM' AND action = 'REPLACE'", Long.class);
        assertThat(audits).isGreaterThanOrEqualTo(1);
    }

    @Test
    void conflictingDailyTotalsAreRejectedNotSummed() throws Exception {
        long v = createVehicle("DL1RT1002", "PETROL");
        long b = parsedBatch("GPS_KM", "{\"recordMode\":\"DAILY_TOTAL\"}", new Object[][]{
                {2, v, D0, gps("100")}, {3, v, D0, gps("90")}});
        call(post("/api/imports/" + b + "/commit"), adminToken, Map.of("policy", "SKIP"), status().isOk());
        assertThat(currentKm(v)).isNull();
        Long errors = jdbc.queryForObject("SELECT count(*) FROM import_row_errors WHERE batch_id = ? AND error_code = 'DAILY_TOTAL_CONFLICT'", Long.class, b);
        assertThat(errors).isEqualTo(2);
        Long alerts = jdbc.queryForObject("SELECT count(*) FROM alerts WHERE alert_type = 'DUPLICATE_GPS' AND vehicle_id = ?", Long.class, v);
        assertThat(alerts).isEqualTo(1);
    }

    @Test
    void tripRowsAreSummedOnce() throws Exception {
        long v = createVehicle("DL1RT1003", "DIESEL");
        String t1 = "{\"km\":\"40\",\"tripStart\":\"" + D0 + "T08:00:00\"}";
        String t2 = "{\"km\":\"35.5\",\"tripStart\":\"" + D0 + "T12:30:00\"}";
        long b = parsedBatch("GPS_KM", "{\"recordMode\":\"TRIP\"}", new Object[][]{{2, v, D0, t1}, {3, v, D0, t2}, {4, v, D0, t1}});
        call(post("/api/imports/" + b + "/commit"), adminToken, Map.of("policy", "SKIP"), status().isOk());
        assertThat(currentKm(v)).isEqualByComparingTo("75.5");
        Integer trips = jdbc.queryForObject("SELECT trip_count FROM gps_daily_km WHERE vehicle_id = ? AND is_current", Integer.class, v);
        assertThat(trips).isEqualTo(2);
    }

    @Test
    void fuelImportRejectsBadRowsKeepsGoodOnesAndIsRepeatSafe() throws Exception {
        long v = createVehicle("MH12AB2001", "CNG");
        String ok1 = "{\"date\":\"" + D0 + "\",\"energyType\":\"CNG\",\"quantity\":\"8\",\"invoiceAmount\":\"720\",\"odometerKm\":\"10000\",\"invoiceNumber\":\"INV-1\"}";
        String ok2 = "{\"date\":\"" + D0.plusDays(2) + "\",\"energyType\":\"CNG\",\"quantity\":\"10\",\"unitPrice\":\"90\",\"invoiceAmount\":\"850\",\"odometerKm\":\"10250\",\"invoiceNumber\":\"INV-2\"}";
        String wrongFuel = "{\"date\":\"" + D0 + "\",\"energyType\":\"DIESEL\",\"quantity\":\"8\",\"invoiceAmount\":\"700\"}";
        Object[][] rows = {{2, v, D0, ok1}, {3, v, D0.plusDays(2), ok2}, {4, v, D0, wrongFuel}};
        long b1 = parsedBatch("FUEL", "{}", rows);
        JsonNode r = call(post("/api/imports/" + b1 + "/commit"), adminToken, Map.of("policy", "SKIP"), status().isOk());
        assertThat(r.get("insertedRows").asInt()).isEqualTo(2);
        assertThat(r.get("rejectedRows").asInt()).isEqualTo(1);
        // invoice 850 vs 10 × 90 = 900: flagged, invoice kept
        Map<String, Object> t = jdbc.queryForMap("SELECT expense_amount, amount_mismatch FROM fuel_transactions WHERE invoice_number = 'INV-2'");
        assertThat((BigDecimal) t.get("expense_amount")).isEqualByComparingTo("850.00");
        assertThat(t.get("amount_mismatch")).isEqualTo(true);
        // mileage interval 250 km / 10 kg = 25 km/kg computed by the Java engine on commit
        BigDecimal mileage = jdbc.queryForObject("SELECT mileage FROM fuel_efficiency_intervals WHERE vehicle_id = ? AND status = 'ELIGIBLE'",
                BigDecimal.class, v);
        assertThat(mileage).isEqualByComparingTo("25");

        long b2 = parsedBatch("FUEL", "{}", rows);
        JsonNode again = call(post("/api/imports/" + b2 + "/commit"), adminToken, Map.of("policy", "SKIP"), status().isOk());
        assertThat(again.get("insertedRows").asInt()).isZero();
        Long count = jdbc.queryForObject("SELECT count(*) FROM fuel_transactions WHERE vehicle_id = ? AND NOT is_void", Long.class, v);
        assertThat(count).isEqualTo(2);
    }

    @Test
    void failedCommitLeavesNoPartialData() throws Exception {
        long v = createVehicle("MH12AB2002", "PETROL");
        // second row has an unparseable km value -> unexpected error -> full rollback
        long b = parsedBatch("GPS_KM", "{\"recordMode\":\"DAILY_TOTAL\"}", new Object[][]{
                {2, v, D0, gps("50")}, {3, v, D0.plusDays(1), gps("not-a-number")}});
        call(post("/api/imports/" + b + "/commit"), adminToken, Map.of("policy", "SKIP"), status().isConflict());
        assertThat(currentKm(v)).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM import_batches WHERE id = ?", String.class, b)).isEqualTo("FAILED");
        List<Map<String, Object>> staging = jdbc.queryForList("SELECT id FROM import_staging_rows WHERE batch_id = ?", b);
        assertThat(staging).hasSize(2);   // still available for review / retry
    }
}
