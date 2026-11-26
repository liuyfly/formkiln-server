package io.formkiln.server.web;

import io.formkiln.server.tokens.ServiceTokenPrincipal;
import io.formkiln.server.accounts.AccountRepository;
import io.formkiln.server.accounts.AccountService.AdminPrincipal;
import io.formkiln.server.expression.Values;
import io.formkiln.server.form.FieldIndex;
import io.formkiln.server.forms.FolderService;
import io.formkiln.server.forms.FormRepository.FormSummary;
import io.formkiln.server.forms.FormService.AcceptedSubmission;
import io.formkiln.server.forms.FormService.Access;
import io.formkiln.server.forms.FormService.NotFoundException;
import io.formkiln.server.forms.FormService.PublishedForm;
import io.formkiln.server.forms.FormService.RuntimeForm;
import io.formkiln.server.forms.FormService.SubmissionInput;
import io.formkiln.server.forms.FormService.SubmissionView;
import io.formkiln.server.forms.FormService;
import io.formkiln.server.forms.SubmissionExport;
import io.formkiln.server.platform.DraftService.DraftCreated;
import io.formkiln.server.platform.DraftService.DraftView;
import io.formkiln.server.platform.DraftService;
import io.formkiln.server.platform.EmbedTokens;
import io.formkiln.server.platform.FileService.StoredFile;
import io.formkiln.server.platform.FileService;
import io.formkiln.server.platform.PlatformRepository;
import io.formkiln.server.platform.RuntimeException4xx;
import io.formkiln.server.platform.WebhookService.DeliveryView;
import io.formkiln.server.platform.WebhookService.EndpointView;
import io.formkiln.server.platform.WebhookService;
import io.formkiln.server.workspaces.WorkspaceContext;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** HTTP API: runtime endpoints for renderers and management endpoints for publishers. */
@RestController
public class FormApi {

    private static final int MAX_PAGE = 200;
    /** Header carrying the receipt token returned with a submission. */
    public static final String RECEIPT_HEADER = "Formkiln-Receipt";
    /** Header carrying the draft token on draft requests. */
    public static final String DRAFT_HEADER = "Formkiln-Draft";

    /** Body of a submission. {@code version} defaults to the latest published version. */
    public record SubmissionRequest(Integer version, JsonNode answers, String locale, String draftToken, String captchaToken,
            String honeypot) {}

    public record DraftRequest(JsonNode answers) {}

    public record ResumeLinkRequest(String email, String locale) {}

    public record AccessRequest(String mode) {}

    public record FolderRequest(String name) {}

    /** {@code folderId} null moves the form to the top level. */
    public record MoveRequest(UUID folderId) {}

    public record EmbedTokenRequest(String formKey, String subject, Long ttlSeconds) {}

    public record WebhookRequest(String url) {}

    private final FormService forms;
    private final DraftService drafts;
    private final FileService files;
    private final WebhookService webhooks;
    private final EmbedTokens embedTokens;
    private final AccountRepository audit;
    private final PlatformRepository platform;
    private final SubmissionExport export;
    private final JsonMapper json;
    private final FolderService folders;

