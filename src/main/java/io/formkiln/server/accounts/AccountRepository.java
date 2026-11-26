package io.formkiln.server.accounts;

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

/** Persistence for admin users, invitations, sessions, login challenges, resets and recovery codes. */
@Repository
public class AccountRepository {

    public record User(
            UUID id,
            String email,
            String passwordHash,
            byte[] totpSecret,
            String role,
            String status,
            int failedLogins,
            Instant lockedUntil,
            Instant lastLoginAt,
            Instant createdAt) {

        public boolean active() {
            return "active".equals(status);
        }
    }

    public record Invitation(
            UUID id, String email, String role, byte[] totpSecret, UUID invitedBy, Instant createdAt, Instant expiresAt) {}

    public record Session(UUID id, UUID userId, Instant createdAt, Instant lastSeenAt, Instant expiresAt, String ip, String userAgent) {}

    public record Challenge(UUID userId, byte[] totpSecret, int attempts, Instant expiresAt) {}

    public record Reset(UUID id, UUID userId, Instant expiresAt) {}

    public record AuditEntry(UUID id, Instant at, String actorType, UUID actorId, String action, String target, String ip, String details) {}

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime odt(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static User user(ResultSet rs, int n) throws SQLException {
        return new User(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("password_hash"),
                rs.getBytes("totp_secret"),
                rs.getString("role"),
                rs.getString("status"),
                rs.getInt("failed_logins"),
                instant(rs, "locked_until"),
                instant(rs, "last_login_at"),
                instant(rs, "created_at"));
    }

    // ---------------------------------------------------------------- users

    public long countUsers() {
        return jdbc.sql("SELECT COUNT(*) FROM admin_user").query(Long.class).single();
    }

    public long countActiveAdmins() {
        return jdbc.sql("SELECT COUNT(*) FROM admin_user WHERE role = 'admin' AND status = 'active'")
                .query(Long.class)
                .single();
    }

    public Optional<User> findUserByEmail(String email) {
        return jdbc.sql("SELECT * FROM admin_user WHERE email = :email").param("email", email).query(AccountRepository::user).optional();
    }

    public Optional<User> findUser(UUID id) {
        return jdbc.sql("SELECT * FROM admin_user WHERE id = :id").param("id", id).query(AccountRepository::user).optional();
    }

    /** Locks the user row for the rest of the transaction. */
    public Optional<User> lockUser(UUID id) {
        return jdbc.sql("SELECT * FROM admin_user WHERE id = :id FOR UPDATE").param("id", id).query(AccountRepository::user).optional();
    }

    public List<User> listUsers() {
        return jdbc.sql("SELECT * FROM admin_user ORDER BY created_at").query(AccountRepository::user).list();
    }

    /**
     * Creates an account with a membership of the default workspace. Until roles are checked per
     * workspace, the account role and the membership role are the same, and admins are instance
     * admins (as the V10 migration mapped them).
     */
    public UUID insertUser(String email, String passwordHash, byte[] totpSecret, String role) {
        UUID id = jdbc.sql("""
                        INSERT INTO admin_user (email, password_hash, totp_secret, role, instance_admin)
                        VALUES (:email, :hash, :totp, :role, :role = 'admin') RETURNING id
                        """)
                .param("email", email)
                .param("hash", passwordHash)
                .param("totp", totpSecret)
                .param("role", role)
                .query(UUID.class)
                .single();
        jdbc.sql("""
                        INSERT INTO workspace_member (workspace_id, user_id, role)
                        VALUES ((SELECT id FROM workspace WHERE slug = 'default'), :id, :role)
                        """)
                .param("id", id)
                .param("role", role)
                .update();
        return id;
    }

    public void recordLoginFailure(UUID id, int failedLogins, Instant lockedUntil) {
        jdbc.sql("UPDATE admin_user SET failed_logins = :failed, locked_until = :locked WHERE id = :id")
                .param("failed", failedLogins)
                .param("locked", odt(lockedUntil))
                .param("id", id)
                .update();
    }

    public void recordLoginSuccess(UUID id, Instant at) {
        jdbc.sql("UPDATE admin_user SET failed_logins = 0, locked_until = NULL, last_login_at = :at WHERE id = :id")
                .param("at", odt(at))
                .param("id", id)
                .update();
    }

    public void updatePassword(UUID id, String passwordHash, Instant at) {
        jdbc.sql("""
                        UPDATE admin_user SET password_hash = :hash, password_changed_at = :at,
                            failed_logins = 0, locked_until = NULL WHERE id = :id
                        """)
                .param("hash", passwordHash)
                .param("at", odt(at))
                .param("id", id)
                .update();
    }

    /**
     * Records an accepted TOTP step. False when this or a later step was already used: the code
     * is a replay. Atomic, so two concurrent requests cannot both use one code.
     */
    public boolean useTotpStep(UUID id, long step) {
        return jdbc.sql("""
                        UPDATE admin_user SET totp_last_step = :step
                        WHERE id = :id AND (totp_last_step IS NULL OR totp_last_step < :step)
                        """)
                .param("step", step)
                .param("id", id)
                .update() == 1;
    }

    public void updateTotp(UUID id, byte[] totpSecret) {
        jdbc.sql("UPDATE admin_user SET totp_secret = :totp WHERE id = :id").param("totp", totpSecret).param("id", id).update();
    }

    public void updateRole(UUID id, String role) {
        jdbc.sql("UPDATE admin_user SET role = :role, instance_admin = :role = 'admin' WHERE id = :id")
                .param("role", role).param("id", id).update();
        jdbc.sql("""
                        UPDATE workspace_member SET role = :role
                        WHERE user_id = :id AND workspace_id = (SELECT id FROM workspace WHERE slug = 'default')
                        """)
                .param("role", role).param("id", id).update();
    }

    public void updateStatus(UUID id, String status) {
        jdbc.sql("UPDATE admin_user SET status = :status WHERE id = :id").param("status", status).param("id", id).update();
    }

    // ---------------------------------------------------------------- invitations

    private static Invitation invitation(ResultSet rs, int n) throws SQLException {
        return new Invitation(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("role"),
                rs.getBytes("totp_secret"),
                rs.getObject("invited_by", UUID.class),
                instant(rs, "created_at"),
                instant(rs, "expires_at"));
    }

    private static final String OPEN = "accepted_at IS NULL AND revoked_at IS NULL";

    public UUID insertInvitation(String email, String role, byte[] tokenHash, UUID invitedBy, Instant expiresAt) {
        return jdbc.sql("""
                        INSERT INTO admin_invitation (email, role, token_hash, invited_by, expires_at, workspace_id)
                        VALUES (:email, :role, :hash, :by, :expires, (SELECT id FROM workspace WHERE slug = 'default'))
                        RETURNING id
                        """)
                .param("email", email)
                .param("role", role)
                .param("hash", tokenHash)
                .param("by", invitedBy)
                .param("expires", odt(expiresAt))
                .query(UUID.class)
                .single();
    }

    /** An open (not accepted, not revoked) invitation by token hash, expired or not. */
    public Optional<Invitation> findOpenInvitationByToken(byte[] tokenHash) {
        return jdbc.sql("SELECT * FROM admin_invitation WHERE token_hash = :hash AND " + OPEN + " FOR UPDATE")
                .param("hash", tokenHash)
                .query(AccountRepository::invitation)
                .optional();
    }

    public Optional<Invitation> findOpenInvitation(UUID id) {
        return jdbc.sql("SELECT * FROM admin_invitation WHERE id = :id AND " + OPEN)
                .param("id", id)
                .query(AccountRepository::invitation)
                .optional();
    }

    public Optional<Invitation> findOpenInvitationByEmail(String email) {
        return jdbc.sql("SELECT * FROM admin_invitation WHERE email = :email AND " + OPEN)
                .param("email", email)
                .query(AccountRepository::invitation)
                .optional();
    }

    public List<Invitation> listOpenInvitations() {
        return jdbc.sql("SELECT * FROM admin_invitation WHERE " + OPEN + " ORDER BY created_at")
                .query(AccountRepository::invitation)
                .list();
    }

    public void setInvitationTotp(UUID id, byte[] totpSecret) {
        jdbc.sql("UPDATE admin_invitation SET totp_secret = :totp WHERE id = :id").param("totp", totpSecret).param("id", id).update();
    }

    public void acceptInvitation(UUID id, Instant at) {
        jdbc.sql("UPDATE admin_invitation SET accepted_at = :at WHERE id = :id").param("at", odt(at)).param("id", id).update();
    }

    public void revokeInvitation(UUID id, Instant at) {
        jdbc.sql("UPDATE admin_invitation SET revoked_at = :at WHERE id = :id AND " + OPEN)
                .param("at", odt(at))
                .param("id", id)
                .update();
    }

    // ---------------------------------------------------------------- recovery codes

    public void replaceRecoveryCodes(UUID userId, List<byte[]> codeHashes) {
        jdbc.sql("DELETE FROM admin_recovery_code WHERE user_id = :user").param("user", userId).update();
        for (byte[] hash : codeHashes) {
            jdbc.sql("INSERT INTO admin_recovery_code (user_id, code_hash) VALUES (:user, :hash)")
                    .param("user", userId)
                    .param("hash", hash)
                    .update();
        }
    }

    /** Marks an unused recovery code as used; returns whether one matched. */
    public boolean useRecoveryCode(UUID userId, byte[] codeHash, Instant at) {
        return jdbc.sql("""
                        UPDATE admin_recovery_code SET used_at = :at
                        WHERE id = (SELECT id FROM admin_recovery_code
                                    WHERE user_id = :user AND code_hash = :hash AND used_at IS NULL LIMIT 1)
                        """)
                .param("at", odt(at))
                .param("user", userId)
                .param("hash", codeHash)
                .update() == 1;
    }

    public long countUnusedRecoveryCodes(UUID userId) {
        return jdbc.sql("SELECT COUNT(*) FROM admin_recovery_code WHERE user_id = :user AND used_at IS NULL")
                .param("user", userId)
                .query(Long.class)
                .single();
    }

    // ---------------------------------------------------------------- sessions

    private static Session session(ResultSet rs, int n) throws SQLException {
        return new Session(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                instant(rs, "created_at"),
                instant(rs, "last_seen_at"),
                instant(rs, "expires_at"),
                rs.getString("ip"),
                rs.getString("user_agent"));
    }

    public UUID insertSession(byte[] tokenHash, UUID userId, Instant now, Instant expiresAt, String ip, String userAgent) {
        return jdbc.sql("""
                        INSERT INTO admin_session (token_hash, user_id, created_at, last_seen_at, expires_at, ip, user_agent)
                        VALUES (:hash, :user, :now, :now, :expires, :ip, :ua) RETURNING id
                        """)
                .param("hash", tokenHash)
                .param("user", userId)
                .param("now", odt(now))
                .param("expires", odt(expiresAt))
                .param("ip", ip)
                .param("ua", userAgent)
                .query(UUID.class)
                .single();
    }

    public Optional<Session> findLiveSession(byte[] tokenHash) {
        return jdbc.sql("SELECT * FROM admin_session WHERE token_hash = :hash AND revoked_at IS NULL")
                .param("hash", tokenHash)
                .query(AccountRepository::session)
                .optional();
    }

    public List<Session> listLiveSessions(UUID userId, Instant now) {
        return jdbc.sql("""
                        SELECT * FROM admin_session
                        WHERE user_id = :user AND revoked_at IS NULL AND expires_at > :now
                        ORDER BY last_seen_at DESC
                        """)
                .param("user", userId)
                .param("now", odt(now))
                .query(AccountRepository::session)
                .list();
    }

    public void touchSession(UUID id, Instant at) {
        jdbc.sql("UPDATE admin_session SET last_seen_at = :at WHERE id = :id").param("at", odt(at)).param("id", id).update();
    }

    public int revokeSession(UUID id, UUID userId, Instant at) {
        return jdbc.sql("UPDATE admin_session SET revoked_at = :at WHERE id = :id AND user_id = :user AND revoked_at IS NULL")
                .param("at", odt(at))
                .param("id", id)
                .param("user", userId)
                .update();
    }

    /** Revokes every live session of a user, except {@code keep} when given. */
    public void revokeUserSessions(UUID userId, UUID keep, Instant at) {
        jdbc.sql("""
                        UPDATE admin_session SET revoked_at = :at
                        WHERE user_id = :user AND revoked_at IS NULL AND (CAST(:keep AS uuid) IS NULL OR id <> :keep)
                        """)
                .param("at", odt(at))
                .param("user", userId)
                .param("keep", keep)
                .update();
    }

    // ---------------------------------------------------------------- login challenges

    public void insertChallenge(byte[] tokenHash, UUID userId, byte[] totpSecret, Instant expiresAt) {
        jdbc.sql("""
                        INSERT INTO admin_login_challenge (token_hash, user_id, totp_secret, expires_at)
                        VALUES (:hash, :user, :totp, :expires)
                        """)
                .param("hash", tokenHash)
                .param("user", userId)
                .param("totp", totpSecret)
                .param("expires", odt(expiresAt))
                .update();
    }

    public Optional<Challenge> lockChallenge(byte[] tokenHash) {
        return jdbc.sql("SELECT * FROM admin_login_challenge WHERE token_hash = :hash FOR UPDATE")
                .param("hash", tokenHash)
                .query((rs, n) -> new Challenge(
                        rs.getObject("user_id", UUID.class),
                        rs.getBytes("totp_secret"),
                        rs.getInt("attempts"),
                        instant(rs, "expires_at")))
                .optional();
    }

    public void incrementChallengeAttempts(byte[] tokenHash) {
        jdbc.sql("UPDATE admin_login_challenge SET attempts = attempts + 1 WHERE token_hash = :hash").param("hash", tokenHash).update();
    }

    public void deleteChallenge(byte[] tokenHash) {
        jdbc.sql("DELETE FROM admin_login_challenge WHERE token_hash = :hash").param("hash", tokenHash).update();
    }

    // ---------------------------------------------------------------- password resets

    public void insertReset(UUID userId, byte[] tokenHash, Instant expiresAt) {
        jdbc.sql("UPDATE admin_password_reset SET used_at = now() WHERE user_id = :user AND used_at IS NULL")
                .param("user", userId)
                .update();
        jdbc.sql("INSERT INTO admin_password_reset (user_id, token_hash, expires_at) VALUES (:user, :hash, :expires)")
                .param("user", userId)
                .param("hash", tokenHash)
                .param("expires", odt(expiresAt))
                .update();
    }

    public Optional<Reset> lockOpenReset(byte[] tokenHash) {
        return jdbc.sql("SELECT * FROM admin_password_reset WHERE token_hash = :hash AND used_at IS NULL FOR UPDATE")
                .param("hash", tokenHash)
                .query((rs, n) -> new Reset(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), instant(rs, "expires_at")))
                .optional();
    }

