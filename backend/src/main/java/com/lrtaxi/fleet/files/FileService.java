package com.lrtaxi.fleet.files;

import com.lrtaxi.fleet.common.ApiException;
import com.lrtaxi.fleet.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class FileService {

    public record StoredFile(long id, String storageKey, String originalFilename, String contentType, long sizeBytes, String sha256) {
    }

    private static final Set<String> IMPORT_EXT = Set.of("xlsx", "xls", "csv");
    private static final Set<String> DOC_EXT = Set.of("pdf", "jpg", "jpeg", "png", "webp", "xlsx", "xls", "csv", "doc", "docx");

    private final JdbcTemplate jdbc;
    private final FileStorage storage;

    public FileService(JdbcTemplate jdbc, FileStorage storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    public StoredFile store(MultipartFile file, String purpose) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("EMPTY_FILE", "Please choose a non-empty file");
        }
        String original = sanitize(file.getOriginalFilename());
        String ext = extension(original);
        Set<String> allowed = "IMPORT".equals(purpose) ? IMPORT_EXT : DOC_EXT;
        if (!allowed.contains(ext)) {
            throw ApiException.badRequest("UNSUPPORTED_FILE", "File type ." + ext + " is not allowed. Allowed: " + allowed);
        }
        Path tmp = null;
        try {
            tmp = Files.createTempFile("upload-", "." + ext);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(file.getInputStream(), md)) {
                Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            String sha = HexFormat.of().formatHex(md.digest());
            verifyMagic(tmp, ext);
            LocalDate today = LocalDate.now();
            String key = purpose.toLowerCase(Locale.ROOT) + "/" + today.getYear() + "/" + String.format("%02d", today.getMonthValue())
                    + "/" + UUID.randomUUID() + "-" + original;
            String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
            storage.put(key, tmp, contentType);
            long size = Files.size(tmp);
            Long id = jdbc.queryForObject("""
                            INSERT INTO stored_files (storage_key, original_filename, content_type, size_bytes, sha256, purpose, uploaded_by)
                            VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                    key, original, contentType, size, sha, purpose, CurrentUser.idOrNull());
            return new StoredFile(id, key, original, contentType, size, sha);
        } catch (IOException e) {
            throw new UncheckedIOException("Upload failed; please retry", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // temp cleanup best effort
                }
            }
        }
    }

    public StoredFile find(long id) {
        List<StoredFile> rows = jdbc.query("SELECT id, storage_key, original_filename, content_type, size_bytes, sha256 FROM stored_files WHERE id = ?",
                (rs, i) -> new StoredFile(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getString(6)), id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("File");
        }
        return rows.get(0);
    }

    public InputStream open(StoredFile f) {
        return storage.get(f.storageKey());
    }

    public Map<String, Object> describe(long id) {
        StoredFile f = find(id);
        return Map.of("id", f.id(), "originalFilename", f.originalFilename(), "contentType", f.contentType(), "sizeBytes", f.sizeBytes());
    }

    static String sanitize(String name) {
        String n = name == null ? "file" : Path.of(name).getFileName().toString();
        n = n.replaceAll("[^A-Za-z0-9._-]", "_");
        if (n.length() > 120) {
            n = n.substring(n.length() - 120);
        }
        return n.isEmpty() ? "file" : n;
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Rejects files whose content does not match the extension (e.g. an .exe renamed to .xlsx). */
    private static void verifyMagic(Path p, String ext) throws IOException {
        byte[] head = new byte[8];
        int n;
        try (InputStream in = Files.newInputStream(p)) {
            n = in.read(head);
        }
        boolean ok = switch (ext) {
            case "xlsx", "docx" -> n >= 4 && head[0] == 'P' && head[1] == 'K';
            case "xls", "doc" -> n >= 8 && (head[0] & 0xFF) == 0xD0 && (head[1] & 0xFF) == 0xCF;
            case "pdf" -> n >= 4 && head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F';
            case "png" -> n >= 4 && (head[0] & 0xFF) == 0x89 && head[1] == 'P';
            case "jpg", "jpeg" -> n >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8;
            // a CSV must not be a zip/xlsx or a Windows executable
            case "csv" -> n < 2 || !((head[0] == 'P' && head[1] == 'K') || (head[0] == 'M' && head[1] == 'Z'));
            default -> true;
        };
        if (!ok) {
            throw ApiException.badRequest("FILE_CONTENT_MISMATCH", "The file content does not match its ." + ext + " extension");
        }
    }
}