    public FormApi(FormService forms, DraftService drafts, FileService files, WebhookService webhooks, EmbedTokens embedTokens,
            AccountRepository audit, PlatformRepository platform, SubmissionExport export, JsonMapper json, FolderService folders) {
        this.forms = forms;
        this.drafts = drafts;
        this.files = files;
        this.webhooks = webhooks;
        this.embedTokens = embedTokens;
        this.audit = audit;
        this.platform = platform;
        this.export = export;
        this.json = json;
        this.folders = folders;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> answers(JsonNode node) {
        return node != null && node.isObject() ? (Map<String, Object>) Values.normalize(json.treeToValue(node, Map.class)) : Map.of();
    }

    /** Audit actor of a management request: a signed-in member or the service token. */
    private static Object[] actor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AdminPrincipal principal) {
            return new Object[] {"user", principal.userId()};
        }
        return new Object[] {"service", ServiceTokenPrincipal.currentId()};
    }

    /** Records a management change in the audit log, attributed to the member or the service token. */
    private void record(String action, String target, Map<String, Object> details, HttpServletRequest request) {
        Object[] actor = actor();
        audit.audit((String) actor[0], (UUID) actor[1], action, target, request.getRemoteAddr(),
                details == null ? null : json.writeValueAsString(details));
    }

    private static boolean hasAuthority(String authority) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(authority::equals);
    }

    // ------------------------------------------------------------------ runtime (public or embed token)

    /** The latest published version, access mode and any human verification to show. */
    @GetMapping("/v1/w/{workspace}/forms/{key}")
    public RuntimeForm form(@PathVariable String key, @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        return forms.runtimeForm(key, forms.access(key, auth));
    }

    /** Re-validates and stores a submission. 422 with errors when the answers are invalid. */
    @PostMapping("/v1/w/{workspace}/forms/{key}/submissions")
    public ResponseEntity<AcceptedSubmission> submit(
            @PathVariable String key,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody SubmissionRequest body,
            HttpServletRequest request) {
        Access access = forms.access(key, auth);
        SubmissionInput input = new SubmissionInput(body.version(), body.answers(), body.locale(), body.draftToken(),
                body.captchaToken(), body.honeypot());
        return ResponseEntity.status(201).body(forms.submit(key, access, input, request.getRemoteAddr()));
    }

    private static ResponseEntity<byte[]> pdf(byte[] body, String filename) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    /** The respondent's receipt as a PDF (receipt token from the submission response, for one hour). */
    @GetMapping("/v1/w/{workspace}/forms/{key}/receipt")
    public ResponseEntity<byte[]> receipt(
            @PathVariable String key,
            @RequestHeader(value = RECEIPT_HEADER, required = false) String token,
            @RequestParam(required = false) String locale) {
        String language = locale != null && locale.matches("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*") ? locale : null;
        return pdf(forms.receipt(key, token, language), key + "-receipt.pdf");
    }

    /** Creates a draft; the returned token is the only way to read or update it. */
    @PostMapping("/v1/w/{workspace}/forms/{key}/drafts")
    public ResponseEntity<DraftCreated> createDraft(
            @PathVariable String key,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody DraftRequest body) {
        Access access = forms.access(key, auth);
        return ResponseEntity.status(201).body(drafts.create(access.formId(), answers(body.answers())));
    }

    @GetMapping("/v1/w/{workspace}/forms/{key}/drafts")
    public DraftView readDraft(
            @PathVariable String key,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestHeader(value = DRAFT_HEADER, required = false) String token) {
        return drafts.read(forms.access(key, auth).formId(), token);
    }

    @PutMapping("/v1/w/{workspace}/forms/{key}/drafts")
    public DraftView saveDraft(
            @PathVariable String key,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestHeader(value = DRAFT_HEADER, required = false) String token,
            @RequestBody DraftRequest body) {
        return drafts.save(forms.access(key, auth).formId(), token, answers(body.answers()));
    }

    /** Emails a link that reopens the draft (when the form offers it). */
    @PostMapping("/v1/w/{workspace}/forms/{key}/drafts/resume-link")
    public ResponseEntity<Void> resumeLink(
            @PathVariable String key,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestHeader(value = DRAFT_HEADER, required = false) String token,
            @RequestBody ResumeLinkRequest body) {
        forms.requestResumeLink(key, forms.access(key, auth), token, body.email(), body.locale());
        return ResponseEntity.accepted().build();
    }

    @DeleteMapping("/v1/w/{workspace}/forms/{key}/drafts")
    public ResponseEntity<Void> deleteDraft(
            @PathVariable String key,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestHeader(value = DRAFT_HEADER, required = false) String token) {
        drafts.delete(forms.access(key, auth).formId(), token);
        return ResponseEntity.noContent().build();
    }

    /** Uploads one file for a file field (multipart: {@code field}, {@code file}); returns its reference. */
    @PostMapping(path = "/v1/w/{workspace}/forms/{key}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> upload(
            @PathVariable String key,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestParam("field") String field,
            @RequestPart("file") MultipartFile file) throws IOException {
        Access access = forms.access(key, auth);
        PublishedForm form = forms.latest(key);
        try (InputStream content = file.getInputStream()) {
            return ResponseEntity.status(201).body(files.upload(access.formId(), FieldIndex.of(form.definition()), field,
                    file.getOriginalFilename(), file.getContentType(), file.getSize(), content));
        }
    }

    // ------------------------------------------------------------------ management (viewer reads, editor writes)

    /** Active forms; {@code archived=true} lists the archived ones instead. */
    @GetMapping("/v1/manage/workspaces/{workspace}/forms")
    public List<FormSummary> listForms(@RequestParam(defaultValue = "false") boolean archived) {
        return forms.list(archived);
    }

    /** Hides a form and stops serving it; its data is kept (admin only). */
    @PostMapping("/v1/manage/workspaces/{workspace}/forms/{key}/archive")
    public Map<String, Object> archive(@PathVariable String key, HttpServletRequest request) {
        forms.archive(key);
        record("form.archived", key, null, request);
        return Map.of("key", key, "archived", true);
    }

    /** Serves an archived form again (admin only). */
    @PostMapping("/v1/manage/workspaces/{workspace}/forms/{key}/restore")
    public Map<String, Object> restore(@PathVariable String key, HttpServletRequest request) {
        forms.restore(key);
        record("form.restored", key, null, request);
        return Map.of("key", key, "archived", false);
    }

    @PutMapping("/v1/manage/workspaces/{workspace}/forms/{key}/folder")
    public Map<String, Object> moveToFolder(@PathVariable String key, @RequestBody MoveRequest body, HttpServletRequest request) {
        String folder = folders.moveForm(key, body.folderId());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("folder", folder);
        record("form.moved", key, details, request);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("folderId", body.folderId());
        return result;
    }

    // ------------------------------------------------------------------ folders

    @GetMapping("/v1/manage/workspaces/{workspace}/folders")
    public List<FolderService.Folder> listFolders() {
        return folders.list();
    }

    @PostMapping("/v1/manage/workspaces/{workspace}/folders")
    public ResponseEntity<FolderService.Folder> createFolder(@RequestBody FolderRequest body, HttpServletRequest request) {
        FolderService.Folder folder = folders.create(body.name());
        record("folder.created", folder.name(), Map.of("id", folder.id().toString()), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(folder);
    }

    @PatchMapping("/v1/manage/workspaces/{workspace}/folders/{id}")
    public FolderService.Folder renameFolder(@PathVariable UUID id, @RequestBody FolderRequest body, HttpServletRequest request) {
        FolderService.Folder folder = folders.rename(id, body.name());
        record("folder.renamed", folder.name(), Map.of("id", id.toString()), request);
        return folder;
    }

    @DeleteMapping("/v1/manage/workspaces/{workspace}/folders/{id}")
    public ResponseEntity<Void> deleteFolder(@PathVariable UUID id, HttpServletRequest request) {
        String name = folders.delete(id);
        record("folder.deleted", name, Map.of("id", id.toString()), request);
        return ResponseEntity.noContent().build();
    }

    /** Publishes a new immutable version. The body is the form document. */
    @PostMapping("/v1/manage/workspaces/{workspace}/forms/{key}/versions")
    public ResponseEntity<Map<String, Object>> publish(@PathVariable String key, @RequestBody JsonNode document,
            HttpServletRequest request) {
        PublishedForm published = forms.publish(key, document);
        record("form.published", key, Map.of("version", published.version()), request);
        return ResponseEntity.created(URI.create("/v1/manage/workspaces/" + WorkspaceContext.require().slug() + "/forms/" + key + "/versions/" + published.version()))
                .body(Map.of("key", key, "version", published.version(), "publishedAt", published.publishedAt()));
    }

    /** One version with its definition (labels, field types; for display and editing). */
    @GetMapping("/v1/manage/workspaces/{workspace}/forms/{key}/versions/{version}")
    public PublishedForm version(@PathVariable String key, @PathVariable int version) {
        return forms.version(key, version);
    }

    @GetMapping("/v1/manage/workspaces/{workspace}/forms/{key}/versions")
    public List<Map<String, Object>> versions(@PathVariable String key) {
        return forms.versions(key).stream()
                .map(v -> Map.<String, Object>of("version", v.version(), "publishedAt", v.publishedAt()))
                .toList();
    }

    /**
     * Submissions, newest first. Sensitive values are masked; {@code reveal=true} (editors only)
     * shows them and is audited. Pass the last {@code submittedAt} as {@code before} for the next page.
     */
    @GetMapping("/v1/manage/workspaces/{workspace}/forms/{key}/submissions")
    public List<SubmissionView> submissions(
            @PathVariable String key,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before,
            @RequestParam(required = false) UUID beforeId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "false") boolean reveal,
            HttpServletRequest request) {
        if (reveal && !hasAuthority("editor")) {
            throw new RuntimeException4xx(HttpStatus.FORBIDDEN, "revealForbidden", "Only editors can reveal sensitive values.");
        }
        Object[] actor = actor();
        return forms.submissions(key, before, beforeId, Math.clamp(limit, 1, MAX_PAGE), reveal, (String) actor[0], (UUID) actor[1],
                request.getRemoteAddr());
    }

    /**
     * Exports all submissions as CSV (audited). Sensitive values are masked unless {@code reveal=true}
     * (editors only).
     */
    @GetMapping("/v1/manage/workspaces/{workspace}/forms/{key}/submissions/export")
    public ResponseEntity<StreamingResponseBody> export(
            @PathVariable String key, @RequestParam(defaultValue = "false") boolean reveal, HttpServletRequest request) {
        if (reveal && !hasAuthority("editor")) {
            throw new RuntimeException4xx(HttpStatus.FORBIDDEN, "revealForbidden", "Only editors can reveal sensitive values.");
        }
        Object[] actor = actor();
        SubmissionExport.Prepared prepared = export.prepare(key, reveal, (String) actor[0], (UUID) actor[1], request.getRemoteAddr());
        String filename = key + "-submissions-" + LocalDate.now(ZoneOffset.UTC) + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(output -> export.write(prepared, output));
    }

    /** A submission as a PDF (audited); {@code reveal=true} (editors only) shows sensitive values. */
    @GetMapping("/v1/manage/workspaces/{workspace}/forms/{key}/submissions/{id}/pdf")
    public ResponseEntity<byte[]> submissionPdf(@PathVariable String key, @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean reveal, HttpServletRequest request) {
        if (reveal && !hasAuthority("editor")) {
            throw new RuntimeException4xx(HttpStatus.FORBIDDEN, "revealForbidden", "Only editors can reveal sensitive values.");
        }
        Object[] actor = actor();
        byte[] body = forms.submissionPdf(key, id, reveal, (String) actor[0], (UUID) actor[1], request.getRemoteAddr());
        return pdf(body, key + "-" + id.toString().substring(0, 8) + ".pdf");
    }

    /** Downloads a file of a submission (audited). */
    @GetMapping("/v1/manage/workspaces/{workspace}/forms/{key}/files/{id}")
    public ResponseEntity<InputStreamResource> download(@PathVariable String key, @PathVariable UUID id, HttpServletRequest request) {
        UUID formId = platform.findForm(key).orElseThrow(() -> new NotFoundException("Form " + key + " is not published")).id();
        StoredFile file = files.download(formId, id);
        Object[] actor = actor();
        audit.audit((String) actor[0], (UUID) actor[1], "file.downloaded", key + "/" + id, request.getRemoteAddr(), null);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.row().size())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.row().name(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(file.content()));
    }

    @PutMapping("/v1/manage/workspaces/{workspace}/forms/{key}/access")
    public Map<String, String> setAccess(@PathVariable String key, @RequestBody AccessRequest body, HttpServletRequest request) {
        forms.setAccessMode(key, body.mode());
        record("form.access_changed", key, Map.of("mode", body.mode()), request);
        return Map.of("key", key, "accessMode", body.mode());
    }

    /** Issues a short-lived embed token for a token-mode form (for the embedding application's backend). */
    @PostMapping("/v1/manage/workspaces/{workspace}/embed-tokens")
    public Map<String, Object> embedToken(@RequestBody EmbedTokenRequest body, HttpServletRequest request) {
        forms.latest(body.formKey() == null ? "" : body.formKey());
        Duration ttl = body.ttlSeconds() == null ? EmbedTokens.DEFAULT_TTL : Duration.ofSeconds(body.ttlSeconds());
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(EmbedTokens.MAX_TTL) > 0) {
            throw new RuntimeException4xx(HttpStatus.BAD_REQUEST, "invalidTtl", "ttlSeconds must be between 1 and 3600.");
        }
        if (body.subject() != null && body.subject().length() > 256) {
            throw new RuntimeException4xx(HttpStatus.BAD_REQUEST, "invalidSubject", "subject must be at most 256 characters.");
        }
        EmbedTokens.Claims claims = embedTokens.issue(body.formKey(), body.subject(), ttl);
        // Service backends issue a token per end user; record member-issued (test) tokens only.
        if (!hasAuthority("SERVICE")) {
            record("embed_token.issued", body.formKey(), Map.of("ttlSeconds", ttl.toSeconds()), request);
        }
        return Map.of("token", embedTokens.encode(claims), "expiresAt", claims.expiresAt());
    }

    @GetMapping("/v1/manage/workspaces/{workspace}/forms/{key}/webhooks")
    public List<EndpointView> webhooks(@PathVariable String key) {
        return webhooks.list(key);
    }

    /** Creates a webhook; the signing secret is returned only in this response. */
    @PostMapping("/v1/manage/workspaces/{workspace}/forms/{key}/webhooks")
    public ResponseEntity<EndpointView> createWebhook(@PathVariable String key, @RequestBody WebhookRequest body,
            HttpServletRequest request) {
        EndpointView created = webhooks.create(key, body.url());
        record("webhook.created", key, Map.of("id", created.id(), "url", created.url()), request);
        return ResponseEntity.status(201).body(created);
    }

    @DeleteMapping("/v1/manage/workspaces/{workspace}/forms/{key}/webhooks/{id}")
    public ResponseEntity<Void> deleteWebhook(@PathVariable String key, @PathVariable UUID id, HttpServletRequest request) {
        webhooks.delete(key, id);
        record("webhook.deleted", key, Map.of("id", id.toString()), request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/v1/manage/workspaces/{workspace}/forms/{key}/webhooks/{id}/deliveries")
    public List<DeliveryView> deliveries(@PathVariable String key, @PathVariable UUID id, @RequestParam(defaultValue = "50") int limit) {
        return webhooks.deliveries(key, id, Math.clamp(limit, 1, MAX_PAGE));
    }

    @PostMapping("/v1/manage/workspaces/{workspace}/forms/{key}/webhook-deliveries/{id}/redeliver")
    public DeliveryView redeliver(@PathVariable String key, @PathVariable UUID id, HttpServletRequest request) {
        DeliveryView delivery = webhooks.redeliver(key, id);
        record("webhook.redelivered", key, Map.of("deliveryId", id.toString()), request);
        return delivery;
    }
}
