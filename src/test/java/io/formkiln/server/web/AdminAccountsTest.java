package io.formkiln.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.formkiln.server.TestcontainersConfiguration;
import io.formkiln.server.accounts.AccountService;
import io.formkiln.server.accounts.Mailer;
import io.formkiln.server.accounts.Totp;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "formkiln.service-token=test-token")
@Import({TestcontainersConfiguration.class, AdminAccountsTest.Fakes.class})
class AdminAccountsTest {

    /** Records outgoing mail and controls time. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Fakes {
        @Bean
        @Primary
        RecordingMailer recordingMailer() {
            return new RecordingMailer();
        }

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    static class RecordingMailer implements Mailer {
        final List<Message> sent = new ArrayList<>();

        @Override
        public synchronized void send(Message message) {
            sent.add(message);
        }

        synchronized String lastToken(String to) {
            for (int i = sent.size() - 1; i >= 0; i--) {
                if (sent.get(i).to().equals(to)) {
                    Matcher matcher = Pattern.compile("token=([A-Za-z0-9_-]+)").matcher(sent.get(i).body());
                    if (matcher.find()) {
                        return matcher.group(1);
                    }
                }
            }
            throw new AssertionError("No mail with a token sent to " + to);
        }
    }

    static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-25T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    /** A signed-in member: their session cookie and TOTP secret. */
    record Member(String email, Cookie cookie, byte[] secret, List<String> recoveryCodes) {}

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcClient jdbc;
    @Autowired private RecordingMailer mailer;
    @Autowired private MutableClock clock;
    @Autowired private AccountService accounts;
    @Autowired private JsonMapper json;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("""
                TRUNCATE audit_log, admin_password_reset, admin_login_challenge, admin_session,
                         admin_recovery_code, admin_invitation, admin_user CASCADE
                """).update();
        mailer.sent.clear();
    }

    // ---------------------------------------------------------------- helpers

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Object body) {
        return request.contentType(MediaType.APPLICATION_JSON)
                .header("X-Requested-With", "formkiln")
                .content(json.writeValueAsString(body));
    }

    private MvcResult call(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        String text = result.getResponse().getContentAsString();
        return text.isEmpty() ? null : json.readTree(text);
    }

    private static Cookie sessionCookie(MvcResult result) {
        String header = result.getResponse().getHeader("Set-Cookie");
        assertThat(header).contains("HttpOnly").contains("SameSite=Strict").contains("Secure");
        String value = header.substring(header.indexOf('=') + 1, header.indexOf(';'));
        return new Cookie(AdminApi.SESSION_COOKIE, value);
    }

    private byte[] secretOf(JsonNode totp) {
        return base32Decode(totp.get("secret").asString());
    }

    private static byte[] base32Decode(String text) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : text.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    private long lastCodeStep = Long.MIN_VALUE;

    /** A fresh code: each code is accepted once, so wait for the next step as a person would. */
    private String code(byte[] secret) {
        if (clock.instant().getEpochSecond() / 30 <= lastCodeStep) {
            clock.advance(Duration.ofSeconds(30));
        }
        lastCodeStep = clock.instant().getEpochSecond() / 30;
        return Totp.code(secret, clock.instant());
    }

    /** Accepts the latest invitation for {@code email}. */
    private Member accept(String email, String password) throws Exception {
        String token = mailer.lastToken(email);
        JsonNode details = body(call(json(post("/v1/admin/invitations/inspect"), Map.of("token", token))));
        byte[] secret = secretOf(details.get("totp"));
        MvcResult accepted = call(json(post("/v1/admin/invitations/accept"),
                Map.of("token", token, "password", password, "code", code(secret))));
        assertThat(accepted.getResponse().getStatus()).isEqualTo(200);
        List<String> codes = new ArrayList<>();
        body(accepted).get("recoveryCodes").forEach(node -> codes.add(node.asString()));
        return new Member(email, sessionCookie(accepted), secret, codes);
    }

    private Member firstAdmin() throws Exception {
        assertThat(accounts.bootstrap("Admin@Example.com")).isTrue();
        return accept("admin@example.com", "correct horse battery");
    }

    private Member invite(Member admin, String email, String role) throws Exception {
        MvcResult result = call(json(post("/v1/admin/invitations"), Map.of("email", email, "role", role)).cookie(admin.cookie()));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return accept(email, "another long password");
    }

    private MvcResult login(String email, String password, byte[] secret) throws Exception {
        MvcResult first = call(json(post("/v1/admin/auth/login"), Map.of("email", email, "password", password)));
        if (first.getResponse().getStatus() != 200) {
            return first;
        }
        return call(json(post("/v1/admin/auth/mfa"), Map.of("challenge", body(first).get("challenge").asString(), "code", code(secret))));
    }

    private int status(MockHttpServletRequestBuilder request, Cookie cookie) throws Exception {
        return call(cookie == null ? request : request.cookie(cookie)).getResponse().getStatus();
    }

    // ---------------------------------------------------------------- tests

    @Test
    void anAuthenticatorCodeWorksOnce() throws Exception {
        Member admin = firstAdmin();
        // The code that enrolled the authenticator cannot sign in.
        String enrollment = Totp.code(admin.secret(), clock.instant());
        assertThat(mfa("admin@example.com", "correct horse battery", enrollment).getResponse().getStatus()).isEqualTo(401);

        String code = code(admin.secret());
        assertThat(mfa("admin@example.com", "correct horse battery", code).getResponse().getStatus()).isEqualTo(200);
        // Seen over a shoulder or through a phishing proxy, the same code is refused, even
        // while it is still within its time window.
        assertThat(mfa("admin@example.com", "correct horse battery", code).getResponse().getStatus()).isEqualTo(401);
        // An earlier step (still inside the clock-drift window) is refused too.
        String previous = Totp.code(admin.secret(), clock.instant().minusSeconds(30));
        assertThat(mfa("admin@example.com", "correct horse battery", previous).getResponse().getStatus()).isEqualTo(401);
        // Regenerating recovery codes uses up a code in the same way.
        String next = code(admin.secret());
        assertThat(status(json(post("/v1/admin/me/recovery-codes"), Map.of("code", next)), admin.cookie())).isEqualTo(200);
        assertThat(status(json(post("/v1/admin/me/recovery-codes"), Map.of("code", next)), admin.cookie())).isEqualTo(400);
    }

    private MvcResult mfa(String email, String password, String code) throws Exception {
        MvcResult first = call(json(post("/v1/admin/auth/login"), Map.of("email", email, "password", password)));
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        return call(json(post("/v1/admin/auth/mfa"), Map.of("challenge", body(first).get("challenge").asString(), "code", code)));
    }

    @Test
    void bootstrapInvitesTheFirstAdminOnce() {
        assertThat(accounts.bootstrap("Admin@Example.com")).isTrue();
        assertThat(mailer.sent).hasSize(1);
        assertThat(mailer.sent.get(0).to()).isEqualTo("admin@example.com");
        assertThat(mailer.sent.get(0).body()).contains("http://localhost:3200/invite#token=");
        assertThat(accounts.bootstrap("admin@example.com")).isFalse();
    }

    @Test
    void invitationRequiresAStrongPasswordAndAValidCode() throws Exception {
        accounts.bootstrap("admin@example.com");
        String token = mailer.lastToken("admin@example.com");
        JsonNode details = body(call(json(post("/v1/admin/invitations/inspect"), Map.of("token", token))));
        assertThat(details.get("email").asString()).isEqualTo("admin@example.com");
        assertThat(details.get("role").asString()).isEqualTo("admin");
        assertThat(details.at("/totp/otpauthUri").asString()).startsWith("otpauth://totp/Formkiln");
        // The enrollment secret is stable across inspections.
        JsonNode again = body(call(json(post("/v1/admin/invitations/inspect"), Map.of("token", token))));
        assertThat(again.at("/totp/secret")).isEqualTo(details.at("/totp/secret"));
        byte[] secret = secretOf(details.get("totp"));

        MvcResult weak = call(json(post("/v1/admin/invitations/accept"), Map.of("token", token, "password", "short", "code", code(secret))));
        assertThat(weak.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(weak).get("code").asString()).isEqualTo("weakPassword");

        MvcResult wrongCode = call(json(post("/v1/admin/invitations/accept"),
                Map.of("token", token, "password", "correct horse battery", "code", "000000")));
        assertThat(body(wrongCode).get("code").asString()).isEqualTo("invalidCode");

        Member admin = accept("admin@example.com", "correct horse battery");
        assertThat(admin.recoveryCodes()).hasSize(10).allMatch(c -> c.matches("[a-z2-9]{5}-[a-z2-9]{5}"));

        MvcResult reused = call(json(post("/v1/admin/invitations/inspect"), Map.of("token", token)));
        assertThat(body(reused).get("code").asString()).isEqualTo("invalidToken");
    }

    @Test
    void expiredInvitationsAreRejected() throws Exception {
        accounts.bootstrap("admin@example.com");
        clock.advance(Duration.ofHours(73));
        MvcResult result = call(json(post("/v1/admin/invitations/inspect"), Map.of("token", mailer.lastToken("admin@example.com"))));
        assertThat(body(result).get("code").asString()).isEqualTo("invalidToken");
    }

    @Test
    void signsInWithPasswordAndTotpAndSignsOut() throws Exception {
        Member admin = firstAdmin();
        assertThat(body(call(get("/v1/admin/auth/me").cookie(admin.cookie()))).get("role").asString()).isEqualTo("admin");
        assertThat(status(get("/v1/admin/auth/me"), null)).isEqualTo(401);

        MvcResult wrong = login("admin@example.com", "wrong password!!", admin.secret());
        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        MvcResult unknown = login("nobody@example.com", "correct horse battery", admin.secret());
        assertThat(body(unknown).get("code")).isEqualTo(body(wrong).get("code"));

        MvcResult first = call(json(post("/v1/admin/auth/login"), Map.of("email", "admin@example.com", "password", "correct horse battery")));
        String challenge = body(first).get("challenge").asString();
        assertThat(status(json(post("/v1/admin/auth/mfa"), Map.of("challenge", challenge, "code", "000000")), null)).isEqualTo(401);
        MvcResult ok = call(json(post("/v1/admin/auth/mfa"), Map.of("challenge", challenge, "code", code(admin.secret()))));
        Cookie cookie = sessionCookie(ok);
        // A challenge can be used once.
        assertThat(status(json(post("/v1/admin/auth/mfa"), Map.of("challenge", challenge, "code", code(admin.secret()))), null))
                .isEqualTo(401);

        assertThat(status(json(post("/v1/admin/auth/logout"), Map.of()), cookie)).isEqualTo(204);
        assertThat(status(get("/v1/admin/auth/me"), cookie)).isEqualTo(401);
    }

    @Test
    void recoveryCodesWorkOnce() throws Exception {
        Member admin = firstAdmin();
        String recovery = admin.recoveryCodes().get(0);
        for (int attempt = 0; attempt < 2; attempt++) {
            MvcResult first = call(json(post("/v1/admin/auth/login"),
                    Map.of("email", "admin@example.com", "password", "correct horse battery")));
            MvcResult result = call(json(post("/v1/admin/auth/mfa"),
                    Map.of("challenge", body(first).get("challenge").asString(), "recoveryCode", recovery.toUpperCase())));
            assertThat(result.getResponse().getStatus()).isEqualTo(attempt == 0 ? 200 : 401);
        }
    }

    @Test
    void locksTheAccountAfterRepeatedFailures() throws Exception {
        Member admin = firstAdmin();
        for (int i = 0; i < 5; i++) {
            assertThat(login("admin@example.com", "wrong password!!", admin.secret()).getResponse().getStatus()).isEqualTo(401);
        }
        assertThat(login("admin@example.com", "correct horse battery", admin.secret()).getResponse().getStatus()).isEqualTo(401);
        clock.advance(Duration.ofMinutes(16));
        assertThat(login("admin@example.com", "correct horse battery", admin.secret()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void sessionsExpireWhenIdle() throws Exception {
        Member admin = firstAdmin();
        clock.advance(Duration.ofMinutes(29));
        assertThat(status(get("/v1/admin/auth/me"), admin.cookie())).isEqualTo(200);
        clock.advance(Duration.ofMinutes(29));
        assertThat(status(get("/v1/admin/auth/me"), admin.cookie())).isEqualTo(200);
        clock.advance(Duration.ofMinutes(31));
        assertThat(status(get("/v1/admin/auth/me"), admin.cookie())).isEqualTo(401);
    }

    @Test
    void unsafeAdminRequestsNeedTheCsrfHeader() throws Exception {
        Member admin = firstAdmin();
        MvcResult result = call(post("/v1/admin/auth/logout").cookie(admin.cookie()));
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(status(get("/v1/admin/auth/me"), admin.cookie())).isEqualTo(200);
    }

    @Test
    void rolesControlAccess() throws Exception {
        Member admin = firstAdmin();
        Member editor = invite(admin, "editor@example.com", "editor");
        Member viewer = invite(admin, "viewer@example.com", "viewer");
        String form = """
                {"schemaVersion": 1, "key": "contact", "title": "Contact",
                 "pages": [{"key": "p", "elements": [{"type": "text", "key": "name", "label": "Name"}]}]}
                """;
        // Request builders are mutable, so build a fresh one per request.
        java.util.function.Supplier<MockHttpServletRequestBuilder> publish = () -> post("/v1/manage/forms/contact/versions")
                .contentType(MediaType.APPLICATION_JSON).header("X-Requested-With", "formkiln").content(form);

        assertThat(status(get("/v1/manage/forms"), viewer.cookie())).isEqualTo(200);
        assertThat(status(publish.get(), viewer.cookie())).isEqualTo(403);
        assertThat(status(publish.get(), editor.cookie())).isEqualTo(201);
        // Cookie-authenticated writes outside /v1/admin also need the CSRF header ...
        assertThat(status(post("/v1/manage/forms/contact/versions").contentType(MediaType.APPLICATION_JSON).content(form),
                editor.cookie())).isEqualTo(403);
        // ... while service-token automation does not.
        assertThat(call(post("/v1/manage/forms/contact/versions").contentType(MediaType.APPLICATION_JSON).content(form)
                .header("Authorization", "Bearer test-token")).getResponse().getStatus()).isEqualTo(201);
        assertThat(status(get("/v1/admin/members"), editor.cookie())).isEqualTo(403);
        assertThat(status(get("/v1/admin/members"), admin.cookie())).isEqualTo(200);
        assertThat(call(get("/v1/admin/members").header("Authorization", "Bearer test-token")).getResponse().getStatus())
                .isIn(401, 403);
        assertThat(call(get("/v1/manage/forms").header("Authorization", "Bearer test-token")).getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    void protectsTheLastActiveAdmin() throws Exception {
        Member admin = firstAdmin();
        String id = body(call(get("/v1/admin/auth/me").cookie(admin.cookie()))).get("id").asString();
        MvcResult demote = call(json(patch("/v1/admin/members/" + id), Map.of("role", "editor")).cookie(admin.cookie()));
        assertThat(demote.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(demote).get("code").asString()).isEqualTo("lastAdmin");
        MvcResult disable = call(json(patch("/v1/admin/members/" + id), Map.of("status", "disabled")).cookie(admin.cookie()));
        assertThat(disable.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void disablingAMemberEndsTheirSessions() throws Exception {
        Member admin = firstAdmin();
        Member editor = invite(admin, "editor@example.com", "editor");
        String id = body(call(get("/v1/admin/auth/me").cookie(editor.cookie()))).get("id").asString();
        MvcResult result = call(json(patch("/v1/admin/members/" + id), Map.of("status", "disabled")).cookie(admin.cookie()));
        assertThat(body(result).get("status").asString()).isEqualTo("disabled");
        assertThat(status(get("/v1/admin/auth/me"), editor.cookie())).isEqualTo(401);
        assertThat(login("editor@example.com", "another long password", editor.secret()).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void invitationsCanBeListedResentAndRevoked() throws Exception {
        Member admin = firstAdmin();
        MvcResult created = call(json(post("/v1/admin/invitations"), Map.of("email", "New@Example.com", "role", "viewer")).cookie(admin.cookie()));
        String id = body(created).get("id").asString();
        String firstToken = mailer.lastToken("new@example.com");

        String resentId = body(call(json(post("/v1/admin/invitations/" + id + "/resend"), Map.of()).cookie(admin.cookie()))).get("id").asString();
        assertThat(mailer.lastToken("new@example.com")).isNotEqualTo(firstToken);
        assertThat(body(call(json(post("/v1/admin/invitations/inspect"), Map.of("token", firstToken)))).get("code").asString())
                .isEqualTo("invalidToken");

        JsonNode open = body(call(get("/v1/admin/invitations").cookie(admin.cookie())));
        assertThat(open).hasSize(1);
        assertThat(status(json(delete("/v1/admin/invitations/" + resentId), Map.of()), admin.cookie())).isEqualTo(204);
        assertThat(body(call(get("/v1/admin/invitations").cookie(admin.cookie())))).isEmpty();

        MvcResult existing = call(json(post("/v1/admin/invitations"), Map.of("email", "admin@example.com", "role", "viewer")).cookie(admin.cookie()));
        assertThat(body(existing).get("code").asString()).isEqualTo("memberExists");
    }

    @Test
    void anAdminCanResetAnotherMembersAuthenticator() throws Exception {
        Member admin = firstAdmin();
        Member editor = invite(admin, "editor@example.com", "editor");
        String id = body(call(get("/v1/admin/auth/me").cookie(editor.cookie()))).get("id").asString();
        assertThat(status(json(post("/v1/admin/members/" + id + "/reset-totp"), Map.of()), admin.cookie())).isEqualTo(204);
        assertThat(status(get("/v1/admin/auth/me"), editor.cookie())).isEqualTo(401);

        MvcResult first = call(json(post("/v1/admin/auth/login"), Map.of("email", "editor@example.com", "password", "another long password")));
        JsonNode enroll = body(first).get("enrollTotp");
        assertThat(enroll).isNotNull();
        byte[] newSecret = secretOf(enroll);
        MvcResult done = call(json(post("/v1/admin/auth/mfa"),
                Map.of("challenge", body(first).get("challenge").asString(), "code", code(newSecret))));
        assertThat(done.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(done).get("recoveryCodes")).hasSize(10);
        assertThat(login("editor@example.com", "another long password", newSecret).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void resetsAForgottenPassword() throws Exception {
        Member admin = firstAdmin();
        assertThat(status(json(post("/v1/admin/auth/password/forgot"), Map.of("email", "nobody@example.com")), null)).isEqualTo(202);
        assertThat(mailer.sent).hasSize(1); // Only the invitation.

        assertThat(status(json(post("/v1/admin/auth/password/forgot"), Map.of("email", "ADMIN@example.com")), null)).isEqualTo(202);
        String token = mailer.lastToken("admin@example.com");
        assertThat(mailer.sent.getLast().body()).contains("http://localhost:3200/reset-password#token=");

        assertThat(status(json(post("/v1/admin/auth/password/reset"), Map.of("token", token, "password", "brand new password")), null))
                .isEqualTo(204);
        assertThat(status(get("/v1/admin/auth/me"), admin.cookie())).isEqualTo(401);
        assertThat(login("admin@example.com", "brand new password", admin.secret()).getResponse().getStatus()).isEqualTo(200);
        assertThat(status(json(post("/v1/admin/auth/password/reset"), Map.of("token", token, "password", "another new password")), null))
                .isEqualTo(400);
    }

    @Test
    void changesThePasswordAndManagesOwnSessions() throws Exception {
        Member admin = firstAdmin();
        Cookie other = sessionCookie(login("admin@example.com", "correct horse battery", admin.secret()));
        JsonNode sessions = body(call(get("/v1/admin/me/sessions").cookie(admin.cookie())));
        assertThat(sessions).hasSize(2);

        MvcResult wrong = call(json(post("/v1/admin/me/password"),
                Map.of("currentPassword", "nope", "newPassword", "brand new password")).cookie(admin.cookie()));
        assertThat(body(wrong).get("code").asString()).isEqualTo("wrongPassword");
        assertThat(status(json(post("/v1/admin/me/password"),
                Map.of("currentPassword", "correct horse battery", "newPassword", "brand new password")), admin.cookie())).isEqualTo(204);
        assertThat(status(get("/v1/admin/auth/me"), admin.cookie())).isEqualTo(200);
        assertThat(status(get("/v1/admin/auth/me"), other)).isEqualTo(401);

        MvcResult codes = call(json(post("/v1/admin/me/recovery-codes"), Map.of("code", code(admin.secret()))).cookie(admin.cookie()));
        assertThat(body(codes).get("recoveryCodes")).hasSize(10);
    }

    @Test
    void recordsAnAuditTrail() throws Exception {
        Member admin = firstAdmin();
        login("admin@example.com", "wrong password!!", admin.secret());
        JsonNode audit = body(call(get("/v1/admin/audit").cookie(admin.cookie())));
        List<String> actions = new ArrayList<>();
        audit.forEach(entry -> actions.add(entry.get("action").asString()));
        assertThat(actions).contains("invitation.created", "invitation.accepted", "login.failed");
        assertThat(audit.toString()).doesNotContain(mailer.lastToken("admin@example.com"));
    }

    @Test
    void secretsAreEncryptedAtRest() throws Exception {
        Member admin = firstAdmin();
        byte[] stored = jdbc.sql("SELECT totp_secret FROM admin_user").query(byte[].class).single();
        assertThat(stored).isNotEqualTo(admin.secret());
        assertThat(Base64.getEncoder().encodeToString(stored)).doesNotContain(Totp.base32(admin.secret()));
        String hash = jdbc.sql("SELECT password_hash FROM admin_user").query(String.class).single();
        assertThat(hash).startsWith("$argon2id$");
    }
}
