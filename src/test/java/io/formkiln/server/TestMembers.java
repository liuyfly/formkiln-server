package io.formkiln.server;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import io.formkiln.server.accounts.AccountService.AdminPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Signed-in accounts for MockMvc tests, without going through password and TOTP. */
public final class TestMembers {

    private TestMembers() {}

    /** A new account that is a member of {@code workspace} with {@code role}. */
    public static RequestPostProcessor member(JdbcClient jdbc, String workspace, String role) {
        UUID id = account(jdbc, false);
        jdbc.sql("INSERT INTO workspace_member (workspace_id, user_id, role) SELECT id, :user, :role FROM workspace WHERE slug = :slug")
                .param("user", id).param("role", role).param("slug", workspace).update();
        return signedIn(jdbc, id);
    }

    /** A new account that is a member of the default workspace with {@code role}. */
    public static RequestPostProcessor member(JdbcClient jdbc, String role) {
        return member(jdbc, "default", role);
    }

    /** A new instance admin without memberships. */
    public static RequestPostProcessor instanceAdmin(JdbcClient jdbc) {
        return signedIn(jdbc, account(jdbc, true));
    }

    private static UUID account(JdbcClient jdbc, boolean instanceAdmin) {
        return jdbc.sql("INSERT INTO admin_user (email, password_hash, instance_admin) VALUES (:email, 'x', :admin) RETURNING id")
                .param("email", "test-" + UUID.randomUUID() + "@example.com").param("admin", instanceAdmin)
                .query(UUID.class).single();
    }

    /** The session principal of the account (the authorities a session would get). */
    public static RequestPostProcessor signedIn(JdbcClient jdbc, UUID id) {
        boolean instanceAdmin = jdbc.sql("SELECT instance_admin FROM admin_user WHERE id = :id").param("id", id)
                .query(Boolean.class).single();
        String email = jdbc.sql("SELECT email FROM admin_user WHERE id = :id").param("id", id).query(String.class).single();
        List<SimpleGrantedAuthority> authorities = instanceAdmin
                ? List.of(new SimpleGrantedAuthority("member"), new SimpleGrantedAuthority("instanceAdmin"))
                : List.of(new SimpleGrantedAuthority("member"));
        return authentication(UsernamePasswordAuthenticationToken.authenticated(
                new AdminPrincipal(id, email, instanceAdmin, UUID.randomUUID()), null, authorities));
    }
}
