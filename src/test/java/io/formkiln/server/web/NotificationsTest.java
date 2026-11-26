package io.formkiln.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.formkiln.server.TestcontainersConfiguration;
import io.formkiln.server.accounts.Mailer;
import io.formkiln.server.notifications.NotificationService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.mail.MailSendException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {"formkiln.service-token=test-token", "formkiln.admin-url=https://admin.test",
        "formkiln.hosted-url=https://forms.test"})
@Import({TestcontainersConfiguration.class, NotificationsTest.MailConfig.class})
class NotificationsTest {

    private static final String TOKEN = "Bearer test-token";

    /** Records messages; fails while {@code failing} is set. */
    static class FakeMailer implements Mailer {
        final List<Message> sent = new ArrayList<>();
        volatile boolean failing;

        @Override
        public synchronized void send(Message message) {
            if (failing) {
                throw new MailSendException("SMTP down");
            }
            sent.add(message);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MailConfig {
        @Bean
        @Primary
        FakeMailer fakeMailer() {
            return new FakeMailer();
        }
    }

    private static final String FORM = """
            {"schemaVersion": 1, "key": "signup", "title": "Sign up",
             "pages": [{"key": "p", "elements": [
               {"type": "text", "key": "name", "label": "Name", "required": true},
               {"type": "email", "key": "email", "label": "Email"},
               {"type": "radio", "key": "plan", "label": "Plan", "options": [{"value": "pro", "label": "Professional"}]},
               {"type": "text", "key": "taxId", "label": "Tax ID", "sensitive": true}
             ]}],
             "submit": {"outcomes": [{"key": "done"}]}}
            """;

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcClient jdbc;
    @Autowired private JsonMapper json;
    @Autowired private FakeMailer mailer;
    @Autowired private NotificationService notifications;

    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("TRUNCATE mail_outbox, form_notification_settings, form_draft, submission, form_version, form, audit_log CASCADE").update();
        synchronized (mailer) {
            mailer.sent.clear();
        }
        mailer.failing = false;
        mvc.perform(post("/v1/manage/forms/signup/versions").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(FORM))
                .andExpect(status().isCreated());
    }

