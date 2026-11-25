package io.formkiln.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import io.formkiln.server.TestcontainersConfiguration;
import io.formkiln.server.platform.CaptchaVerifier;
import io.formkiln.server.platform.WebhookService;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** Sensitive fields, drafts, files, embed tokens, webhooks and anti-spam on the runtime API. */
@SpringBootTest(properties = "formkiln.service-token=test-token")
@Import({TestcontainersConfiguration.class, RuntimePlatformTest.FakeCaptchaConfig.class})
class RuntimePlatformTest {

    private static final String TOKEN = "Bearer test-token";

    /** Accepts the token {@code pass} when enabled. */
    static final class FakeCaptcha implements CaptchaVerifier {
        volatile boolean enabled;

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public String provider() {
            return "turnstile";
        }

        @Override
        public String siteKey() {
            return "site-key";
        }

        @Override
        public boolean verify(String token, String clientIp) {
            return "pass".equals(token);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeCaptchaConfig {
        @Bean
        @Primary
        FakeCaptcha fakeCaptcha() {
            return new FakeCaptcha();
        }
    }

    private static final String FORM = """
            {"schemaVersion": 1, "key": "apply", "title": "Apply",
             "pages": [{"key": "p", "elements": [
               {"type": "text", "key": "name", "label": "Name", "required": true},
               {"type": "text", "key": "ssn", "label": "SSN", "sensitive": true},
               {"type": "file", "key": "doc", "label": "Document", "multiple": true, "accept": ["application/pdf"],
                "validators": [{"type": "maxFileSize", "value": 1000}]}
             ]}],
             "submit": {"outcomes": [{"key": "done"}]}}
            """;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private FakeCaptcha captcha;

    @Autowired
    private WebhookService webhooks;

    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("TRUNCATE submission, form_version, form, audit_log CASCADE").update();
        captcha.enabled = false;
        mvc.perform(post("/v1/manage/forms/apply/versions")
                        .header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FORM))
                .andExpect(status().isCreated());
    }

    @AfterEach
    void tearDown() {
        captcha.enabled = false;
    }

    private Map<?, ?> body(MvcResult result) throws Exception {
        return json.readValue(result.getResponse().getContentAsString(), Map.class);
    }

    private MvcResult submit(String answers, String extra) throws Exception {
        return mvc.perform(post("/v1/forms/apply/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\": " + answers + extra + "}"))
                .andReturn();
    }

    // ---------------------------------------------------------------- sensitive fields

    @Test
    void encryptsSensitiveValuesAtRestAndMasksThemUnlessRevealed() throws Exception {
        assertThat(submit("{\"name\": \"Ada\", \"ssn\": \"123-45-6789\"}", "").getResponse().getStatus()).isEqualTo(201);

        String stored = jdbc.sql("SELECT data::text FROM submission").query(String.class).single();
        assertThat(stored).doesNotContain("123-45-6789").contains("$enc").contains("Ada");

        mvc.perform(get("/v1/manage/forms/apply/submissions").header("Authorization", TOKEN))
                .andExpect(jsonPath("$[0].data.name").value("Ada"))
                .andExpect(jsonPath("$[0].data.ssn").value("••••"));
        mvc.perform(get("/v1/manage/forms/apply/submissions").param("reveal", "true").header("Authorization", TOKEN))
                .andExpect(jsonPath("$[0].data.ssn").value("123-45-6789"));
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'submissions.revealed'").query(Long.class).single())
                .isEqualTo(1);
    }

