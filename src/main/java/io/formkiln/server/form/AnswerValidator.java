package io.formkiln.server.form;

import io.formkiln.server.expression.ExpressionEvaluator;
import io.formkiln.server.expression.PortableRegex;
import io.formkiln.server.expression.Values;
import io.formkiln.server.form.FormModel.Kind;
import io.formkiln.server.form.FormModel.Node;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Validates answers against a valid form document (Form Schema §11): normalises and type-checks
 * them, computes visibility and calculated values, validates every active field, selects the
 * outcome and builds the submission. Inputs must be canonical values ({@link Values#normalize}).
 */
public final class AnswerValidator {

    /** One validation error (Form Schema §11.2). {@code message} and {@code params} may be null. */
    public record ValidationError(String path, String code, String message, Map<String, Object> params) {}

    /** The result of validating answers. */
    public record FormEvaluation(
            boolean valid,
            List<ValidationError> errors,
            String outcome,
            Map<String, Object> submission,
            List<String> inactive) {}

    private static final Map<String, String[]> FORMATS = Map.of(
            "email", new String[] {"format.email", "[^\\s@]+@[^\\s@]+\\.[^\\s@]+"},
            "phone", new String[] {"format.phone", "\\+?[0-9 ()\\-]{6,20}"},
            "url", new String[] {"format.url", "https?://[^\\s/?#]+(?:[/?#]\\S*)?"});

    private record Slot(Object value, boolean typeError) {
        static final Slot EMPTY = new Slot(null, false);
    }

    private final FormModel model;
    private final Instant now;
    private final String timeZone;
    private final String locale;
    private final String defaultLocale;

    private final Map<String, Slot> topSlots = new HashMap<>();
    private final Map<String, List<Map<String, Slot>>> itemSlots = new HashMap<>();
    private final Map<String, Boolean> activeMemo = new HashMap<>();
    private final Map<String, Object> effectiveMemo = new HashMap<>();
    private final Map<String, Slot> calcMemo = new HashMap<>();
    private final Set<String> inProgress = new HashSet<>();
    private final List<ValidationError> errors = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private AnswerValidator(FormModel model, Instant now, String locale) {
        this.model = model;
        this.now = now;
        Map<String, Object> settings = model.doc.get("settings") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
        this.timeZone = settings.get("timeZone") instanceof String tz ? tz : "UTC";
        this.defaultLocale = settings.get("defaultLocale") instanceof String l ? l : "en";
        this.locale = locale != null ? locale : defaultLocale;
    }

    /**
     * Validates {@code answers}.
     *
     * @param now evaluation instant; {@code null} means now
     * @param locale locale for custom messages; {@code null} means the form's default locale
     */
    public static FormEvaluation validate(
            Map<String, Object> doc, Map<String, Object> answers, Instant now, String locale) {
        AnswerValidator validator =
                new AnswerValidator(FormModel.build(doc), now != null ? now : Instant.now(), locale);
        return validator.run(answers != null ? answers : Map.of());
    }

    // ------------------------------------------------------------------ values

    private static Slot checked(Node node, Object raw) {
        Object value = FormValues.normalize(node.valueType, raw);
        if (value == null) {
            return Slot.EMPTY;
        }
        boolean ok = FormValues.hasValueType(node.valueType, value, node.kind == Kind.FIELD ? node.source : null);
        return ok ? new Slot(value, false) : new Slot(null, true);
    }

    @SuppressWarnings("unchecked")
    private void loadAnswers(Map<String, Object> answers) {
        for (Map.Entry<String, Node> entry : model.topValues.entrySet()) {
            Node node = entry.getValue();
            if (node.isCalculated()) {
                continue;
            }
            Slot slot = checked(node, answers.get(entry.getKey()));
            topSlots.put(entry.getKey(), slot);
            if (node.kind == Kind.REPEATER) {
                List<Map<String, Slot>> items = new ArrayList<>();
                if (slot.value() instanceof List<?> list) {
                    for (Object raw : list) {
                        Map<String, Object> item = (Map<String, Object>) raw;
                        Map<String, Slot> slots = new HashMap<>();
                        model.repeaterFields.get(node.key).forEach((childKey, child) -> {
                            if (!child.isCalculated()) {
                                slots.put(childKey, checked(child, item.get(childKey)));
                            }
                        });
                        items.add(slots);
                    }
                }
                itemSlots.put(node.key, items);
            }
        }
    }

    private static String pathOf(Node node, Integer index) {
        return node.repeater != null && index != null ? node.repeater.key + "." + index + "." + node.key : node.key;
    }

    private <T> T memoized(Map<String, T> memo, String id, T fallback, Supplier<T> compute) {
        if (memo.containsKey(id)) {
            return memo.get(id);
        }
        if (!inProgress.add(id)) {
            return fallback; // Only reachable for invalid (cyclic) documents.
        }
        T value = compute.get();
        inProgress.remove(id);
        memo.put(id, value);
        return value;
    }

    private Object evalIn(Object expression, Node node, Integer index, Map<String, Object> extra) {
        Map<String, Object> data = new HashMap<>();
        Map<String, Object> scope = new HashMap<>(extra);
        Node repeater = node.repeater;
        for (String path : DefinitionValidator.varPaths(expression)) {
            String[] segments = path.split("\\.", -1);
            String head = segments[0];
            if (head.equals("$item") && repeater != null && index != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> item = (Map<String, Object>) scope.computeIfAbsent("$item", k -> new HashMap<>());
                Map<String, Node> fields = model.repeaterFields.get(repeater.key);
                List<String> keys = segments.length == 1 ? new ArrayList<>(fields.keySet()) : List.of(segments[1]);
                for (String key : keys) {
                    if (fields.containsKey(key)) {
                        item.put(key, childEffective(repeater, index, key));
                    }
                }
            } else if (head.equals("$index") && index != null) {
                scope.put("$index", (double) index);
            } else if (model.topValues.containsKey(head)) {
                data.put(head, topEffective(head));
            }
        }
        return ExpressionEvaluator.evaluate(
                expression, new ExpressionEvaluator.Options(data, scope, now, timeZone));
    }

    private boolean holds(Object condition, Node node, Integer index, boolean fallback) {
        return condition == null ? fallback : ExpressionEvaluator.isTrue(evalIn(condition, node, index, Map.of()));
    }

    private boolean isActive(Node node, Integer index) {
        return memoized(activeMemo, pathOf(node, index), false, () -> {
            Node parent = node.parent;
            Integer parentIndex = parent != null && parent.repeater != null ? index : null;
            if (parent != null && !isActive(parent, parentIndex)) {
                return false;
            }
            return holds(node.get("visible"), node, index, true);
        });
    }

    private Slot calculated(Node node, Integer index) {
        return memoized(calcMemo, pathOf(node, index), Slot.EMPTY,
                () -> checked(node, evalIn(node.get("calculate"), node, index, Map.of())));
    }

    private Slot slotOf(Node node, Integer index) {
        if (node.isCalculated()) {
            return calculated(node, index);
        }
        if (node.repeater != null && index != null) {
            return itemSlots.get(node.repeater.key).get(index).get(node.key);
        }
        return topSlots.get(node.key);
    }

    private Object childEffective(Node repeater, int index, String key) {
        Node node = model.repeaterFields.get(repeater.key).get(key);
        return memoized(effectiveMemo, pathOf(node, index), null,
                () -> isActive(node, index) ? slotOf(node, index).value() : null);
    }

    private Object topEffective(String key) {
        Node node = model.topValues.get(key);
        return memoized(effectiveMemo, key, null, () -> {
            if (!isActive(node, null)) {
                return null;
            }
            if (node.kind == Kind.FIELD) {
                return slotOf(node, null).value();
            }
            if (topSlots.get(key).value() == null) {
                return null;
            }
            List<Object> items = new ArrayList<>();
            for (int i = 0; i < itemSlots.get(key).size(); i++) {
                Map<String, Object> item = new LinkedHashMap<>();
                for (String childKey : model.repeaterFields.get(key).keySet()) {
                    item.put(childKey, childEffective(node, i, childKey));
                }
                items.add(item);
            }
            return items;
        });
    }

    // ------------------------------------------------------------------ validation

    @SuppressWarnings("unchecked")
    private String resolveText(Object text) {
        if (text == null) {
            return null;
        }
        if (text instanceof String s) {
            return s;
        }
        Map<String, Object> map = (Map<String, Object>) text;
        Object value = map.get(locale);
        if (value == null) {
            value = map.get(defaultLocale);
        }
        if (value == null && !map.isEmpty()) {
            value = map.values().iterator().next();
        }
        return (String) value;
    }

    private void report(String path, String code, Object message, Object paramValue) {
        Map<String, Object> params = paramValue == null ? null : Collections.singletonMap("value", paramValue);
        errors.add(new ValidationError(path, code, resolveText(message), params));
    }

    private void report(String path, String code) {
        report(path, code, null, null);
    }

    @SuppressWarnings("unchecked")
    private void validateField(Node node, Integer index) {
        Map<String, Object> field = node.source;
        String path = pathOf(node, index);
        Slot slot = slotOf(node, index);
        if (slot.typeError()) {
            report(path, "type");
            return;
        }
        Object value = slot.value();
        if (value == null) {
            if (holds(field.get("required"), node, index, false)) {
                report(path, "required", field.get("requiredMessage"), null);
            }
            return;
        }

        String type = node.type();
        if (type.equals("name") || type.equals("address")) {
            List<String> required = FormModel.requiredParts(field);
            for (String part : FormModel.parts(field)) {
                if (required.contains(part) && !((Map<String, Object>) value).containsKey(part)) {
                    report(path + "." + part, "required");
                }
            }
        }
        if (field.get("options") instanceof List<?> options) {
            Set<Object> allowed = new HashSet<>();
            options.forEach(option -> allowed.add(((Map<?, ?>) option).get("value")));
            List<?> values = value instanceof List<?> list ? list : List.of(value);
            if (!allowed.containsAll(values)) {
                report(path, "option");
            }
        }
        String[] format = FORMATS.get(type);
        if (format != null) {
            PortableRegex.Result regex = PortableRegex.compile(format[1]);
            if (regex.ok() && !regex.matches((String) value)) {
                report(path, format[0]);
            }
        }
        if (type.equals("file") && !Boolean.TRUE.equals(field.get("multiple")) && ((List<?>) value).size() > 1) {
            report(path, "maxItems", null, 1.0);
        }

        for (Map<String, Object> validator : FormModel.list(field.get("validators"))) {
            runValidator(validator, node, index, path, value);
        }
    }

    @SuppressWarnings("unchecked")
    private void runValidator(Map<String, Object> validator, Node node, Integer index, String path, Object value) {
        String type = (String) validator.get("type");
        Object limit = validator.get("value");
        Object message = validator.get("message");
        String code = type.equals("fileTypes") ? "fileType" : type;
        switch (type) {
            case "minLength", "maxLength" -> {
                if (value instanceof String s && limit instanceof Double max) {
                    int length = s.codePointCount(0, s.length());
                    if (type.equals("minLength") ? length < max : length > max) {
                        report(path, code, message, limit);
                    }
                }
            }
            case "pattern" -> {
                PortableRegex.Result regex = PortableRegex.compile((String) limit);
                if (value instanceof String s && regex.ok() && !regex.matches(s)) {
                    report(path, code, message, limit);
                }
            }
            case "min", "max" -> {
                if (value instanceof Double n && limit instanceof Double bound
                        && (type.equals("min") ? n < bound : n > bound)) {
                    report(path, code, message, limit);
                }
            }
            case "integer" -> {
                if (value instanceof Double n && n != Math.rint(n)) {
                    report(path, code, message, null);
                }
            }
            case "minDate", "maxDate" -> {
                Object date = limit instanceof String ? limit : evalIn(limit, node, index, Map.of());
                if (value instanceof String s && FormValues.hasValueType("date", date, null)) {
                    int cmp = s.compareTo((String) date);
                    if (type.equals("minDate") ? cmp < 0 : cmp > 0) {
                        report(path, code, message, date);
                    }
                }
            }
            case "minItems", "maxItems" -> {
                if (value instanceof List<?> list && limit instanceof Double bound) {
                    int count = list.size();
                    if (type.equals("minItems") ? count < bound : count > bound) {
                        report(path, code, message, limit);
                    }
                }
            }
            case "maxFileSize" -> {
                if (value instanceof List<?> files && limit instanceof Double max) {
                    for (int i = 0; i < files.size(); i++) {
                        if (files.get(i) instanceof Map<?, ?> file && (Double) file.get("size") > max) {
                            report(path + "." + i, code, message, limit);
                        }
                    }
                }
            }
            case "fileTypes" -> {
                if (value instanceof List<?> files && limit instanceof List<?> entries) {
                    for (int i = 0; i < files.size(); i++) {
                        Map<String, Object> file = (Map<String, Object>) files.get(i);
                        if (entries.stream().noneMatch(entry -> matchesFileType(file, (String) entry))) {
                            report(path + "." + i, code, message, limit);
                        }
                    }
                }
            }
            case "equals" -> {
                if (!Values.deepEqual(value, limit)) {
                    report(path, code, message, limit);
                }
            }
            case "expression" -> {
                if (!ExpressionEvaluator.isTrue(evalIn(validator.get("rule"), node, index, Map.of("$value", value)))) {
                    report(path, code, message, null);
                }
            }
            default -> {}
        }
    }

    private static boolean matchesFileType(Map<String, Object> file, String entry) {
        String name = String.valueOf(file.get("name")).toLowerCase(Locale.ROOT);
        String contentType = String.valueOf(file.get("contentType")).toLowerCase(Locale.ROOT);
        String wanted = entry.toLowerCase(Locale.ROOT);
        if (wanted.startsWith(".")) {
            return name.endsWith(wanted);
        }
        if (wanted.endsWith("/*")) {
            return contentType.startsWith(wanted.substring(0, wanted.length() - 1));
        }
        return contentType.equals(wanted);
    }

    // ------------------------------------------------------------------ run

    private FormEvaluation run(Map<String, Object> answers) {
        loadAnswers(answers);

        for (Node node : model.nodes) {
            if (node.repeater != null) {
                continue;
            }
            if (node.kind == Kind.FIELD && isActive(node, null)) {
                validateField(node, null);
            }
            if (node.kind == Kind.REPEATER && isActive(node, null)) {
                Slot slot = topSlots.get(node.key);
                if (slot.typeError()) {
                    report(node.key, "type");
                    continue;
                }
                int count = slot.value() instanceof List<?> list ? list.size() : 0;
                if (node.get("minItems") instanceof Double min && count < min) {
                    report(node.key, "minItems", null, min);
                }
                if (node.get("maxItems") instanceof Double max && count > max) {
                    report(node.key, "maxItems", null, max);
                }
                List<Node> children = FormModel.repeaterChildFields(node);
                for (int i = 0; i < count; i++) {
                    for (Node child : children) {
                        if (isActive(child, i)) {
                            validateField(child, i);
                        }
                    }
                }
            }
        }

        String outcome = null;
        if (errors.isEmpty() && model.doc.get("submit") instanceof Map<?, ?> submit) {
            Node anchor = model.nodes.get(0);
            for (Map<String, Object> candidate : FormModel.list(submit.get("outcomes"))) {
                if (holds(candidate.get("when"), anchor, null, true)) {
                    outcome = (String) candidate.get("key");
                    break;
                }
            }
        }

        Map<String, Object> submission = new LinkedHashMap<>();
        List<String> inactive = new ArrayList<>();
        for (Node node : model.nodes) {
            if (node.repeater != null) {
                continue;
            }
            if (!isActive(node, null)) {
                inactive.add(node.key);
            }
            if (node.kind == Kind.FIELD) {
                Object value = topEffective(node.key);
                if (value != null) {
                    submission.put(node.key, value);
                }
            }
            if (node.kind == Kind.REPEATER) {
                List<Map<String, Slot>> slots = itemSlots.getOrDefault(node.key, List.of());
                List<Node> descendants = model.nodes.stream().filter(n -> n.repeater == node).toList();
                List<Object> items = new ArrayList<>();
                for (int i = 0; i < slots.size(); i++) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    for (Node child : descendants) {
                        if (!isActive(child, i)) {
                            inactive.add(pathOf(child, i));
                        } else if (child.kind == Kind.FIELD) {
                            Object value = childEffective(node, i, child.key);
                            if (value != null) {
                                item.put(child.key, value);
                            }
                        }
                    }
                    items.add(item);
                }
                if (isActive(node, null) && topSlots.get(node.key).value() != null) {
                    submission.put(node.key, items);
                }
            }
        }
        return new FormEvaluation(errors.isEmpty(), List.copyOf(errors), outcome, submission, inactive);
    }
}
