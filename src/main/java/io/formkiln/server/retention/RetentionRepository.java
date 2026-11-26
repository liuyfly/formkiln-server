package io.formkiln.server.retention;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Deleting submissions with everything that holds their data, and retention settings. */
@Repository
public class RetentionRepository {

    /** A submission found for erasure, with its form key. */
    public record Match(UUID id, String formKey) {}

    public record FormRetention(UUID formId, String key, UUID workspaceId, int days) {}

    private final JdbcClient jdbc;

    public RetentionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime odt(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public void setRetention(UUID formId, Integer days) {
        jdbc.sql("UPDATE form SET retention_days = :days WHERE id = :id").param("days", days).param("id", formId).update();
    }

    public Integer retentionOf(UUID formId) {
        // NULL means "keep until deleted"; Optional/findFirst cannot hold null.
        List<Integer> days = jdbc.sql("SELECT retention_days FROM form WHERE id = :id")
                .param("id", formId)
                .query((rs, n) -> (Integer) rs.getObject(1))
                .list();
        return days.isEmpty() ? null : days.getFirst();
    }

    public List<FormRetention> formsWithRetention() {
        return jdbc.sql("SELECT id, key, workspace_id, retention_days FROM form WHERE retention_days IS NOT NULL")
                .query((rs, n) -> new FormRetention(rs.getObject("id", UUID.class), rs.getString("key"),
                        rs.getObject("workspace_id", UUID.class), rs.getInt("retention_days")))
                .list();
    }

    /** Whether the submission belongs to the form. */
    public boolean belongsTo(UUID submissionId, UUID formId) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM submission s JOIN form_version v ON v.id = s.form_version_id
                        WHERE s.id = :id AND v.form_id = :form
                        """)
                .param("id", submissionId)
                .param("form", formId)
                .query(Integer.class)
                .single() == 1;
    }

    public List<UUID> expired(UUID formId, Instant cutoff, int limit) {
        return jdbc.sql("""
                        SELECT s.id FROM submission s JOIN form_version v ON v.id = s.form_version_id
                        WHERE v.form_id = :form AND s.submitted_at < :cutoff
                        ORDER BY s.submitted_at LIMIT :limit
                        """)
                .param("form", formId)
                .param("cutoff", odt(cutoff))
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    /** Submissions whose subject is {@code subject}. */
    public List<Match> bySubject(UUID workspace, String subject) {
        return jdbc.sql("""
                        SELECT s.id, f.key FROM submission s
                        JOIN form_version v ON v.id = s.form_version_id JOIN form f ON f.id = v.form_id
                        WHERE s.subject = :subject AND f.workspace_id = :ws
                        """)
                .param("subject", subject)
                .param("ws", workspace)
                .query((rs, n) -> new Match(rs.getObject("id", UUID.class), rs.getString("key")))
                .list();
    }

    /**
     * Submissions that contain {@code email} as a string value anywhere in their data (any field,
     * repeater items included), case-insensitively. Encrypted (sensitive) values are not searched.
     */
    public List<Match> byEmail(UUID workspace, String email) {
        return jdbc.sql("""
                        SELECT s.id, f.key FROM submission s
                        JOIN form_version v ON v.id = s.form_version_id JOIN form f ON f.id = v.form_id
                        WHERE f.workspace_id = :ws
                          AND EXISTS (SELECT 1 FROM jsonb_path_query(s.data, 'strict $.**') AS value
                                      WHERE jsonb_typeof(value) = 'string' AND lower(value #>> '{}') = lower(:email))
                        """)
                .param("email", email)
                .param("ws", workspace)
                .query((rs, n) -> new Match(rs.getObject("id", UUID.class), rs.getString("key")))
                .list();
    }

    /**
     * Deletes submissions with their webhook deliveries, queued emails and file rows. Returns the
     * storage keys of the deleted files (the objects are removed after commit).
     */
    public List<String> delete(List<UUID> submissionIds) {
        if (submissionIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> ids = Map.of("ids", submissionIds);
        jdbc.sql("DELETE FROM webhook_delivery WHERE submission_id IN (:ids)").params(ids).update();
        jdbc.sql("DELETE FROM mail_outbox WHERE submission_id IN (:ids)").params(ids).update();
        List<String> keys = jdbc.sql("DELETE FROM file_object WHERE submission_id IN (:ids) RETURNING storage_key")
                .params(ids)
                .query(String.class)
                .list();
        jdbc.sql("DELETE FROM submission WHERE id IN (:ids)").params(ids).update();
        return keys;
    }
}