    private String export(String reveal) throws Exception {
        MvcResult started = mvc.perform(get("/v1/manage/forms/apply/submissions/export").param("reveal", reveal)
                        .header("Authorization", TOKEN))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult done = mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", startsWith("attachment; filename=\"apply-submissions-")))
                .andReturn();
        return done.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void exportsSubmissionsAsCsv() throws Exception {
        assertThat(submit("{\"name\": \"=HYPERLINK(1)\", \"ssn\": \"123-45\"}", "").getResponse().getStatus()).isEqualTo(201);
        assertThat(submit("{\"name\": \"Lovelace, \\\"Ada\\\"\"}", "").getResponse().getStatus()).isEqualTo(201);
        // A second version renames a field; the old key stays as a trailing column.
        mvc.perform(post("/v1/manage/forms/apply/versions")
                        .header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FORM.replace("\"key\": \"ssn\"", "\"key\": \"taxId\"")))
                .andExpect(status().isCreated());

        String masked = export("false");
        String[] lines = masked.split("\r\n");
        assertThat(lines[0]).isEqualTo("\uFEFFid,version,outcome,submittedAt,subject,name,taxId,doc,ssn");
        assertThat(lines).hasSize(3);
        assertThat(lines[1]).contains(",1,done,").contains(",\"Lovelace, \"\"Ada\"\"\",");
        assertThat(lines[2]).contains(",'=HYPERLINK(1),,,••••");
        assertThat(export("true")).contains(",'=HYPERLINK(1),,,123-45").doesNotContain("••••");
        assertThat(jdbc.sql("SELECT details::text FROM audit_log WHERE action = 'submissions.exported' ORDER BY at")
                        .query(String.class).list())
                .containsExactly("{\"reveal\": false}", "{\"reveal\": true}");

        mvc.perform(get("/v1/manage/forms/apply/submissions/export").param("reveal", "true")
                        .with(user("v").authorities(() -> "viewer")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/v1/manage/forms/missing/submissions/export").header("Authorization", TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    void viewersCannotReveal() throws Exception {
        mvc.perform(get("/v1/manage/forms/apply/submissions").with(user("v").authorities(() -> "viewer")))
                .andExpect(status().isOk());
        mvc.perform(get("/v1/manage/forms/apply/submissions").param("reveal", "true").with(user("v").authorities(() -> "viewer")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("revealForbidden"));
    }

    // ---------------------------------------------------------------- drafts

    @Test
    void savesReadsAndDiscardsDraftsByToken() throws Exception {
        Map<?, ?> created = body(mvc.perform(post("/v1/forms/apply/drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\": {\"name\": \"Ada\", \"ssn\": \"secret\"}}"))
                .andExpect(status().isCreated())
                .andReturn());
        String token = (String) created.get("token");
        assertThat(jdbc.sql("SELECT encode(data, 'escape') FROM form_draft").query(String.class).single()).doesNotContain("secret");

        mvc.perform(put("/v1/forms/apply/drafts")
                        .header("Formkiln-Draft", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\": {\"name\": \"Grace\"}}"))
                .andExpect(status().isOk());
        mvc.perform(get("/v1/forms/apply/drafts").header("Formkiln-Draft", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answers.name").value("Grace"));
        mvc.perform(get("/v1/forms/apply/drafts").header("Formkiln-Draft", "wrong")).andExpect(status().isNotFound());

        assertThat(submit("{\"name\": \"Grace\"}", ", \"draftToken\": \"" + token + "\"").getResponse().getStatus()).isEqualTo(201);
        mvc.perform(get("/v1/forms/apply/drafts").header("Formkiln-Draft", token)).andExpect(status().isNotFound());
    }

    @Test
    void deletesADraft() throws Exception {
        String token = (String) body(mvc.perform(post("/v1/forms/apply/drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\": {}}"))
                .andReturn()).get("token");
        mvc.perform(delete("/v1/forms/apply/drafts").header("Formkiln-Draft", token)).andExpect(status().isNoContent());
        mvc.perform(get("/v1/forms/apply/drafts").header("Formkiln-Draft", token)).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- files

    private MvcResult upload(String field, String name, String type, byte[] data) throws Exception {
        return mvc.perform(multipart("/v1/forms/apply/files")
                        .file(new MockMultipartFile("file", name, type, data))
                        .param("field", field))
                .andReturn();
    }

    @Test
    void uploadsAttachesAndDownloadsFiles() throws Exception {
        MvcResult uploaded = upload("doc", "../payslip.pdf", "application/pdf", "%PDF-1.7".getBytes(StandardCharsets.UTF_8));
        assertThat(uploaded.getResponse().getStatus()).isEqualTo(201);
        Map<?, ?> ref = body(uploaded);
        assertThat(ref.get("name")).isEqualTo("payslip.pdf");
        String id = (String) ref.get("id");

        // The client's claimed metadata is replaced by the stored one.
        String answers = "{\"name\": \"Ada\", \"doc\": [{\"id\": \"" + id + "\", \"name\": \"x.exe\", \"size\": 1, \"contentType\": \"x/y\"}]}";
        assertThat(submit(answers, "").getResponse().getStatus()).isEqualTo(201);
        mvc.perform(get("/v1/manage/forms/apply/submissions").header("Authorization", TOKEN))
                .andExpect(jsonPath("$[0].data.doc[0].name").value("payslip.pdf"))
                .andExpect(jsonPath("$[0].data.doc[0].contentType").value("application/pdf"));

        mvc.perform(get("/v1/manage/forms/apply/files/{id}", id).header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", startsWith("attachment")))
                .andExpect(content().bytes("%PDF-1.7".getBytes(StandardCharsets.UTF_8)));

        // A file can only be used by one submission.
        assertThat(submit(answers, "").getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void rejectsUnacceptableUploads() throws Exception {
        assertThat(upload("name", "a.pdf", "application/pdf", new byte[] {1}).getResponse().getStatus()).isEqualTo(400);
        assertThat(upload("doc", "a.exe", "application/x-msdownload", new byte[] {1}).getResponse().getStatus()).isEqualTo(415);
        assertThat(upload("doc", "a.pdf", "application/pdf", new byte[2000]).getResponse().getStatus()).isEqualTo(413);
        assertThat(upload("doc", "a.pdf", "application/pdf", new byte[0]).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void unattachedFilesCannotBeDownloaded() throws Exception {
        String id = (String) body(upload("doc", "a.pdf", "application/pdf", new byte[] {1})).get("id");
        mvc.perform(get("/v1/manage/forms/apply/files/{id}", id).header("Authorization", TOKEN)).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- embed tokens

    @Test
    void tokenFormsNeedAValidEmbedToken() throws Exception {
        mvc.perform(put("/v1/manage/forms/apply/access")
                        .header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\": \"token\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/v1/forms/apply"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("embedTokenRequired"));

        String embed = (String) body(mvc.perform(post("/v1/manage/embed-tokens")
                        .header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"formKey\": \"apply\", \"subject\": \"tenant-42\"}"))
                .andExpect(status().isOk())
                .andReturn()).get("token");

        mvc.perform(get("/v1/forms/apply").header("Authorization", "Bearer " + embed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessMode").value("token"));
        mvc.perform(post("/v1/forms/apply/submissions")
                        .header("Authorization", "Bearer " + embed)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\": {\"name\": \"Ada\"}}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/v1/manage/forms/apply/submissions").header("Authorization", TOKEN))
                .andExpect(jsonPath("$[0].subject").value("tenant-42"));

        mvc.perform(get("/v1/forms/apply").header("Authorization", "Bearer " + embed + "x")).andExpect(status().isUnauthorized());
    }

    @Test
    void embedTokensAreBoundToTheirForm() throws Exception {
        mvc.perform(post("/v1/manage/forms/other/versions")
                        .header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FORM.replace("\"apply\"", "\"other\"")))
                .andExpect(status().isCreated());
        mvc.perform(put("/v1/manage/forms/apply/access")
                .header("Authorization", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\": \"token\"}"));
        String other = (String) body(mvc.perform(post("/v1/manage/embed-tokens")
                        .header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"formKey\": \"other\"}"))
                .andReturn()).get("token");
        mvc.perform(get("/v1/forms/apply").header("Authorization", "Bearer " + other)).andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/manage/embed-tokens")
                        .header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"formKey\": \"apply\", \"ttlSeconds\": 7200}"))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- anti-spam

    @Test
    void honeypotSubmissionsLookAcceptedButAreDropped() throws Exception {
        assertThat(submit("{\"name\": \"Bot\"}", ", \"honeypot\": \"http://spam\"").getResponse().getStatus()).isEqualTo(201);
        assertThat(jdbc.sql("SELECT count(*) FROM submission").query(Long.class).single()).isZero();
    }

    @Test
    void publicFormsRequireHumanVerificationWhenEnabled() throws Exception {
        captcha.enabled = true;
        mvc.perform(get("/v1/forms/apply"))
                .andExpect(jsonPath("$.captcha.provider").value("turnstile"))
                .andExpect(jsonPath("$.captcha.siteKey").value("site-key"));
        MvcResult failed = submit("{\"name\": \"Ada\"}", ", \"captchaToken\": \"fail\"");
        assertThat(failed.getResponse().getStatus()).isEqualTo(400);
        assertThat(submit("{\"name\": \"Ada\"}", ", \"captchaToken\": \"pass\"").getResponse().getStatus()).isEqualTo(201);

        captcha.enabled = false;
        mvc.perform(get("/v1/forms/apply")).andExpect(jsonPath("$.captcha").value(nullValue()));
    }

    // ---------------------------------------------------------------- webhooks

    @Test
    void deliversSignedWebhooksAndRetriesFailures() throws Exception {
        List<Map<String, String>> received = new CopyOnWriteArrayList<>();
        AtomicInteger respond = new AtomicInteger(500);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(Map.of(
                    "body", body,
                    "signature", exchange.getRequestHeaders().getFirst("Formkiln-Signature"),
                    "event", exchange.getRequestHeaders().getFirst("Formkiln-Event")));
            exchange.sendResponseHeaders(respond.get(), -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
            Map<?, ?> endpoint = body(mvc.perform(post("/v1/manage/forms/apply/webhooks")
                            .header("Authorization", TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\": \"" + url + "\"}"))
                    .andExpect(status().isCreated())
                    .andReturn());
            String secret = (String) endpoint.get("secret");
            assertThat(secret).startsWith("whsec_");
            mvc.perform(get("/v1/manage/forms/apply/webhooks").header("Authorization", TOKEN))
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].secret").value(nullValue()));

            assertThat(submit("{\"name\": \"Ada\", \"ssn\": \"123\"}", "").getResponse().getStatus()).isEqualTo(201);

            webhooks.deliverDue();
            assertThat(received).hasSize(1);
            String deliveries = "/v1/manage/forms/apply/webhooks/" + endpoint.get("id") + "/deliveries";
            mvc.perform(get(deliveries).header("Authorization", TOKEN))
                    .andExpect(jsonPath("$[0].status").value("pending"))
                    .andExpect(jsonPath("$[0].attempts").value(1))
                    .andExpect(jsonPath("$[0].lastStatus").value(500));

            // Not due yet; then forced through a redelivery.
            webhooks.deliverDue();
            assertThat(received).hasSize(1);
            respond.set(204);
            String deliveryId = (String) ((Map<?, ?>) json.readValue(
                    mvc.perform(get(deliveries).header("Authorization", TOKEN)).andReturn().getResponse().getContentAsString(),
                    List.class).getFirst()).get("id");
            mvc.perform(post("/v1/manage/forms/apply/webhook-deliveries/{id}/redeliver", deliveryId).header("Authorization", TOKEN))
                    .andExpect(status().isOk());
            webhooks.deliverDue();
            assertThat(received).hasSize(2);
            mvc.perform(get(deliveries).header("Authorization", TOKEN)).andExpect(jsonPath("$[0].status").value("succeeded"));

            Map<String, String> last = received.get(1);
            assertThat(last.get("event")).isEqualTo("submission.created");
            assertThat(last.get("body")).contains("\"ssn\":\"123\"").contains("\"key\":\"apply\"");
            String signature = last.get("signature");
            String timestamp = signature.substring(2, signature.indexOf(','));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal((timestamp + "." + last.get("body")).getBytes(StandardCharsets.UTF_8)));
            assertThat(signature).isEqualTo("t=" + timestamp + ",v1=" + expected);

            // The queued payload is encrypted at rest.
            assertThat(jdbc.sql("SELECT encode(payload, 'escape') FROM webhook_delivery").query(String.class).single())
                    .doesNotContain("Ada");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsInvalidWebhookUrls() throws Exception {
        for (String url : List.of("ftp://example.com/x", "https://user:pw@example.com/x", "not a url")) {
            mvc.perform(post("/v1/manage/forms/apply/webhooks")
                            .header("Authorization", TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\": \"" + url + "\"}"))
                    .andExpect(status().isBadRequest());
        }
    }
}
