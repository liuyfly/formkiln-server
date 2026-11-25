package io.formkiln.server.form;

import io.formkiln.server.form.FormModel.Kind;
import io.formkiln.server.form.FormModel.Node;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compares a new form definition with the published one and lists changes that affect existing
 * submissions or integrations: removed fields, changed value types and removed choice options.
 */
public final class CompatibilityChecker {

    /** A change at {@code path} (an answer key, or {@code repeater.child}). */
    public record Change(String kind, String path, Map<String, Object> detail) {}

    public static final String FIELD_REMOVED = "fieldRemoved";
    public static final String TYPE_CHANGED = "typeChanged";
    public static final String OPTIONS_REMOVED = "optionsRemoved";

    private CompatibilityChecker() {}

    /** Answer paths of fields and repeaters, in document order. */
    private static Map<String, Node> answers(Map<String, Object> definition) {
        Map<String, Node> result = new LinkedHashMap<>();
        for (Node node : FormModel.build(definition).nodes) {
            if ((node.kind != Kind.FIELD && node.kind != Kind.REPEATER) || node.key == null) {
                continue;
            }
            String path = node.repeater == null ? node.key : node.repeater.key + "." + node.key;
            result.putIfAbsent(path, node);
        }
        return result;
    }

    private static Set<String> optionValues(Node node) {
        Set<String> values = new LinkedHashSet<>();
        for (Map<String, Object> option : FormModel.list(node.get("options"))) {
            if (option.get("value") instanceof String value) {
                values.add(value);
            }
        }
        return values;
    }

    public static List<Change> compare(Map<String, Object> published, Map<String, Object> next) {
        Map<String, Node> before = answers(published);
        Map<String, Node> after = answers(next);
        List<Change> changes = new ArrayList<>();
        for (Map.Entry<String, Node> entry : before.entrySet()) {
            String path = entry.getKey();
            Node old = entry.getValue();
            Node current = after.get(path);
            if (current == null) {
                changes.add(new Change(FIELD_REMOVED, path, Map.of()));
                continue;
            }
            if (old.valueType != null && !old.valueType.equals(current.valueType)) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("from", old.valueType);
                detail.put("to", current.valueType);
                changes.add(new Change(TYPE_CHANGED, path, detail));
                continue;
            }
            Set<String> removed = optionValues(old);
            removed.removeAll(optionValues(current));
            if (!removed.isEmpty() && current.get("options") != null) {
                changes.add(new Change(OPTIONS_REMOVED, path, Map.of("values", List.copyOf(removed))));
            }
        }
        return changes;
    }
}
