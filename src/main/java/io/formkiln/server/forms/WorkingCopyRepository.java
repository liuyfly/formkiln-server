package io.formkiln.server.forms;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Forms that may not be published yet, and their working copies. */
@Repository
public class WorkingCopyRepository {

    /** A form row with its latest published version (null when never published). */
    public record FormRef(UUID id, String key, Integer latestVersion) {}

    public record StoredCopy(UUID formId, String documentJson, long revision, Integer baseVersion, Instant updatedAt,
            UUID updatedBy, String updatedByEmail) {}

    private final JdbcClient jdbc;

    public WorkingCopyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The form, optionally locking its row (serializes saves and publishes of one form). */
    public Optional<FormRef> findForm(String key, boolean lock) {
        Optional<UUID> id = jdbc.sql("SELECT id FROM form WHERE key = :key" + (lock ? " FOR UPDATE" : ""))
                .param("key", key)
                .query(UUID.class)
                .optional();
        return id.map(formId -> {
            // Versions start at 1, so 0 means the form was never published.
            int latest = jdbc.sql("SELECT COALESCE(MAX(version), 0) FROM form_version WHERE form_id = :id")
                    .param("id", formId)
                    .query(Integer.class)
                    .single();
            return new FormRef(formId, key, latest == 0 ? null : latest);
        });
    }

    /** Creates an unpublished form; empty when the key is taken. */
    public Optional<UUID> createForm(String key) {
        return jdbc.sql("INSERT INTO form (key) VALUES (:key) ON CONFLICT (key) DO NOTHING RETURNING id")
                .param("key", key)
                .query(UUID.class)
                .optional();
    }

    public Optional<StoredCopy> find(UUID formId) {
        return jdbc.sql("""
                        SELECT c.form_id, c.document::text AS document, c.revision, c.base_version, c.updated_at,
                               c.updated_by, u.email
                        FROM form_working_copy c LEFT JOIN admin_user u ON u.id = c.updated_by
                        WHERE c.form_id = :id
                        """)
                .param("id", formId)
                .query((rs, n) -> new StoredCopy(
                        rs.getObject("form_id", UUID.class),
                        rs.getString("document"),
                        rs.getLong("revision"),
                        (Integer) rs.getObject("base_version"),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("updated_by", UUID.class),
                        rs.getString("email")))
                .optional();
    }

    /** First save of a copy. Returns false when one exists already (a concurrent first save). */
    public boolean insert(UUID formId, String documentJson, Integer baseVersion, UUID updatedBy) {
        return jdbc.sql("""
                        INSERT INTO form_working_copy (form_id, document, revision, base_version, updated_by)
                        VALUES (:id, CAST(:document AS jsonb), nextval('form_working_copy_revision'), :base, :user)
                        ON CONFLICT (form_id) DO NOTHING
                        """)
                .param("id", formId)
                .param("document", documentJson)
                .param("base", baseVersion)
                .param("user", updatedBy)
                .update() == 1;
    }

    /** Saves over {@code expectedRevision}; returns false when the copy has moved on. */
    public boolean update(UUID formId, long expectedRevision, String documentJson, UUID updatedBy) {
        return jdbc.sql("""
                        UPDATE form_working_copy
                        SET document = CAST(:document AS jsonb), revision = nextval('form_working_copy_revision'), updated_at = now(),
                            updated_by = :user
                        WHERE form_id = :id AND revision = :revision
                        """)
                .param("id", formId)
                .param("revision", expectedRevision)
                .param("document", documentJson)
                .param("user", updatedBy)
                .update() == 1;
    }

    public void delete(UUID formId) {
        jdbc.sql("DELETE FROM form_working_copy WHERE form_id = :id").param("id", formId).update();
    }
}
