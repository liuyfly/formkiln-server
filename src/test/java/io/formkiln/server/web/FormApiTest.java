package io.formkiln.server.web;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.formkiln.server.TestcontainersConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = "formkiln.service-token=test-token")
@Import(TestcontainersConfiguration.class)
class FormApiTest {

    private static final String TOKEN = "Bearer test-token";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    private MockMvc mvc;
    private String example;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("TRUNCATE submission, form_version, form CASCADE").update();
        example = Files.readString(Path.of(System.getProperty("formkiln.spec.dir"), "examples", "rental-application.json"));
    }

    private MockHttpServletRequestBuilder publish(String key, String body) {
        return post("/v1/manage/forms/{key}/versions", key)
                .header("Authorization", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static String minimalForm(String key, String extraField) {
        return """
                {"schemaVersion": 1, "key": "%s", "title": "Contact",
                 "pages": [{"key": "p", "elements": [
                   {"type": "text", "key": "name", "label": "Name", "required": true}%s
                 ]}],
                 "submit": {"outcomes": [{"key": "done"}]}}
                """.formatted(key, extraField);
    }

    @Test
    void managementRequiresTheServiceToken() throws Exception {
        mvc.perform(get("/v1/manage/forms")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/manage/forms").header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/manage/forms").header("Authorization", TOKEN)).andExpect(status().isOk());
    }

    @Test
    void publishesTheExampleAsImmutableVersions() throws Exception {
        mvc.perform(publish("rentalApplication", example))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(publish("rentalApplication", example))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2));

        mvc.perform(get("/v1/forms/rentalApplication"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.definition.key").value("rentalApplication"));
        mvc.perform(get("/v1/manage/forms/rentalApplication/versions").header("Authorization", TOKEN))
                .andExpect(jsonPath("$[*].version", contains(2, 1)));
    }

    @Test
    void rejectsStructurallyInvalidDocuments() throws Exception {
        mvc.perform(publish("contact", "{\"schemaVersion\": 2, \"key\": \"contact\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[*].code", hasItem("schema")));
    }

    @Test
    void rejectsSemanticallyInvalidDocuments() throws Exception {
        String duplicate = minimalForm("contact", ", {\"type\": \"text\", \"key\": \"name\", \"label\": \"Again\"}");
        mvc.perform(publish("contact", duplicate))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].pointer").value("/pages/0/elements/1/key"))
                .andExpect(jsonPath("$.errors[0].code").value("duplicateKey"));
    }

    @Test
    void publishesElementWidthsWithinTheLayoutGrid() throws Exception {
        String narrow = minimalForm("contact", ", {\"type\": \"text\", \"key\": \"city\", \"label\": \"City\", \"width\": 2}");
        mvc.perform(publish("contact", narrow)).andExpect(status().isCreated());

        String tooWide = minimalForm("contact", ", {\"type\": \"text\", \"key\": \"city\", \"label\": \"City\", \"width\": 4}");
        mvc.perform(publish("contact", tooWide))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].pointer").value("/pages/0/elements/1/width"))
                .andExpect(jsonPath("$.errors[0].code").value("width"));
    }

    @Test
    void rejectsADocumentPublishedUnderAnotherKey() throws Exception {
        mvc.perform(publish("other", minimalForm("contact", "")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("keyMismatch"));
    }

    @Test
    void acceptsValidAnswersAndStoresTheSubmission() throws Exception {
        mvc.perform(publish("contact", minimalForm("contact", ", {\"type\": \"text\", \"key\": \"note\", \"label\": \"Note\"}")))
                .andExpect(status().isCreated());

        mvc.perform(post("/v1/forms/contact/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\": {\"name\": \"Ada\", \"note\": \"\", \"unknown\": 1}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.outcome").value("done"));

        mvc.perform(get("/v1/manage/forms/contact/submissions").header("Authorization", TOKEN))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].data.name").value("Ada"))
                .andExpect(jsonPath("$[0].data.note").doesNotExist())
                .andExpect(jsonPath("$[0].data.unknown").doesNotExist());
        mvc.perform(get("/v1/manage/forms").header("Authorization", TOKEN))
                .andExpect(jsonPath("$[0].key").value("contact"))
                .andExpect(jsonPath("$[0].submissionCount").value(1));
    }

    @Test
    void rejectsInvalidAnswersWithErrors() throws Exception {
        mvc.perform(publish("contact", minimalForm("contact", ""))).andExpect(status().isCreated());
        mvc.perform(post("/v1/forms/contact/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 1, \"answers\": {\"name\": 42}}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].path").value("name"))
                .andExpect(jsonPath("$.errors[0].code").value("type"));
        mvc.perform(get("/v1/manage/forms/contact/submissions").header("Authorization", TOKEN))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void validatesTheExampleAgainstTheReceiptTime() throws Exception {
        mvc.perform(publish("rentalApplication", example)).andExpect(status().isCreated());
        mvc.perform(post("/v1/forms/rentalApplication/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\": {\"dateOfBirth\": \"2099-01-01\"}}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[*].path", hasItem("dateOfBirth")))
                .andExpect(jsonPath("$.errors[*].code", containsInAnyOrder(
                        "required", "required", "required", "expression", "required", "required",
                        "required", "required", "required", "required", "required", "required")));
    }

    @Test
    void reportsUnknownFormsAndVersions() throws Exception {
        mvc.perform(get("/v1/forms/missing")).andExpect(status().isNotFound());
        mvc.perform(publish("contact", minimalForm("contact", ""))).andExpect(status().isCreated());
        mvc.perform(post("/v1/forms/contact/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 9, \"answers\": {}}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deniesEverythingElse() throws Exception {
        mvc.perform(get("/v1/other")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
