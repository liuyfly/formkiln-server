package io.formkiln.server.forms;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence for forms, versions and submissions. JSON is stored as text in jsonb columns. */
@Repository
public class FormRepository {

    /** A published, immutable form version. */
    public record StoredVersion(UUID id, UUID formId, String formKey, int version, String definitionJson, Instant publishedAt) {}

    /** A summary row for listings. */
    /** A form; {@code latestVersion} and {@code latestPublishedAt} are null until it is published. */
    public record FormSummary(String key, String accessMode, Integer latestVersion, Instant latestPublishedAt, long submissionCount,
            boolean unpublishedChanges) {}

    /** A stored submission. */
    public record StoredSubmission(UUID id, int version, String dataJson, String outcome, Instant submittedAt, String subject) {}

    private final JdbcClient jdbc;

    public FormRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant instant(OffsetDateTime value) {
        return value.toInstant();
    }

    /** Creates the form if needed and inserts the next version; returns the stored version. */
    public StoredVersion publish(String key, int schemaVersion, String definitionJson) {
        jdbc.sql("INSERT INTO form (key) VALUES (:key) ON CONFLICT (key) DO NOTHING").param("key", key).update();
        // Lock the form row so concurrent publishes get distinct version numbers.
        UUID formId = jdbc.sql("SELECT id FROM form WHERE key = :key FOR UPDATE")
                .param("key", key)
                .query(UUID.class)
                .single();
        return jdbc.sql("""
                        INSERT INTO form_version (form_id, version, schema_version, definition)
                        VALUES (:formId,
                                (SELECT COALESCE(MAX(version), 0) + 1 FROM form_version WHERE form_id = :formId),
                                :schemaVersion, CAST(:definition AS jsonb))
                        RETURNING id, version, definition::text AS definition, published_at
                        """)
                .param("formId", formId)
                .param("schemaVersion", schemaVersion)
                .param("definition", definitionJson)
                .query((rs, n) -> new StoredVersion(
                        rs.getObject("id", UUID.class),
                        formId,
                        key,
                        rs.getInt("version"),
                        rs.getString("definition"),
                        instant(rs.getObject("published_at", OffsetDateTime.class))))
                .single();
    }

    /** The given version, or the latest version when {@code version} is null. */
    public Optional<StoredVersion> findVersion(String key, Integer version) {
        return jdbc.sql("""
                        SELECT v.id, v.form_id, v.version, v.definition::text AS definition, v.published_at
                        FROM form_version v JOIN form f ON f.id = v.form_id
                        WHERE f.key = :key AND (CAST(:version AS integer) IS NULL OR v.version = :version)
                        ORDER BY v.version DESC
                        LIMIT 1
                        """)
                .param("key", key)
                .param("version", version)
                .query((rs, n) -> new StoredVersion(
                        rs.getObject("id", UUID.class),
                        rs.getObject("form_id", UUID.class),
                        key,
                        rs.getInt("version"),
                        rs.getString("definition"),
                        instant(rs.getObject("published_at", OffsetDateTime.class))))
                .optional();
    }

    public List<FormSummary> listForms() {
        return jdbc.sql("""
                        SELECT f.key, f.access_mode,
                               MAX(v.version) AS latest_version,
                               MAX(v.published_at) AS latest_published_at,
                               (SELECT COUNT(*) FROM submission s JOIN form_version sv ON sv.id = s.form_version_id
                                WHERE sv.form_id = f.id) AS submission_count,
                               EXISTS (SELECT 1 FROM form_working_copy c WHERE c.form_id = f.id) AS unpublished_changes
                        FROM form f LEFT JOIN form_version v ON v.form_id = f.id
                        GROUP BY f.id, f.key, f.access_mode
                        ORDER BY f.key
                        """)
                .query((rs, n) -> {
                    OffsetDateTime published = rs.getObject("latest_published_at", OffsetDateTime.class);
                    return new FormSummary(
                            rs.getString("key"),
                            rs.getString("access_mode"),
                            (Integer) rs.getObject("latest_version"),
                            published == null ? null : published.toInstant(),
                            rs.getLong("submission_count"),
                            rs.getBoolean("unpublished_changes"));
                })
                .list();
    }

    public List<StoredVersion> listVersions(String key) {
        return jdbc.sql("""
                        SELECT v.id, v.form_id, v.version, v.definition::text AS definition, v.published_at
                        FROM form_version v JOIN form f ON f.id = v.form_id
                        WHERE f.key = :key
                        ORDER BY v.version DESC
                        """)
                .param("key", key)
                .query((rs, n) -> new StoredVersion(
                        rs.getObject("id", UUID.class),
                        rs.getObject("form_id", UUID.class),
                        key,
                        rs.getInt("version"),
                        rs.getString("definition"),
                        instant(rs.getObject("published_at", OffsetDateTime.class))))
                .list();
    }

