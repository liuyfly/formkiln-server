package io.formkiln.server.form;

import static org.assertj.core.api.Assertions.assertThat;

import io.formkiln.server.expression.Values;
import io.formkiln.server.form.AnswerValidator.FormEvaluation;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * A rule may reference a field later in the document. Also covered by the spec's validation
 * conformance cases (rules.json) from @formkiln/spec 0.3.1.
 */
class ForwardReferenceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @SuppressWarnings("unchecked")
    private static final Map<String, Object> FORM = (Map<String, Object>) Values.normalize(JSON.readValue("""
            {"schemaVersion": 1, "key": "f", "title": "F", "pages": [{"key": "p", "elements": [
              {"type": "textarea", "key": "details", "label": "Details", "required": true,
               "visible": {"==": [{"var": "status"}, "other"]}},
              {"type": "radio", "key": "status", "label": "Status",
               "options": [{"value": "employed", "label": "Employed"}, {"value": "other", "label": "Other"}]}
            ]}]}
            """, Map.class));

    private static FormEvaluation validate(Map<String, Object> answers) {
        return AnswerValidator.validate(FORM, answers, Instant.parse("2026-01-01T00:00:00Z"), "en");
    }

    @Test
    void activeWhenTheLaterFieldMatches() {
        FormEvaluation result = validate(Map.of("status", "other", "details", "Freelance"));
        assertThat(result.valid()).isTrue();
        assertThat(result.submission()).containsEntry("status", "other").containsEntry("details", "Freelance");
    }

    @Test
    void inactiveButTheLaterFieldIsKept() {
        FormEvaluation result = validate(Map.of("status", "employed", "details", "ignored"));
        assertThat(result.submission()).isEqualTo(Map.of("status", "employed"));
    }

    @Test
    void requiredWhenShownByTheLaterField() {
        FormEvaluation result = validate(Map.of("status", "other"));
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).extracting(e -> e.path() + ":" + e.code()).containsExactly("details:required");
    }
}
