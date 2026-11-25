package io.formkiln.server.form;

import io.formkiln.server.form.FormModel.Kind;
import io.formkiln.server.form.FormModel.Node;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Facts about the fields of a form document that the platform needs outside validation. */
public final class FieldIndex {

    /** Upload rules of a file field (Form Schema §6.4, §9). */
    public record FileRule(String key, boolean multiple, List<String> accept, List<String> fileTypes, Long maxFileSize) {}

    private final Set<String> sensitiveTop = new HashSet<>();
    private final Map<String, Set<String>> sensitiveInRepeater = new HashMap<>();
    private final Map<String, FileRule> fileFields = new HashMap<>();
    private final Map<String, String> repeaterOfFileField = new HashMap<>();

    private FieldIndex() {}

    @SuppressWarnings("unchecked")
    public static FieldIndex of(Map<String, Object> document) {
        FieldIndex index = new FieldIndex();
        FormModel model = FormModel.build(document);
        for (Node node : model.nodes) {
            if (node.kind != Kind.FIELD) {
                continue;
            }
            if (Boolean.TRUE.equals(node.get("sensitive"))) {
                if (node.repeater == null) {
                    index.sensitiveTop.add(node.key);
                } else {
                    index.sensitiveInRepeater.computeIfAbsent(node.repeater.key, k -> new HashSet<>()).add(node.key);
                }
            }
            if ("file".equals(node.type()) && !index.fileFields.containsKey(node.key)) {
                List<String> fileTypes = null;
                Long maxFileSize = null;
                for (Map<String, Object> validator : FormModel.list(node.get("validators"))) {
                    if ("fileTypes".equals(validator.get("type"))) {
                        fileTypes = (List<String>) validator.get("value");
                    }
                    if ("maxFileSize".equals(validator.get("type")) && validator.get("value") instanceof Double d) {
                        maxFileSize = d.longValue();
                    }
                }
                List<String> accept = node.get("accept") instanceof List<?> list ? (List<String>) list : List.of();
                index.fileFields.put(node.key, new FileRule(
                        node.key, Boolean.TRUE.equals(node.get("multiple")), accept, fileTypes, maxFileSize));
                if (node.repeater != null) {
                    index.repeaterOfFileField.put(node.key, node.repeater.key);
                }
            }
        }
        return index;
    }

    public Set<String> sensitiveTop() {
        return sensitiveTop;
    }

    public Set<String> sensitiveIn(String repeaterKey) {
        return sensitiveInRepeater.getOrDefault(repeaterKey, Set.of());
    }

    public Set<String> repeatersWithSensitiveFields() {
        return sensitiveInRepeater.keySet();
    }

    public Optional<FileRule> fileField(String key) {
        return Optional.ofNullable(fileFields.get(key));
    }

    public Map<String, FileRule> fileFields() {
        return fileFields;
    }

    /** The repeater containing a file field, or null for top-level fields. */
    public String repeaterOf(String fileFieldKey) {
        return repeaterOfFileField.get(fileFieldKey);
    }
}
