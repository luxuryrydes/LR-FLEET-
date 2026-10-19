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

    static String master(String reg, String label, String name, String phone) {
        return "{\"registration\":\"" + reg + "\",\"model\":\"Ciaz\",\"fuelType\":\"CNG / PETROL\",\"modelYear\":2021,\"operatorLabel\":\""
                + label + "\",\"driverName\":" + (name == null ? "null" : "\"" + name + "\"") + ",\"driverPhone\":"
                + (phone == null ? "null" : "\"" + phone + "\"") + ",\"department\":\"MHA\",\"registrationClass\":\"Private\"}";
    }

    @Test
    void vehicleMasterSetsStatusOperatorAndGpsTrackingAndOnlyOwnDriverKmAreImported() throws Exception {
        long b = parsedBatch("VEHICLE_MASTER", "{}", new Object[][]{
                {2, null, null, master("HR51MA0001", "DRIVER", "MASTER DRIVER", "9000000001")},
                {3, null, null, master("HR51MA0002", "DRIVER", null, null)},
                {4, null, null, master("HR51MA0003", "WORKSHOP CH", null, null)},
                {5, null, null, master("HR51MA0004", "VENDOR", "Master Vendor", "9000000004")},
                {6, null, null, master("HR51MA0005", "GOVT DRIVER", "GOVT ONE", "9000000005")}});
        JsonNode r = call(post("/api/imports/" + b + "/commit"), adminToken, Map.of("policy", "SKIP"), status().isOk());
        assertThat(r.get("insertedRows").asInt()).isEqualTo(5);
        List<Map<String, Object>> v = jdbc.queryForList("""
                SELECT registration_normalized AS reg, status, operator_type, gps_km_tracked, ownership_type, current_driver_id, vendor_id,
                       registration_class, make FROM vehicles WHERE registration_normalized LIKE 'HR51MA%' ORDER BY 1""");
        assertThat(v).extracting(m -> m.get("status")).containsExactly("ACTIVE", "IDLE", "WORKSHOP", "ACTIVE", "ACTIVE");
        assertThat(v).extracting(m -> m.get("gps_km_tracked")).containsExactly(true, false, false, false, false);
        assertThat(v).extracting(m -> m.get("operator_type")).containsExactly("OWN_DRIVER", "OWN_DRIVER", "NONE", "VENDOR", "GOVT_DRIVER");
        assertThat(v.get(3).get("ownership_type")).isEqualTo("VENDOR_ATTACHED");
        assertThat(v.get(3).get("vendor_id")).isNotNull();
        assertThat(v.get(0).get("current_driver_id")).isNotNull();
        assertThat(v.get(0).get("registration_class")).isEqualTo("PRIVATE");
        assertThat(v.get(0).get("make")).isEqualTo("Maruti Suzuki");

        // Same sheet again: nothing duplicated
        long b2 = parsedBatch("VEHICLE_MASTER", "{}", new Object[][]{{2, null, null, master("HR51MA0001", "DRIVER", "MASTER DRIVER", "9000000001")}});
        JsonNode r2 = call(post("/api/imports/" + b2 + "/commit"), adminToken, Map.of("policy", "SKIP"), status().isOk());
        assertThat(r2.get("insertedRows").asInt()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM drivers WHERE full_name = 'MASTER DRIVER'", Long.class)).isEqualTo(1);

        // GPS km: only the own-driver vehicle is imported
        long own = ((Number) jdbc.queryForObject("SELECT id FROM vehicles WHERE registration_normalized = 'HR51MA0001'", Long.class)).longValue();
        long vendor = ((Number) jdbc.queryForObject("SELECT id FROM vehicles WHERE registration_normalized = 'HR51MA0004'", Long.class)).longValue();
        long g = parsedBatch("GPS_KM", "{\"recordMode\":\"DAILY_TOTAL\"}", new Object[][]{{2, own, D0, gps("100")}, {3, vendor, D0, gps("90")}});
        JsonNode gr = call(post("/api/imports/" + g + "/commit"), adminToken, Map.of("policy", "SKIP"), status().isOk());
        assertThat(gr.get("insertedRows").asInt()).isEqualTo(1);
        assertThat(gr.get("excludedRows").asInt()).isEqualTo(1);
        assertThat(currentKm(vendor)).isNull();

        // Sold: requires a dealer, keeps history, re-import does not revive it
        call(post("/api/vehicles/" + own + "/dispose"), adminToken, Map.of("type", "SOLD", "date", D0.toString()), status().isBadRequest());
        call(post("/api/vehicles/" + own + "/dispose"), adminToken,
                Map.of("type", "SOLD", "date", D0.toString(), "party", "Test Dealer", "amount", "350000"), status().isOk());
        long b3 = parsedBatch("VEHICLE_MASTER", "{}", new Object[][]{{2, null, null, master("HR51MA0001", "DRIVER", "MASTER DRIVER", "9000000001")}});
        call(post("/api/imports/" + b3 + "/commit"), adminToken, Map.of("policy", "REPLACE", "reason", "refresh"), status().isOk());
        Map<String, Object> sold = jdbc.queryForMap("SELECT status, disposal_party, gps_km_tracked FROM vehicles WHERE id = ?", own);
        assertThat(sold.get("status")).isEqualTo("SOLD");
        assertThat(sold.get("disposal_party")).isEqualTo("Test Dealer");
        assertThat(sold.get("gps_km_tracked")).isEqualTo(false);
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
