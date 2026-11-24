package io.formkiln.server.form;

import io.formkiln.server.expression.Dates;
import io.formkiln.server.expression.Values;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Normalisation (Form Schema §6.5) and type checking (§6.7) of answers. */
final class FormValues {

    private static final Pattern TIME = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d(:[0-5]\\d)?");
    private static final Pattern DATETIME = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2})T([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d(\\.\\d{1,9})?(Z|[+-]([01]\\d|2[0-3]):[0-5]\\d)");
    private static final Set<String> FILE_KEYS = Set.of("contentType", "id", "name", "size");

    private FormValues() {}

    /** Normalises a raw answer. Repeater items are returned as is. */
    static Object normalize(String valueType, Object raw) {
        if (raw == null || "".equals(raw)) {
            return null;
        }
        if (raw instanceof List<?> list) {
            return list.isEmpty() ? null : raw;
        }
        if (raw instanceof Map<?, ?> map) {
            if ("name".equals(valueType) || "address".equals(valueType)) {
                Map<String, Object> members = new LinkedHashMap<>();
                map.forEach((key, value) -> {
                    if (value != null && !"".equals(value)) {
                        members.put(String.valueOf(key), value);
                    }
                });
                return members.isEmpty() ? null : members;
            }
            return map.isEmpty() ? null : raw;
        }
        return raw;
    }

    private static boolean isNonEmptyString(Object value) {
        return value instanceof String s && !s.isEmpty();
    }

    private static boolean isFileRef(Object value) {
        if (!(value instanceof Map<?, ?> file) || !file.keySet().equals(FILE_KEYS)) {
            return false;
        }
        return isNonEmptyString(file.get("id"))
                && isNonEmptyString(file.get("name"))
                && isNonEmptyString(file.get("contentType"))
                && file.get("size") instanceof Double size
                && size == Math.rint(size)
                && size >= 0;
    }

    /** Checks a normalised, non-null value against a value type. */
    static boolean hasValueType(String valueType, Object value, Map<String, Object> field) {
        return switch (valueType) {
            case "string" -> value instanceof String;
            case "number" -> Values.isNumber(value);
            case "boolean" -> value instanceof Boolean;
            case "date" -> Dates.parse(value) != null;
            case "time" -> value instanceof String s && TIME.matcher(s).matches();
            case "datetime" -> {
                if (!(value instanceof String s)) {
                    yield false;
                }
                Matcher matcher = DATETIME.matcher(s);
                yield matcher.matches() && Dates.parse(matcher.group(1)) != null;
            }
            case "string[]" -> value instanceof List<?> list
                    && list.stream().allMatch(item -> item instanceof String)
                    && new HashSet<>(list).size() == list.size();
            case "file[]" -> value instanceof List<?> list && list.stream().allMatch(FormValues::isFileRef);
            case "name", "address" -> {
                if (!(value instanceof Map<?, ?> map)) {
                    yield false;
                }
                List<String> parts = field != null ? FormModel.parts(field) : null;
                yield map.entrySet().stream().allMatch(e -> e.getValue() instanceof String
                        && (parts == null || parts.contains(String.valueOf(e.getKey()))));
            }
            case "item[]" -> value instanceof List<?> list && list.stream().allMatch(item -> item instanceof Map);
            default -> false;
        };
    }
}
