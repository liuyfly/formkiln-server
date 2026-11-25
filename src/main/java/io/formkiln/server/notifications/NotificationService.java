package io.formkiln.server.notifications;

import io.formkiln.server.accounts.AccountRepository;
import io.formkiln.server.accounts.Mailer;
import io.formkiln.server.accounts.Secrets;
import io.formkiln.server.form.FieldIndex;
import io.formkiln.server.forms.FormService.NotFoundException;
import io.formkiln.server.notifications.NotificationRepository.Mail;
import io.formkiln.server.notifications.NotificationRepository.Settings;
import io.formkiln.server.platform.DraftService;
import io.formkiln.server.platform.PlatformRepository;
import io.formkiln.server.platform.PlatformRepository.FormRow;
import io.formkiln.server.platform.RuntimeException4xx;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Email notifications: a team notification and a confirmation to the submitter on every
 * submission, and "continue later" links for drafts. Messages go through an outbox (encrypted at
 * rest) and are sent by {@link #deliverDue()} with retries.
 */
@Service
public class NotificationService {

    public static final int MAX_RECIPIENTS = 20;
    public static final int MAX_BODY = 10_000;
    static final int MAX_ATTEMPTS = 5;
    static final Duration FIRST_RETRY = Duration.ofMinutes(1);
    static final Duration LEASE = Duration.ofMinutes(2);
    /** "Continue later" emails per draft and day. */
    static final int RESUME_PER_DAY = 3;

    static final String DEFAULT_CONFIRMATION_SUBJECT = "We received your response: {{form.title}}";
    static final String DEFAULT_CONFIRMATION_BODY = """
            Thank you. We received your response to {{form.title}}.

            Reference: {{submission.id}}
            Submitted: {{submission.submittedAt}}

            {{answers}}
            """;

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern KEY = Pattern.compile("^[a-z][a-zA-Z0-9_]{0,63}$");
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** Settings as shown and edited in the admin; templates fall back to the defaults. */
    public record SettingsView(List<String> teamRecipients, boolean teamIncludeAnswers, boolean confirmationEnabled,
            String confirmationEmailField, String confirmationSubject, String confirmationBody, boolean resumeEnabled,
            String defaultConfirmationSubject, String defaultConfirmationBody) {}

    public record MailView(String id, String kind, String submissionId, String recipient, String status, int attempts,
            String lastError, Instant createdAt, Instant sentAt) {}

    /** A queued message (serialized and encrypted in the outbox). */
    public record Outgoing(String to, String subject, String body) {}

    private final NotificationRepository repository;
    private final PlatformRepository platform;
    private final DraftService drafts;
    private final Secrets secrets;
    private final Mailer mailer;
    private final AccountRepository audit;
    private final JsonMapper json;
    private final Clock clock;
    private final String adminUrl;
    private final String hostedUrl;

    public NotificationService(NotificationRepository repository, PlatformRepository platform, DraftService drafts, Secrets secrets,
            Mailer mailer, AccountRepository audit, JsonMapper json, Clock clock,
            @Value("${formkiln.admin-url}") String adminUrl,
            @Value("${formkiln.hosted-url:http://localhost:3100}") String hostedUrl) {
        this.repository = repository;
        this.platform = platform;
        this.drafts = drafts;
        this.secrets = secrets;
        this.mailer = mailer;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
        this.adminUrl = adminUrl.replaceAll("/+$", "");
        this.hostedUrl = hostedUrl.replaceAll("/+$", "");
    }

    private FormRow form(String key) {
        return platform.findForm(key).orElseThrow(() -> new NotFoundException("No form " + key));
    }

    public Settings settingsOf(UUID formId) {
        return repository.findSettings(formId).orElse(Settings.DEFAULT);
    }

    static boolean isEmail(String value) {
        return value != null && value.length() <= 254 && EMAIL.matcher(value).matches();
    }

    // ---------------------------------------------------------------- settings

    @Transactional(readOnly = true)
    public SettingsView settings(String formKey) {
        Settings s = settingsOf(form(formKey).id());
        return new SettingsView(s.teamRecipients(), s.teamIncludeAnswers(), s.confirmationEnabled(), s.confirmationEmailField(),
                s.confirmationSubject(), s.confirmationBody(), s.resumeEnabled(), DEFAULT_CONFIRMATION_SUBJECT,
                DEFAULT_CONFIRMATION_BODY);
    }

    private static RuntimeException4xx invalid(String code, String message) {
        return new RuntimeException4xx(HttpStatus.BAD_REQUEST, code, message);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @Transactional
    public SettingsView update(String formKey, Settings input, String actorType, UUID actorId, String ip) {
        FormRow form = form(formKey);
        Set<String> recipients = new LinkedHashSet<>();
        for (String recipient : input.teamRecipients() == null ? List.<String>of() : input.teamRecipients()) {
            String address = recipient == null ? "" : recipient.strip().toLowerCase(Locale.ROOT);
            if (!isEmail(address)) {
                throw invalid("invalidRecipient", "Not an email address: " + recipient);
            }
            recipients.add(address);
        }
        if (recipients.size() > MAX_RECIPIENTS) {
            throw invalid("tooManyRecipients", "At most " + MAX_RECIPIENTS + " recipients.");
        }
        String field = blankToNull(input.confirmationEmailField());
        if (field != null && !KEY.matcher(field).matches()) {
            throw invalid("invalidField", "Not a field key: " + field);
        }
        if (input.confirmationEnabled() && field == null) {
            throw invalid("confirmationField", "Choose the email field that receives the confirmation.");
        }
        String subject = blankToNull(input.confirmationSubject());
        String body = blankToNull(input.confirmationBody());
        if (subject != null && subject.length() > MailTemplate.MAX_SUBJECT) {
            throw invalid("subjectTooLong", "The subject must be at most " + MailTemplate.MAX_SUBJECT + " characters.");
        }
        if (body != null && body.length() > MAX_BODY) {
            throw invalid("bodyTooLong", "The message must be at most " + MAX_BODY + " characters.");
        }
        Settings saved = new Settings(List.copyOf(recipients), input.teamIncludeAnswers(), input.confirmationEnabled(), field,
                subject, body, input.resumeEnabled());
        repository.saveSettings(form.id(), saved, clock.instant());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("teamRecipients", recipients.size());
        details.put("confirmation", saved.confirmationEnabled());
        details.put("resume", saved.resumeEnabled());
        audit.audit(actorType, actorId, "notifications.updated", formKey, ip, json.writeValueAsString(details));
        return settings(formKey);
    }

    // ---------------------------------------------------------------- enqueue

    private void enqueue(UUID formId, String kind, UUID submissionId, UUID draftId, Outgoing message) {
        byte[] payload = secrets.encrypt(json.writeValueAsBytes(message));
        repository.insert(formId, kind, submissionId, draftId, MailTemplate.mask(message.to()), payload, clock.instant());
    }

    /**
     * Queues the team notification and the confirmation for a stored submission. Runs inside the
     * submission's transaction. {@code data} is the plaintext submission data.
     */
    public void onSubmission(UUID formId, String formKey, Map<String, Object> definition, UUID submissionId, Instant submittedAt,
            Map<String, Object> data, String locale) {
        Settings settings = settingsOf(formId);
        if (settings.teamRecipients().isEmpty() && !settings.confirmationEnabled()) {
            return;
        }
        FieldIndex fields = FieldIndex.of(definition);
        String defaultLocale = definition.get("settings") instanceof Map<?, ?> s && s.get("defaultLocale") instanceof String l ? l : "en";
        String textLocale = locale == null ? defaultLocale : locale;
        String title = AnswerText.text(definition.get("title"), textLocale);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("form.title", title);
        values.put("submission.id", submissionId.toString());
        values.put("submission.submittedAt", submittedAt.toString());
        values.put("answers", AnswerText.lines(fields, data, textLocale));
        for (FieldIndex.AnswerField field : fields.answerFields()) {
            if (!field.sensitive()) {
                String shown = AnswerText.value(field, data.get(field.key()), textLocale);
                values.put("answer." + field.key(), shown == null ? "" : shown);
            }
        }

        if (!settings.teamRecipients().isEmpty()) {
            String body = "A new response to " + title + " was submitted on " + submittedAt + ".\n\n"
                    + "View it in Formkiln: " + adminUrl + "/forms/" + formKey + "\n"
                    + (settings.teamIncludeAnswers() && !values.get("answers").isEmpty() ? "\n" + values.get("answers") + "\n" : "");
            String subject = MailTemplate.subject("New response: {{form.title}}", values);
            for (String recipient : settings.teamRecipients()) {
                enqueue(formId, "team", submissionId, null, new Outgoing(recipient, subject, body));
            }
        }
        if (settings.confirmationEnabled() && settings.confirmationEmailField() != null
                && data.get(settings.confirmationEmailField()) instanceof String address && isEmail(address)) {
            String subject = MailTemplate.subject(
                    settings.confirmationSubject() == null ? DEFAULT_CONFIRMATION_SUBJECT : settings.confirmationSubject(), values);
            String body = MailTemplate.render(
                    settings.confirmationBody() == null ? DEFAULT_CONFIRMATION_BODY : settings.confirmationBody(), values).strip() + "\n";
            enqueue(formId, "confirmation", submissionId, null, new Outgoing(address, subject, body));
        }
    }

    /** Whether a hosted form offers "email me a link to continue later". */
    public boolean resumeByEmail(UUID formId, String accessMode) {
        return "public".equals(accessMode) && settingsOf(formId).resumeEnabled();
    }

    /**
     * Emails a link that reopens the draft on the hosted site. Public forms only (token forms are
     * reopened by the embedding application). Limited per draft and day.
     */
    @Transactional
    public void requestResumeLink(String formKey, UUID formId, String accessMode, String draftToken, String email, String title) {
        if (!resumeByEmail(formId, accessMode)) {
            throw new RuntimeException4xx(HttpStatus.NOT_FOUND, "resumeUnavailable", "This form does not send links to continue later.");
        }
        String address = email == null ? "" : email.strip();
        if (!isEmail(address)) {
            throw invalid("invalidEmail", "Enter a valid email address.");
        }
        UUID draftId = drafts.idOf(formId, draftToken);
        Instant now = clock.instant();
        if (repository.countForDraftSince(draftId, now.minus(Duration.ofDays(1))) >= RESUME_PER_DAY) {
            throw new RuntimeException4xx(HttpStatus.TOO_MANY_REQUESTS, "tooManyEmails",
                    "Too many emails for this form today. Use the link you already received.");
        }
        String link = hostedUrl + "/f/" + formKey + "#draft=" + draftToken;
        String subject = MailTemplate.subject("Continue your form: {{form.title}}", Map.of("form.title", title));
        String body = """
                Use this link to continue %s where you left off:

                %s

                The link works for 30 days after your last change. Anyone with it can see and change your answers, so do not forward it.
                """.formatted(title, link);
        enqueue(formId, "resume", null, draftId, new Outgoing(address, subject, body));
    }

    // ---------------------------------------------------------------- delivery

    /** Sends every due message once. Called on a schedule; safe to run on several instances. */
    @Scheduled(fixedDelayString = "${formkiln.mail.poll-interval:5000}", initialDelayString = "${formkiln.mail.poll-interval:5000}")
    public void deliverDue() {
        Instant now = clock.instant();
        for (Mail mail : repository.claimDue(now, now.plus(LEASE), 20)) {
            deliver(mail);
        }
    }

    private void deliver(Mail mail) {
        int attempts = mail.attempts() + 1;
        Instant now = clock.instant();
        String error;
        try {
            Outgoing message = json.readValue(secrets.decrypt(mail.payload()), Outgoing.class);
            mailer.send(new Mailer.Message(message.to(), message.subject(), message.body()));
            repository.recordResult(mail.id(), "sent", attempts, now, null, now);
            return;
        } catch (RuntimeException e) {
            error = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
        boolean dead = attempts >= MAX_ATTEMPTS;
        Instant next = now.plus(FIRST_RETRY.multipliedBy(1L << Math.min(attempts - 1, 20)));
        repository.recordResult(mail.id(), dead ? "dead" : "pending", attempts, next, error.length() > 512 ? error.substring(0, 512) : error, null);
        log.info("Mail {} attempt {} failed: {}", mail.id(), attempts, error);
    }

    @Transactional(readOnly = true)
    public List<MailView> log(String formKey, int limit) {
        List<MailView> result = new ArrayList<>();
        for (Mail mail : repository.list(form(formKey).id(), limit)) {
            result.add(new MailView(mail.id().toString(), mail.kind(), mail.submissionId() == null ? null : mail.submissionId().toString(),
                    mail.recipientHint(), mail.status(), mail.attempts(), mail.lastError(), mail.createdAt(), mail.sentAt()));
        }
        return result;
    }
}
