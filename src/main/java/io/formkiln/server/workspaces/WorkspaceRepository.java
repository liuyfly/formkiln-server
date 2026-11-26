package io.formkiln.server.workspaces;

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

/** Workspaces and their members. */
@Repository
public class WorkspaceRepository {

    public record Workspace(UUID id, String slug, String name, Instant createdAt, Instant archivedAt) {
        public boolean archived() {
            return archivedAt != null;
        }
    }

    /** A workspace with its size, for the instance admin's list. */
    public record WorkspaceSummary(String slug, String name, Instant createdAt, Instant archivedAt, long members, long forms) {}

    /** A workspace the account belongs to. */
    public record Membership(String slug, String name, String role, boolean archived) {}

    /** A workspace an instance admin may read without being a member, until {@code expiresAt}. */
    public record AccessWindow(String slug, String name, Instant expiresAt) {}

    /** A member of a workspace. */
    public record Member(UUID userId, String email, String role, String status, Instant lastLoginAt, Instant addedAt) {}

    private final JdbcClient jdbc;

    public WorkspaceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Workspace workspace(ResultSet rs, int n) throws SQLException {
        return new Workspace(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("name"), instant(rs, "created_at"),
                instant(rs, "archived_at"));
    }

    public Optional<Workspace> findBySlug(String slug) {
        return jdbc.sql("SELECT * FROM workspace WHERE slug = :slug").param("slug", slug).query(WorkspaceRepository::workspace).optional();
    }

    public Optional<Workspace> findById(UUID id) {
        return jdbc.sql("SELECT * FROM workspace WHERE id = :id").param("id", id).query(WorkspaceRepository::workspace).optional();
    }

    public List<WorkspaceSummary> list() {
        return jdbc.sql("""
                        SELECT w.slug, w.name, w.created_at, w.archived_at,
                               (SELECT COUNT(*) FROM workspace_member m WHERE m.workspace_id = w.id) AS members,
                               (SELECT COUNT(*) FROM form f WHERE f.workspace_id = w.id) AS forms
                        FROM workspace w
                        ORDER BY w.archived_at IS NOT NULL, lower(w.name)
                        """)
                .query((rs, n) -> new WorkspaceSummary(rs.getString("slug"), rs.getString("name"), instant(rs, "created_at"),
                        instant(rs, "archived_at"), rs.getLong("members"), rs.getLong("forms")))
                .list();
    }

    public UUID insert(String slug, String name) {
        return jdbc.sql("INSERT INTO workspace (slug, name) VALUES (:slug, :name) RETURNING id")
                .param("slug", slug).param("name", name).query(UUID.class).single();
    }

    public void rename(UUID id, String name) {
        jdbc.sql("UPDATE workspace SET name = :name WHERE id = :id").param("name", name).param("id", id).update();
    }

    public void setArchived(UUID id, Instant at) {
        jdbc.sql("UPDATE workspace SET archived_at = :at WHERE id = :id")
                .param("at", at == null ? null : OffsetDateTime.ofInstant(at, ZoneOffset.UTC)).param("id", id).update();
    }

    // ---------------------------------------------------------------- break-glass access

