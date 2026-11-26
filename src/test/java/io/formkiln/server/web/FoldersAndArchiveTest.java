package io.formkiln.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "formkiln.service-token=test-token")
@Import(TestcontainersConfiguration.class)
class FoldersAndArchiveTest {

    private static final String TOKEN = "Bearer test-token";
    private static final RequestPostProcessor ADMIN = user("admin").authorities(() -> "admin", () -> "editor", () -> "viewer");
    private static final RequestPostProcessor EDITOR = user("editor").authorities(() -> "editor", () -> "viewer");
    private static final RequestPostProcessor VIEWER = user("viewer").authorities(() -> "viewer");

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcClient jdbc;
    @Autowired private JsonMapper json;

    private MockMvc mvc;

    private static String form(String key) {
        return """
                {"schemaVersion": 1, "key": "%s", "title": "T",
                 "pages": [{"key": "p", "elements": [{"type": "text", "key": "name", "label": "Name"}]}],
                 "submit": {"outcomes": [{"key": "done"}]}}
                """.formatted(key);
    }

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("TRUNCATE form_working_copy, submission, form_version, form, form_folder, audit_log CASCADE").update();
        for (String key : new String[] {"alpha", "beta"}) {
            mvc.perform(post("/v1/manage/forms/{key}/versions", key).header("Authorization", TOKEN)
                    .contentType(MediaType.APPLICATION_JSON).content(form(key))).andExpect(status().isCreated());
        }
    }

    private String createFolder(String name) throws Exception {
        String body = mvc.perform(post("/v1/manage/folders").with(EDITOR).header("X-Requested-With", "formkiln")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return (String) json.readValue(body, Map.class).get("id");
    }

    private void move(String key, String folderId) throws Exception {
        mvc.perform(put("/v1/manage/forms/{key}/folder", key).with(EDITOR).header("X-Requested-With", "formkiln")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(folderId == null ? "{\"folderId\": null}" : "{\"folderId\": \"" + folderId + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void foldersOrganiseForms() throws Exception {
        String hr = createFolder(" HR ");
        // Names are unique ignoring case, 1 to 120 characters.
        mvc.perform(post("/v1/manage/folders").with(EDITOR).header("X-Requested-With", "formkiln")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"hr\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("folderNameTaken"));
        mvc.perform(post("/v1/manage/folders").with(EDITOR).header("X-Requested-With", "formkiln")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"  \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalidFolderName"));
        // Viewers read, but do not organise.
        mvc.perform(post("/v1/manage/folders").with(VIEWER).header("X-Requested-With", "formkiln")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Mine\"}"))
                .andExpect(status().isForbidden());

        move("alpha", hr);
        mvc.perform(get("/v1/manage/folders").with(VIEWER))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("HR"))
                .andExpect(jsonPath("$[0].formCount").value(1));
        mvc.perform(get("/v1/manage/forms").with(VIEWER))
                .andExpect(jsonPath("$[?(@.key == 'alpha')].folderId").value(hr))
                .andExpect(jsonPath("$[?(@.key == 'beta')].folderId").value(org.hamcrest.Matchers.contains((Object) null)));

        mvc.perform(patch("/v1/manage/folders/{id}", hr).with(EDITOR).header("X-Requested-With", "formkiln")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"People\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("People"));

        // Only an empty folder can be deleted.
        mvc.perform(delete("/v1/manage/folders/{id}", hr).with(EDITOR).header("X-Requested-With", "formkiln"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("folderNotEmpty"));
        move("alpha", null);
        mvc.perform(delete("/v1/manage/folders/{id}", hr).with(EDITOR).header("X-Requested-With", "formkiln"))
                .andExpect(status().isNoContent());
        mvc.perform(put("/v1/manage/forms/alpha/folder").with(EDITOR).header("X-Requested-With", "formkiln")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"folderId\": \"" + hr + "\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("folderNotFound"));

        assertThat(jdbc.sql("SELECT action FROM audit_log ORDER BY at").query(String.class).list())
                .contains("folder.created", "form.moved", "folder.renamed", "folder.deleted");
    }

    @Test
    void archivedFormsAreHiddenAndNotServedButKeepTheirData() throws Exception {
        mvc.perform(post("/v1/forms/alpha/submissions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"answers\": {\"name\": \"Ada\"}}")).andExpect(status().isCreated());

        // Admins only: not editors, not the service token.
        mvc.perform(post("/v1/manage/forms/alpha/archive").with(EDITOR).header("X-Requested-With", "formkiln"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/v1/manage/forms/alpha/archive").header("Authorization", TOKEN)).andExpect(status().isForbidden());
        mvc.perform(post("/v1/manage/forms/alpha/archive").with(ADMIN).header("X-Requested-With", "formkiln"))
                .andExpect(status().isOk());

        mvc.perform(get("/v1/manage/forms").with(VIEWER)).andExpect(jsonPath("$[*].key").value(org.hamcrest.Matchers.contains("beta")));
        mvc.perform(get("/v1/manage/forms").param("archived", "true").with(VIEWER))
                .andExpect(jsonPath("$[0].key").value("alpha"))
                .andExpect(jsonPath("$[0].submissionCount").value(1))
                .andExpect(jsonPath("$[0].archivedAt").isNotEmpty());

        // Respondents get a 404; the submissions stay readable for the team.
        mvc.perform(get("/v1/forms/alpha")).andExpect(status().isNotFound());
        mvc.perform(post("/v1/forms/alpha/submissions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"answers\": {\"name\": \"Grace\"}}")).andExpect(status().isNotFound());
        mvc.perform(get("/v1/manage/forms/alpha/submissions").with(VIEWER)).andExpect(jsonPath("$", hasSize(1)));
        // No new versions while archived; archiving twice is a 404.
        mvc.perform(post("/v1/manage/forms/alpha/versions").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(form("alpha")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("formArchived"));
        mvc.perform(post("/v1/manage/forms/alpha/archive").with(ADMIN).header("X-Requested-With", "formkiln"))
                .andExpect(status().isNotFound());

        mvc.perform(post("/v1/manage/forms/alpha/restore").with(ADMIN).header("X-Requested-With", "formkiln"))
                .andExpect(status().isOk());
        mvc.perform(get("/v1/forms/alpha")).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT action FROM audit_log WHERE target = 'alpha' ORDER BY at").query(String.class).list())
                .contains("form.archived", "form.restored");
    }

    @Test
    void archivedFormsDoNotKeepAFolderFromBeingDeleted() throws Exception {
        String folder = createFolder("Old");
        move("alpha", folder);
        mvc.perform(post("/v1/manage/forms/alpha/archive").with(ADMIN).header("X-Requested-With", "formkiln"))
                .andExpect(status().isOk());
        mvc.perform(get("/v1/manage/folders").with(VIEWER)).andExpect(jsonPath("$[0].formCount").value(0));
        mvc.perform(delete("/v1/manage/folders/{id}", folder).with(EDITOR).header("X-Requested-With", "formkiln"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/v1/manage/forms").param("archived", "true").with(VIEWER))
                .andExpect(jsonPath("$[0].folderId").value(org.hamcrest.Matchers.nullValue()));
    }
}
