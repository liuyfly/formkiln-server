package io.formkiln.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.formkiln.server.TestMembers;
import io.formkiln.server.TestcontainersConfiguration;
import io.formkiln.server.accounts.Mailer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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

/** Teams cannot reach each other's data: every path under a workspace answers 404 to outsiders. */
@SpringBootTest(properties = "formkiln.service-token=test-token")
@Import({TestcontainersConfiguration.class, WorkspaceIsolationTest.Mail.class})
class WorkspaceIsolationTest {

    /** Account emails (invitations, "you were added") are not sent anywhere in these tests. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Mail {
        @Bean
        @Primary
        Mailer mailer() {
            return message -> {};
        }
    }

    private static final String TOKEN = "Bearer test-token";

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcClient jdbc;
    @Autowired private JsonMapper json;

    private MockMvc mvc;
    /** Editor of the default workspace. */
    private RequestPostProcessor defaultEditor;
    /** Admin of "hr". */
    private RequestPostProcessor hrAdmin;
    /** Editor of "hr". */
    private RequestPostProcessor hrEditor;

    private static String form(String key, String title) {
        return """
                {"schemaVersion": 1, "key": "%s", "title": "%s",
                 "pages": [{"key": "p", "elements": [
                   {"type": "text", "key": "name", "label": "Name"},
                   {"type": "email", "key": "email", "label": "Email"}]}],
                 "submit": {"outcomes": [{"key": "done"}]}}
                """.formatted(key, title);
    }

