package io.formkiln.server.form;

import static org.assertj.core.api.Assertions.assertThat;

import io.formkiln.server.expression.Values;
import io.formkiln.server.form.CompatibilityChecker.Change;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CompatibilityCheckerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> form(String elements) {
        return (Map<String, Object>) Values.normalize(JSON.readValue("""
                {"schemaVersion": 1, "key": "f", "title": "F",
                 "pages": [{"key": "p", "elements": [%s]}]}
                """.formatted(elements), Map.class));
    }

    private static final String BEFORE = """
            {"type": "text", "key": "name", "label": "Name"},
            {"type": "radio", "key": "status", "label": "Status",
             "options": [{"value": "a", "label": "A"}, {"value": "b", "label": "B"}]},
            {"type": "repeater", "key": "pets", "label": "Pets",
             "elements": [{"type": "text", "key": "petName", "label": "Pet"}, {"type": "number", "key": "age", "label": "Age"}]},
            {"type": "email", "key": "email", "label": "Email"}
            """;

    @Test
    void noChangesForTheSameOrExtendedForm() {
        assertThat(CompatibilityChecker.compare(form(BEFORE), form(BEFORE))).isEmpty();
        // Adding fields and options, and changing labels, is compatible.
        String extended = BEFORE.replace("\"label\": \"Name\"", "\"label\": \"Full name\"")
                .replace("{\"value\": \"b\", \"label\": \"B\"}", "{\"value\": \"b\", \"label\": \"B\"}, {\"value\": \"c\", \"label\": \"C\"}")
                + ", {\"type\": \"text\", \"key\": \"extra\", \"label\": \"Extra\"}";
        assertThat(CompatibilityChecker.compare(form(BEFORE), form(extended))).isEmpty();
    }

    @Test
    void reportsRemovedFieldsTypeChangesAndRemovedOptions() {
        String after = """
                {"type": "number", "key": "name", "label": "Name"},
                {"type": "radio", "key": "status", "label": "Status", "options": [{"value": "a", "label": "A"}]},
                {"type": "repeater", "key": "pets", "label": "Pets",
                 "elements": [{"type": "number", "key": "age", "label": "Age"}]}
                """;
        List<Change> changes = CompatibilityChecker.compare(form(BEFORE), form(after));
        assertThat(changes).containsExactly(
                new Change(CompatibilityChecker.TYPE_CHANGED, "name", Map.of("from", "string", "to", "number")),
                new Change(CompatibilityChecker.OPTIONS_REMOVED, "status", Map.of("values", List.of("b"))),
                new Change(CompatibilityChecker.FIELD_REMOVED, "pets.petName", Map.of()),
                new Change(CompatibilityChecker.FIELD_REMOVED, "email", Map.of()));
    }
}
