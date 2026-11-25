package io.formkiln.server.forms;

import io.formkiln.server.accounts.AccountRepository;
import io.formkiln.server.expression.Values;
import io.formkiln.server.form.FieldIndex;
import io.formkiln.server.platform.CaptchaVerifier;
import io.formkiln.server.platform.DraftService;
import io.formkiln.server.platform.EmbedTokens;
import io.formkiln.server.platform.FileService;
import io.formkiln.server.platform.PlatformRepository;
import io.formkiln.server.platform.RuntimeException4xx;
import io.formkiln.server.platform.SubmissionData;
import io.formkiln.server.platform.WebhookService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import io.formkiln.server.form.AnswerValidator;
import io.formkiln.server.form.AnswerValidator.FormEvaluation;
import io.formkiln.server.form.DefinitionValidator.DefinitionError;
import io.formkiln.server.forms.FormRepository.StoredSubmission;
import io.formkiln.server.forms.FormRepository.StoredVersion;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Publishing forms and accepting submissions. */
@Service
public class FormService {

    /** The form document is not valid (Form Schema §10, or structure). */
    public static class InvalidFormException extends RuntimeException {
        private final transient List<DefinitionError> errors;

        public InvalidFormException(List<DefinitionError> errors) {
            super("Invalid form document");
            this.errors = errors;
        }

        public List<DefinitionError> errors() {
            return errors;
        }
    }

    /** The answers are not valid (Form Schema §11). */
    public static class InvalidAnswersException extends RuntimeException {
        private final transient FormEvaluation evaluation;

        public InvalidAnswersException(FormEvaluation evaluation) {
            super("Invalid answers");
            this.evaluation = evaluation;
        }

        public FormEvaluation evaluation() {
            return evaluation;
        }
    }

    /** The form or version does not exist. */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    /** A published form as served to renderers. */
    public record PublishedForm(String key, int version, Instant publishedAt, Map<String, Object> definition) {}

    /** Human verification the renderer must show, or null. */
    public record Captcha(String provider, String siteKey) {}

    /** A published form as served by the runtime API. */
    public record RuntimeForm(String key, int version, Instant publishedAt, String accessMode, Captcha captcha,
            Map<String, Object> definition) {}

    /** Who may use the runtime API for a form (Form access mode, embed token subject). */
    public record Access(UUID formId, String accessMode, String subject) {}

    /** Everything a submission request carries besides the form key. */
    public record SubmissionInput(Integer version, JsonNode answers, String locale, String draftToken, String captchaToken,
            String honeypot) {}

    /** The result of an accepted submission. */
    public record AcceptedSubmission(String id, int version, String outcome, Instant submittedAt) {}

    /** A submission as returned by the management API. */
    public record SubmissionView(String id, int version, String outcome, Instant submittedAt, String subject,
            Map<String, Object> data) {}

    private static final Logger log = LoggerFactory.getLogger(FormService.class);

    private final FormRepository repository;
    private final FormDocumentValidator documentValidator;
    private final JsonMapper json;
    private final Clock clock;
    private final PlatformRepository platform;
    private final EmbedTokens embedTokens;
    private final CaptchaVerifier captcha;
    private final DraftService drafts;
    private final FileService files;
    private final WebhookService webhooks;
    private final SubmissionData submissionData;
    private final AccountRepository audit;