    private static MockHttpServletRequestBuilder withJson(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).header("X-Requested-With", "formkiln").content(body);
    }

    private ResultActions publish(String workspace, String key, String title, RequestPostProcessor as) throws Exception {
        return mvc.perform(withJson(post("/v1/manage/workspaces/{ws}/forms/{key}/versions", workspace, key), form(key, title)).with(as));
    }

    private ResultActions submit(String workspace, String key, String email) throws Exception {
        return mvc.perform(post("/v1/w/{ws}/forms/{key}/submissions", workspace, key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"answers\": {\"name\": \"Ada\", \"email\": \"" + email + "\"}}"));
    }

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("TRUNCATE form_working_copy, submission, form_version, form, form_folder, audit_log CASCADE").update();
        jdbc.sql("DELETE FROM admin_invitation WHERE workspace_id IN (SELECT id FROM workspace WHERE slug <> 'default')").update();
        jdbc.sql("DELETE FROM workspace_member WHERE workspace_id IN (SELECT id FROM workspace WHERE slug <> 'default')").update();
        jdbc.sql("DELETE FROM workspace_access").update();
        jdbc.sql("DELETE FROM service_token").update();
        jdbc.sql("DELETE FROM workspace WHERE slug <> 'default'").update();
        jdbc.sql("INSERT INTO workspace (slug, name) VALUES ('hr', 'HR')").update();
        defaultEditor = TestMembers.member(jdbc, "default", "editor");
        hrAdmin = TestMembers.member(jdbc, "hr", "admin");
        hrEditor = TestMembers.member(jdbc, "hr", "editor");
        publish("default", "onboarding", "Default onboarding", defaultEditor).andExpect(status().isCreated());
        publish("hr", "onboarding", "HR onboarding", hrEditor).andExpect(status().isCreated());
    }

    @Test
    void teamsShareKeysAndEachFormIsServedAtItsOwnUrl() throws Exception {
        mvc.perform(get("/v1/w/default/forms/onboarding")).andExpect(status().isOk())
                .andExpect(jsonPath("$.definition.title").value("Default onboarding"));
        mvc.perform(get("/v1/w/hr/forms/onboarding")).andExpect(status().isOk())
                .andExpect(jsonPath("$.definition.title").value("HR onboarding"));
        mvc.perform(get("/v1/w/nowhere/forms/onboarding")).andExpect(status().isNotFound());

        submit("hr", "onboarding", "ada@example.com").andExpect(status().isCreated());
        mvc.perform(get("/v1/manage/workspaces/hr/forms").with(hrEditor)).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title").value("HR onboarding")).andExpect(jsonPath("$[0].submissionCount").value(1));
        mvc.perform(get("/v1/manage/workspaces/default/forms").with(defaultEditor))
                .andExpect(jsonPath("$[0].submissionCount").value(0));
    }

    @Test
    void outsidersGetNotFoundForEverythingUnderAWorkspace() throws Exception {
        String id = (String) json.readValue(submit("hr", "onboarding", "ada@example.com").andReturn().getResponse().getContentAsString(),
                Map.class).get("id");
        String base = "/v1/manage/workspaces/hr/forms/onboarding";
        for (String path : List.of("/v1/manage/workspaces/hr/forms", "/v1/manage/workspaces/hr/folders", base + "/versions",
                base + "/versions/1", base + "/submissions", base + "/submissions/export", base + "/submissions/" + id + "/pdf",
                base + "/working-copy", base + "/webhooks", base + "/notifications", base + "/retention", base + "/mail",
                "/v1/admin/workspaces/hr", "/v1/admin/workspaces/hr/members", "/v1/admin/workspaces/hr/audit")) {
            mvc.perform(get(path).with(defaultEditor)).andExpect(status().isNotFound());
            mvc.perform(get(path).header("Authorization", TOKEN)).andExpect(status().isNotFound());
        }
        publish("hr", "onboarding", "Taken over", defaultEditor).andExpect(status().isNotFound());
        mvc.perform(delete(base + "/submissions/" + id).with(defaultEditor).header("X-Requested-With", "formkiln"))
                .andExpect(status().isNotFound());
        mvc.perform(withJson(post("/v1/manage/workspaces/hr/forms"), "{\"key\": \"x\", \"title\": \"X\"}").with(defaultEditor))
                .andExpect(status().isNotFound());
        // Signed out: 401 as before, without revealing anything either.
        mvc.perform(get("/v1/manage/workspaces/hr/forms")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/manage/workspaces/nowhere/forms").with(defaultEditor)).andExpect(status().isNotFound());
        // The same key in one's own workspace is a different form.
        mvc.perform(get("/v1/manage/workspaces/default/forms/onboarding/submissions").with(defaultEditor))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void foldersBelongToOneWorkspace() throws Exception {
        String folder = (String) json.readValue(mvc.perform(withJson(post("/v1/manage/workspaces/hr/folders"), "{\"name\": \"Intake\"}")
                .with(hrEditor)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), Map.class).get("id");
        // Same name elsewhere is fine; another workspace's folder id is unknown here.
        mvc.perform(withJson(post("/v1/manage/workspaces/default/folders"), "{\"name\": \"Intake\"}").with(defaultEditor))
                .andExpect(status().isCreated());
        mvc.perform(withJson(put("/v1/manage/workspaces/default/forms/onboarding/folder"), "{\"folderId\": \"" + folder + "\"}")
                .with(defaultEditor)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("folderNotFound"));
        mvc.perform(withJson(patch("/v1/manage/workspaces/default/folders/" + folder), "{\"name\": \"Mine\"}").with(defaultEditor))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/v1/manage/workspaces/default/folders/" + folder).with(defaultEditor).header("X-Requested-With", "formkiln"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/manage/workspaces/hr/folders").with(hrEditor)).andExpect(jsonPath("$[0].name").value("Intake"));
    }

    @Test
    void embedTokensOnlyWorkInTheirWorkspace() throws Exception {
        jdbc.sql("UPDATE form SET access_mode = 'token'").update();
        String token = (String) json.readValue(mvc.perform(withJson(post("/v1/manage/workspaces/hr/embed-tokens"),
                        "{\"formKey\": \"onboarding\"}").with(hrEditor)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), Map.class).get("token");
        mvc.perform(get("/v1/w/hr/forms/onboarding").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(get("/v1/w/default/forms/onboarding").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test
    void erasureStaysWithinTheWorkspace() throws Exception {
        submit("hr", "onboarding", "ada@example.com").andExpect(status().isCreated());
        submit("default", "onboarding", "ada@example.com").andExpect(status().isCreated());
        mvc.perform(withJson(post("/v1/admin/workspaces/hr/erasure"), "{\"email\": \"ada@example.com\", \"dryRun\": false}")
                .with(hrAdmin)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.deleted").value(true));
        assertThat(jdbc.sql("""
                        SELECT w.slug FROM submission s JOIN workspace w ON w.id = s.workspace_id
                        """).query(String.class).list()).containsExactly("default");
        // Only workspace admins erase: not editors, not instance admins from outside.
        mvc.perform(withJson(post("/v1/admin/workspaces/hr/erasure"), "{\"email\": \"ada@example.com\"}").with(hrEditor))
                .andExpect(status().isForbidden());
        mvc.perform(withJson(post("/v1/admin/workspaces/hr/erasure"), "{\"email\": \"ada@example.com\"}")
                .with(TestMembers.instanceAdmin(jdbc))).andExpect(status().isNotFound());
    }

    @Test
    void auditLogsAreKeptPerWorkspace() throws Exception {
        mvc.perform(get("/v1/admin/workspaces/hr/audit").with(hrAdmin)).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].workspace").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("hr"))))
                .andExpect(jsonPath("$[*].action").value(org.hamcrest.Matchers.hasItem("form.published")));
        mvc.perform(get("/v1/admin/workspaces/hr/audit").with(hrEditor)).andExpect(status().isForbidden());
        mvc.perform(get("/v1/admin/instance/audit").with(TestMembers.instanceAdmin(jdbc))).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].workspace").value(org.hamcrest.Matchers.hasItems("hr", "default")));
    }

    @Test
    void archivedWorkspacesAreReadOnlyAndStopServingForms() throws Exception {
        mvc.perform(withJson(patch("/v1/admin/workspaces/hr"), "{\"archived\": true}").with(hrEditor)).andExpect(status().isForbidden());
        mvc.perform(withJson(patch("/v1/admin/workspaces/hr"), "{\"archived\": true}").with(hrAdmin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").isNotEmpty());

        mvc.perform(get("/v1/w/hr/forms/onboarding")).andExpect(status().isNotFound());
        submit("hr", "onboarding", "ada@example.com").andExpect(status().isNotFound());
        mvc.perform(get("/v1/manage/workspaces/hr/forms").with(hrEditor)).andExpect(status().isOk());
        publish("hr", "onboarding", "Changed", hrEditor).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("workspaceArchived"));
        mvc.perform(withJson(post("/v1/admin/workspaces/hr/members"), "{\"email\": \"new@example.com\", \"role\": \"viewer\"}")
                .with(hrAdmin)).andExpect(status().isConflict());
        // The default workspace is not affected.
        mvc.perform(get("/v1/w/default/forms/onboarding")).andExpect(status().isOk());

        mvc.perform(withJson(patch("/v1/admin/workspaces/hr"), "{\"archived\": false}").with(hrAdmin)).andExpect(status().isOk());
        publish("hr", "onboarding", "Changed", hrEditor).andExpect(status().isCreated());
        mvc.perform(get("/v1/w/hr/forms/onboarding")).andExpect(status().isOk());
    }

    @Test
    void instanceAdminsManageWorkspacesAndMembersButNotFormData() throws Exception {
        RequestPostProcessor instanceAdmin = TestMembers.instanceAdmin(jdbc);
        mvc.perform(withJson(post("/v1/admin/instance/workspaces"), "{\"slug\": \"sales\", \"name\": \"Sales\"}").with(instanceAdmin))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.slug").value("sales"));
        mvc.perform(withJson(post("/v1/admin/instance/workspaces"), "{\"slug\": \"sales\", \"name\": \"Again\"}").with(instanceAdmin))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("slugTaken"));
        mvc.perform(withJson(post("/v1/admin/instance/workspaces"), "{\"slug\": \"Bad Slug\", \"name\": \"X\"}").with(instanceAdmin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalidSlug"));
        mvc.perform(get("/v1/admin/instance/workspaces").with(instanceAdmin))
                .andExpect(jsonPath("$[*].slug").value(org.hamcrest.Matchers.hasItems("default", "hr", "sales")));
        mvc.perform(get("/v1/admin/instance/workspaces").with(hrAdmin)).andExpect(status().isForbidden());

        // Members of any workspace: yes. Its forms and submissions: no.
        mvc.perform(withJson(post("/v1/admin/workspaces/sales/members"), "{\"email\": \"lead@example.com\", \"role\": \"admin\"}")
                .with(instanceAdmin)).andExpect(status().isCreated()).andExpect(jsonPath("$.result").value("invited"));
        mvc.perform(get("/v1/admin/workspaces/hr/members").with(instanceAdmin)).andExpect(status().isOk());
        mvc.perform(withJson(patch("/v1/admin/workspaces/hr"), "{\"name\": \"People\"}").with(instanceAdmin)).andExpect(status().isOk());
        mvc.perform(get("/v1/manage/workspaces/hr/forms").with(instanceAdmin)).andExpect(status().isNotFound());
        mvc.perform(get("/v1/manage/workspaces/hr/forms/onboarding/submissions").with(instanceAdmin)).andExpect(status().isNotFound());
    }

    @Test
    void anExistingAccountIsAddedAtOnceAndSeesBothWorkspaces() throws Exception {
        UUID user = UUID.fromString(jdbc.sql("""
                        INSERT INTO admin_user (email, password_hash) VALUES ('both@example.com', 'x') RETURNING id::text
                        """).query(String.class).single());
        jdbc.sql("INSERT INTO workspace_member (workspace_id, user_id, role) SELECT id, :u, 'viewer' FROM workspace WHERE slug = 'default'")
                .param("u", user).update();
        mvc.perform(withJson(post("/v1/admin/workspaces/hr/members"), "{\"email\": \"Both@Example.com\", \"role\": \"editor\"}")
                .with(hrAdmin)).andExpect(status().isCreated()).andExpect(jsonPath("$.result").value("added"))
                .andExpect(jsonPath("$.member.role").value("editor"));
        mvc.perform(withJson(post("/v1/admin/workspaces/hr/members"), "{\"email\": \"both@example.com\", \"role\": \"viewer\"}")
                .with(hrAdmin)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("memberExists"));

        RequestPostProcessor both = TestMembers.signedIn(jdbc, user);
        mvc.perform(get("/v1/admin/auth/me").with(both)).andExpect(jsonPath("$.workspaces[*].slug")
                .value(org.hamcrest.Matchers.containsInAnyOrder("default", "hr")));
        // A viewer in one workspace, an editor in the other.
        publish("default", "other", "Other", both).andExpect(status().isForbidden());
        publish("hr", "other", "Other", both).andExpect(status().isCreated());

        // Removing them from one workspace keeps the account and the other membership.
        mvc.perform(delete("/v1/admin/workspaces/hr/members/" + user).with(hrAdmin).header("X-Requested-With", "formkiln"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/v1/manage/workspaces/hr/forms").with(both)).andExpect(status().isNotFound());
        mvc.perform(get("/v1/manage/workspaces/default/forms").with(both)).andExpect(status().isOk());
    }

    @Test
    void aWorkspaceKeepsAnAdmin() throws Exception {
        String admin = jdbc.sql("""
                        SELECT m.user_id::text FROM workspace_member m JOIN workspace w ON w.id = m.workspace_id
                        WHERE w.slug = 'hr' AND m.role = 'admin'
                        """).query(String.class).single();
        mvc.perform(withJson(patch("/v1/admin/workspaces/hr/members/" + admin), "{\"role\": \"editor\"}").with(hrAdmin))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("lastAdmin"));
        mvc.perform(delete("/v1/admin/workspaces/hr/members/" + admin).with(hrAdmin).header("X-Requested-With", "formkiln"))
                .andExpect(status().isConflict());
        // Members of another workspace cannot be changed through this one.
        String other = jdbc.sql("""
                        SELECT m.user_id::text FROM workspace_member m JOIN workspace w ON w.id = m.workspace_id
                        WHERE w.slug = 'default' LIMIT 1
                        """).query(String.class).single();
        mvc.perform(withJson(patch("/v1/admin/workspaces/hr/members/" + other), "{\"role\": \"viewer\"}").with(hrAdmin))
                .andExpect(status().isNotFound());
    }

    @Test
    void instanceAdminsReadAWorkspaceOnlyThroughAnAuditedReadOnlyWindow() throws Exception {
        RequestPostProcessor instanceAdmin = TestMembers.instanceAdmin(jdbc);
        submit("hr", "onboarding", "ada@example.com").andExpect(status().isCreated());
        String base = "/v1/manage/workspaces/hr/forms/onboarding";
        mvc.perform(get(base + "/submissions").with(instanceAdmin)).andExpect(status().isNotFound());

        mvc.perform(withJson(post("/v1/admin/instance/workspaces/hr/access"), "{\"reason\": \"short\"}").with(instanceAdmin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("reasonRequired"));
        mvc.perform(withJson(post("/v1/admin/instance/workspaces/hr/access"), "{\"reason\": \"Support ticket 4711: missing submission\"}")
                .with(hrAdmin)).andExpect(status().isForbidden());
        mvc.perform(withJson(post("/v1/admin/instance/workspaces/hr/access"), "{\"reason\": \"Support ticket 4711: missing submission\"}")
                .with(instanceAdmin)).andExpect(status().isCreated()).andExpect(jsonPath("$.expiresAt").isNotEmpty());

        // Reads work, as a viewer: no revealing, no writes, no erasure.
        mvc.perform(get("/v1/manage/workspaces/hr/forms").with(instanceAdmin)).andExpect(status().isOk());
        mvc.perform(get(base + "/submissions").with(instanceAdmin)).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get(base + "/submissions").param("reveal", "true").with(instanceAdmin)).andExpect(status().isForbidden());
        publish("hr", "onboarding", "Changed", instanceAdmin).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("readOnlyAccess"));
        mvc.perform(withJson(post("/v1/admin/workspaces/hr/erasure"), "{\"email\": \"ada@example.com\"}").with(instanceAdmin))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/admin/auth/me").with(instanceAdmin)).andExpect(jsonPath("$.access[0].slug").value("hr"));

        // The workspace's admins see who looked, why and at what.
        mvc.perform(get("/v1/admin/workspaces/hr/audit").with(hrAdmin))
                .andExpect(jsonPath("$[*].action").value(org.hamcrest.Matchers.hasItems("workspace.accessOpened", "workspace.accessRead")))
                .andExpect(jsonPath("$[?(@.action == 'workspace.accessRead')].target")
                        .value(org.hamcrest.Matchers.hasItem(base + "/submissions")));
        // Other workspaces stay closed, and the window ends.
        mvc.perform(get("/v1/manage/workspaces/default/forms").with(instanceAdmin)).andExpect(status().isNotFound());
        jdbc.sql("UPDATE workspace_access SET expires_at = now() - interval '1 second'").update();
        mvc.perform(get(base + "/submissions").with(instanceAdmin)).andExpect(status().isNotFound());

        // Members do not need it.
        mvc.perform(withJson(post("/v1/admin/instance/workspaces/default/access"), "{\"reason\": \"Looking at my own team\"}")
                .with(TestMembers.instanceAdminMemberOf(jdbc, "default"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("alreadyMember"));
    }
}
