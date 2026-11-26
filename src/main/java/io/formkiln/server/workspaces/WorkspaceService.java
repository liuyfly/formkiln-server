package io.formkiln.server.workspaces;

import io.formkiln.server.accounts.AccountRepository;
import io.formkiln.server.accounts.AccountService.Actor;
import io.formkiln.server.platform.RuntimeException4xx;
import io.formkiln.server.workspaces.WorkspaceRepository.Workspace;
import io.formkiln.server.workspaces.WorkspaceRepository.WorkspaceSummary;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Creating, renaming, archiving and restoring workspaces. */
@Service
public class WorkspaceService {

    /** Lower-case letters, digits and inner hyphens; part of every URL of the workspace, so it never changes. */
    static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{0,46}[a-z0-9]$");
    static final int MAX_NAME = 120;

    /** A workspace as shown to its members and to instance admins. */
    public record WorkspaceView(String slug, String name, java.time.Instant createdAt, java.time.Instant archivedAt) {
        static WorkspaceView of(Workspace workspace) {
            return new WorkspaceView(workspace.slug(), workspace.name(), workspace.createdAt(), workspace.archivedAt());
        }
    }

    /** How long a break-glass access window stays open. */
    public static final java.time.Duration ACCESS_WINDOW = java.time.Duration.ofHours(1);

    /** An open break-glass access window. */
    public record AccessGranted(String workspace, java.time.Instant expiresAt) {}

    /** Changes to a workspace; null fields stay as they are. */
    public record Update(String name, Boolean archived) {}

    private final WorkspaceRepository repository;
    private final AccountRepository audit;
    private final JsonMapper json;
    private final Clock clock;

    public WorkspaceService(WorkspaceRepository repository, AccountRepository audit, JsonMapper json, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    private static RuntimeException4xx notFound() {
        return new RuntimeException4xx(HttpStatus.NOT_FOUND, "notFound", "No such workspace.");
    }

    private static String cleanName(String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty() || clean.length() > MAX_NAME) {
            throw new RuntimeException4xx(HttpStatus.BAD_REQUEST, "invalidName", "A workspace name has 1 to " + MAX_NAME + " characters.");
        }
        return clean;
    }

    private void record(UUID workspaceId, Actor actor, String action, String target, Map<String, Object> details) {
        audit.auditIn(workspaceId, actor.type(), actor.id(), action, target, actor.ip(),
                details == null ? null : json.writeValueAsString(details));
    }

    @Transactional(readOnly = true)
    public List<WorkspaceSummary> list() {
        return repository.list();
    }

    @Transactional
    public WorkspaceView create(Actor actor, String slug, String name) {
        if (slug == null || !SLUG.matcher(slug).matches()) {
            throw new RuntimeException4xx(HttpStatus.BAD_REQUEST, "invalidSlug",
                    "Use 2 to 48 lower-case letters, digits and hyphens, starting and ending with a letter or digit.");
        }
        String clean = cleanName(name);
        UUID id;
        try {
            id = repository.insert(slug, clean);
        } catch (DuplicateKeyException e) {
            throw new RuntimeException4xx(HttpStatus.CONFLICT, "slugTaken", "A workspace with this address already exists.");
        }
        record(id, actor, "workspace.created", slug, Map.of("name", clean));
        return WorkspaceView.of(repository.findById(id).orElseThrow());
    }

    /** Renames, archives or restores the workspace {@code slug}. */
    @Transactional
    public WorkspaceView update(Actor actor, String slug, Update update) {
        Workspace workspace = repository.findBySlug(slug).orElseThrow(WorkspaceService::notFound);
        if (update.name() != null) {
            String clean = cleanName(update.name());
            if (!clean.equals(workspace.name())) {
                repository.rename(workspace.id(), clean);
                record(workspace.id(), actor, "workspace.renamed", slug, Map.of("from", workspace.name(), "to", clean));
            }
        }
        if (update.archived() != null && update.archived() != workspace.archived()) {
            repository.setArchived(workspace.id(), update.archived() ? clock.instant() : null);
            record(workspace.id(), actor, update.archived() ? "workspace.archived" : "workspace.restored", slug, null);
        }
        return WorkspaceView.of(repository.findById(workspace.id()).orElseThrow());
    }

    /**
     * Opens the workspace {@code slug} read-only for an instance admin who is not a member, for
     * {@link #ACCESS_WINDOW}. The reason is recorded in the workspace's audit log, so its admins
     * see who looked and why.
     */
    @Transactional
    public AccessGranted openAccess(Actor actor, String slug, String reason) {
        Workspace workspace = repository.findBySlug(slug).orElseThrow(WorkspaceService::notFound);
        String clean = reason == null ? "" : reason.strip();
        if (clean.length() < 10 || clean.length() > 500) {
            throw new RuntimeException4xx(HttpStatus.BAD_REQUEST, "reasonRequired",
                    "Explain in 10 to 500 characters why you need to read this workspace's data.");
        }
        if (repository.role(workspace.id(), actor.id()).isPresent()) {
            throw new RuntimeException4xx(HttpStatus.CONFLICT, "alreadyMember", "You are a member of this workspace already.");
        }
        java.time.Instant now = clock.instant();
        java.time.Instant until = now.plus(ACCESS_WINDOW);
        repository.insertAccess(workspace.id(), actor.id(), clean, now, until);
        record(workspace.id(), actor, "workspace.accessOpened", slug, Map.of("reason", clean, "expiresAt", until.toString()));
        return new AccessGranted(slug, until);
    }

    @Transactional(readOnly = true)
    public WorkspaceView get(String slug) {
        return WorkspaceView.of(repository.findBySlug(slug).orElseThrow(WorkspaceService::notFound));
    }
}
