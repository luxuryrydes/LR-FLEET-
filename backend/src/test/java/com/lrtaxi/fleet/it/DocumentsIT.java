package com.lrtaxi.fleet.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DocumentsIT extends AbstractIT {

    static final LocalDate T = LocalDate.now();

    static String row(String reg, String regClass, String docs) {
        return "{\"registration\":\"" + reg + "\",\"registrationClass\":" + (regClass == null ? "null" : "\"" + regClass + "\"")
                + ",\"sheet\":\"Commercial cars\",\"documents\":[" + docs + "]}";
    }

    static String doc(String type, LocalDate expiry) {
        return "{\"docType\":\"" + type + "\",\"expiryDate\":\"" + expiry + "\"}";
    }

    JsonNode cell(String reg, String type) throws Exception {
        JsonNode m = getJson("/api/documents/matrix?q=" + reg, adminToken);
        assertThat(m.get("rows")).hasSize(1);
        return m.get("rows").get(0).get("documents").get(type);
    }

    @Test
    void documentsRegisterImportRenewalsDashboardAndScans() throws Exception {
        long v = createVehicle("DL52DC0247", "ELECTRIC");
        long b = parsedBatch("DOCUMENTS", "{\"allSheets\":true}", new Object[][]{
                {3, v, null, row("DL52DC0247", "COMMERCIAL", String.join(",", doc("INSURANCE", T.plusDays(200)),
                        "{\"docType\":\"PUC\",\"notApplicable\":true,\"note\":\"NA in register\"}", doc("PERMIT_1Y", T.minusDays(5)),
                        "{\"docType\":\"REGISTRATION\",\"issueDate\":\"2021-09-13\"}"))},
                {4, null, null, row("DL1ZZ0000", null, doc("INSURANCE", T.plusDays(10)))}});
        JsonNode r = call(post("/api/imports/" + b + "/commit"), adminToken, Map.of("policy", "SKIP"), status().isOk());
        assertThat(r.get("insertedRows").asInt()).isEqualTo(4);
        assertThat(r.get("rejectedRows").asInt()).isEqualTo(1);           // DL1ZZ0000 is not in the vehicle master
        assertThat(jdbc.queryForObject("SELECT registration_class FROM vehicles WHERE id = ?", String.class, v)).isEqualTo("COMMERCIAL");

        // dashboard: commercial vehicle -> fitness and the 5-year permit are required and missing
        assertThat(cell("DL52DC0247", "INSURANCE").get("status").asText()).isEqualTo("VALID");
        assertThat(cell("DL52DC0247", "PUC").get("status").asText()).isEqualTo("NOT_REQUIRED");
        assertThat(cell("DL52DC0247", "PERMIT_1Y").get("status").asText()).isEqualTo("EXPIRED");
        assertThat(cell("DL52DC0247", "FITNESS").get("status").asText()).isEqualTo("MISSING");
        assertThat(cell("DL52DC0247", "ROAD_TAX").get("status").asText()).isEqualTo("OPTIONAL");
        assertThat(cell("DL52DC0247", "REGISTRATION").get("status").asText()).isEqualTo("NO_EXPIRY");
        assertThat(getJson("/api/documents/matrix?q=DL52DC0247&docType=PERMIT_1Y&state=EXPIRED", adminToken).get("rows")).hasSize(1);
        assertThat(getJson("/api/documents/matrix?q=DL52DC0247&docType=INSURANCE&state=EXPIRED", adminToken).get("rows")).isEmpty();
        assertThat(getJson("/api/documents/compliance", adminToken).get("missing").toString()).contains("DL52DC0247").contains("FITNESS");

        // same register again: nothing added; a later insurance date is a renewal and keeps the old one as history
        long same = parsedBatch("DOCUMENTS", "{}", new Object[][]{{3, v, null, row("DL52DC0247", "COMMERCIAL", doc("INSURANCE", T.plusDays(200)))}});
        assertThat(call(post("/api/imports/" + same + "/commit"), adminToken, Map.of("policy", "SKIP"), status().isOk())
                .get("insertedRows").asInt()).isZero();
        long renew = parsedBatch("DOCUMENTS", "{}", new Object[][]{{3, v, null, row("DL52DC0247", null, doc("INSURANCE", T.plusDays(565)))}});
        assertThat(call(post("/api/imports/" + renew + "/commit"), adminToken, Map.of("policy", "SKIP"), status().isOk())
                .get("insertedRows").asInt()).isEqualTo(1);
        assertThat(cell("DL52DC0247", "INSURANCE").get("expiryDate").asText()).isEqualTo(T.plusDays(565).toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vehicle_documents WHERE vehicle_id = ? AND doc_type = 'INSURANCE' AND NOT is_void",
                Integer.class, v)).isEqualTo(2);

        // an earlier date is ignored with SKIP, and corrected (old voided) with REPLACE + reason
        long earlier = parsedBatch("DOCUMENTS", "{}", new Object[][]{{3, v, null, row("DL52DC0247", null, doc("INSURANCE", T.plusDays(300)))}});
        assertThat(call(post("/api/imports/" + earlier + "/commit"), adminToken, Map.of("policy", "SKIP"), status().isOk())
                .get("skippedRows").asInt()).isEqualTo(1);
        long fix = parsedBatch("DOCUMENTS", "{}", new Object[][]{{3, v, null, row("DL52DC0247", null, doc("INSURANCE", T.plusDays(300)))}});
        assertThat(call(post("/api/imports/" + fix + "/commit"), adminToken, Map.of("policy", "REPLACE", "reason", "typo in renewal"),
                status().isOk()).get("replacedRows").asInt()).isEqualTo(1);
        assertThat(cell("DL52DC0247", "INSURANCE").get("expiryDate").asText()).isEqualTo(T.plusDays(300).toString());

        // scan upload: attach to the current insurance document; it then needs verifying
        JsonNode ins = cell("DL52DC0247", "INSURANCE");
        assertThat(getJson("/api/documents/matrix?q=DL52DC0247&docType=INSURANCE&state=NO_FILE", adminToken).get("rows")).hasSize(1);
        String up = mvc.perform(multipart("/api/files").file(new MockMultipartFile("file", "policy.pdf", "application/pdf",
                        "%PDF-1.4\n% test policy\n".getBytes())).param("purpose", "VEHICLE_DOCUMENT").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long fileId = om.readTree(up).get("id").asLong();
        call(post("/api/documents/" + ins.get("id").asLong() + "/file"), adminToken, Map.of("fileId", fileId), status().isOk());
        JsonNode after = cell("DL52DC0247", "INSURANCE");
        assertThat(after.get("fileId").asLong()).isEqualTo(fileId);
        assertThat(after.get("fileName").asText()).isEqualTo("policy.pdf");
        assertThat(getJson("/api/documents/matrix?q=DL52DC0247&docType=INSURANCE&state=NO_FILE", adminToken).get("rows")).isEmpty();
        assertThat(getJson("/api/documents/compliance", adminToken).get("pendingVerification").toString()).contains("DL52DC0247");

        // manual "not required" entry from the dashboard
        call(post("/api/documents"), adminToken, Map.of("vehicleId", v, "docType", "ROAD_TAX", "notApplicable", true), status().isOk());
        assertThat(cell("DL52DC0247", "ROAD_TAX").get("status").asText()).isEqualTo("NOT_REQUIRED");

        // Excel export of the dashboard
        byte[] x = mvc.perform(get("/api/documents/matrix/export?q=DL52DC0247&format=csv").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(x, java.nio.charset.StandardCharsets.UTF_8)).contains("DL52DC0247").contains("Not required").contains("Uploaded");
    }

    @Test
    void workerStatusTellsWhetherQueuedFilesWillBeRead() throws Exception {
        jdbc.update("DELETE FROM worker_heartbeats");
        JsonNode off = getJson("/api/worker/status", adminToken);
        assertThat(off.get("online").asBoolean()).isFalse();
        jdbc.update("INSERT INTO worker_heartbeats (worker_id, hostname) VALUES ('it-worker', 'test')");
        assertThat(getJson("/api/worker/status", adminToken).get("online").asBoolean()).isTrue();
        jdbc.update("UPDATE worker_heartbeats SET last_seen = now() - interval '5 minutes'");
        assertThat(getJson("/api/worker/status", adminToken).get("online").asBoolean()).isFalse();
        jdbc.update("DELETE FROM worker_heartbeats");
    }
}