    public FormService(FormRepository repository, FormDocumentValidator documentValidator, JsonMapper json, Clock clock,
            PlatformRepository platform, EmbedTokens embedTokens, CaptchaVerifier captcha, DraftService drafts, FileService files,
            WebhookService webhooks, SubmissionData submissionData, AccountRepository audit) {
        this.repository = repository;
        this.documentValidator = documentValidator;
        this.json = json;
        this.clock = clock;
        this.platform = platform;
        this.embedTokens = embedTokens;
        this.captcha = captcha;
        this.drafts = drafts;
        this.files = files;
        this.webhooks = webhooks;
        this.submissionData = submissionData;
        this.audit = audit;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> canonical(String jsonText) {
        return (Map<String, Object>) Values.normalize(json.readValue(jsonText, Map.class));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> canonical(JsonNode node) {
        return (Map<String, Object>) Values.normalize(json.treeToValue(node, Map.class));
    }

    private PublishedForm view(StoredVersion version) {
        return new PublishedForm(version.formKey(), version.version(), version.publishedAt(), canonical(version.definitionJson()));
    }

    /**
     * Validates and publishes a new immutable version of the form {@code key}. The document's own
     * {@code key} must equal {@code key}.
     */
    @Transactional
    public PublishedForm publish(String key, JsonNode document) {
        if (!document.isObject()) {
            throw new InvalidFormException(List.of(new DefinitionError("", FormDocumentValidator.STRUCTURE)));
        }
        Map<String, Object> definition = canonical(document);
        List<DefinitionError> errors = documentValidator.validate(document, definition);
        if (!errors.isEmpty()) {
            throw new InvalidFormException(errors);
        }
        if (!key.equals(definition.get("key"))) {
            throw new InvalidFormException(List.of(new DefinitionError("/key", "keyMismatch")));
        }
        int schemaVersion = ((Double) definition.get("schemaVersion")).intValue();
        return view(repository.publish(key, schemaVersion, json.writeValueAsString(document)));
    }

    @Transactional(readOnly = true)
    public PublishedForm latest(String key) {
        return repository.findVersion(key, null).map(this::view)
                .orElseThrow(() -> new NotFoundException("Form " + key + " is not published"));
    }

    /** One published version with its definition. */
    @Transactional(readOnly = true)
    public PublishedForm version(String key, int version) {
        return repository.findVersion(key, version).map(this::view)
                .orElseThrow(() -> new NotFoundException("Form " + key + " version " + version + " is not published"));
    }

    @Transactional(readOnly = true)
    public List<PublishedForm> versions(String key) {
        List<PublishedForm> versions = repository.listVersions(key).stream().map(this::view).toList();
        if (versions.isEmpty()) {
            throw new NotFoundException("Form " + key + " is not published");
        }
        return versions;
    }

    @Transactional(readOnly = true)
    public List<FormRepository.FormSummary> list() {
        return repository.listForms();
    }

    // ---------------------------------------------------------------- runtime access

    /**
     * Resolves who may use the runtime API for {@code key}: anyone for {@code public} forms, and
     * holders of a valid embed token ({@code Authorization: Bearer ...}) for {@code token} forms.
     */
    @Transactional(readOnly = true)
    public Access access(String key, String authorization) {
        PlatformRepository.FormRow form = platform.findForm(key)
                .orElseThrow(() -> new NotFoundException("Form " + key + " is not published"));
        if ("public".equals(form.accessMode())) {
            return new Access(form.id(), form.accessMode(), null);
        }
        String token = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
        EmbedTokens.Claims claims = embedTokens.verify(token, key)
                .orElseThrow(() -> new RuntimeException4xx(HttpStatus.UNAUTHORIZED, "embedTokenRequired",
                        "This form needs a valid embed token."));
        return new Access(form.id(), form.accessMode(), claims.subject());
    }

    @Transactional(readOnly = true)
    public RuntimeForm runtimeForm(String key, Access access) {
        PublishedForm form = latest(key);
        Captcha challenge = "public".equals(access.accessMode()) && captcha.enabled()
                ? new Captcha(captcha.provider(), captcha.siteKey())
                : null;
        return new RuntimeForm(form.key(), form.version(), form.publishedAt(), access.accessMode(), challenge, form.definition());
    }

    @Transactional
    public void setAccessMode(String key, String mode) {
        if (!"public".equals(mode) && !"token".equals(mode)) {
            throw new RuntimeException4xx(HttpStatus.BAD_REQUEST, "invalidAccessMode", "Access mode must be public or token.");
        }
        PlatformRepository.FormRow form = platform.findForm(key)
                .orElseThrow(() -> new NotFoundException("Form " + key + " is not published"));
        platform.setAccessMode(form.id(), mode);
    }

    // ---------------------------------------------------------------- submissions

    /** Replaces the file references of every file field (top level and in repeaters) with stored metadata. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> canonicalFiles(UUID formId, FieldIndex fields, Map<String, Object> answers, List<UUID> claimed) {
        Map<String, Object> result = new LinkedHashMap<>(answers);
        for (String fieldKey : fields.fileFields().keySet()) {
            String repeater = fields.repeaterOf(fieldKey);
            if (repeater == null) {
                if (result.get(fieldKey) instanceof List<?> refs) {
                    result.put(fieldKey, refs.stream().map(r -> files.canonicalReference(formId, fieldKey, r, claimed)).toList());
                }
            } else if (result.get(repeater) instanceof List<?> items) {
                List<Object> copy = new ArrayList<>();
                for (Object item : items) {
                    if (item instanceof Map<?, ?> map && map.get(fieldKey) instanceof List<?> refs) {
                        Map<String, Object> entry = new LinkedHashMap<>((Map<String, Object>) map);
                        entry.put(fieldKey, refs.stream().map(r -> files.canonicalReference(formId, fieldKey, r, claimed)).toList());
                        copy.add(entry);
                    } else {
                        copy.add(item);
                    }
                }
                result.put(repeater, copy);
            }
        }
        return result;
    }

    /** File ids referenced by the submission data (only active fields are stored). */
    private static void collectFileIds(Object value, List<UUID> ids) {
        if (value instanceof Map<?, ?> map) {
            if (map.size() == 4 && map.get("id") instanceof String id && map.containsKey("contentType")) {
                ids.add(UUID.fromString(id));
                return;
            }
            map.values().forEach(v -> collectFileIds(v, ids));
        } else if (value instanceof List<?> list) {
            list.forEach(v -> collectFileIds(v, ids));
        }
    }

    /**
     * Accepts a submission: honeypot and human verification for public forms, canonical file
     * references, re-validation at the time of receipt, encryption of sensitive fields, file
     * claiming, webhook outbox and draft clean-up, all in one transaction.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public AcceptedSubmission submit(String key, Access access, SubmissionInput input, String clientIp) {
        StoredVersion stored = repository.findVersion(key, input.version())
                .orElseThrow(() -> new NotFoundException(
                        "Form " + key + (input.version() == null ? "" : " version " + input.version()) + " is not published"));
        Instant receivedAt = clock.instant();
        if (input.honeypot() != null && !input.honeypot().isBlank()) {
            // Looks accepted to the bot; nothing is stored.
            log.info("Discarded a submission to {} that filled the honeypot", key);
            return new AcceptedSubmission(UUID.randomUUID().toString(), stored.version(), null, receivedAt);
        }
        if ("public".equals(access.accessMode()) && captcha.enabled() && !captcha.verify(input.captchaToken(), clientIp)) {
            throw new RuntimeException4xx(HttpStatus.BAD_REQUEST, "captchaFailed", "Human verification failed. Please try again.");
        }
        Map<String, Object> definition = canonical(stored.definitionJson());
        FieldIndex fields = FieldIndex.of(definition);
        Map<String, Object> answers = input.answers() != null && input.answers().isObject()
                ? (Map<String, Object>) Values.normalize(json.treeToValue(input.answers(), Map.class))
                : Map.of();
        answers = canonicalFiles(access.formId(), fields, answers, new ArrayList<>());

        FormEvaluation evaluation = AnswerValidator.validate(definition, answers, receivedAt, input.locale());
        if (!evaluation.valid()) {
            throw new InvalidAnswersException(evaluation);
        }
        Map<String, Object> data = evaluation.submission();
        StoredSubmission saved = repository.insertSubmission(
                stored, json.writeValueAsString(submissionData.encrypt(data, fields)), evaluation.outcome(), receivedAt,
                access.subject());

        List<UUID> fileIds = new ArrayList<>();
        collectFileIds(data, fileIds);
        files.attach(fileIds, saved.id());

        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("id", saved.id().toString());
        submission.put("outcome", saved.outcome());
        submission.put("submittedAt", saved.submittedAt().toString());
        submission.put("subject", saved.subject());
        submission.put("data", data);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", WebhookService.EVENT_SUBMISSION_CREATED);
        payload.put("form", Map.of("key", key, "version", stored.version()));
        payload.put("submission", submission);
        webhooks.enqueue(access.formId(), saved.id(), payload);

        drafts.discardQuietly(access.formId(), input.draftToken());
        return new AcceptedSubmission(saved.id().toString(), saved.version(), saved.outcome(), saved.submittedAt());
    }

    /**
     * Submissions, newest first. Sensitive values are masked unless {@code reveal} is set, which
     * is recorded in the audit log.
     */
    @Transactional
    public List<SubmissionView> submissions(String key, Instant before, UUID beforeId, int limit, boolean reveal, String actorType,
            UUID actorId, String ip) {
        latest(key);
        if (reveal) {
            audit.audit(actorType, actorId, "submissions.revealed", key, ip, null);
        }
        return repository.listSubmissions(key, before, beforeId, limit).stream()
                .map(listed -> {
                    StoredSubmission s = listed.submission();
                    FieldIndex fields = FieldIndex.of(canonical(listed.definitionJson()));
                    Map<String, Object> stored = canonical(s.dataJson());
                    Map<String, Object> data = reveal ? submissionData.decrypt(stored, fields) : submissionData.mask(stored, fields);
                    return new SubmissionView(s.id().toString(), s.version(), s.outcome(), s.submittedAt(), s.subject(), data);
                })
                .toList();
    }
}
