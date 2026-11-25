package io.formkiln.server.web;

import io.formkiln.server.accounts.AccountService.AdminPrincipal;
import io.formkiln.server.retention.RetentionService;
import io.formkiln.server.retention.RetentionService.Actor;
import io.formkiln.server.retention.RetentionService.ErasureResult;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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

/** Deleting submissions, retention periods and erasure requests. */
@RestController
public class RetentionApi {

    public record RetentionRequest(Integer days) {}

    public record ErasureRequest(String email, String subject, Boolean dryRun) {}

    private final RetentionService retention;

    public RetentionApi(RetentionService retention) {
        this.retention = retention;
    }

    private static Actor actor(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AdminPrincipal principal) {
            return new Actor("user", principal.userId(), request.getRemoteAddr());
        }
        return new Actor("service", null, request.getRemoteAddr());
    }

    /** Deletes a submission with its files (editors; audited). */
    @DeleteMapping("/v1/manage/forms/{key}/submissions/{id}")
    public ResponseEntity<Void> delete(@PathVariable String key, @PathVariable UUID id, HttpServletRequest request) {
        retention.deleteSubmission(key, id, actor(request));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/v1/manage/forms/{key}/retention")
    public Map<String, Object> get(@PathVariable String key) {
        Map<String, Object> body = new HashMap<>();
        body.put("days", retention.retention(key));
        return body;
    }

    /** {@code days}: keep submissions this many days (1–36500), or null to keep them until deleted. */
    @PutMapping("/v1/manage/forms/{key}/retention")
    public Map<String, Object> set(@PathVariable String key, @RequestBody RetentionRequest body, HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        result.put("days", retention.setRetention(key, body.days(), actor(request)));
        return result;
    }

    /**
     * Finds ({@code dryRun}, the default) or deletes every submission of a person by email address
     * or embed-token subject, across all forms. Admins only.
     */
    @PostMapping("/v1/admin/erasure")
    public ErasureResult erase(@RequestBody ErasureRequest body, HttpServletRequest request) {
        return retention.erase(body.email(), body.subject(), !Boolean.FALSE.equals(body.dryRun()), actor(request));
    }
}
