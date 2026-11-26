package io.formkiln.server.tokens;

import io.formkiln.server.accounts.AccountRepository;
import io.formkiln.server.accounts.AccountService.Actor;
import io.formkiln.server.accounts.Secrets;
import io.formkiln.server.platform.RuntimeException4xx;
import io.formkiln.server.workspaces.WorkspaceContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Workspace service tokens: {@code fk_} followed by 32 random bytes (base64url). Only the SHA-256
 * is stored; the token is shown once. Tokens act with their role in their workspace only, on the
 * management API, until they expire or are revoked.
 */
@Service
public class ServiceTokenService {

    public static final String PREFIX = "fk_";
    static final Set<String> ROLES = Set.of("editor", "viewer");
    static final int MAX_DAYS = 3650;
    /** Last use is recorded at most this often per token. */
    static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    /** A token as listed to workspace admins (never the token itself). */
    public record TokenView(String id, String name, String role, Instant createdAt, String createdBy, Instant lastUsedAt,
            Instant expiresAt, Instant revokedAt) {}

    /** A new token: shown once. */
    public record CreatedToken(String token, TokenView details) {}

    private final JdbcClient jdbc;
    private final AccountRepository audit;
    private final JsonMapper json;
    private final Clock clock;

    public ServiceTokenService(JdbcClient jdbc, AccountRepository audit, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime odt(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static TokenView view(ResultSet rs, int n) throws SQLException {
        return new TokenView(rs.getObject("id", UUID.class).toString(), rs.getString("name"), rs.getString("role"),
                instant(rs, "created_at"), rs.getString("created_by_email"), instant(rs, "last_used_at"), instant(rs, "expires_at"),
                instant(rs, "revoked_at"));
    }

    private static final String VIEW = """
            SELECT t.*, u.email AS created_by_email FROM service_token t LEFT JOIN admin_user u ON u.id = t.created_by
            """;

    private void record(Actor actor, String action, String target, Map<String, Object> details) {
        audit.audit(actor.type(), actor.id(), action, target, actor.ip(), details == null ? null : json.writeValueAsString(details));
    }

    /** The tokens of the request's workspace, newest first (revoked ones included). */
    @Transactional(readOnly = true)
    public List<TokenView> list() {
        return jdbc.sql(VIEW + " WHERE t.workspace_id = :ws ORDER BY t.created_at DESC")
                .param("ws", WorkspaceContext.id()).query(ServiceTokenService::view).list();
    }

    @Transactional
    public CreatedToken create(Actor actor, String name, String role, Integer expiresInDays) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty() || clean.length() > 120) {
            throw new RuntimeException4xx(HttpStatus.BAD_REQUEST, "invalidName", "A token name has 1 to 120 characters.");
        }
        if (!ROLES.contains(role)) {
            throw new RuntimeException4xx(HttpStatus.BAD_REQUEST, "invalidRole", "Role must be editor or viewer.");
        }
        if (expiresInDays != null && (expiresInDays < 1 || expiresInDays > MAX_DAYS)) {
            throw new RuntimeException4xx(HttpStatus.BAD_REQUEST, "invalidExpiry", "Expiry must be 1 to " + MAX_DAYS + " days.");
        }
        Instant now = clock.instant();
        String token = PREFIX + Secrets.newToken();
        UUID id = jdbc.sql("""
                        INSERT INTO service_token (workspace_id, name, token_hash, role, created_by, created_at, expires_at)
                        VALUES (:ws, :name, :hash, :role, :by, :now, :expires) RETURNING id
                        """)
                .param("ws", WorkspaceContext.id()).param("name", clean).param("hash", Secrets.hash(token)).param("role", role)
                .param("by", actor.id()).param("now", odt(now))
                .param("expires", expiresInDays == null ? null : odt(now.plus(Duration.ofDays(expiresInDays))))
                .query(UUID.class).single();
        record(actor, "serviceToken.created", clean, Map.of("id", id.toString(), "role", role));
        return new CreatedToken(token, find(id).orElseThrow());
    }

    @Transactional
    public void revoke(Actor actor, UUID id) {
        TokenView token = find(id).filter(t -> t.revokedAt() == null)
                .orElseThrow(() -> new RuntimeException4xx(HttpStatus.NOT_FOUND, "notFound", "No such token."));
        jdbc.sql("UPDATE service_token SET revoked_at = :now WHERE id = :id").param("now", odt(clock.instant())).param("id", id).update();
        record(actor, "serviceToken.revoked", token.name(), Map.of("id", id.toString()));
    }

    private Optional<TokenView> find(UUID id) {
        return jdbc.sql(VIEW + " WHERE t.id = :id AND t.workspace_id = :ws")
                .param("id", id).param("ws", WorkspaceContext.id()).query(ServiceTokenService::view).optional();
    }

    /** The principal of a live token (not revoked, not expired), recording its use. */
    @Transactional
    public Optional<ServiceTokenPrincipal> authenticate(String token) {
        if (token == null || !token.startsWith(PREFIX) || token.length() > 128) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        record Live(ServiceTokenPrincipal principal, Instant lastUsedAt) {}
        Optional<Live> live = jdbc.sql("""
                        SELECT id, workspace_id, role, name, last_used_at FROM service_token
                        WHERE token_hash = :hash AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > :now)
                        """)
                .param("hash", Secrets.hash(token)).param("now", odt(now))
                .query((rs, n) -> new Live(new ServiceTokenPrincipal(rs.getObject("id", UUID.class),
                        rs.getObject("workspace_id", UUID.class), rs.getString("role"), rs.getString("name")),
                        instant(rs, "last_used_at")))
                .optional();
        live.filter(l -> l.lastUsedAt() == null || l.lastUsedAt().plus(TOUCH_INTERVAL).isBefore(now))
                .ifPresent(l -> jdbc.sql("UPDATE service_token SET last_used_at = :now WHERE id = :id")
                        .param("now", odt(now)).param("id", l.principal().tokenId()).update());
        return live.map(Live::principal);
    }
}
