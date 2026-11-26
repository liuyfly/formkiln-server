package io.formkiln.server.form;

import io.formkiln.server.expression.PortableRegex;
import io.formkiln.server.form.FormModel.Kind;
import io.formkiln.server.form.FormModel.Node;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

/** Validates the semantic rules of a structurally valid form document (Form Schema §10). */
public final class DefinitionValidator {

    /** A static error located by a JSON Pointer (RFC 6901). */
    public record DefinitionError(String pointer, String code) {}

    private static final int MANY = Integer.MAX_VALUE;
    private static final Map<String, int[]> ARITY = new HashMap<>();

    static {
        arity(0, 0, "date.today");
        arity(0, MANY, "cat");
        arity(1, 1, "!", "empty", "length", "lower", "upper", "sum");
        arity(1, 2, "var", "-", "round");
        arity(1, MANY, "and", "or", "if", "+", "*", "min", "max");
        arity(2, 2, "==", "!=", ">", ">=", "/", "%", "matches", "in", "some", "all", "none", "map", "filter");
        arity(2, 3, "<", "<=");
        arity(3, 3, "date.add", "date.diff");
    }

    private static void arity(int min, int max, String... operators) {
        for (String operator : operators) {
            ARITY.put(operator, new int[] {min, max});
        }
    }

    private static final Set<String> ARRAY_OPERATORS = Set.of("some", "all", "none", "map", "filter");

    /** Value types each validator applies to; absent means any. */
    private static final Map<String, Set<String>> VALIDATOR_TYPES = Map.ofEntries(
            Map.entry("minLength", Set.of("string")),
            Map.entry("maxLength", Set.of("string")),
            Map.entry("pattern", Set.of("string")),
            Map.entry("min", Set.of("number")),
            Map.entry("max", Set.of("number")),
            Map.entry("integer", Set.of("number")),
            Map.entry("minDate", Set.of("date")),
            Map.entry("maxDate", Set.of("date")),
            Map.entry("minItems", Set.of("string[]", "file[]")),
            Map.entry("maxItems", Set.of("string[]", "file[]")),
            Map.entry("maxFileSize", Set.of("file[]")),
            Map.entry("fileTypes", Set.of("file[]")));

    private record Scope(Node repeater, boolean allowValue, int arrayDepth) {}

    /** An operation split into operator, arguments and argument pointers. */
    record Operation(String operator, List<Object> args, IntFunction<String> argPointer) {}

    private final FormModel model;
    private final List<DefinitionError> errors = new ArrayList<>();

    private DefinitionValidator(FormModel model) {
        this.model = model;
    }

    /** Returns every problem found; an empty list means the document is valid. */
    public static List<DefinitionError> validate(Map<String, Object> doc) {
        DefinitionValidator validator = new DefinitionValidator(FormModel.build(doc));
        validator.run();
        return validator.errors;
    }