    private ResultActions configure(String settings) throws Exception {
        return mvc.perform(put("/v1/manage/forms/signup/notifications").header("Authorization", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(settings));
    }

    private void submit(String answers) throws Exception {
        mvc.perform(post("/v1/forms/signup/submissions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\": " + answers + "}"))
                .andExpect(status().isCreated());
    }

    private Mailer.Message sentTo(String address) {
        synchronized (mailer) {
            return mailer.sent.stream().filter(m -> m.to().equals(address)).findFirst().orElseThrow();
        }
    }

    @Test
    void validatesAndAuditsSettings() throws Exception {
        mvc.perform(get("/v1/manage/forms/signup/notifications").header("Authorization", TOKEN))
                .andExpect(jsonPath("$.teamRecipients").isEmpty())
                .andExpect(jsonPath("$.confirmationEnabled").value(false))
                .andExpect(jsonPath("$.defaultConfirmationSubject").value("We received your response: {{form.title}}"));
        configure("{\"teamRecipients\": [\"not-an-email\"]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalidRecipient"));
        configure("{\"confirmationEnabled\": true}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("confirmationField"));
        configure("{\"teamRecipients\": [\" Team@Example.com \", \"team@example.com\"], \"resumeEnabled\": true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamRecipients[0]").value("team@example.com"))
                .andExpect(jsonPath("$.teamRecipients.length()").value(1));
        assertThat(jdbc.sql("SELECT details::text FROM audit_log WHERE action = 'notifications.updated'").query(String.class).single())
                .contains("\"teamRecipients\": 1").contains("\"resume\": true");
        mvc.perform(put("/v1/manage/forms/signup/notifications").with(user("v").authorities(() -> "viewer"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void sendsTeamNotificationsAndConfirmationsWithoutSensitiveValues() throws Exception {
        configure("""
                {"teamRecipients": ["ops@example.com", "boss@example.com"], "teamIncludeAnswers": true,
                 "confirmationEnabled": true, "confirmationEmailField": "email",
                 "confirmationSubject": "Hi {{answer.name}}\\r\\nBcc: x@evil.test", "confirmationBody": "Plan: {{answer.plan}}\\n{{answers}}"}
                """).andExpect(status().isOk());
        submit("{\"name\": \"Ada\", \"email\": \"ada@example.com\", \"plan\": \"pro\", \"taxId\": \"SECRET-123\"}");

        // Queued encrypted, then sent by the background job.
        assertThat(jdbc.sql("SELECT encode(payload, 'escape') FROM mail_outbox").query(String.class).list())
                .hasSize(3)
                .allSatisfy(payload -> assertThat(payload).doesNotContain("ada@example.com").doesNotContain("Ada"));
        notifications.deliverDue();

        Mailer.Message team = sentTo("ops@example.com");
        assertThat(team.subject()).isEqualTo("New response: Sign up");
        assertThat(team.body()).contains("https://admin.test/forms/signup").contains("Name: Ada").contains("Plan: Professional")
                .doesNotContain("SECRET-123").doesNotContain("Tax ID");
        sentTo("boss@example.com");
        Mailer.Message confirmation = sentTo("ada@example.com");
        assertThat(confirmation.subject()).isEqualTo("Hi Ada Bcc: x@evil.test");
        assertThat(confirmation.body()).startsWith("Plan: Professional\n").contains("Email: ada@example.com").doesNotContain("SECRET-123");

        mvc.perform(get("/v1/manage/forms/signup/mail").header("Authorization", TOKEN))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.kind == 'confirmation')].recipient").value("a***@example.com"))
                .andExpect(jsonPath("$[?(@.kind == 'confirmation')].status").value("sent"));
    }

    @Test
    void teamNotificationsLeaveAnswersOutByDefault() throws Exception {
        configure("{\"teamRecipients\": [\"ops@example.com\"]}");
        submit("{\"name\": \"Ada\"}");
        notifications.deliverDue();
        assertThat(sentTo("ops@example.com").body()).doesNotContain("Ada");
    }

    @Test
    void retriesThenGivesUp() throws Exception {
        configure("{\"teamRecipients\": [\"ops@example.com\"]}");
        submit("{\"name\": \"Ada\"}");
        mailer.failing = true;
        for (int i = 0; i < 5; i++) {
            jdbc.sql("UPDATE mail_outbox SET next_attempt_at = now() - interval '1 second' WHERE status = 'pending'").update();
            notifications.deliverDue();
        }
        Map<String, Object> row = jdbc.sql("SELECT status, attempts, last_error FROM mail_outbox").query().singleRow();
        assertThat(row).containsEntry("status", "dead").containsEntry("attempts", 5);
        assertThat((String) row.get("last_error")).contains("SMTP down");
        assertThat(mailer.sent).isEmpty();
    }

    private String draft() throws Exception {
        String body = mvc.perform(post("/v1/forms/signup/drafts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\": {\"name\": \"Ada\"}}"))
                .andReturn().getResponse().getContentAsString();
        return (String) json.readValue(body, Map.class).get("token");
    }

    private ResultActions resume(String token, String email) throws Exception {
        return mvc.perform(post("/v1/forms/signup/drafts/resume-link").header("Formkiln-Draft", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\": \"" + email + "\"}"));
    }

    @Test
    void emailsALinkToContinueLater() throws Exception {
        String token = draft();
        resume(token, "ada@example.com").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("resumeUnavailable"));
        mvc.perform(get("/v1/forms/signup")).andExpect(jsonPath("$.resumeByEmail").value(false));

        configure("{\"resumeEnabled\": true}");
        mvc.perform(get("/v1/forms/signup")).andExpect(jsonPath("$.resumeByEmail").value(true));
        resume(token, "nope").andExpect(status().isBadRequest());
        resume("wrong-token-123456", "ada@example.com").andExpect(status().isNotFound());
        for (int i = 0; i < 3; i++) {
            resume(token, "ada@example.com").andExpect(status().isAccepted());
        }
        resume(token, "ada@example.com").andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("tooManyEmails"));
        notifications.deliverDue();
        Mailer.Message link = sentTo("ada@example.com");
        assertThat(link.subject()).isEqualTo("Continue your form: Sign up");
        assertThat(link.body()).contains("https://forms.test/f/signup#draft=" + token);

        // Token forms are reopened by the embedding application, not by email.
        mvc.perform(put("/v1/manage/forms/signup/access").header("Authorization", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\": \"token\"}"));
        String embed = (String) json.readValue(mvc.perform(post("/v1/manage/embed-tokens").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"formKey\": \"signup\"}"))
                .andReturn().getResponse().getContentAsString(), Map.class).get("token");
        mvc.perform(post("/v1/forms/signup/drafts/resume-link").header("Authorization", "Bearer " + embed)
                        .header("Formkiln-Draft", token).contentType(MediaType.APPLICATION_JSON).content("{\"email\": \"ada@example.com\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void theConfirmationFieldFollowsARename() throws Exception {
        String withIds = FORM.replace("{\"type\": \"email\", \"key\": \"email\"", "{\"type\": \"email\", \"id\": \"e1\", \"key\": \"email\"");
        mvc.perform(post("/v1/manage/forms/signup/versions").header("Authorization", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(withIds)).andExpect(status().isCreated());
        configure("""
                {"confirmationEnabled": true, "confirmationEmailField": "email"}
                """).andExpect(status().isOk());

        mvc.perform(post("/v1/manage/forms/signup/versions").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withIds.replace("\"key\": \"email\"", "\"key\": \"contactEmail\"")))
                .andExpect(status().isCreated());
        assertThat(jdbc.sql("SELECT confirmation_email_field FROM form_notification_settings").query(String.class).single())
                .isEqualTo("contactEmail");

        submit("{\"name\": \"Ada\", \"contactEmail\": \"ada@example.com\"}");
        notifications.deliverDue();
        assertThat(sentTo("ada@example.com")).isNotNull();
    }
}
