package io.formkiln.server.forms;

import io.formkiln.server.accounts.AccountRepository;
import io.formkiln.server.expression.Values;
import io.formkiln.server.form.CompatibilityChecker;
import io.formkiln.server.form.CompatibilityChecker.Change;
import io.formkiln.server.form.DefinitionValidator.DefinitionError;
import io.formkiln.server.forms.FormService.InvalidFormException;
import io.formkiln.server.forms.FormService.NotFoundException;
import io.formkiln.server.forms.FormService.PublishedForm;
import io.formkiln.server.forms.WorkingCopyRepository.FormRef;
import io.formkiln.server.forms.WorkingCopyRepository.StoredCopy;
import io.formkiln.server.platform.RuntimeException4xx;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Working copies: the unpublished document a builder edits. Saves use optimistic concurrency (the
 * client names the revision it edited; a different current revision is a conflict). A copy may be
 * invalid while it is edited; publishing validates it, checks compatibility with the published
 * version and removes the copy.
 */
@Service
public class WorkingCopyService {

    /** Largest document accepted (serialized JSON). */
    public static final int MAX_DOCUMENT_BYTES = 1024 * 1024;
    private static final Pattern KEY = Pattern.compile("^[a-z][a-zA-Z0-9_]{0,63}$");

    /** Who did it, for {@code updated_by} and the audit log. */
    public record Actor(String type, UUID userId, String ip) {}

    /**
     * The document being edited. {@code revision} is 0 when there is no saved copy yet (the
     * document is then the latest published definition). {@code latestVersion} lets a client see
     * that someone published since the copy was started ({@code baseVersion}).
     */
    public record WorkingCopyView(String key, long revision, Integer baseVersion, Integer latestVersion, Map<String, Object> document,
            Instant updatedAt, String updatedBy, List<DefinitionError> problems) {}

    public record CheckResult(List<DefinitionError> problems, List<Change> changes) {}

    /** A 409 with a stable code and extra problem-detail properties. */
    public static class ConflictException extends RuntimeException4xx {
        private final Map<String, Object> details;

        public ConflictException(String code, String message, Map<String, Object> details) {
            super(HttpStatus.CONFLICT, code, message);
            this.details = details;
        }

        public Map<String, Object> details() {
            return details;
        }
    }

    private final WorkingCopyRepository copies;
    private final FormRepository forms;
    private final FormService formService;
    private final AccountRepository audit;
    private final JsonMapper json;

