package io.formkiln.server.form;

import static org.assertj.core.api.Assertions.assertThat;

import io.formkiln.server.expression.Values;
import io.formkiln.server.form.AnswerValidator.FormEvaluation;
import io.formkiln.server.form.AnswerValidator.ValidationError;
import io.formkiln.server.form.DefinitionValidator.DefinitionError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.json.JsonMapper;

/** Runs the validation and definition conformance cases published in @formkiln/spec. */
class FormConformanceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static Path conformanceDir(String name) {
        String specDir = System.getProperty("formkiln.spec.dir");
        assertThat(specDir).as("system property formkiln.spec.dir").isNotNull();
        return Path.of(specDir, "conformance", name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(Path file) {
        try {
            return (Map<String, Object>) Values.normalize(JSON.readValue(Files.readString(file), Map.class));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Path> files(String name) throws IOException {
        try (Stream<Path> list = Files.list(conformanceDir(name))) {
            return list.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cases(Map<String, Object> suite) {
        return (List<Map<String, Object>>) suite.get("cases");
    }

    @TestFactory
    Stream<DynamicContainer> validationCases() throws IOException {
        return files("validation").stream().map(file -> {
            Map<String, Object> suite = read(file);
            return DynamicContainer.dynamicContainer(file.getFileName().toString(),
                    cases(suite).stream().map(c -> validationTest(suite, c)));
        });
    }

    @SuppressWarnings("unchecked")
    private static DynamicTest validationTest(Map<String, Object> suite, Map<String, Object> c) {
        return DynamicTest.dynamicTest((String) c.get("name"), () -> {
            Map<String, Object> form = (Map<String, Object>) ((Map<String, Object>) suite.get("forms")).get(c.get("form"));
            assertThat(DefinitionValidator.validate(form)).as("the case form must be valid").isEmpty();
            String now = (String) c.get("now");
            FormEvaluation result = AnswerValidator.validate(
                    form,
                    (Map<String, Object>) c.get("answers"),
                    now == null ? null : Instant.parse(now),
                    (String) c.get("locale"));
            Map<String, Object> expected = (Map<String, Object>) c.get("expected");
            List<Map<String, Object>> expectedErrors = (List<Map<String, Object>>) expected.get("errors");

            // path and code always; message and params only when the case specifies them.
            List<Map<String, Object>> actualErrors = new ArrayList<>();
            for (int i = 0; i < result.errors().size(); i++) {
                ValidationError error = result.errors().get(i);
                Map<String, Object> want = i < expectedErrors.size() ? expectedErrors.get(i) : Map.of();
                Map<String, Object> actual = new LinkedHashMap<>();
                actual.put("path", error.path());
                actual.put("code", error.code());
                if (want.containsKey("message")) {
                    actual.put("message", error.message());
                }
                if (want.containsKey("params")) {
                    actual.put("params", error.params());
                }
                actualErrors.add(actual);
            }
            assertThat(Values.deepEqual(Values.normalize(actualErrors), expectedErrors))
                    .as("errors: expected %s, got %s", JSON.writeValueAsString(expectedErrors), JSON.writeValueAsString(actualErrors))
                    .isTrue();
            assertThat(result.valid()).isEqualTo(expectedErrors.isEmpty());
            if (expected.containsKey("inactive")) {
                assertThat(result.inactive()).containsExactlyInAnyOrderElementsOf((List<String>) expected.get("inactive"));
            }
            if (expected.containsKey("submission")) {
                assertThat(Values.deepEqual(Values.normalize(result.submission()), expected.get("submission")))
                        .as("submission: got %s", JSON.writeValueAsString(result.submission()))
                        .isTrue();
            }
            if (expected.containsKey("outcome")) {
                assertThat(result.outcome()).isEqualTo(expected.get("outcome"));
            }
        });
    }

    @TestFactory
    Stream<DynamicContainer> definitionCases() throws IOException {
        return files("definitions").stream().map(file -> DynamicContainer.dynamicContainer(
                file.getFileName().toString(), cases(read(file)).stream().map(FormConformanceTest::definitionTest)));
    }

    @SuppressWarnings("unchecked")
    private static DynamicTest definitionTest(Map<String, Object> c) {
        return DynamicTest.dynamicTest((String) c.get("name"), () -> {
            List<String> actual = DefinitionValidator.validate((Map<String, Object>) c.get("form")).stream()
                    .map(DefinitionError::code)
                    .toList();
            List<String> actualKeys = DefinitionValidator.validate((Map<String, Object>) c.get("form")).stream()
                    .map(e -> e.code() + " " + e.pointer())
                    .sorted()
                    .toList();
            List<String> expectedKeys = ((List<Map<String, Object>>) c.get("expected")).stream()
                    .map(e -> e.get("code") + " " + e.get("pointer"))
                    .sorted()
                    .toList();
            assertThat(actualKeys).as("codes %s", actual).isEqualTo(expectedKeys);
        });
    }
}
