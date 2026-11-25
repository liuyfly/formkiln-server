package io.formkiln.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.formkiln.server.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = {"formkiln.service-token=test-token", "formkiln.metrics-token=metrics-token"})
@Import(TestcontainersConfiguration.class)
class MetricsTest {

    private static final String FORM = """
            {"schemaVersion": 1, "key": "metered", "title": "Metered",
             "pages": [{"key": "p", "elements": [
               {"type": "text", "key": "name", "label": "Name", "required": true},
               {"type": "email", "key": "email", "label": "Email"}
             ]}],
             "submit": {"outcomes": [{"key": "done"}]}}
            """;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("TRUNCATE submission, form_version, form, audit_log CASCADE").update();
        mvc.perform(post("/v1/manage/forms/metered/versions")
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FORM))
                .andExpect(status().isCreated());
    }

    private void submit(String body) throws Exception {
        mvc.perform(post("/v1/forms/metered/submissions").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String scrape() throws Exception {
        return mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer metrics-token"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static double value(String scrape, String series) {
        return scrape.lines()
                .filter(line -> line.startsWith(series + " "))
                .mapToDouble(line -> Double.parseDouble(line.substring(series.length() + 1).trim()))
                .findFirst()
                .orElse(0);
    }

    @Test
    void prometheusNeedsTheMetricsToken() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        // The service token is for the management API only.
        mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer test-token")).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized());
        // And the metrics token does not open the management API.
        mvc.perform(get("/v1/manage/forms").header("Authorization", "Bearer metrics-token")).andExpect(status().isForbidden());
        assertThat(scrape()).contains("jvm_memory_used_bytes").contains("formkiln_webhook_pending").contains("formkiln_mail_pending");
    }

    @Test
    void countsSubmissionsAndValidationErrors() throws Exception {
        String before = scrape();
        submit("{\"answers\": {\"name\": \"Ada\"}}");
        submit("{\"answers\": {\"email\": \"nope\"}}");
        submit("{\"answers\": {\"name\": \"Bot\"}, \"honeypot\": \"spam\"}");
        String after = scrape();

        String accepted = "formkiln_submissions_total{form=\"metered\",result=\"accepted\"}";
        String invalid = "formkiln_submissions_total{form=\"metered\",result=\"invalid\"}";
        String honeypot = "formkiln_submissions_total{form=\"metered\",result=\"honeypot\"}";
        assertThat(value(after, accepted) - value(before, accepted)).isEqualTo(1);
        assertThat(value(after, invalid) - value(before, invalid)).isEqualTo(1);
        assertThat(value(after, honeypot) - value(before, honeypot)).isEqualTo(1);

        String required = "formkiln_validation_errors_total{code=\"required\",field=\"name\",form=\"metered\"}";
        String email = "formkiln_validation_errors_total{code=\"format.email\",field=\"email\",form=\"metered\"}";
        assertThat(value(after, required) - value(before, required)).isEqualTo(1);
        assertThat(value(after, email) - value(before, email)).isEqualTo(1);
    }

    @Test
    void countsFailedSignIns() throws Exception {
        String series = "formkiln_logins_total{result=\"failed\"}";
        double before = value(scrape(), series);
        mvc.perform(post("/v1/admin/auth/login")
                .header("X-Requested-With", "formkiln")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"nobody@example.com\", \"password\": \"wrong-password\"}"));
        assertThat(value(scrape(), series) - before).isEqualTo(1);
    }
}