    public WorkingCopyService(WorkingCopyRepository copies, FormRepository forms, FormService formService, AccountRepository audit,
            JsonMapper json) {
        this.copies = copies;
        this.forms = forms;
        this.formService = formService;
        this.audit = audit;
        this.json = json;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(String text) {
        return (Map<String, Object>) json.readValue(text, Map.class);
    }

    private FormRef form(String key, boolean lock) {
        return copies.findForm(key, lock).orElseThrow(() -> new NotFoundException("No form " + key));
    }

    private void record(Actor actor, String action, String key, Map<String, Object> details) {
        audit.audit(actor.type(), actor.userId(), action, key, actor.ip(), details == null ? null : json.writeValueAsString(details));
    }

    private WorkingCopyView view(FormRef form, StoredCopy copy) {
        JsonNode document = json.readTree(copy.documentJson());
        return new WorkingCopyView(form.key(), copy.revision(), copy.baseVersion(), form.latestVersion(), map(copy.documentJson()),
                copy.updatedAt(), copy.updatedByEmail(), formService.problems(form.key(), document));
    }

    /** A new document with one empty page, for {@link #create}. */
    static Map<String, Object> skeleton(String key, String title) {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("key", "page1");
        page.put("elements", List.of());
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemaVersion", 1);
        document.put("key", key);
        document.put("title", title);
        document.put("pages", List.of(page));
        document.put("submit", Map.of("outcomes", List.of(Map.of("key", "submitted"))));
        return document;
    }

    // ---------------------------------------------------------------- operations

    /** Creates an unpublished form with a skeleton working copy. */
    @Transactional
    public WorkingCopyView create(String key, String title, Actor actor) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new RuntimeException4xx(HttpStatus.BAD_REQUEST, "invalidKey",
                    "The key must start with a lowercase letter, then letters, digits or underscores (at most 64).");
        }
        String name = title == null || title.isBlank() ? key : title.strip();
        if (name.length() > 200) {
            throw new RuntimeException4xx(HttpStatus.BAD_REQUEST, "invalidTitle", "The title must be at most 200 characters.");
        }
        UUID formId = copies.createForm(key)
                .orElseThrow(() -> new ConflictException("formExists", "A form with this key exists.", Map.of()));
        copies.insert(formId, json.writeValueAsString(skeleton(key, name)), null, actor.userId());
        record(actor, "form.created", key, null);
        return view(new FormRef(formId, key, null), copies.find(formId).orElseThrow());
    }

    /** The saved copy, or the latest published definition as revision 0. */
    @Transactional(readOnly = true)
    public WorkingCopyView get(String key) {
        FormRef form = form(key, false);
        return copies.find(form.id()).map(copy -> view(form, copy)).orElseGet(() -> {
            StoredVersionHolder latest = latest(key);
            return new WorkingCopyView(key, 0, form.latestVersion(), form.latestVersion(), map(latest.definitionJson),
                    latest.publishedAt, null, List.of());
        });
    }

    private record StoredVersionHolder(String definitionJson, Instant publishedAt) {}

    private StoredVersionHolder latest(String key) {
        return forms.findVersion(key, null)
                .map(v -> new StoredVersionHolder(v.definitionJson(), v.publishedAt()))
                .orElseThrow(() -> new NotFoundException("Form " + key + " has nothing to edit"));
    }

    private ConflictException revisionConflict(FormRef form) {
        Map<String, Object> details = new LinkedHashMap<>();
        copies.find(form.id()).ifPresentOrElse(current -> {
            details.put("currentRevision", current.revision());
            details.put("updatedAt", current.updatedAt());
            details.put("updatedBy", current.updatedByEmail());
        }, () -> details.put("currentRevision", 0));
        return new ConflictException("revisionConflict",
                "Someone else changed this form. Reload it to see their changes.", details);
    }

    /**
     * Saves the document over {@code revision} (0 for the first save). The document may be
     * invalid; the problems are returned.
     */
    @Transactional
    public WorkingCopyView save(String key, long revision, JsonNode document, Actor actor) {
        if (document == null || !document.isObject()) {
            throw new RuntimeException4xx(HttpStatus.BAD_REQUEST, "invalidDocument", "The document must be a JSON object.");
        }
        String text = json.writeValueAsString(document);
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES) {
            throw new RuntimeException4xx(HttpStatus.CONTENT_TOO_LARGE, "documentTooLarge", "The document is larger than 1 MB.");
        }
        FormRef form = form(key, true);
        boolean saved = revision == 0
                ? copies.insert(form.id(), text, form.latestVersion(), actor.userId())
                : copies.update(form.id(), revision, text, actor.userId());
        if (!saved) {
            throw revisionConflict(form);
        }
        return view(form, copies.find(form.id()).orElseThrow());
    }

    /** Drops the working copy; editing then starts again from the published version. */
    @Transactional
    public void discard(String key, Actor actor) {
        FormRef form = form(key, true);
        if (form.latestVersion() == null) {
            throw new RuntimeException4xx(HttpStatus.CONFLICT, "neverPublished",
                    "This form was never published; there is nothing to go back to.");
        }
        if (copies.find(form.id()).isPresent()) {
            copies.delete(form.id());
            record(actor, "working_copy.discarded", key, null);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Change> changes(String key, Map<String, Object> next) {
        return forms.findVersion(key, null)
                .map(published -> CompatibilityChecker.compare(
                        (Map<String, Object>) Values.normalize(map(published.definitionJson())),
                        (Map<String, Object>) Values.normalize(next)))
                .orElse(List.of());
    }

    /** Problems of the working copy, and its changes against the published version when it is valid. */
    @Transactional(readOnly = true)
    public CheckResult check(String key) {
        WorkingCopyView copy = get(key);
        List<Change> changes = copy.problems().isEmpty() ? changes(key, copy.document()) : List.of();
        return new CheckResult(copy.problems(), changes);
    }

    /**
     * Publishes the working copy at {@code revision}. Changes that affect existing submissions must
     * be acknowledged ({@code acknowledgeChanges}); otherwise they are returned in a 409.
     */
    @Transactional
    public PublishedForm publish(String key, long revision, boolean acknowledgeChanges, Actor actor) {
        FormRef form = form(key, true);
        StoredCopy copy = copies.find(form.id()).filter(c -> c.revision() == revision).orElseThrow(() -> revisionConflict(form));
        JsonNode document = json.readTree(copy.documentJson());
        List<DefinitionError> problems = formService.problems(key, document);
        if (!problems.isEmpty()) {
            throw new InvalidFormException(problems);
        }
        List<Change> changes = changes(key, map(copy.documentJson()));
        if (!changes.isEmpty() && !acknowledgeChanges) {
            throw new ConflictException("changesNotAcknowledged",
                    "This version removes or changes fields that existing submissions use. Review the changes and confirm.",
                    Map.of("changes", changes));
        }
        PublishedForm published = formService.publish(key, document);
        copies.delete(form.id());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("version", published.version());
        details.put("source", "workingCopy");
        if (!changes.isEmpty()) {
            details.put("acknowledgedChanges", changes.size());
        }
        record(actor, "form.published", key, details);
        return published;
    }
}
