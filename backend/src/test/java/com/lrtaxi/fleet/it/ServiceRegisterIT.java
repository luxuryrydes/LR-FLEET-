package com.lrtaxi.fleet.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ServiceRegisterIT extends AbstractIT {

    static String svc(String reg, LocalDate date, String odo, String next) {
        return "{\"registration\":\"" + reg + "\",\"serviceDate\":" + (date == null ? "null" : "\"" + date + "\"") + ",\"odometerKm\":"
                + (odo == null ? "null" : "\"" + odo + "\"") + ",\"nextServiceOdometer\":" + (next == null ? "null" : "\"" + next + "\"")
                + ",\"category\":\"ROUTINE_SERVICE\"}";
    }

    JsonNode due(String reg) throws Exception {
        for (JsonNode r : getJson("/api/service-status?vehicle=" + reg, adminToken)) {
            if (r.get("registrationNumber").asText().equals(reg)) {
                return r;
            }
        }
        throw new AssertionError(reg + " not in service status");
    }

    @Test
    void serviceRegisterImportDrivesTheServicePortal() throws Exception {
        long dated = createVehicle("DL1SR0001", "CNG");
        long undated = createVehicle("DL1SR0002", "CNG");
        LocalDate d = LocalDate.now().minusDays(28);
        long b = parsedBatch("SERVICE", "{}", new Object[][]{
                {9, dated, d, svc("DL1SR0001", d, "178024", "188024")},
                {10, undated, null, svc("DL1SR0002", null, "132806", "142806"), "WARNING"},
                {11, null, null, svc("DL9ZZ0009", d, "1000", null)}});
        JsonNode r = call(post("/api/imports/" + b + "/commit"), adminToken, Map.of("policy", "SKIP"), status().isOk());
        assertThat(r.get("insertedRows").asInt()).isEqualTo(2);
        assertThat(r.get("rejectedRows").asInt()).isEqualTo(1);              // not in the vehicle master

        // dated service: next at 188,024 km; its odometer is the baseline, GPS km after it are added
        jdbc.update("INSERT INTO gps_daily_km (vehicle_id, km_date, distance_km, record_mode, source, is_current) VALUES (?, ?, 9700, 'MANUAL', 'MANUAL', TRUE)",
                dated, d.plusDays(3));
        JsonNode s1 = due("DL1SR0001");
        assertThat(s1.get("lastServiceDate").asText()).isEqualTo(d.toString());
        assertThat(s1.get("nextServiceOdometer").decimalValue()).isEqualByComparingTo("188024");
        assertThat(s1.get("currentOdometer").decimalValue()).isEqualByComparingTo("187724");
        assertThat(s1.get("remainingKm").decimalValue()).isEqualByComparingTo("300");
        assertThat(s1.get("status").asText()).isEqualTo("DUE_SOON");
        assertThat(s1.get("daysSinceService").asInt()).isEqualTo(28);
        assertThat(s1.get("lastServiceSource").asText()).isEqualTo("IMPORT");

        // undated: km reminder kept, no date reminder; no odometer baseline yet
        JsonNode s2 = due("DL1SR0002");
        assertThat(s2.get("serviceDateRecorded").asBoolean()).isFalse();
        assertThat(s2.get("nextServiceOdometer").decimalValue()).isEqualByComparingTo("142806");
        assertThat(s2.get("nextServiceDate").isNull()).isTrue();
        assertThat(s2.get("status").asText()).isEqualTo("MISSING_BASELINE");
        assertThat(getJson("/api/maintenance/vehicle/" + undated, adminToken)).hasSize(1);

        // importing the same register again adds nothing
        long again = parsedBatch("SERVICE", "{}", new Object[][]{{9, dated, d, svc("DL1SR0001", d, "178024", "188024")},
                {10, undated, null, svc("DL1SR0002", null, "132806", "142806"), "WARNING"}});
        JsonNode r2 = call(post("/api/imports/" + again + "/commit"), adminToken, Map.of("policy", "SKIP"), status().isOk());
        assertThat(r2.get("insertedRows").asInt()).isZero();
        assertThat(r2.get("skippedRows").asInt()).isEqualTo(2);

        // job card scan on the last service
        String up = mvc.perform(multipart("/api/files").file(new MockMultipartFile("file", "jobcard.pdf", "application/pdf",
                        "%PDF-1.4\n% job card\n".getBytes())).param("purpose", "SERVICE_INVOICE").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long fileId = om.readTree(up).get("id").asLong();
        call(post("/api/maintenance/" + s1.get("lastServiceId").asLong() + "/file"), adminToken, Map.of("fileId", fileId), status().isOk());
        assertThat(due("DL1SR0001").get("lastServiceFileName").asText()).isEqualTo("jobcard.pdf");

        // a new service recorded from the portal restarts the interval
        call(post("/api/maintenance"), adminToken, Map.of("vehicleId", dated, "serviceDate", LocalDate.now().toString(), "odometerKm", "187800",
                "categoryCode", "ROUTINE_SERVICE", "description", "Periodic service"), status().isOk());
        JsonNode s3 = due("DL1SR0001");
        assertThat(s3.get("nextServiceOdometer").decimalValue()).isEqualByComparingTo("197800");
        assertThat(s3.get("status").asText()).isEqualTo("OK");
        assertThat(s3.get("lastServiceFileId").isNull()).isTrue();          // the new service has no scan yet
    }
}
