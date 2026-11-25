package io.formkiln.server.platform;

import io.formkiln.server.accounts.Secrets;
import io.formkiln.server.expression.Values;
import io.formkiln.server.form.FieldIndex;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Encryption at rest of {@code sensitive} fields (Form Schema §5.4): each sensitive value is
 * stored as {@code {"$enc": "<base64 AES-GCM of its JSON>"}}. Management views mask the values
 * unless they are revealed explicitly.
 */
@Component
public class SubmissionData {

    public static final String ENCRYPTED = "$enc";
    public static final String MASK = "••••";

    private final Secrets secrets;
    private final JsonMapper json;

    public SubmissionData(Secrets secrets, JsonMapper json) {
        this.secrets = secrets;
        this.json = json;
    }

    public Map<String, Object> encrypt(Map<String, Object> data, FieldIndex fields) {
        return transform(data, fields, value -> Map.of(ENCRYPTED,
                Base64.getEncoder().encodeToString(secrets.encrypt(json.writeValueAsBytes(value)))));
    }

    public Map<String, Object> decrypt(Map<String, Object> data, FieldIndex fields) {
        return transform(data, fields, value -> value instanceof Map<?, ?> map && map.get(ENCRYPTED) instanceof String text
                ? Values.normalize(json.readValue(secrets.decrypt(Base64.getDecoder().decode(text)), Object.class))
                : value);
    }

    public Map<String, Object> mask(Map<String, Object> data, FieldIndex fields) {
        return transform(data, fields, value -> MASK);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> transform(Map<String, Object> data, FieldIndex fields, UnaryOperator<Object> operation) {
        Map<String, Object> result = new LinkedHashMap<>(data);
        for (String key : fields.sensitiveTop()) {
            if (result.containsKey(key)) {
                result.put(key, operation.apply(result.get(key)));
            }
        }
        for (String repeater : fields.repeatersWithSensitiveFields()) {
            if (result.get(repeater) instanceof List<?> items) {
                List<Object> copy = new ArrayList<>();
                for (Object item : items) {
                    Map<String, Object> entry = new LinkedHashMap<>((Map<String, Object>) item);
                    for (String key : fields.sensitiveIn(repeater)) {
                        if (entry.containsKey(key)) {
                            entry.put(key, operation.apply(entry.get(key)));
                        }
                    }
                    copy.add(entry);
                }
                result.put(repeater, copy);
            }
        }
        return result;
    }
}
