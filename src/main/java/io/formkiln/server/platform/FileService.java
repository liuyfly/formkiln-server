package io.formkiln.server.platform;

import io.formkiln.server.form.FieldIndex;
import io.formkiln.server.form.FieldIndex.FileRule;
import io.formkiln.server.platform.PlatformRepository.FileRow;
import io.formkiln.server.storage.ObjectStorage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;

/**
 * File uploads through the server (Form Schema §6.4): the server checks the field, type and size,
 * stores the file under a generated key and returns its reference. A submission later claims the
 * reference; files never claimed are deleted after a day.
 */
@Service
public class FileService {

    static final Duration UNATTACHED_TTL = Duration.ofDays(1);
    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    public record StoredFile(FileRow row, InputStream content) {}

    private final PlatformRepository repository;
    private final ObjectStorage storage;
    private final Clock clock;
    private final long maxBytes;

    public FileService(PlatformRepository repository, ObjectStorage storage, Clock clock,
            @Value("${formkiln.max-file-size:10MB}") DataSize maxFileSize) {
        this.repository = repository;
        this.storage = storage;
        this.clock = clock;
        this.maxBytes = maxFileSize.toBytes();
    }

    static String safeName(String original) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").strip();
        if (name.isEmpty()) {
            name = "file";
        }
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    /** Same matching as the {@code fileTypes} validator: MIME types, {@code type/*} or extensions. */
    static boolean matches(String name, String contentType, List<String> entries) {
        String lowerName = name.toLowerCase(Locale.ROOT);
        String lowerType = contentType.toLowerCase(Locale.ROOT);
        for (String entry : entries) {
            String wanted = entry.toLowerCase(Locale.ROOT);
            if (wanted.startsWith(".") ? lowerName.endsWith(wanted)
                    : wanted.endsWith("/*") ? lowerType.startsWith(wanted.substring(0, wanted.length() - 1))
                    : lowerType.equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    /** Checks and stores an upload; returns the file reference to put in the answers. */
    public Map<String, Object> upload(UUID formId, FieldIndex fields, String fieldKey, String originalName, String contentType,
            long size, InputStream content) {
        FileRule rule = fields.fileField(fieldKey == null ? "" : fieldKey)
                .orElseThrow(() -> new RuntimeException4xx(HttpStatus.BAD_REQUEST, "notAFileField", "The field does not accept files."));
        String name = safeName(originalName);
        String type = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType.toLowerCase(Locale.ROOT);
        long limit = rule.maxFileSize() == null ? maxBytes : Math.min(maxBytes, rule.maxFileSize());
        if (size <= 0) {
            throw new RuntimeException4xx(HttpStatus.BAD_REQUEST, "emptyFile", "The file is empty.");
        }
        if (size > limit) {
            throw new RuntimeException4xx(HttpStatus.CONTENT_TOO_LARGE, "maxFileSize", "The file is larger than " + limit + " bytes.");
        }
        List<String> allowed = rule.fileTypes() != null ? rule.fileTypes() : rule.accept();
        if (!allowed.isEmpty() && !matches(name, type, allowed)) {
            throw new RuntimeException4xx(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "fileType", "This file type is not allowed.");
        }
        UUID id = UUID.randomUUID();
        String key = "forms/" + formId + "/" + id;
        try {
            storage.put(key, content, size, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        repository.insertFile(id, formId, fieldKey, name, size, type, key, clock.instant());
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("id", id.toString());
        ref.put("name", name);
        ref.put("size", (double) size);
        ref.put("contentType", type);
        return ref;
    }

    /**
     * Replaces client-supplied file references with the stored metadata. Unknown, foreign or
     * already-used files become an invalid reference, so validation reports a {@code type} error.
     */
    public Object canonicalReference(UUID formId, String fieldKey, Object reference, List<UUID> claimed) {
        if (!(reference instanceof Map<?, ?> map) || !(map.get("id") instanceof String text)) {
            return reference;
        }
        UUID id;
        try {
            id = UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return Map.of("id", text);
        }
        return repository.findUnattachedFile(id, formId)
                .filter(row -> row.fieldKey().equals(fieldKey) && !claimed.contains(row.id()))
                .<Object>map(row -> {
                    claimed.add(row.id());
                    Map<String, Object> ref = new LinkedHashMap<>();
                    ref.put("id", row.id().toString());
                    ref.put("name", row.name());
                    ref.put("size", (double) row.size());
                    ref.put("contentType", row.contentType());
                    return ref;
                })
                .orElse(Map.of("id", text));
    }

    public void attach(List<UUID> fileIds, UUID submissionId) {
        repository.attachFiles(fileIds, submissionId);
    }

    public StoredFile download(UUID formId, UUID fileId) {
        FileRow row = repository.findAttachedFile(fileId, formId)
                .orElseThrow(() -> new RuntimeException4xx(HttpStatus.NOT_FOUND, "fileNotFound", "No such file."));
        try {
            return new StoredFile(row, storage.get(row.storageKey()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Removes files that no submission claimed within a day. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    public void removeAbandoned() {
        for (FileRow row : repository.unattachedFilesBefore(clock.instant().minus(UNATTACHED_TTL), 500)) {
            try {
                storage.delete(row.storageKey());
                repository.deleteFile(row.id());
            } catch (IOException | RuntimeException e) {
                log.warn("Could not remove abandoned file {}: {}", row.id(), e.getMessage());
            }
        }
    }
}
