package io.formkiln.server.form;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The node tree of a structurally valid form document (canonical JSON values). */
final class FormModel {

    enum Kind { PAGE, PANEL, CONTENT, REPEATER, FIELD }

    /** A page or element with its position and context. */
    static final class Node {
        final Kind kind;
        final String key;
        final Map<String, Object> source;
        /** JSON Pointer (RFC 6901) to the object. */
        final String pointer;
        final Node parent;
        /** The enclosing repeater, if any. */
        final Node repeater;
        final List<Node> children = new ArrayList<>();
        /** Value type of fields and repeaters, otherwise {@code null}. */
        final String valueType;

        Node(Kind kind, Map<String, Object> source, String pointer, Node parent, Node repeater) {
            this.kind = kind;
            this.key = (String) source.get("key");
            this.source = source;
            this.pointer = pointer;
            this.parent = parent;
            this.repeater = repeater;
            this.valueType = switch (kind) {
                case FIELD -> fieldValueType(source);
                case REPEATER -> "item[]";
                default -> null;
            };
        }

        Object get(String property) {
            return source.get(property);
        }

        boolean isCalculated() {
            return kind == Kind.FIELD && source.containsKey("calculate");
        }

        String type() {
            return (String) source.get("type");
        }
    }

    static final Map<String, String> FIELD_VALUE_TYPES = Map.ofEntries(
            Map.entry("text", "string"),
            Map.entry("textarea", "string"),
            Map.entry("email", "string"),
            Map.entry("phone", "string"),
            Map.entry("url", "string"),
            Map.entry("number", "number"),
            Map.entry("radio", "string"),
            Map.entry("select", "string"),
            Map.entry("checkboxes", "string[]"),
            Map.entry("boolean", "boolean"),
            Map.entry("date", "date"),
            Map.entry("time", "time"),
            Map.entry("datetime", "datetime"),
            Map.entry("file", "file[]"),
            Map.entry("name", "name"),
            Map.entry("address", "address"));

    private static final Map<String, List<String>> DEFAULT_PARTS = Map.of(
            "name", List.of("given", "family"),
            "address", List.of("line1", "line2", "city", "postalCode", "country"));
    private static final Map<String, List<String>> DEFAULT_REQUIRED_PARTS = Map.of(
            "name", List.of("given", "family"),
            "address", List.of("line1", "city", "postalCode"));

    final Map<String, Object> doc;
    /** Every node in document order (depth first). */
    final List<Node> nodes = new ArrayList<>();
    /** Fields and repeaters outside repeaters, by key. */
    final Map<String, Node> topValues = new LinkedHashMap<>();
    /** Fields inside each repeater, by repeater key and child key. */
    final Map<String, Map<String, Node>> repeaterFields = new LinkedHashMap<>();

    private FormModel(Map<String, Object> doc) {
        this.doc = doc;
    }

    static String fieldValueType(Map<String, Object> field) {
        String type = (String) field.get("type");
        return "hidden".equals(type) ? (String) field.get("valueType") : FIELD_VALUE_TYPES.get(type);
    }

    @SuppressWarnings("unchecked")
    static List<String> parts(Map<String, Object> field) {
        String type = (String) field.get("type");
        if (!DEFAULT_PARTS.containsKey(type)) {
            return List.of();
        }
        Object parts = field.get("parts");
        return parts instanceof List<?> list ? (List<String>) list : DEFAULT_PARTS.get(type);
    }

    @SuppressWarnings("unchecked")
    static List<String> requiredParts(Map<String, Object> field) {
        String type = (String) field.get("type");
        if (!DEFAULT_REQUIRED_PARTS.containsKey(type)) {
            return List.of();
        }
        Object parts = field.get("requiredParts");
        return parts instanceof List<?> list ? (List<String>) list : DEFAULT_REQUIRED_PARTS.get(type);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    static FormModel build(Map<String, Object> doc) {
        FormModel model = new FormModel(doc);
        List<Map<String, Object>> pages = list(doc.get("pages"));
        for (int i = 0; i < pages.size(); i++) {
            model.visit(pages.get(i), "/pages/" + i, null, null);
        }
        return model;
    }

    private Node visit(Map<String, Object> source, String pointer, Node parent, Node repeater) {
        Kind kind;
        if (parent == null) {
            kind = Kind.PAGE;
        } else {
            kind = switch (String.valueOf(source.get("type"))) {
                case "panel" -> Kind.PANEL;
                case "content" -> Kind.CONTENT;
                case "repeater" -> Kind.REPEATER;
                default -> Kind.FIELD;
            };
        }
        Node node = new Node(kind, source, pointer, parent, repeater);
        nodes.add(node);

        if (kind == Kind.FIELD || kind == Kind.REPEATER) {
            Map<String, Node> namespace = repeater != null ? repeaterFields.get(repeater.key) : topValues;
            namespace.putIfAbsent(node.key, node);
        }
        if (kind == Kind.REPEATER) {
            repeaterFields.putIfAbsent(node.key, new LinkedHashMap<>());
        }

        List<Map<String, Object>> elements = list(source.get("elements"));
        Node inner = kind == Kind.REPEATER ? node : repeater;
        for (int i = 0; i < elements.size(); i++) {
            node.children.add(visit(elements.get(i), pointer + "/elements/" + i, node, inner));
        }
        return node;
    }

    /** Fields of a repeater in document order. */
    static List<Node> repeaterChildFields(Node repeater) {
        List<Node> result = new ArrayList<>();
        collectFields(repeater, result);
        return result;
    }

    private static void collectFields(Node node, List<Node> result) {
        for (Node child : node.children) {
            if (child.kind == Kind.FIELD) {
                result.add(child);
            }
            collectFields(child, result);
        }
    }
}
