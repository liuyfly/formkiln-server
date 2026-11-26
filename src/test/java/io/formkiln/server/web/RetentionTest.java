package io.formkiln.server.web;

import io.formkiln.server.TestMembers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.formkiln.server.TestcontainersConfiguration;
import io.formkiln.server.retention.RetentionService;
import io.formkiln.server.storage.ObjectStorage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "formkiln.service-token=test-token")
@Import(TestcontainersConfiguration.class)
class RetentionTest {

    private static final String TOKEN = "Bearer test-token";

    private static String form(String key) {
        return """
                {"schemaVersion": 1, "key": "%s", "title": "T",
                 "pages": [{"key": "p", "elements": [
                   {"type": "text", "key": "name", "label": "Name"},
                   {"type": "email", "key": "email", "label": "Email"},
                   {"type": "file", "key": "doc", "label": "Document", "multiple": true},
                   {"type": "repeater", "key": "contacts", "label": "Contacts",
                    "elements": [{"type": "email", "key": "contactEmail", "label": "Email"}]}
                 ]}],
                 "submit": {"outcomes": [{"key": "done"}]}}
                """.formatted(key);
    }

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcClient jdbc;
    @Autowired private JsonMapper json;
    @Autowired private RetentionService retention;
    @Autowired private ObjectStorage storage;

    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("TRUNCATE mail_outbox, form_notification_settings, webhook_delivery, webhook_endpoint, file_object, form_draft,"
                + " submission, form_version, form, audit_log CASCADE").update();
        for (String key : new String[] {"alpha", "beta"}) {
            mvc.perform(post("/v1/manage/workspaces/default/forms/" + key + "/versions").header("Authorization", TOKEN)
                            .contentType(MediaType.APPLICATION_JSON).content(form(key)))
                    .andExpect(status().isCreated());
        }
    }

    private String submit(String formKey, String answers) throws Exception {
        String body = mvc.perform(post("/v1/w/default/forms/" + formKey + "/submissions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\": " + answers + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return (String) json.readValue(body, Map.class).get("id");
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    @Test
    void deletesASubmissionWithItsFilesDeliveriesAndEmails() throws Exception {
        mvc.perform(post("/v1/manage/workspaces/default/forms/alpha/webhooks").header("Authorization", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"url\": \"https://example.com/hook\"}"));
        mvc.perform(put("/v1/manage/workspaces/default/forms/alpha/notifications").header("Authorization", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"teamRecipients\": [\"ops@example.com\"]}"));
        String upload = mvc.perform(multipart("/v1/w/default/forms/alpha/files")
                        .file(new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8)))
                        .param("field", "doc"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String fileId = (String) json.readValue(upload, Map.class).get("id");
        String storageKey = jdbc.sql("SELECT storage_key FROM file_object").query(String.class).single();
        String id = submit("alpha", "{\"name\": \"Ada\", \"doc\": [{\"id\": \"" + fileId + "\"}]}");
        String other = submit("alpha", "{\"name\": \"Grace\"}");
        assertThat(count("webhook_delivery")).isEqualTo(2);
        assertThat(count("mail_outbox")).isEqualTo(2);

        mvc.perform(delete("/v1/manage/workspaces/default/forms/beta/submissions/" + id).header("Authorization", TOKEN)).andExpect(status().isNotFound());
        mvc.perform(delete("/v1/manage/workspaces/default/forms/alpha/submissions/" + id).with(TestMembers.member(jdbc, "viewer")))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/v1/manage/workspaces/default/forms/alpha/submissions/" + id).header("Authorization", TOKEN)).andExpect(status().isNoContent());

        assertThat(jdbc.sql("SELECT id::text FROM submission").query(String.class).list()).containsExactly(other);
        assertThat(count("file_object")).isZero();
        assertThat(count("webhook_delivery")).isEqualTo(1);
        assertThat(count("mail_outbox")).isEqualTo(1);
        assertThatThrownBy(() -> storage.get(storageKey).close()).isInstanceOf(IOException.class);
        assertThat(jdbc.sql("SELECT target FROM audit_log WHERE action = 'submission.deleted'").query(String.class).single())
                .isEqualTo("alpha/" + id);
    }

    @Test
    void purgesSubmissionsPastTheRetentionPeriod() throws Exception {
        mvc.perform(get("/v1/manage/workspaces/default/forms/alpha/retention").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").doesNotExist());
        mvc.perform(put("/v1/manage/workspaces/default/forms/alpha/retention").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"days\": 0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/v1/manage/workspaces/default/forms/alpha/retention").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"days\": 30}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.days").value(30));
        mvc.perform(get("/v1/manage/workspaces/default/forms/alpha/retention").header("Authorization", TOKEN)).andExpect(jsonPath("$.days").value(30));
        mvc.perform(get("/v1/manage/workspaces/default/forms").header("Authorization", TOKEN))
                .andExpect(jsonPath("$[?(@.key == 'alpha')].retentionDays").value(30));

        String old = submit("alpha", "{\"name\": \"Old\"}");
        String recent = submit("alpha", "{\"name\": \"Recent\"}");
        String otherForm = submit("beta", "{\"name\": \"Old elsewhere\"}");
        jdbc.sql("UPDATE submission SET submitted_at = now() - interval '31 days' WHERE id IN (CAST(:a AS uuid), CAST(:b AS uuid))")
                .param("a", old).param("b", otherForm).update();

        retention.purgeExpired();

        assertThat(jdbc.sql("SELECT id::text FROM submission").query(String.class).list()).containsExactlyInAnyOrder(recent, otherForm);
        assertThat(jdbc.sql("SELECT actor_type || ' ' || target || ' ' || details::text FROM audit_log WHERE action = 'retention.purged'")
                        .query(String.class).single())
                .isEqualTo("system alpha {\"days\": 30, \"deleted\": 1}");

        mvc.perform(put("/v1/manage/workspaces/default/forms/alpha/retention").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"days\": null}"))
                .andExpect(jsonPath("$.days").doesNotExist());
    }

    private ResultActions erase(String body) throws Exception {
        return mvc.perform(post("/v1/admin/workspaces/default/erasure").with(TestMembers.member(jdbc, "admin"))
                .header("X-Requested-With", "formkiln").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void erasesEverySubmissionOfAPerson() throws Exception {
        submit("alpha", "{\"name\": \"Ada\", \"email\": \"Ada@Example.com\"}");
        submit("beta", "{\"name\": \"Someone\", \"contacts\": [{\"contactEmail\": \"ada@example.com\"}]}");
        String kept = submit("beta", "{\"name\": \"Grace\", \"email\": \"grace@example.com\"}");

        erase("{\"email\": \"ada@example.com\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.forms.alpha").value(1))
                .andExpect(jsonPath("$.forms.beta").value(1));
        assertThat(count("submission")).isEqualTo(3);

        erase("{\"email\": \"ADA@example.com\", \"dryRun\": false}").andExpect(jsonPath("$.deleted").value(true));
        assertThat(jdbc.sql("SELECT id::text FROM submission").query(String.class).list()).containsExactly(kept);
        String details = jdbc.sql("SELECT details::text FROM audit_log WHERE action = 'erasure.performed'").query(String.class).single();
        assertThat(details).contains("\"deleted\": 2").doesNotContain("ada@").doesNotContain("example.com");

        erase("{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("identifierRequired"));
        mvc.perform(post("/v1/admin/workspaces/default/erasure").with(TestMembers.member(jdbc, "editor")).header("X-Requested-With", "formkiln")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\": \"x@example.com\"}"))
                .andExpect(status().isForbidden());
        // The service token never reaches member administration: not even the workspace is revealed.
        mvc.perform(post("/v1/admin/workspaces/default/erasure").header("Authorization", TOKEN).header("X-Requested-With", "formkiln")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\": \"x@example.com\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void erasesBySubject() throws Exception {
        mvc.perform(put("/v1/manage/workspaces/default/forms/alpha/access").header("Authorization", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\": \"token\"}"));
        String token = (String) json.readValue(mvc.perform(post("/v1/manage/workspaces/default/embed-tokens").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"formKey\": \"alpha\", \"subject\": \"user-7\"}"))
                .andReturn().getResponse().getContentAsString(), Map.class).get("token");
        mvc.perform(post("/v1/w/default/forms/alpha/submissions").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"answers\": {\"name\": \"Ada\"}}"))
                .andExpect(status().isCreated());
        erase("{\"subject\": \"user-7\", \"dryRun\": false}").andExpect(jsonPath("$.total").value(1));
        assertThat(count("submission")).isZero();
    }
}
