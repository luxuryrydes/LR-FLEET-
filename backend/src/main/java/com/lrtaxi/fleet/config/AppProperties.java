package com.lrtaxi.fleet.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String jwtSecret,
        int jwtTtlMinutes,
        List<String> allowedOrigins,
        int loginPerMinute,
        int apiPerMinute,
        String alertsCron,
        Storage storage,
        BootstrapAdmin bootstrapAdmin) {

    public record Storage(String type, String localDir, String s3Bucket, String s3Endpoint, String s3Region,
                          String s3AccessKey, String s3SecretKey) {
    }

    public record BootstrapAdmin(String email, String password, String name) {
    }
}
