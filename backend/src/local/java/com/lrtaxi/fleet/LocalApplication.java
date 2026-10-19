package com.lrtaxi.fleet;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.SpringApplication;

import java.io.File;
import java.nio.file.Path;

/**
 * Development launcher (Maven profile "local" only): starts a private PostgreSQL 16 on port 54329 with data kept in
 * ../data/pg, fills in development-only defaults, then starts the normal application. Not part of production builds.
 */
public final class LocalApplication {

    public static final int PORT = 54329;

    private LocalApplication() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        File pgData = root.resolve("data").resolve("pg").toFile();
        pgData.mkdirs();
        EmbeddedPostgres pg = EmbeddedPostgres.builder()
                .setPort(PORT)
                .setDataDirectory(pgData)
                .setCleanDataDirectory(false)
                .start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                pg.close();
            } catch (Exception ignored) {
                // shutting down
            }
        }));

        def("spring.datasource.url", "jdbc:postgresql://localhost:" + PORT + "/postgres");
        def("spring.datasource.username", "postgres");
        def("spring.datasource.password", "postgres");
        def("app.storage.local-dir", root.resolve("data").resolve("files").toString());
        // Development-only defaults; real deployments must set JWT_SECRET / ADMIN_* themselves.
        defEnv("app.jwt-secret", "JWT_SECRET", "local-development-only-secret-do-not-use-in-production-123");
        defEnv("app.bootstrap-admin.email", "ADMIN_EMAIL", "admin@lrtaxi.local");
        defEnv("app.bootstrap-admin.password", "ADMIN_PASSWORD", "Admin@12345");
        defEnv("app.allowed-origins", "ALLOWED_ORIGINS", "http://localhost:5173");

        System.out.println();
        System.out.println("  Local PostgreSQL running on localhost:" + PORT + " (data: " + pgData + ")");
        System.out.println("  First admin (only created when there are no users yet): "
                + System.getProperty("app.bootstrap-admin.email") + " / " + maskedHint());
        System.out.println();
        SpringApplication.run(FleetApplication.class, args);
    }

    private static String maskedHint() {
        return System.getenv("ADMIN_PASSWORD") != null ? "(your ADMIN_PASSWORD)" : "Admin@12345";
    }

    private static void def(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

    private static void defEnv(String key, String env, String fallback) {
        String v = System.getenv(env);
        def(key, v != null && !v.isBlank() ? v : fallback);
    }
}
