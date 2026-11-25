package io.formkiln.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.formkiln.server.TestcontainersConfiguration;
import io.formkiln.server.pdf.PdfWriterTest;
import java.util.Map;
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
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "formkiln.service-token=test-token")
@Import(TestcontainersConfiguration.class)
class PdfTest {

    private static final String TOKEN = "Bearer test-token";

    private static final String FORM = """
            {"schemaVersion": 1, "key": "order", "title": "Order",
             "pages": [{"key": "p", "elements": [
               {"type": "text", "key": "name", "label": "Name"},
               {"type": "radio", "key": "plan", "label": "Plan", "options": [{"value": "pro", "label": "Professional"}]},
               {"type": "text", "key": "card", "label": "Card number", "sensitive": true},
               {"type": "repeater", "key": "items", "label": "Items",
                "elements": [{"type": "text", "key": "sku", "label": "SKU"}, {"type": "number", "key": "qty", "label": "Quantity"}]}
             ]}],
             "submit": {"outcomes": [{"key": "done", "message": "Thanks for your order."}]}}
            """;

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcClient jdbc;
    @Autowired private JsonMapper json;

    private MockMvc mvc;
    private Map<?, ?> accepted;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("TRUNCATE submission, form_version, form, audit_log CASCADE").update();
        mvc.perform(post("/v1/manage/forms/order/versions").header("Authorization", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(FORM)).andExpect(status().isCreated());
        mvc.perform(post("/v1/manage/forms/other/versions").header("Authorization", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(FORM.replace("\"order\"", "\"other\""))).andExpect(status().isCreated());
        String body = mvc.perform(post("/v1/forms/order/submissions").contentType(MediaType.APPLICATION_JSON).content("""
                        {"answers": {"name": "Ada", "plan": "pro", "card": "4111-1111", "items": [{"sku": "A-1", "qty": 2}, {"sku": "B-7", "qty": 1}]}}
                        """))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        accepted = json.readValue(body, Map.class);
    }

    private byte[] adminPdf(String query, boolean expectOk) throws Exception {
        var result = mvc.perform(get("/v1/manage/forms/order/submissions/" + accepted.get("id") + "/pdf" + query).header("Authorization", TOKEN));
        if (!expectOk) {
            return null;
        }
        return result.andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment; filename=\"order-")))
                .andReturn().getResponse().getContentAsByteArray();
    }

    @Test
    void exportsASubmissionWithSensitiveValuesMaskedUnlessRevealed() throws Exception {
        String masked = PdfWriterTest.text(adminPdf("", true));
        assertThat(masked).contains("Order").contains("Submission").contains("reference " + accepted.get("id"))
                .contains("Name").contains("Ada").contains("Professional").contains("Card number").contains("••••")
                .doesNotContain("4111").contains("Items").contains("#1").contains("A-1").contains("#2").contains("B-7")
                .contains("Quantity");
        assertThat(PdfWriterTest.text(adminPdf("?reveal=true", true))).contains("4111-1111");
        mvc.perform(get("/v1/manage/forms/order/submissions/" + accepted.get("id") + "/pdf?reveal=true")
                        .with(user("v").authorities(() -> "viewer")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/v1/manage/forms/other/submissions/" + accepted.get("id") + "/pdf").header("Authorization", TOKEN))
                .andExpect(status().isNotFound());
        assertThat(jdbc.sql("SELECT coalesce(details::text, '-') FROM audit_log WHERE action = 'submission.pdf_exported' ORDER BY at")
                        .query(String.class).list())
                .containsExactly("-", "{\"reveal\": true}");
    }

    @Test
    void givesTheRespondentAReceiptWithoutSensitiveAnswers() throws Exception {
        String token = (String) accepted.get("receiptToken");
        assertThat(token).isNotBlank();
        byte[] pdf = mvc.perform(get("/v1/forms/order/receipt").header("Formkiln-Receipt", token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"order-receipt.pdf\""))
                .andReturn().getResponse().getContentAsByteArray();
        String text = PdfWriterTest.text(pdf);
        assertThat(text).contains("Receipt for your response").contains("Thanks for your order.").contains("Ada")
                .doesNotContain("Card number").doesNotContain("••••").doesNotContain("4111");

        mvc.perform(get("/v1/forms/order/receipt")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/forms/order/receipt").header("Formkiln-Receipt", token + "x")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/forms/other/receipt").header("Formkiln-Receipt", token)).andExpect(status().isUnauthorized());
    }
}
