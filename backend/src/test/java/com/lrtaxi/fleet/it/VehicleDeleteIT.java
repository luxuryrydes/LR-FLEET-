package com.lrtaxi.fleet.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VehicleDeleteIT extends AbstractIT {

    static String master(String reg) {
        return "{\"registration\":\"" + reg + "\",\"model\":\"Ciaz\",\"fuelType\":\"CNG\",\"operatorLabel\":\"DRIVER\",\"driverName\":\"DEL DRIVER\"}";
    }

    @Test
    void deleteManyRestoreAndPermanentDelete() throws Exception {
        long used = createVehicle("DL1DE0001", "CNG");
        long mistake = createVehicle("DL1DE0002", "CNG");
        jdbc.update("INSERT INTO gps_daily_km (vehicle_id, km_date, distance_km, record_mode, source, is_current) VALUES (?, ?, 120, 'MANUAL', 'MANUAL', TRUE)",
                used, LocalDate.now().minusDays(2));

        // operators cannot delete; a reason is required
        call(post("/api/users"), adminToken, Map.of("email", "del-op@lrtaxi.test", "fullName", "Operator", "password", "OperatorPass123",
                "roles", List.of("OPERATOR")), status().isOk());
        String op = login("del-op@lrtaxi.test", "OperatorPass123");
        call(post("/api/vehicles/delete"), op, Map.of("ids", List.of(used), "reason", "x"), status().isForbidden());
        call(post("/api/vehicles/delete"), adminToken, Map.of("ids", List.of(used)), status().isBadRequest());

        JsonNode d = call(post("/api/vehicles/delete"), adminToken, Map.of("ids", List.of(used, mistake), "reason", "Test cleanup"), status().isOk());
        assertThat(d.get("deleted").asInt()).isEqualTo(2);

        // hidden from lists and dashboards, visible under status DELETED
        assertThat(getJson("/api/vehicles?vehicle=DL1DE", adminToken).get("total").asInt()).isZero();
        assertThat(getJson("/api/vehicles?vehicle=DL1DE&status=DELETED", adminToken).get("total").asInt()).isEqualTo(2);
        assertThat(getJson("/api/service-status?vehicle=DL1DE", adminToken)).isEmpty();
        assertThat(getJson("/api/documents/matrix?q=DL1DE", adminToken).get("rows")).isEmpty();
        // the registration can be used again, and a master sheet does not bring the deleted vehicle back
        long b = parsedBatch("VEHICLE_MASTER", "{}", new Object[][]{{2, null, null, master("DL1DE0002")}});
        call(post("/api/imports/" + b + "/commit"), adminToken, Map.of("policy", "REPLACE", "reason", "refresh"), status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM vehicles WHERE id = ?", String.class, mistake)).isEqualTo("DELETED");

        // permanent delete: only the vehicle without records goes
        JsonNode p = call(post("/api/vehicles/purge"), adminToken, Map.of("ids", List.of(used, mistake)), status().isOk());
        assertThat(p.get("removed").asInt()).isEqualTo(1);
        assertThat(p.get("kept").toString()).contains("DL1DE0001").contains("gps daily km");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vehicles WHERE id = ?", Integer.class, mistake)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gps_daily_km WHERE vehicle_id = ?", Integer.class, used)).isEqualTo(1);

        // restore brings back the earlier status
        JsonNode r = call(post("/api/vehicles/restore"), adminToken, Map.of("ids", List.of(used)), status().isOk());
        assertThat(r.get("restored").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM vehicles WHERE id = ?", String.class, used)).isEqualTo("ACTIVE");
        assertThat(getJson("/api/vehicles?vehicle=DL1DE0001", adminToken).get("total").asInt()).isEqualTo(1);
    }
}
