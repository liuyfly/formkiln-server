package io.formkiln.server.web;

import io.formkiln.server.forms.FormRepository.FormSummary;
import io.formkiln.server.forms.FormService;
import io.formkiln.server.forms.FormService.AcceptedSubmission;
import io.formkiln.server.forms.FormService.PublishedForm;
import io.formkiln.server.forms.FormService.SubmissionView;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** HTTP API: runtime endpoints for renderers and management endpoints for publishers. */
@RestController
public class FormApi {

    private static final int MAX_PAGE = 200;

    /** Body of a submission. {@code version} defaults to the latest published version. */
    public record SubmissionRequest(Integer version, JsonNode answers, String locale) {}

    private final FormService forms;

    public FormApi(FormService forms) {
        this.forms = forms;
    }

    // ------------------------------------------------------------------ runtime (public)

    /** The latest published version of a form. */
    @GetMapping("/v1/forms/{key}")
    public PublishedForm form(@PathVariable String key) {
        return forms.latest(key);
    }

    /** Re-validates and stores a submission. 422 with errors when the answers are invalid. */
    @PostMapping("/v1/forms/{key}/submissions")
    public ResponseEntity<AcceptedSubmission> submit(@PathVariable String key, @RequestBody SubmissionRequest request) {
        AcceptedSubmission accepted = forms.submit(key, request.version(), request.answers(), request.locale());
        return ResponseEntity.status(201).body(accepted);
    }

    // ------------------------------------------------------------------ management (service token)

    @GetMapping("/v1/manage/forms")
    public List<FormSummary> listForms() {
        return forms.list();
    }

    /** Publishes a new immutable version. The body is the form document. */
    @PostMapping("/v1/manage/forms/{key}/versions")
    public ResponseEntity<Map<String, Object>> publish(@PathVariable String key, @RequestBody JsonNode document) {
        PublishedForm published = forms.publish(key, document);
        return ResponseEntity.created(URI.create("/v1/manage/forms/" + key + "/versions/" + published.version()))
                .body(Map.of("key", key, "version", published.version(), "publishedAt", published.publishedAt()));
    }

    @GetMapping("/v1/manage/forms/{key}/versions")
    public List<Map<String, Object>> versions(@PathVariable String key) {
        return forms.versions(key).stream()
                .map(v -> Map.<String, Object>of("version", v.version(), "publishedAt", v.publishedAt()))
                .toList();
    }

    /** Submissions, newest first. Pass the last {@code submittedAt} as {@code before} for the next page. */
    @GetMapping("/v1/manage/forms/{key}/submissions")
    public List<SubmissionView> submissions(
            @PathVariable String key,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before,
            @RequestParam(defaultValue = "50") int limit) {
        return forms.submissions(key, before, Math.clamp(limit, 1, MAX_PAGE));
    }
}