    public StoredSubmission insertSubmission(StoredVersion version, String dataJson, String outcome, Instant submittedAt,
            String subject) {
        return jdbc.sql("""
                        INSERT INTO submission (form_version_id, data, outcome, submitted_at, subject)
                        VALUES (:versionId, CAST(:data AS jsonb), :outcome, :submittedAt, :subject)
                        RETURNING id, data::text AS data, outcome, submitted_at, subject
                        """)
                .param("versionId", version.id())
                .param("data", dataJson)
                .param("outcome", outcome)
                .param("submittedAt", OffsetDateTime.ofInstant(submittedAt, ZoneOffset.UTC))
                .param("subject", subject)
                .query((rs, n) -> new StoredSubmission(
                        rs.getObject("id", UUID.class),
                        version.version(),
                        rs.getString("data"),
                        rs.getString("outcome"),
                        instant(rs.getObject("submitted_at", OffsetDateTime.class)),
                        rs.getString("subject")))
                .single();
    }

    /** A submission with the definition of its version (needed to decrypt or mask sensitive fields). */
    public record ListedSubmission(StoredSubmission submission, String definitionJson) {}

    /**
     * Newest first. The exclusive cursor is ({@code before}, {@code beforeId}) — the submission time
     * and id of the last row of the previous page — so rows that share a submission time are not
     * skipped. Without {@code beforeId} only the time is compared (older clients).
     */
    public List<ListedSubmission> listSubmissions(String key, Instant before, UUID beforeId, int limit) {
        return jdbc.sql("""
                        SELECT s.id, v.version, v.definition::text AS definition, s.data::text AS data, s.outcome,
                               s.submitted_at, s.subject
                        FROM submission s
                        JOIN form_version v ON v.id = s.form_version_id
                        JOIN form f ON f.id = v.form_id
                        WHERE f.key = :key
                          AND (CAST(:before AS timestamptz) IS NULL
                               OR (CAST(:beforeId AS uuid) IS NULL AND s.submitted_at < CAST(:before AS timestamptz))
                               OR (s.submitted_at, s.id) < (CAST(:before AS timestamptz), CAST(:beforeId AS uuid)))
                        ORDER BY s.submitted_at DESC, s.id DESC
                        LIMIT :limit
                        """)
                .param("key", key)
                .param("before", before == null ? null : OffsetDateTime.ofInstant(before, ZoneOffset.UTC))
                .param("beforeId", beforeId)
                .param("limit", limit)
                .query((rs, n) -> new ListedSubmission(
                        new StoredSubmission(
                                rs.getObject("id", UUID.class),
                                rs.getInt("version"),
                                rs.getString("data"),
                                rs.getString("outcome"),
                                instant(rs.getObject("submitted_at", OffsetDateTime.class)),
                                rs.getString("subject")),
                        rs.getString("definition")))
                .list();
    }

    /**
     * A page of submissions with their version number, newest first, after the keyset cursor
     * ({@code afterAt}, {@code afterId}) exclusive. Used by the export, which must not skip rows
     * that share a submission time.
     */
    public List<StoredSubmission> exportPage(String key, Instant afterAt, UUID afterId, int limit) {
        return jdbc.sql("""
                        SELECT s.id, v.version, s.data::text AS data, s.outcome, s.submitted_at, s.subject
                        FROM submission s
                        JOIN form_version v ON v.id = s.form_version_id
                        JOIN form f ON f.id = v.form_id
                        WHERE f.key = :key
                          AND (CAST(:at AS timestamptz) IS NULL OR (s.submitted_at, s.id) < (CAST(:at AS timestamptz), CAST(:id AS uuid)))
                        ORDER BY s.submitted_at DESC, s.id DESC
                        LIMIT :limit
                        """)
                .param("key", key)
                .param("at", afterAt == null ? null : OffsetDateTime.ofInstant(afterAt, ZoneOffset.UTC))
                .param("id", afterId)
                .param("limit", limit)
                .query((rs, n) -> new StoredSubmission(
                        rs.getObject("id", UUID.class),
                        rs.getInt("version"),
                        rs.getString("data"),
                        rs.getString("outcome"),
                        instant(rs.getObject("submitted_at", OffsetDateTime.class)),
                        rs.getString("subject")))
                .list();
    }
}
