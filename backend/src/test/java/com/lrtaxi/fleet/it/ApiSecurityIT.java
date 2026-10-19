package com.lrtaxi.fleet.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiSecurityIT extends AbstractIT {

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/vehicles")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        mvc.perform(post("/api/auth/login").contentType("application/json")
                .content("{\"email\":\"admin@lrtaxi.test\",\"password\":\"wrong-password-1\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void viewerCanReadButNotWrite() throws Exception {
        call(post("/api/users"), adminToken, Map.of("email", "viewer@lrtaxi.test", "fullName", "Viewer",
                "password", "ViewerPass123", "roles", List.of("VIEWER")), status().isOk());
        String viewer = login("viewer@lrtaxi.test", "ViewerPass123");
        getJson("/api/vehicles", viewer);
        getJson("/api/dashboard/kpis", viewer);
        call(post("/api/vehicles"), viewer, Map.of("registrationNumber", "DL1AB0001", "make", "X", "model", "Y",
                "fuelType", "PETROL", "categoryCode", "SEDAN", "ownershipType", "OWNED"), status().isForbidden());
        call(get("/api/users"), viewer, null, status().isForbidden());
        call(get("/api/audit-logs"), viewer, null, status().isForbidden());
    }

    @Test
    void operatorCannotReplaceImportsOrDeactivate() throws Exception {
        call(post("/api/users"), adminToken, Map.of("email", "op@lrtaxi.test", "fullName", "Operator",
                "password", "OperatorPass123", "roles", List.of("OPERATOR")), status().isOk());
        String op = login("op@lrtaxi.test", "OperatorPass123");
        long v = createVehicle("HR26 DK 0002", "CNG");
        call(post("/api/vehicles/" + v + "/deactivate"), op, Map.of("reason", "test"), status().isForbidden());
        long batch = parsedBatch("GPS_KM", "{\"recordMode\":\"DAILY_TOTAL\"}", new Object[][]{});
        call(post("/api/imports/" + batch + "/commit"), op, Map.of("policy", "REPLACE", "reason", "x"), status().isForbidden());
    }

    @Test
    void registrationIsNormalisedAndDuplicatesBlocked() throws Exception {
        long id = createVehicle("ka-05 mn.4321", "PETROL");
        JsonNode v = getJson("/api/vehicles/" + id, adminToken);
        assertThat(v.get("registrationNumber").asText()).isEqualTo("KA05MN4321");
        call(post("/api/vehicles"), adminToken, Map.of("registrationNumber", "KA 05 MN 4321", "make", "X", "model", "Y",
                "fuelType", "PETROL", "categoryCode", "SEDAN", "ownershipType", "OWNED"), status().isConflict());
        JsonNode audit = getJson("/api/audit-logs?entityType=VEHICLE&entityId=" + id, adminToken);
        assertThat(audit.get("total").asLong()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void validationErrorsAreReadable() throws Exception {
        JsonNode err = call(post("/api/vehicles"), adminToken, Map.of("registrationNumber", "", "make", "X"), status().isBadRequest());
        assertThat(err.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(err.get("fieldErrors").isArray()).isTrue();
    }
}
