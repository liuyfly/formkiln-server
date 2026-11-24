package io.formkiln.server.expression;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical JSON values used by the evaluator: {@code null}, {@link Boolean}, {@link Double},
 * {@link String}, {@link List} and {@link Map} with string keys.
 */
public final class Values {

    private Values() {}

    /**
     * Converts parsed JSON (for example from Jackson) into canonical values. Every number becomes
     * a {@link Double}.
     *
     * @throws IllegalArgumentException if the value is not JSON-like
     */
    public static Object normalize(Object value) {
        if (value == null || value instanceof Boolean || value instanceof String) {
            return value;
        }
        if (value instanceof Number number) {
            return finiteOrNull(number.doubleValue());
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(normalize(item));
            }
            return result;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("JSON object keys must be strings");
                }
                result.put(key, normalize(entry.getValue()));
            }
            return result;
        }
        throw new IllegalArgumentException("Not a JSON value: " + value.getClass().getName());
    }

    /** A finite JSON number. */
    static boolean isNumber(Object value) {
        return value instanceof Double d && Double.isFinite(d);
    }

    /** Maps non-finite results to {@code null} and {@code -0} to {@code 0}. */
    static Double finiteOrNull(double value) {
        if (!Double.isFinite(value)) {
            return null;
        }
        return value == 0 ? 0.0 : value;
    }

    /** Strict deep equality without type conversion (operators-v1 §4). */
    public static boolean deepEqual(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Double x && b instanceof Double y) {
            return x.doubleValue() == y.doubleValue();
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) {
                return false;
            }
            for (int i = 0; i < x.size(); i++) {
                if (!deepEqual(x.get(i), y.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            if (x.size() != y.size()) {
                return false;
            }
            for (Map.Entry<?, ?> entry : x.entrySet()) {
                if (!y.containsKey(entry.getKey()) || !deepEqual(entry.getValue(), y.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Boolean || a instanceof String) {
            return Objects.equals(a, b);
        }
        return false;
    }

    /** {@code null}, {@code ""}, {@code []} and {@code {}} are empty (operators-v1 §6). */
    static boolean isEmpty(Object value) {
        return value == null
                || "".equals(value)
                || (value instanceof List<?> list && list.isEmpty())
                || (value instanceof Map<?, ?> map && map.isEmpty());
    }
}
