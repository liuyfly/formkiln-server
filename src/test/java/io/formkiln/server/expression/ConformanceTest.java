package io.formkiln.server.expression;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.json.JsonMapper;

/** Runs every expression conformance case published in @formkiln/spec. */
class ConformanceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static Path casesDir() {
        String specDir = System.getProperty("formkiln.spec.dir");
        assertThat(specDir).as("system property formkiln.spec.dir").isNotNull();
        return Path.of(specDir, "conformance", "expressions");
    }

    @TestFactory
    Stream<DynamicContainer> expressionCases() throws IOException {
        List<Path> files;
        try (Stream<Path> list = Files.list(casesDir())) {
            files = list.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        assertThat(files).isNotEmpty();
        return files.stream().map(file -> DynamicContainer.dynamicContainer(
                file.getFileName().toString(), cases(file).map(ConformanceTest::toTest)));
    }

    @SuppressWarnings("unchecked")
    private static Stream<Map<String, Object>> cases(Path file) {
        try {
            Map<String, Object> suite = JSON.readValue(Files.readString(file), Map.class);
            return ((List<Map<String, Object>>) suite.get("cases")).stream();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static DynamicTest toTest(Map<String, Object> testCase) {
        return DynamicTest.dynamicTest((String) testCase.get("name"), () -> {
            Object expression = Values.normalize(testCase.get("expression"));
            Object expected = Values.normalize(testCase.get("expected"));
            Map<String, Object> data = (Map<String, Object>) Values.normalize(testCase.get("data"));
            Map<String, Object> scope = (Map<String, Object>) Values.normalize(testCase.get("scope"));
            String now = (String) testCase.get("now");
            var options = new ExpressionEvaluator.Options(
                    data, scope, now == null ? null : Instant.parse(now), (String) testCase.get("timeZone"));

            Object actual = ExpressionEvaluator.evaluate(expression, options);

            assertThat(Values.deepEqual(actual, expected))
                    .as("expected %s, got %s", JSON.writeValueAsString(expected), JSON.writeValueAsString(actual))
                    .isTrue();
        });
    }
}
