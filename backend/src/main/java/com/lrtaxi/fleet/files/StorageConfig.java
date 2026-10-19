package com.lrtaxi.fleet.files;

import com.lrtaxi.fleet.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * STORAGE_TYPE=local : shared directory (Docker volume) - development and single-host deployments.
 * STORAGE_TYPE=s3    : any S3-compatible private bucket, e.g. Supabase Storage (S3 endpoint), AWS S3, Cloudflare R2.
 */
@Configuration
public class StorageConfig {

    @Bean
    public FileStorage fileStorage(AppProperties props) {
        AppProperties.Storage s = props.storage();
        if (s != null && "s3".equalsIgnoreCase(s.type())) {
            return s3(s);
        }
        Path root = Path.of(s == null || s.localDir() == null ? "./data/files" : s.localDir()).toAbsolutePath().normalize();
        return new FileStorage() {
            @Override
            public void put(String key, Path source, String contentType) {
                try {
                    Path target = resolve(key);
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new UncheckedIOException("Could not store file", e);
                }
            }

            @Override
            public InputStream get(String key) {
                try {
                    return Files.newInputStream(resolve(key));
                } catch (IOException e) {
                    throw new UncheckedIOException("Stored file is missing", e);
                }
            }

            private Path resolve(String key) {
                Path p = root.resolve(key).normalize();
                if (!p.startsWith(root)) {
                    throw new IllegalArgumentException("Invalid storage key");
                }
                return p;
            }
        };
    }

    private static FileStorage s3(AppProperties.Storage s) {
        var builder = S3Client.builder()
                .region(Region.of(s.s3Region()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(s.s3AccessKey(), s.s3SecretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        if (s.s3Endpoint() != null && !s.s3Endpoint().isBlank()) {
            builder.endpointOverride(URI.create(s.s3Endpoint()));
        }
        S3Client client = builder.build();
        String bucket = s.s3Bucket();
        return new FileStorage() {
            @Override
            public void put(String key, Path source, String contentType) {
                client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
                        RequestBody.fromFile(source));
            }

            @Override
            public InputStream get(String key) {
                return client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
            }
        };
    }
}
