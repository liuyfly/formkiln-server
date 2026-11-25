package io.formkiln.server.forms;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.formkiln.server.form.DefinitionValidator;
import io.formkiln.server.form.DefinitionValidator.DefinitionError;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Validates a form document: first against the JSON Schema of Form Schema v1 (structure), then
 * against the semantic rules of §10. Semantic rules run only on structurally valid documents.
 */
@Component
public class FormDocumentValidator {

    /** Code used for structural (JSON Schema) problems. */
    public static final String STRUCTURE = "schema";

    private final Schema schema;

    public FormDocumentValidator() {
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        try (InputStream in = new ClassPathResource("formkiln-spec/schema/form-v1.schema.json").getInputStream()) {
            this.schema = registry.getSchema(in);
        } catch (IOException e) {
            throw new UncheckedIOException("The bundled form JSON Schema is missing", e);
        }
    }

    /** Returns every problem found; an empty list means the document can be published. */
    public List<DefinitionError> validate(JsonNode document, Map<String, Object> canonical) {
        List<Error> structural = schema.validate(document);
        if (!structural.isEmpty()) {
            return structural.stream()
                    .map(error -> new DefinitionError(error.getInstanceLocation().toString(), STRUCTURE))
                    .distinct()
                    .sorted(Comparator.comparing(DefinitionError::pointer))
                    .toList();
        }
        return DefinitionValidator.validate(canonical);
    }
}
