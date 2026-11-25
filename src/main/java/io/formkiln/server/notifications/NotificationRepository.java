package io.formkiln.server.notifications;

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

/** Notification settings and the mail outbox. */
@Repository
public class NotificationRepository {

    public record Settings(List<String> teamRecipients, boolean teamIncludeAnswers, boolean confirmationEnabled,
            String confirmationEmailField, String confirmationSubject, String confirmationBody, boolean resumeEnabled) {

        public static final Settings DEFAULT = new Settings(List.of(), false, false, null, null, null, false);
    }

    public record Mail(UUID id, UUID formId, String kind, UUID submissionId, String recipientHint, byte[] payload, String status,
            int attempts, Instant nextAttemptAt, String lastError, Instant createdAt, Instant sentAt) {}

    private final JdbcClient jdbc;
    private final tools.jackson.databind.json.JsonMapper json;

    public NotificationRepository(JdbcClient jdbc, tools.jackson.databind.json.JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private static OffsetDateTime odt(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    // ---------------------------------------------------------------- settings

    @SuppressWarnings("unchecked")
    public Optional<Settings> findSettings(UUID formId) {
        return jdbc.sql("SELECT * FROM form_notification_settings WHERE form_id = :id")
                .param("id", formId)
                .query((rs, n) -> new Settings(
                        List.copyOf((List<String>) json.readValue(rs.getString("team_recipients"), List.class)),
                        rs.getBoolean("team_include_answers"),
                        rs.getBoolean("confirmation_enabled"),
                        rs.getString("confirmation_email_field"),
                        rs.getString("confirmation_subject"),
                        rs.getString("confirmation_body"),
                        rs.getBoolean("resume_enabled")))
                .optional();
    }

    public void saveSettings(UUID formId, Settings settings, Instant now) {
        jdbc.sql("""
                        INSERT INTO form_notification_settings (form_id, team_recipients, team_include_answers, confirmation_enabled,
                            confirmation_email_field, confirmation_subject, confirmation_body, resume_enabled, updated_at)
                        VALUES (:id, CAST(:recipients AS jsonb), :include, :confirmation, :field, :subject, :body, :resume, :now)
                        ON CONFLICT (form_id) DO UPDATE SET
                            team_recipients = EXCLUDED.team_recipients, team_include_answers = EXCLUDED.team_include_answers,
                            confirmation_enabled = EXCLUDED.confirmation_enabled,
                            confirmation_email_field = EXCLUDED.confirmation_email_field,
                            confirmation_subject = EXCLUDED.confirmation_subject, confirmation_body = EXCLUDED.confirmation_body,
                            resume_enabled = EXCLUDED.resume_enabled, updated_at = EXCLUDED.updated_at
                        """)
                .param("id", formId)
                .param("recipients", json.writeValueAsString(settings.teamRecipients()))
                .param("include", settings.teamIncludeAnswers())
                .param("confirmation", settings.confirmationEnabled())
                .param("field", settings.confirmationEmailField())
                .param("subject", settings.confirmationSubject())
                .param("body", settings.confirmationBody())
                .param("resume", settings.resumeEnabled())
                .param("now", odt(now))
                .update();
    }

    // ---------------------------------------------------------------- outbox

    private static Mail mail(ResultSet rs, int n) throws SQLException {
        return new Mail(rs.getObject("id", UUID.class), rs.getObject("form_id", UUID.class), rs.getString("kind"),
                rs.getObject("submission_id", UUID.class), rs.getString("recipient_hint"), rs.getBytes("payload"),
                rs.getString("status"), rs.getInt("attempts"), instant(rs, "next_attempt_at"), rs.getString("last_error"),
                instant(rs, "created_at"), instant(rs, "sent_at"));
    }

    public void insert(UUID formId, String kind, UUID submissionId, UUID draftId, String recipientHint, byte[] payload, Instant now) {
        jdbc.sql("""
                        INSERT INTO mail_outbox (form_id, kind, submission_id, draft_id, recipient_hint, payload, next_attempt_at, created_at)
                        VALUES (:form, :kind, :submission, :draft, :hint, :payload, :now, :now)
                        """)
                .param("form", formId)
                .param("kind", kind)
                .param("submission", submissionId)
                .param("draft", draftId)
                .param("hint", recipientHint)
                .param("payload", payload)
                .param("now", odt(now))
                .update();
    }

    /** Claims due messages by pushing their next attempt into the future (a lease). */
    public List<Mail> claimDue(Instant now, Instant leaseUntil, int limit) {
        return jdbc.sql("""
                        UPDATE mail_outbox SET next_attempt_at = :lease
                        WHERE id IN (SELECT id FROM mail_outbox
                                     WHERE status = 'pending' AND next_attempt_at <= :now
                                     ORDER BY next_attempt_at LIMIT :limit FOR UPDATE SKIP LOCKED)
                        RETURNING *
                        """)
                .param("lease", odt(leaseUntil))
                .param("now", odt(now))
                .param("limit", limit)
                .query(NotificationRepository::mail)
                .list();
    }

    public void recordResult(UUID id, String status, int attempts, Instant next, String error, Instant sentAt) {
        jdbc.sql("""
                        UPDATE mail_outbox SET status = :status, attempts = :attempts, next_attempt_at = :next,
                            last_error = :error, sent_at = :sent
                        WHERE id = :id
                        """)
                .param("status", status)
                .param("attempts", attempts)
                .param("next", odt(next))
                .param("error", error)
                .param("sent", odt(sentAt))
                .param("id", id)
                .update();
    }

    public List<Mail> list(UUID formId, int limit) {
        return jdbc.sql("SELECT * FROM mail_outbox WHERE form_id = :form ORDER BY created_at DESC LIMIT :limit")
                .param("form", formId)
                .param("limit", limit)
                .query(NotificationRepository::mail)
                .list();
    }

    public int countForDraftSince(UUID draftId, Instant since) {
        return jdbc.sql("SELECT COUNT(*) FROM mail_outbox WHERE draft_id = :draft AND created_at > :since")
                .param("draft", draftId)
                .param("since", odt(since))
                .query(Integer.class)
                .single();
    }
}
