package io.formkiln.server.platform;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence for access modes, drafts, files, webhook endpoints and deliveries. */
@Repository
public class PlatformRepository {

    public record FormRow(UUID id, String key, String accessMode, boolean archived) {}

    public record Draft(UUID id, UUID formId, byte[] data, Instant updatedAt, Instant expiresAt) {}

    public record FileRow(UUID id, UUID formId, String fieldKey, String name, long size, String contentType, String storageKey,
            Instant createdAt, UUID submissionId) {}

    public record Endpoint(UUID id, UUID formId, String url, byte[] secret, Instant createdAt) {}

    public record Delivery(UUID id, UUID endpointId, UUID submissionId, String event, byte[] payload, String status, int attempts,
            Instant nextAttemptAt, Integer lastStatus, String lastError, Instant createdAt, Instant deliveredAt) {}

    private final JdbcClient jdbc;

    public PlatformRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime odt(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    // ---------------------------------------------------------------- forms

    public Optional<FormRow> findForm(String key) {
        return jdbc.sql("SELECT id, key, access_mode, archived_at IS NOT NULL AS archived FROM form WHERE key = :key")
                .param("key", key)
                .query((rs, n) -> new FormRow(rs.getObject("id", UUID.class), rs.getString("key"), rs.getString("access_mode"),
                        rs.getBoolean("archived")))
                .optional();
    }

    public void setAccessMode(UUID formId, String mode) {
        jdbc.sql("UPDATE form SET access_mode = :mode WHERE id = :id").param("mode", mode).param("id", formId).update();
    }

    // ---------------------------------------------------------------- drafts

    private static Draft draft(ResultSet rs, int n) throws SQLException {
        return new Draft(rs.getObject("id", UUID.class), rs.getObject("form_id", UUID.class), rs.getBytes("data"),
                instant(rs, "updated_at"), instant(rs, "expires_at"));
    }

    public void insertDraft(UUID formId, byte[] tokenHash, byte[] data, Instant now, Instant expiresAt) {
        jdbc.sql("""
                        INSERT INTO form_draft (form_id, token_hash, data, created_at, updated_at, expires_at)
                        VALUES (:form, :hash, :data, :now, :now, :expires)
                        """)
                .param("form", formId)
                .param("hash", tokenHash)
                .param("data", data)
                .param("now", odt(now))
                .param("expires", odt(expiresAt))
                .update();
    }

    public Optional<Draft> findDraft(UUID formId, byte[] tokenHash, Instant now) {
        return jdbc.sql("SELECT * FROM form_draft WHERE form_id = :form AND token_hash = :hash AND expires_at > :now")
                .param("form", formId)
                .param("hash", tokenHash)
                .param("now", odt(now))
                .query(PlatformRepository::draft)
                .optional();
    }

    public int updateDraft(UUID id, byte[] data, Instant now, Instant expiresAt) {
        return jdbc.sql("UPDATE form_draft SET data = :data, updated_at = :now, expires_at = :expires WHERE id = :id")
                .param("data", data)
                .param("now", odt(now))
                .param("expires", odt(expiresAt))
                .param("id", id)
                .update();
    }

    public void deleteDraft(UUID id) {
        jdbc.sql("DELETE FROM form_draft WHERE id = :id").param("id", id).update();
    }

    public int deleteExpiredDrafts(Instant now) {
        return jdbc.sql("DELETE FROM form_draft WHERE expires_at <= :now").param("now", odt(now)).update();
    }

    // ---------------------------------------------------------------- files

    private static FileRow file(ResultSet rs, int n) throws SQLException {
        return new FileRow(rs.getObject("id", UUID.class), rs.getObject("form_id", UUID.class), rs.getString("field_key"),
                rs.getString("name"), rs.getLong("size"), rs.getString("content_type"), rs.getString("storage_key"),
                instant(rs, "created_at"), rs.getObject("submission_id", UUID.class));
    }

    public void insertFile(UUID id, UUID formId, String fieldKey, String name, long size, String contentType, String storageKey,
            Instant now) {
        jdbc.sql("""
                        INSERT INTO file_object (id, form_id, field_key, name, size, content_type, storage_key, created_at)
                        VALUES (:id, :form, :field, :name, :size, :type, :key, :now)
                        """)
                .param("id", id)
                .param("form", formId)
                .param("field", fieldKey)
                .param("name", name)
                .param("size", size)
                .param("type", contentType)
                .param("key", storageKey)
                .param("now", odt(now))
                .update();
    }

    public Optional<FileRow> findUnattachedFile(UUID id, UUID formId) {
        return jdbc.sql("SELECT * FROM file_object WHERE id = :id AND form_id = :form AND submission_id IS NULL")
                .param("id", id)
                .param("form", formId)
                .query(PlatformRepository::file)
                .optional();
    }

    public Optional<FileRow> findAttachedFile(UUID id, UUID formId) {
        return jdbc.sql("SELECT * FROM file_object WHERE id = :id AND form_id = :form AND submission_id IS NOT NULL")
                .param("id", id)
                .param("form", formId)
                .query(PlatformRepository::file)
                .optional();
    }

    public void attachFiles(List<UUID> ids, UUID submissionId) {
        for (UUID id : ids) {
            jdbc.sql("UPDATE file_object SET submission_id = :submission WHERE id = :id AND submission_id IS NULL")
                    .param("submission", submissionId)
                    .param("id", id)
                    .update();
        }
    }

    public List<FileRow> unattachedFilesBefore(Instant cutoff, int limit) {
        return jdbc.sql("SELECT * FROM file_object WHERE submission_id IS NULL AND created_at < :cutoff LIMIT :limit")
                .param("cutoff", odt(cutoff))
                .param("limit", limit)
                .query(PlatformRepository::file)
                .list();
    }

    public void deleteFile(UUID id) {
        jdbc.sql("DELETE FROM file_object WHERE id = :id AND submission_id IS NULL").param("id", id).update();
    }

    // ---------------------------------------------------------------- webhooks

    private static Endpoint endpoint(ResultSet rs, int n) throws SQLException {
        return new Endpoint(rs.getObject("id", UUID.class), rs.getObject("form_id", UUID.class), rs.getString("url"),
                rs.getBytes("secret"), instant(rs, "created_at"));
    }

    private static Delivery delivery(ResultSet rs, int n) throws SQLException {
        int lastStatus = rs.getInt("last_status");
        return new Delivery(rs.getObject("id", UUID.class), rs.getObject("endpoint_id", UUID.class),
                rs.getObject("submission_id", UUID.class), rs.getString("event"), rs.getBytes("payload"), rs.getString("status"),
                rs.getInt("attempts"), instant(rs, "next_attempt_at"), rs.wasNull() ? null : lastStatus, rs.getString("last_error"),
                instant(rs, "created_at"), instant(rs, "delivered_at"));
    }

    public UUID insertEndpoint(UUID formId, String url, byte[] secret) {
        return jdbc.sql("INSERT INTO webhook_endpoint (form_id, url, secret) VALUES (:form, :url, :secret) RETURNING id")
                .param("form", formId)
                .param("url", url)
                .param("secret", secret)
                .query(UUID.class)
                .single();
    }

    public List<Endpoint> listEndpoints(UUID formId) {
        return jdbc.sql("SELECT * FROM webhook_endpoint WHERE form_id = :form AND deleted_at IS NULL ORDER BY created_at")
                .param("form", formId)
                .query(PlatformRepository::endpoint)
                .list();
    }

    public Optional<Endpoint> findEndpoint(UUID id, UUID formId) {
        return jdbc.sql("SELECT * FROM webhook_endpoint WHERE id = :id AND form_id = :form AND deleted_at IS NULL")
                .param("id", id)
                .param("form", formId)
                .query(PlatformRepository::endpoint)
                .optional();
    }

    public Optional<Endpoint> findEndpointById(UUID id) {
        return jdbc.sql("SELECT * FROM webhook_endpoint WHERE id = :id").param("id", id).query(PlatformRepository::endpoint).optional();
    }

    public void deleteEndpoint(UUID id, Instant at) {
        jdbc.sql("UPDATE webhook_endpoint SET deleted_at = :at WHERE id = :id").param("at", odt(at)).param("id", id).update();
        jdbc.sql("UPDATE webhook_delivery SET status = 'dead', last_error = 'Endpoint deleted' WHERE endpoint_id = :id AND status = 'pending'")
                .param("id", id)
                .update();
    }

    public void insertDelivery(UUID endpointId, UUID submissionId, String event, byte[] payload, Instant now) {
        jdbc.sql("""
                        INSERT INTO webhook_delivery (endpoint_id, submission_id, event, payload, next_attempt_at, created_at)
                        VALUES (:endpoint, :submission, :event, :payload, :now, :now)
                        """)
                .param("endpoint", endpointId)
                .param("submission", submissionId)
                .param("event", event)
                .param("payload", payload)
                .param("now", odt(now))
                .update();
    }

    /** Claims due deliveries by pushing their next attempt into the future (a lease). */
    public List<Delivery> claimDue(Instant now, Instant leaseUntil, int limit) {
        return jdbc.sql("""
                        UPDATE webhook_delivery SET next_attempt_at = :lease
                        WHERE id IN (SELECT id FROM webhook_delivery
                                     WHERE status = 'pending' AND next_attempt_at <= :now
                                     ORDER BY next_attempt_at LIMIT :limit FOR UPDATE SKIP LOCKED)
                        RETURNING *
                        """)
                .param("lease", odt(leaseUntil))
                .param("now", odt(now))
                .param("limit", limit)
                .query(PlatformRepository::delivery)
                .list();
    }

    public void recordDeliveryResult(UUID id, String status, int attempts, Instant nextAttemptAt, Integer lastStatus, String lastError,
            Instant deliveredAt) {
        jdbc.sql("""
                        UPDATE webhook_delivery SET status = :status, attempts = :attempts, next_attempt_at = :next,
                            last_status = :code, last_error = :error, delivered_at = :delivered
                        WHERE id = :id
                        """)
                .param("status", status)
                .param("attempts", attempts)
                .param("next", odt(nextAttemptAt))
                .param("code", lastStatus)
                .param("error", lastError)
                .param("delivered", odt(deliveredAt))
                .param("id", id)
                .update();
    }

    public List<Delivery> listDeliveries(UUID endpointId, int limit) {
        return jdbc.sql("SELECT * FROM webhook_delivery WHERE endpoint_id = :endpoint ORDER BY created_at DESC LIMIT :limit")
                .param("endpoint", endpointId)
                .param("limit", limit)
                .query(PlatformRepository::delivery)
                .list();
    }

    public Optional<Delivery> findDelivery(UUID id) {
        return jdbc.sql("SELECT * FROM webhook_delivery WHERE id = :id").param("id", id).query(PlatformRepository::delivery).optional();
    }

    public void requeueDelivery(UUID id, Instant now) {
        jdbc.sql("UPDATE webhook_delivery SET status = 'pending', next_attempt_at = :now, attempts = 0 WHERE id = :id")
                .param("now", odt(now))
                .param("id", id)
                .update();
    }
}
