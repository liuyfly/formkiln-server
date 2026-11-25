package io.formkiln.server.forms;

import io.formkiln.server.expression.Values;
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

    /** The result of an accepted submission. */
    public record AcceptedSubmission(String id, int version, String outcome, Instant submittedAt) {}

    /** A submission as returned by the management API. */
    public record SubmissionView(String id, int version, String outcome, Instant submittedAt, Map<String, Object> data) {}

    private final FormRepository repository;
    private final FormDocumentValidator documentValidator;
    private final JsonMapper json;
    private final Clock clock;

    public FormService(FormRepository repository, FormDocumentValidator documentValidator, JsonMapper json, Clock clock) {
        this.repository = repository;
        this.documentValidator = documentValidator;
        this.json = json;
        this.clock = clock;
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

    /**
     * Re-validates {@code answers} against the given version (the latest when {@code version} is
     * null) at the time of receipt, and stores the submission data when they are valid.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public AcceptedSubmission submit(String key, Integer version, JsonNode answers, String locale) {
        StoredVersion stored = repository.findVersion(key, version)
                .orElseThrow(() -> new NotFoundException(
                        "Form " + key + (version == null ? "" : " version " + version) + " is not published"));
        Map<String, Object> canonicalAnswers = answers != null && answers.isObject()
                ? (Map<String, Object>) Values.normalize(json.treeToValue(answers, Map.class))
                : Map.of();
        Instant receivedAt = clock.instant();
        FormEvaluation evaluation =
                AnswerValidator.validate(canonical(stored.definitionJson()), canonicalAnswers, receivedAt, locale);
        if (!evaluation.valid()) {
            throw new InvalidAnswersException(evaluation);
        }
        StoredSubmission saved = repository.insertSubmission(
                stored, json.writeValueAsString(evaluation.submission()), evaluation.outcome(), receivedAt);
        return new AcceptedSubmission(saved.id().toString(), saved.version(), saved.outcome(), saved.submittedAt());
    }

    @Transactional(readOnly = true)
    public List<SubmissionView> submissions(String key, Instant before, int limit) {
        latest(key);
        return repository.listSubmissions(key, before, limit).stream()
                .map(s -> new SubmissionView(s.id().toString(), s.version(), s.outcome(), s.submittedAt(), canonical(s.dataJson())))
                .toList();
    }
}