    public void useReset(UUID id, Instant at) {
        jdbc.sql("UPDATE admin_password_reset SET used_at = :at WHERE id = :id").param("at", odt(at)).param("id", id).update();
    }

    // ---------------------------------------------------------------- audit

    public void audit(String actorType, UUID actorId, String action, String target, String ip, String detailsJson) {
        jdbc.sql("""
                        INSERT INTO audit_log (actor_type, actor_id, action, target, ip, details)
                        VALUES (:type, :actor, :action, :target, :ip, CAST(:details AS jsonb))
                        """)
                .param("type", actorType)
                .param("actor", actorId)
                .param("action", action)
                .param("target", target)
                .param("ip", ip)
                .param("details", detailsJson)
                .update();
    }

    public List<AuditEntry> listAudit(Instant before, int limit) {
        return jdbc.sql("""
                        SELECT id, at, actor_type, actor_id, action, target, ip, details::text AS details FROM audit_log
                        WHERE CAST(:before AS timestamptz) IS NULL OR at < :before
                        ORDER BY at DESC, id DESC LIMIT :limit
                        """)
                .param("before", odt(before))
                .param("limit", limit)
                .query((rs, n) -> new AuditEntry(
                        rs.getObject("id", UUID.class),
                        instant(rs, "at"),
                        rs.getString("actor_type"),
                        rs.getObject("actor_id", UUID.class),
                        rs.getString("action"),
                        rs.getString("target"),
                        rs.getString("ip"),
                        rs.getString("details")))
                .list();
    }
}
