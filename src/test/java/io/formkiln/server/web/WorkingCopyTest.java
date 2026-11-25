package io.formkiln.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "formkiln.service-token=test-token")
@Import(TestcontainersConfiguration.class)
class WorkingCopyTest {

    private static final String TOKEN = "Bearer test-token";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JsonMapper json;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("TRUNCATE form_working_copy, submission, form_version, form, audit_log CASCADE").update();
    }

    private static String document(String key, String elements) {
        return """
                {"schemaVersion": 1, "key": "%s", "title": "Survey",
                 "pages": [{"key": "p", "elements": [%s]}],
                 "submit": {"outcomes": [{"key": "done"}]}}
                """.formatted(key, elements);
    }

    private static final String FIELDS = """
            {"type": "text", "key": "name", "label": "Name"},
            {"type": "radio", "key": "status", "label": "Status",
             "options": [{"value": "a", "label": "A"}, {"value": "b", "label": "B"}]}
            """;

    private ResultActions create(String key) throws Exception {
        return mvc.perform(post("/v1/manage/forms")
                .header("Authorization", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\": \"" + key + "\", \"title\": \"Survey\"}"));
    }

    private ResultActions save(String key, long revision, String document) throws Exception {
        return mvc.perform(put("/v1/manage/forms/{key}/working-copy", key)
                .header("Authorization", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"revision\": " + revision + ", \"document\": " + document + "}"));
    }

    private ResultActions publish(String key, long revision, boolean acknowledge) throws Exception {
        return mvc.perform(post("/v1/manage/forms/{key}/working-copy/publish", key)
                .header("Authorization", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"revision\": " + revision + ", \"acknowledgeChanges\": " + acknowledge + "}"));
    }

    private long revision(ResultActions result) throws Exception {
        Map<?, ?> body = json.readValue(result.andReturn().getResponse().getContentAsString(), Map.class);
        return ((Number) body.get("revision")).longValue();
    }

    @Test
    void createsAnUnpublishedForm() throws Exception {
        create("survey")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.document.key").value("survey"))
                .andExpect(jsonPath("$.document.title").value("Survey"))
                .andExpect(jsonPath("$.latestVersion").value(nullValue()));
        create("survey").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("formExists"));
        create("Bad-Key").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalidKey"));

        mvc.perform(get("/v1/manage/forms").header("Authorization", TOKEN))
                .andExpect(jsonPath("$[0].key").value("survey"))
                .andExpect(jsonPath("$[0].latestVersion").value(nullValue()))
                .andExpect(jsonPath("$[0].unpublishedChanges").value(true));
        // Not served before it is published.
        mvc.perform(get("/v1/forms/survey")).andExpect(status().isNotFound());
        mvc.perform(post("/v1/forms/survey/drafts").contentType(MediaType.APPLICATION_JSON).content("{\"answers\": {}}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/v1/manage/forms/survey/working-copy").header("Authorization", TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("neverPublished"));
    }

    @Test
    void savesWithOptimisticConcurrencyAndPublishes() throws Exception {
        long first = revision(create("survey"));
        // Invalid documents can be saved; their problems are reported.
        long second = revision(save("survey", first, document("survey", "{\"type\": \"text\", \"key\": \"name\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.problems[0].code").exists()));
        save("survey", first, document("survey", FIELDS))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("revisionConflict"))
                .andExpect(jsonPath("$.currentRevision").value(second));
        long third = revision(save("survey", second, document("survey", FIELDS)).andExpect(jsonPath("$.problems", hasSize(0))));

        publish("survey", second, false).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("revisionConflict"));
        publish("survey", third, false).andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(1));

        mvc.perform(get("/v1/manage/forms/survey/working-copy").header("Authorization", TOKEN))
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.baseVersion").value(1))
                .andExpect(jsonPath("$.document.pages[0].elements", hasSize(2)));
        mvc.perform(get("/v1/manage/forms").header("Authorization", TOKEN))
                .andExpect(jsonPath("$[0].latestVersion").value(1))
                .andExpect(jsonPath("$[0].unpublishedChanges").value(false));
        mvc.perform(get("/v1/forms/survey")).andExpect(status().isOk());

        // Revisions never repeat: a revision from before the publish cannot overwrite a new copy.
        long next = revision(save("survey", 0, document("survey", FIELDS)));
        assertThat(next).isGreaterThan(third);
        save("survey", third, document("survey", FIELDS)).andExpect(status().isConflict());
        save("survey", 0, document("survey", FIELDS)).andExpect(status().isConflict());
    }

    @Test
    void publishingNeedsAValidDocument() throws Exception {
        long revision = revision(create("survey"));
        long saved = revision(save("survey", revision, document("other", FIELDS)));
        publish("survey", saved, false)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("keyMismatch"));
    }

    @Test
    void breakingChangesMustBeAcknowledged() throws Exception {
        long revision = revision(save("survey", revision(create("survey")), document("survey", FIELDS)));
        publish("survey", revision, false).andExpect(status().isCreated());

        String changed = document("survey", """
                {"type": "number", "key": "name", "label": "Name"},
                {"type": "radio", "key": "status", "label": "Status", "options": [{"value": "a", "label": "A"}]}
                """);
        long next = revision(save("survey", 0, changed));
        mvc.perform(get("/v1/manage/forms/survey/working-copy/check").header("Authorization", TOKEN))
                .andExpect(jsonPath("$.problems", hasSize(0)))
                .andExpect(jsonPath("$.changes[*].kind", hasItem("typeChanged")))
                .andExpect(jsonPath("$.changes[*].kind", hasItem("optionsRemoved")));
        publish("survey", next, false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("changesNotAcknowledged"))
                .andExpect(jsonPath("$.changes", hasSize(2)));
        publish("survey", next, true).andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(2));

        assertThat(jdbc.sql("SELECT action || ' ' || coalesce(details::text, '') FROM audit_log ORDER BY at, action")
                        .query(String.class).list())
                .containsExactly(
                        "form.created ",
                        "form.published {\"source\": \"workingCopy\", \"version\": 1}",
                        "form.published {\"source\": \"workingCopy\", \"version\": 2, \"acknowledgedChanges\": 2}");
    }

    @Test
    void discardsBackToThePublishedVersion() throws Exception {
        publish("survey", revision(save("survey", revision(create("survey")), document("survey", FIELDS))), false);
        save("survey", 0, document("survey", "{\"type\": \"text\", \"key\": \"name\", \"label\": \"Name\"}"));
        mvc.perform(delete("/v1/manage/forms/survey/working-copy").header("Authorization", TOKEN))
                .andExpect(status().isNoContent());
        mvc.perform(get("/v1/manage/forms/survey/working-copy").header("Authorization", TOKEN))
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.document.pages[0].elements", hasSize(2)));
    }

    @Test
    void viewersReadEditorsWrite() throws Exception {
        create("survey");
        mvc.perform(get("/v1/manage/forms/survey/working-copy").with(user("v").authorities(() -> "viewer")))
                .andExpect(status().isOk());
        mvc.perform(put("/v1/manage/forms/survey/working-copy")
                        .with(user("v").authorities(() -> "viewer"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\": 1, \"document\": {}}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/v1/manage/forms/missing/working-copy").header("Authorization", TOKEN)).andExpect(status().isNotFound());
    }
}
