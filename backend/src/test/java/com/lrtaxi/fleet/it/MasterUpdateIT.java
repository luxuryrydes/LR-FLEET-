package com.lrtaxi.fleet.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MasterUpdateIT extends AbstractIT {

    static String row(String reg, String label, String driver, String dept) {
        return "{\"registration\":\"" + reg + "\",\"model\":\"Ciaz\",\"fuelType\":\"CNG / PETROL\",\"modelYear\":2023,\"operatorLabel\":\"" + label
                + "\",\"driverName\":" + (driver == null ? "null" : "\"" + driver + "\"") + ",\"department\":\"" + dept + "\",\"registrationClass\":\"Commercial\"}";
    }

    @Test
    void anUpdatedMasterSheetShowsAndAppliesTheChanges() throws Exception {
        long first = parsedBatch("VEHICLE_MASTER", "{}", new Object[][]{
                {2, null, null, row("HR55MU0001", "DRIVER", "UPDATE DRIVER ONE", "MHA")},
                {3, null, null, row("HR55MU0002", "DRIVER", "UPDATE DRIVER TWO", "MHA")}});
        call(post("/api/imports/" + first + "/commit"), adminToken, Map.of("policy", "SKIP"), status().isOk());

        // next month's sheet: vehicle 1 got a new driver and department, vehicle 2 is the same, vehicle 3 is new
        Object[][] next = {
                {2, null, null, row("HR55MU0001", "VENDOR", "Some Vendor", "DPCC")},
                {3, null, null, row("HR55MU0002", "DRIVER", "UPDATE DRIVER TWO", "MHA")},
                {4, null, null, row("HR55MU0003", "DRIVER", "UPDATE DRIVER THREE", "MHA")}};

        // review before saving: nothing is written
        long b = parsedBatch("VEHICLE_MASTER", "{}", next);
        JsonNode rv = getJson("/api/imports/" + b + "/master-review", adminToken);
        assertThat(rv.get("newVehicles").asInt()).isEqualTo(1);
        assertThat(rv.get("updatedVehicles").asInt()).isEqualTo(1);
        assertThat(rv.get("unchangedVehicles").asInt()).isEqualTo(1);
        assertThat(rv.get("updates").get(0).get("registration").asText()).isEqualTo("HR55MU0001");
        assertThat(rv.get("updates").get(0).get("changes").toString()).contains("department").contains("driver type").contains("vendor");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vehicles WHERE registration_normalized = 'HR55MU0003'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT operator_type FROM vehicles WHERE registration_normalized = 'HR55MU0001'", String.class))
                .isEqualTo("OWN_DRIVER");

        // "add new only": the change is reported, not silently skipped
        JsonNode skip = call(post("/api/imports/" + b + "/commit"), adminToken, Map.of("policy", "SKIP"), status().isOk());
        assertThat(skip.get("insertedRows").asInt()).isEqualTo(1);
        assertThat(skip.get("replacedRows").asInt()).isZero();
        assertThat(skip.get("existingConflictRows").asInt()).isEqualTo(1);

        // update (the default on screen): no reason needs typing
        long again = parsedBatch("VEHICLE_MASTER", "{}", next);
        JsonNode upd = call(post("/api/imports/" + again + "/commit"), adminToken, Map.of("policy", "REPLACE"), status().isOk());
        assertThat(upd.get("replacedRows").asInt()).isEqualTo(1);
        assertThat(upd.get("skippedRows").asInt()).isEqualTo(2);
        assertThat(upd.get("existingConflictRows").asInt()).isZero();
        Map<String, Object> v = jdbc.queryForMap("""
                SELECT v.operator_type, d.name AS dept FROM vehicles v LEFT JOIN departments d ON d.id = v.current_department_id
                WHERE v.registration_normalized = 'HR55MU0001'""");
        assertThat(v.get("operator_type")).isEqualTo("VENDOR");
        assertThat(v.get("dept")).isEqualTo("DPCC");
    }
}
