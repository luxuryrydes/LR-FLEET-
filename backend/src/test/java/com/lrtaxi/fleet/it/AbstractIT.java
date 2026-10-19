package com.lrtaxi.fleet.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.time.LocalDate;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Integration tests run the real Spring Boot app against PostgreSQL 16 in a Testcontainers container. */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractIT {

    /** Set IT_JDBC_URL (+ IT_DB_USER / IT_DB_PASSWORD) to run against an existing empty database instead of Docker. */
    static final String EXTERNAL_URL = System.getenv("IT_JDBC_URL");
    static final PostgreSQLContainer<?> PG = EXTERNAL_URL != null ? null : new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fleet").withUsername("fleet").withPassword("fleet");

    static {
        if (PG != null) {
            PG.start();
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        if (PG != null) {
            r.add("spring.datasource.url", PG::getJdbcUrl);
            r.add("spring.datasource.username", PG::getUsername);
            r.add("spring.datasource.password", PG::getPassword);
        } else {
            r.add("spring.datasource.url", () -> EXTERNAL_URL);
            r.add("spring.datasource.username", () -> System.getenv().getOrDefault("IT_DB_USER", "postgres"));
            r.add("spring.datasource.password", () -> System.getenv().getOrDefault("IT_DB_PASSWORD", "postgres"));
        }
        r.add("app.jwt-secret", () -> "integration-test-secret-that-is-long-enough-123456");
        r.add("app.bootstrap-admin.email", () -> "admin@lrtaxi.test");
        r.add("app.bootstrap-admin.password", () -> "AdminPass123");
        r.add("app.storage.local-dir", () -> tmp());
        r.add("app.alerts-cron", () -> "0 0 0 1 1 *");
        r.add("app.api-per-minute", () -> "100000");
        r.add("app.login-per-minute", () -> "100000");
    }

    private static String tmpDir;

    static synchronized String tmp() {
        if (tmpDir == null) {
            try {
                tmpDir = Files.createTempDirectory("fleet-it").toString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return tmpDir;
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcTemplate jdbc;
    protected final ObjectMapper om = new ObjectMapper();
    protected String adminToken;

    protected static final LocalDate D0 = LocalDate.now().minusDays(20);

    @BeforeEach
    void loginAdmin() throws Exception {
        adminToken = login("admin@lrtaxi.test", "AdminPass123");
    }

    protected String login(String email, String password) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("email", email, "password", password)))).andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    protected JsonNode call(MockHttpServletRequestBuilder req, String token, Object body, ResultMatcher expect) throws Exception {
        req.header("Authorization", "Bearer " + token);
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body));
        }
        String s = mvc.perform(req).andExpect(expect).andReturn().getResponse().getContentAsString();
        return s.isEmpty() ? om.createObjectNode() : om.readTree(s);
    }

    protected JsonNode getJson(String url, String token) throws Exception {
        return call(get(url), token, null, status().isOk());
    }

    protected long createVehicle(String reg, String fuelType) throws Exception {
        return createVehicle(reg, fuelType, null);
    }

    protected long createVehicle(String reg, String fuelType, String expectedMileage) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("registrationNumber", reg);
        body.put("make", "Maruti");
        body.put("model", "Dzire");
        body.put("fuelType", fuelType);
        body.put("categoryCode", "SEDAN");
        body.put("ownershipType", "OWNED");
        body.put("expectedMileage", expectedMileage);
        return call(post("/api/vehicles"), adminToken, body, status().isOk()).get("id").asLong();
    }

    /** Simulates the Python worker's output: a PARSED batch with staging rows. */
    protected long parsedBatch(String type, String options, Object[][] rows) {
        Long fileId = jdbc.queryForObject("""
                INSERT INTO stored_files (storage_key, original_filename, content_type, size_bytes, sha256, purpose)
                VALUES (?, 'test.csv', 'text/csv', 10, ?, 'IMPORT') RETURNING id""", Long.class,
                "import/test/" + System.nanoTime(), "0".repeat(64));
        Long batchId = jdbc.queryForObject("""
                INSERT INTO import_batches (import_type, file_id, original_filename, file_sha256, status, options)
                VALUES (?, ?, 'test.csv', ?, 'PARSED', CAST(? AS jsonb)) RETURNING id""", Long.class,
                type, fileId, "0".repeat(64), options);
        for (Object[] r : rows) {
            // r = {rowNumber, vehicleId, date, parsedJson, status}
            jdbc.update("""
                    INSERT INTO import_staging_rows (batch_id, row_number, raw, parsed, vehicle_id, record_date, status)
                    VALUES (?, ?, '{}'::jsonb, CAST(? AS jsonb), ?, ?, ?)""",
                    batchId, r[0], r[3], r[1], r[2], r.length > 4 ? r[4] : "VALID");
        }
        return batchId;
    }
}
