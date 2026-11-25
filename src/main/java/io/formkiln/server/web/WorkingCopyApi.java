package io.formkiln.server.web;

import io.formkiln.server.accounts.AccountService.AdminPrincipal;
import io.formkiln.server.forms.FormService.PublishedForm;
import io.formkiln.server.forms.WorkingCopyService;
import io.formkiln.server.forms.WorkingCopyService.Actor;
import io.formkiln.server.forms.WorkingCopyService.CheckResult;
import io.formkiln.server.forms.WorkingCopyService.WorkingCopyView;
import io.formkiln.server.platform.RuntimeException4xx;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Creating forms and editing their working copies (the builder's API). Viewers read; editors write. */
@RestController
public class WorkingCopyApi {

    /**
     * {@code document}: optional initial document (a template or a copy of another form); its key
     * is replaced, and its title too when {@code title} is given. {@code source}: optional short
     * identifier of where it came from (for example {@code template:contact} or
     * {@code form:rentalApplication}), recorded in the audit log.
     */
    public record CreateRequest(String key, String title, JsonNode document, String source) {}

    public record SaveRequest(Long revision, JsonNode document) {}

    public record PublishRequest(Long revision, Boolean acknowledgeChanges) {}

    private final WorkingCopyService copies;

    public WorkingCopyApi(WorkingCopyService copies) {
        this.copies = copies;
    }

    private static Actor actor(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AdminPrincipal principal) {
            return new Actor("user", principal.userId(), request.getRemoteAddr());
        }
        return new Actor("service", null, request.getRemoteAddr());
    }

    private static long revision(Long revision) {
        if (revision == null || revision < 0) {
            throw new RuntimeException4xx(HttpStatus.BAD_REQUEST, "invalidRevision", "revision is required (0 for the first save).");
        }
        return revision;
    }

    /** Creates an unpublished form with an empty working copy. */
    @PostMapping("/v1/manage/forms")
    public ResponseEntity<WorkingCopyView> create(@RequestBody CreateRequest body, HttpServletRequest request) {
        WorkingCopyView created = copies.create(body.key(), body.title(), body.document(), body.source(), actor(request));
        return ResponseEntity.created(URI.create("/v1/manage/forms/" + created.key() + "/working-copy")).body(created);
    }

    @GetMapping("/v1/manage/forms/{key}/working-copy")
    public WorkingCopyView get(@PathVariable String key) {
        return copies.get(key);
    }

    /** Saves over the revision the client edited; 409 {@code revisionConflict} when it moved on. */
    @PutMapping("/v1/manage/forms/{key}/working-copy")
    public WorkingCopyView save(@PathVariable String key, @RequestBody SaveRequest body, HttpServletRequest request) {
        return copies.save(key, revision(body.revision()), body.document(), actor(request));
    }

    @DeleteMapping("/v1/manage/forms/{key}/working-copy")
    public ResponseEntity<Void> discard(@PathVariable String key, HttpServletRequest request) {
        copies.discard(key, actor(request));
        return ResponseEntity.noContent().build();
    }

    /** Problems, and changes against the published version that affect existing submissions. */
    @GetMapping("/v1/manage/forms/{key}/working-copy/check")
    public CheckResult check(@PathVariable String key) {
        return copies.check(key);
    }

    @PostMapping("/v1/manage/forms/{key}/working-copy/publish")
    public ResponseEntity<Map<String, Object>> publish(@PathVariable String key, @RequestBody PublishRequest body,
            HttpServletRequest request) {
        PublishedForm published = copies.publish(key, revision(body.revision()), Boolean.TRUE.equals(body.acknowledgeChanges()),
                actor(request));
        return ResponseEntity.created(URI.create("/v1/manage/forms/" + key + "/versions/" + published.version()))
                .body(Map.of("key", key, "version", published.version(), "publishedAt", published.publishedAt()));
    }
}
