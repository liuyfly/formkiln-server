package io.formkiln.server.notifications;

import io.formkiln.server.form.FieldIndex;
import io.formkiln.server.form.FieldIndex.AnswerField;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Answers as plain text for email: "Label: value" lines in document order. Sensitive fields are
 * never included; choice values show their option labels.
 */
public final class AnswerText {

    private AnswerText() {}

    /** The text of a label: a string, or the locale's (else the first) entry of a locale map. */
    public static String text(Object value, String locale) {
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            Object local = map.get(locale);
            return String.valueOf(local != null ? local : map.values().iterator().next());
        }
        return "";
    }

    private static String number(Double value) {
        BigDecimal decimal = BigDecimal.valueOf(value).stripTrailingZeros();
        return decimal.scale() <= 0 ? decimal.toBigInteger().toString() : decimal.toPlainString();
    }

    private static String option(AnswerField field, Object value, String locale) {
        for (Map<String, Object> option : field.options()) {
            if (Objects.equals(option.get("value"), value)) {
                return text(option.get("label"), locale);
            }
        }
        return String.valueOf(value);
    }

    /** One value as text, or null when there is nothing to show. */
    public static String value(AnswerField field, Object value, String locale) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b ? "Yes" : "No";
        }
        if (value instanceof Double d) {
            return number(d);
        }
        if (value instanceof String s) {
            return field.options().isEmpty() ? s : option(field, s, locale);
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                return null;
            }
            if ("repeater".equals(field.type())) {
                return list.size() + (list.size() == 1 ? " item" : " items");
            }
            List<String> parts = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> file && file.get("name") != null) {
                    parts.add(String.valueOf(file.get("name")));
                } else {
                    parts.add(field.options().isEmpty() ? String.valueOf(item) : option(field, item, locale));
                }
            }
            return String.join(", ", parts);
        }
        if (value instanceof Map<?, ?> composite) {
            List<String> parts = new ArrayList<>();
            for (Object part : composite.values()) {
                if (part != null && !String.valueOf(part).isBlank()) {
                    parts.add(String.valueOf(part));
                }
            }
            return parts.isEmpty() ? null : String.join(" ", parts);
        }
        return String.valueOf(value);
    }

    /** "Label: value" lines of the non-sensitive answers present in {@code data}. */
    public static String lines(FieldIndex fields, Map<String, Object> data, String locale) {
        StringBuilder out = new StringBuilder();
        for (AnswerField field : fields.answerFields()) {
            if (field.sensitive() || !data.containsKey(field.key())) {
                continue;
            }
            String shown = value(field, data.get(field.key()), locale);
            if (shown == null) {
                continue;
            }
            String label = text(field.label(), locale);
            out.append(label.isBlank() ? field.key() : label).append(": ").append(shown).append('\n');
        }
        return out.toString().stripTrailing();
    }
}