    static String escapePointer(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    static Operation operation(Object expression, String pointer) {
        if (!(expression instanceof Map<?, ?> map) || map.size() != 1) {
            return null;
        }
        Map.Entry<?, ?> entry = map.entrySet().iterator().next();
        String operator = String.valueOf(entry.getKey());
        String base = pointer + "/" + escapePointer(operator);
        if (entry.getValue() instanceof List<?> raw) {
            return new Operation(operator, new ArrayList<>(raw), i -> base + "/" + i);
        }
        List<Object> single = new ArrayList<>();
        single.add(entry.getValue());
        return new Operation(operator, single, i -> base);
    }

    /** Every literal {@code var} path in an expression. */
    static List<String> varPaths(Object expression) {
        List<String> paths = new ArrayList<>();
        collectVarPaths(expression, paths);
        return paths;
    }

    private static void collectVarPaths(Object expression, List<String> paths) {
        if (expression instanceof List<?> list) {
            list.forEach(item -> collectVarPaths(item, paths));
            return;
        }
        Operation op = operation(expression, "");
        if (op == null) {
            return;
        }
        if (op.operator().equals("var") && !op.args().isEmpty() && op.args().get(0) instanceof String path) {
            paths.add(path);
        }
        op.args().forEach(arg -> collectVarPaths(arg, paths));
    }

    private void report(String pointer, String code) {
        errors.add(new DefinitionError(pointer, code));
    }

    private void run() {
        checkKeys();
        checkOutcomeKeys();
        checkWidths();
        checkIds();
        for (Node node : model.nodes) {
            Scope scope = new Scope(node.repeater, false, 0);
            for (String property : List.of("visible", "required", "readOnly", "calculate")) {
                if (node.source.containsKey(property)) {
                    checkExpression(node.get(property), node.pointer + "/" + property, scope);
                }
            }
            if (node.kind == Kind.FIELD) {
                checkField(node, scope);
            }
        }
        List<Map<String, Object>> outcomes = outcomes();
        for (int i = 0; i < outcomes.size(); i++) {
            if (outcomes.get(i).containsKey("when")) {
                checkExpression(outcomes.get(i).get("when"), "/submit/outcomes/" + i + "/when", new Scope(null, false, 0));
            }
        }
        checkCycles();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> outcomes() {
        return model.doc.get("submit") instanceof Map<?, ?> submit
                ? FormModel.list(((Map<String, Object>) submit).get("outcomes"))
                : List.of();
    }

    private void checkKeys() {
        Map<Node, Set<String>> seen = new HashMap<>();
        Set<String> top = new HashSet<>();
        for (Node node : model.nodes) {
            Set<String> keys = node.repeater == null ? top : seen.computeIfAbsent(node.repeater, r -> new HashSet<>());
            if (!keys.add(node.key)) {
                report(node.pointer + "/key", "duplicateKey");
            }
        }
    }

    /** Form Schema §5.6: element ids are unique in the document. */
    private void checkIds() {
        Set<String> seen = new HashSet<>();
        for (Node node : model.nodes) {
            if (node.kind != Kind.PAGE && node.source.get("id") instanceof String id && !seen.add(id)) {
                report(node.pointer + "/id", "duplicateId");
            }
        }
    }

    /** Form Schema §5.5: an element spans at most the columns of the layout grid (default 3). */
    private void checkWidths() {
        double columns = 3;
        if (model.doc.get("settings") instanceof Map<?, ?> settings
                && settings.get("layout") instanceof Map<?, ?> layout
                && layout.get("columns") instanceof Number number) {
            columns = number.doubleValue();
        }
        for (Node node : model.nodes) {
            if (node.kind != Kind.PAGE && node.source.get("width") instanceof Number width && width.doubleValue() > columns) {
                report(node.pointer + "/width", "width");
            }
        }
    }

    private void checkOutcomeKeys() {
        Set<Object> keys = new HashSet<>();
        List<Map<String, Object>> outcomes = outcomes();
        for (int i = 0; i < outcomes.size(); i++) {
            if (!keys.add(outcomes.get(i).get("key"))) {
                report("/submit/outcomes/" + i + "/key", "duplicateOutcomeKey");
            }
        }
    }

    private void checkExpression(Object expression, String pointer, Scope scope) {
        if (expression instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                checkExpression(list.get(i), pointer + "/" + i, scope);
            }
            return;
        }
        Operation op = operation(expression, pointer);
        if (op == null) {
            return;
        }
        int[] range = ARITY.getOrDefault(op.operator(), new int[] {0, MANY});
        int count = op.args().size();
        if (count < range[0] || count > range[1]) {
            report(pointer, "arity");
        }
        if (op.operator().equals("var")) {
            Object path = count > 0 ? op.args().get(0) : null;
            if (!(path instanceof String text)) {
                report(pointer, "varPath");
            } else if (!isKnownReference(text, scope)) {
                report(pointer, "unknownReference");
            }
        }
        if (op.operator().equals("matches") && count > 1 && op.args().get(1) instanceof String pattern
                && !PortableRegex.compile(pattern).ok()) {
            report(op.argPointer().apply(1), "invalidPattern");
        }
        for (int i = 0; i < count; i++) {
            if (op.operator().equals("var") && i == 0) {
                continue;
            }
            Scope inner = ARRAY_OPERATORS.contains(op.operator()) && i == 1
                    ? new Scope(scope.repeater(), scope.allowValue(), scope.arrayDepth() + 1)
                    : scope;
            checkExpression(op.args().get(i), op.argPointer().apply(i), inner);
        }
    }

    private boolean isKnownReference(String path, Scope scope) {
        String head = path.split("\\.", -1)[0];
        return switch (head) {
            case "$item", "$index" -> scope.repeater() != null;
            case "$value" -> scope.allowValue();
            case "$this", "$thisIndex" -> scope.arrayDepth() > 0;
            default -> model.topValues.containsKey(head);
        };
    }

    private boolean matchesType(Node node, Object value) {
        Object normalized = FormValues.normalize(node.valueType, value);
        return normalized == null || FormValues.hasValueType(node.valueType, normalized, node.source);
    }

    private void checkField(Node node, Scope scope) {
        Map<String, Object> field = node.source;
        if (field.get("options") instanceof List<?> options) {
            Set<Object> values = new HashSet<>();
            for (int i = 0; i < options.size(); i++) {
                Object value = ((Map<?, ?>) options.get(i)).get("value");
                if (!values.add(value)) {
                    report(node.pointer + "/options/" + i + "/value", "duplicateOption");
                }
            }
        }
        if (!FormModel.parts(field).containsAll(FormModel.requiredParts(field))) {
            report(node.pointer + "/requiredParts", "requiredParts");
        }
        if (field.containsKey("defaultValue") && !matchesType(node, field.get("defaultValue"))) {
            report(node.pointer + "/defaultValue", "valueType");
        }
        List<Map<String, Object>> validators = FormModel.list(field.get("validators"));
        for (int i = 0; i < validators.size(); i++) {
            Map<String, Object> validator = validators.get(i);
            String pointer = node.pointer + "/validators/" + i;
            String type = (String) validator.get("type");
            Set<String> allowed = VALIDATOR_TYPES.get(type);
            if (allowed != null && !allowed.contains(node.valueType)) {
                report(pointer, "validatorType");
            }
            switch (type) {
                case "equals" -> {
                    if (!matchesType(node, validator.get("value"))) {
                        report(pointer + "/value", "valueType");
                    }
                }
                case "pattern" -> {
                    if (!(validator.get("value") instanceof String pattern) || !PortableRegex.compile(pattern).ok()) {
                        report(pointer + "/value", "invalidPattern");
                    }
                }
                case "minDate", "maxDate" -> {
                    if (!(validator.get("value") instanceof String)) {
                        checkExpression(validator.get("value"), pointer + "/value", scope);
                    }
                }
                case "expression" -> checkExpression(
                        validator.get("rule"), pointer + "/rule", new Scope(scope.repeater(), true, scope.arrayDepth()));
                default -> {}
            }
        }
    }

    // ----------------------------------------------------------------- cycles (§7.5)

    private List<Node> resolve(String path, Node from) {
        String[] segments = path.split("\\.", -1);
        if (segments[0].equals("$item") && from.repeater != null) {
            Map<String, Node> fields = model.repeaterFields.get(from.repeater.key);
            if (segments.length == 1) {
                return new ArrayList<>(fields.values());
            }
            Node target = fields.get(segments[1]);
            return target != null ? List.of(target) : List.of();
        }
        Node target = model.topValues.get(segments[0]);
        return target != null ? List.of(target) : List.of();
    }

    private final Map<Node, Set<Node>> edges = new IdentityHashMap<>();
    private final Map<Node, Integer> indices = new IdentityHashMap<>();
    private final Map<Node, Integer> lowLinks = new IdentityHashMap<>();
    private final Map<Node, Integer> order = new IdentityHashMap<>();
    private final List<Node> stack = new ArrayList<>();
    private final Set<Node> onStack = Collections.newSetFromMap(new IdentityHashMap<>());
    private int nextIndex;

    private void checkCycles() {
        List<Node> valueNodes = model.nodes.stream()
                .filter(n -> n.kind == Kind.FIELD || n.kind == Kind.REPEATER)
                .toList();
        for (int i = 0; i < valueNodes.size(); i++) {
            order.put(valueNodes.get(i), i);
        }
        for (Node node : valueNodes) {
            Set<Node> targets = new LinkedHashSet<>();
            if (node.isCalculated()) {
                varPaths(node.get("calculate")).forEach(p -> targets.addAll(resolve(p, node)));
            }
            for (Node n = node; n != null; n = n.parent) {
                Node from = n;
                if (n.source.containsKey("visible")) {
                    varPaths(n.get("visible")).forEach(p -> targets.addAll(resolve(p, from)));
                }
            }
            if (node.kind == Kind.REPEATER) {
                targets.addAll(model.repeaterFields.getOrDefault(node.key, Map.of()).values());
            }
            edges.put(node, targets);
        }
        for (Node node : valueNodes) {
            if (!indices.containsKey(node)) {
                connect(node);
            }
        }
    }

    private void connect(Node node) {
        indices.put(node, nextIndex);
        lowLinks.put(node, nextIndex);
        nextIndex++;
        stack.add(node);
        onStack.add(node);
        for (Node target : edges.getOrDefault(node, Set.of())) {
            if (!indices.containsKey(target)) {
                connect(target);
                lowLinks.put(node, Math.min(lowLinks.get(node), lowLinks.get(target)));
            } else if (onStack.contains(target)) {
                lowLinks.put(node, Math.min(lowLinks.get(node), indices.get(target)));
            }
        }
        if (lowLinks.get(node).equals(indices.get(node))) {
            List<Node> component = new ArrayList<>();
            Node member;
            do {
                member = stack.remove(stack.size() - 1);
                onStack.remove(member);
                component.add(member);
            } while (member != node);
            if (component.size() > 1 || edges.get(node).contains(node)) {
                Node first = component.stream().min((a, b) -> order.get(a) - order.get(b)).orElseThrow();
                report(first.pointer, "cycle");
            }
        }
    }
}