    public void insertAccess(UUID workspaceId, UUID userId, String reason, Instant startedAt, Instant expiresAt) {
        jdbc.sql("""
                        INSERT INTO workspace_access (workspace_id, user_id, reason, started_at, expires_at)
                        VALUES (:workspace, :user, :reason, :start, :end)
                        """)
                .param("workspace", workspaceId).param("user", userId).param("reason", reason)
                .param("start", OffsetDateTime.ofInstant(startedAt, ZoneOffset.UTC))
                .param("end", OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
                .update();
    }

    /** The end of the account's open access window to the workspace, if one is open at {@code now}. */
    public Optional<Instant> activeAccess(UUID workspaceId, UUID userId, Instant now) {
        return jdbc.sql("""
                        SELECT expires_at FROM workspace_access
                        WHERE workspace_id = :workspace AND user_id = :user AND expires_at > :now
                        ORDER BY expires_at DESC LIMIT 1
                        """)
                .param("workspace", workspaceId).param("user", userId).param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .query((rs, n) -> instant(rs, "expires_at")).optional();
    }

    /** The account's open access windows. */
    public List<AccessWindow> activeAccessOf(UUID userId, Instant now) {
        return jdbc.sql("""
                        SELECT w.slug, w.name, MAX(a.expires_at) AS expires_at
                        FROM workspace_access a JOIN workspace w ON w.id = a.workspace_id
                        WHERE a.user_id = :user AND a.expires_at > :now
                        GROUP BY w.slug, w.name
                        ORDER BY w.slug
                        """)
                .param("user", userId).param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .query((rs, n) -> new AccessWindow(rs.getString("slug"), rs.getString("name"), instant(rs, "expires_at")))
                .list();
    }

    // ---------------------------------------------------------------- members

    /** The account's role in the workspace, if it is a member. */
    public Optional<String> role(UUID workspaceId, UUID userId) {
        return jdbc.sql("SELECT role FROM workspace_member WHERE workspace_id = :workspace AND user_id = :user")
                .param("workspace", workspaceId).param("user", userId).query(String.class).optional();
    }

    public List<Membership> membershipsOf(UUID userId) {
        return jdbc.sql("""
                        SELECT w.slug, w.name, m.role, w.archived_at IS NOT NULL AS archived
                        FROM workspace_member m JOIN workspace w ON w.id = m.workspace_id
                        WHERE m.user_id = :user
                        ORDER BY w.archived_at IS NOT NULL, lower(w.name)
                        """)
                .param("user", userId)
                .query((rs, n) -> new Membership(rs.getString("slug"), rs.getString("name"), rs.getString("role"), rs.getBoolean("archived")))
                .list();
    }

    private static Member member(ResultSet rs, int n) throws SQLException {
        return new Member(rs.getObject("user_id", UUID.class), rs.getString("email"), rs.getString("role"), rs.getString("status"),
                instant(rs, "last_login_at"), instant(rs, "added_at"));
    }

    private static final String MEMBER = """
            SELECT m.user_id, u.email, m.role, u.status, u.last_login_at, m.added_at
            FROM workspace_member m JOIN admin_user u ON u.id = m.user_id
            """;

    public List<Member> members(UUID workspaceId) {
        return jdbc.sql(MEMBER + " WHERE m.workspace_id = :workspace ORDER BY u.email")
                .param("workspace", workspaceId).query(WorkspaceRepository::member).list();
    }

    /** The member, locking the workspace's memberships so that admin counts stay true until commit. */
    public Optional<Member> lockMember(UUID workspaceId, UUID userId) {
        jdbc.sql("SELECT 1 FROM workspace_member WHERE workspace_id = :workspace FOR UPDATE").param("workspace", workspaceId).query().listOfRows();
        return jdbc.sql(MEMBER + " WHERE m.workspace_id = :workspace AND m.user_id = :user")
                .param("workspace", workspaceId).param("user", userId).query(WorkspaceRepository::member).optional();
    }

    /** Active admins of the workspace. */
    public long countAdmins(UUID workspaceId) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM workspace_member m JOIN admin_user u ON u.id = m.user_id
                        WHERE m.workspace_id = :workspace AND m.role = 'admin' AND u.status = 'active'
                        """)
                .param("workspace", workspaceId).query(Long.class).single();
    }

    /** Adds a member; false when the account already is one. */
    public boolean insertMember(UUID workspaceId, UUID userId, String role, UUID addedBy) {
        return jdbc.sql("""
                        INSERT INTO workspace_member (workspace_id, user_id, role, added_by) VALUES (:workspace, :user, :role, :by)
                        ON CONFLICT DO NOTHING
                        """)
                .param("workspace", workspaceId).param("user", userId).param("role", role).param("by", addedBy).update() == 1;
    }

    public void updateMemberRole(UUID workspaceId, UUID userId, String role) {
        jdbc.sql("UPDATE workspace_member SET role = :role WHERE workspace_id = :workspace AND user_id = :user")
                .param("role", role).param("workspace", workspaceId).param("user", userId).update();
    }

    public void deleteMember(UUID workspaceId, UUID userId) {
        jdbc.sql("DELETE FROM workspace_member WHERE workspace_id = :workspace AND user_id = :user")
                .param("workspace", workspaceId).param("user", userId).update();
    }

    /** Workspaces where the account is the only active admin. */
    public List<String> soleAdminOf(UUID userId) {
        return jdbc.sql("""
                        SELECT w.slug FROM workspace_member m JOIN workspace w ON w.id = m.workspace_id
                        WHERE m.user_id = :user AND m.role = 'admin'
                          AND NOT EXISTS (SELECT 1 FROM workspace_member o JOIN admin_user u ON u.id = o.user_id
                                          WHERE o.workspace_id = m.workspace_id AND o.role = 'admin' AND o.user_id <> :user
                                            AND u.status = 'active')
                        ORDER BY w.slug
                        """)
                .param("user", userId).query(String.class).list();
    }
}
