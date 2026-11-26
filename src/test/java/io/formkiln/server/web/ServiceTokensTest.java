package io.formkiln.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.formkiln.server.TestMembers;
import io.formkiln.server.TestcontainersConfiguration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** Workspace service tokens: their role, their workspace, revocation and expiry. */
@SpringBootTest(properties = "formkiln.service-token=test-token")
@Import(TestcontainersConfiguration.class)
class ServiceTokensTest {

    private static final String FORM = """
            {"schemaVersion": 1, "key": "intake", "title": "Intake",
             "pages": [{"key": "p", "elements": [{"type": "text", "key": "name", "label": "Name", "sensitive": true}]}],
             "submit": {"outcomes": [{"key": "done"}]}}
            """;

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcClient jdbc;
    @Autowired private JsonMapper json;

    private MockMvc mvc;
    private RequestPostProcessor hrAdmin;

    private static MockHttpServletRequestBuilder withJson(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).header("X-Requested-With", "formkiln").content(body);
    }

    private String create(String role) throws Exception {
        String body = mvc.perform(withJson(post("/v1/admin/workspaces/hr/service-tokens"),
                        "{\"name\": \"CRM sync\", \"role\": \"" + role + "\"}").with(hrAdmin))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = (String) json.readValue(body, Map.class).get("token");
        assertThat(token).startsWith("fk_");
        return token;
    }

    private ResultActions publish(String token) throws Exception {
        return mvc.perform(post("/v1/manage/workspaces/hr/forms/intake/versions").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(FORM));
    }

    private ResultActions read(String token, String path) throws Exception {
        return mvc.perform(get(path).header("Authorization", "Bearer " + token));
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("TRUNCATE form_working_copy, submission, form_version, form, form_folder, audit_log, service_token CASCADE").update();
        jdbc.sql("DELETE FROM workspace_access").update();
        jdbc.sql("DELETE FROM admin_invitation WHERE workspace_id IN (SELECT id FROM workspace WHERE slug <> 'default')").update();
        jdbc.sql("DELETE FROM workspace_member WHERE workspace_id IN (SELECT id FROM workspace WHERE slug <> 'default')").update();
        jdbc.sql("DELETE FROM workspace WHERE slug <> 'default'").update();
        jdbc.sql("INSERT INTO workspace (slug, name) VALUES ('hr', 'HR')").update();
        hrAdmin = TestMembers.member(jdbc, "hr", "admin");
    }

    @Test
    void anEditorTokenWorksInItsWorkspaceOnly() throws Exception {
        String token = create("editor");
        publish(token).andExpect(status().isCreated());
        read(token, "/v1/manage/workspaces/hr/forms").andExpect(status().isOk()).andExpect(jsonPath("$[0].key").value("intake"));
        mvc.perform(post("/v1/w/hr/forms/intake/submissions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"answers\": {\"name\": \"Ada\"}}")).andExpect(status().isCreated());
        read(token, "/v1/manage/workspaces/hr/forms/intake/submissions?reveal=true").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].data.name").value("Ada"));

        // Not another workspace, and never administration.
        read(token, "/v1/manage/workspaces/default/forms").andExpect(status().isNotFound());
        read(token, "/v1/admin/workspaces/hr/members").andExpect(status().isNotFound());
        read(token, "/v1/admin/auth/me").andExpect(status().isForbidden());

        // The audit log names the token.
        String tokenId = jdbc.sql("SELECT id::text FROM service_token").query(String.class).single();
        assertThat(jdbc.sql("SELECT actor_id::text FROM audit_log WHERE action = 'form.published'").query(String.class).single())
                .isEqualTo(tokenId);
    }

    @Test
    void aViewerTokenOnlyReads() throws Exception {
        String editor = create("editor");
        publish(editor).andExpect(status().isCreated());
        String viewer = create("viewer");
        read(viewer, "/v1/manage/workspaces/hr/forms/intake/versions").andExpect(status().isOk());
        publish(viewer).andExpect(status().isForbidden());
        read(viewer, "/v1/manage/workspaces/hr/forms/intake/submissions?reveal=true").andExpect(status().isForbidden());
    }

    @Test
    void revokedAndExpiredTokensStopWorking() throws Exception {
        String token = create("viewer");
        String id = jdbc.sql("SELECT id::text FROM service_token").query(String.class).single();
        read(token, "/v1/manage/workspaces/hr/forms").andExpect(status().isOk());
        mvc.perform(get("/v1/admin/workspaces/hr/service-tokens").with(hrAdmin)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("CRM sync")).andExpect(jsonPath("$[0].lastUsedAt").isNotEmpty())
                .andExpect(jsonPath("$[0].token").doesNotExist());

        mvc.perform(delete("/v1/admin/workspaces/hr/service-tokens/" + id).with(hrAdmin).header("X-Requested-With", "formkiln"))
                .andExpect(status().isNoContent());
        read(token, "/v1/manage/workspaces/hr/forms").andExpect(status().isUnauthorized());
        mvc.perform(delete("/v1/admin/workspaces/hr/service-tokens/" + id).with(hrAdmin).header("X-Requested-With", "formkiln"))
                .andExpect(status().isNotFound());

        String expiring = create("viewer");
        jdbc.sql("UPDATE service_token SET expires_at = now() - interval '1 second' WHERE revoked_at IS NULL").update();
        read(expiring, "/v1/manage/workspaces/hr/forms").andExpect(status().isUnauthorized());
        read("fk_not-a-real-token", "/v1/manage/workspaces/hr/forms").andExpect(status().isUnauthorized());
    }

    @Test
    void onlyTheWorkspacesAdminsManageItsTokens() throws Exception {
        create("editor");
        mvc.perform(get("/v1/admin/workspaces/hr/service-tokens").with(TestMembers.member(jdbc, "hr", "editor")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/v1/admin/workspaces/hr/service-tokens").with(TestMembers.member(jdbc, "default", "admin")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/admin/workspaces/hr/service-tokens").with(TestMembers.instanceAdmin(jdbc)))
                .andExpect(status().isNotFound());
        mvc.perform(withJson(post("/v1/admin/workspaces/hr/service-tokens"), "{\"name\": \"x\", \"role\": \"admin\"}").with(hrAdmin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalidRole"));
        mvc.perform(withJson(post("/v1/admin/workspaces/hr/service-tokens"), "{\"name\": \" \", \"role\": \"viewer\"}").with(hrAdmin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalidName"));
        // A token of another workspace cannot be revoked from here.
        String id = jdbc.sql("SELECT id::text FROM service_token").query(String.class).single();
        mvc.perform(delete("/v1/admin/workspaces/default/service-tokens/" + id).with(TestMembers.member(jdbc, "default", "admin"))
                .header("X-Requested-With", "formkiln")).andExpect(status().isNotFound());
    }
}
