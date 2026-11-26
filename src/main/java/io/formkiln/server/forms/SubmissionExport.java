package io.formkiln.server.forms;

import io.formkiln.server.workspaces.WorkspaceContext;
import io.formkiln.server.accounts.AccountRepository;
import io.formkiln.server.expression.Values;
import io.formkiln.server.form.FieldIndex;
import io.formkiln.server.forms.FormRepository.StoredSubmission;
import io.formkiln.server.forms.FormRepository.StoredVersion;
import io.formkiln.server.forms.FormService.NotFoundException;
import io.formkiln.server.platform.SubmissionData;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exports the submissions of a form as CSV (RFC 4180, UTF-8 with BOM for spreadsheet apps).
 *
 * <p>Columns: {@code id, version, outcome, submittedAt, subject}, then the answers of the latest
 * version in document order, then answers that only older versions have. A field renamed between
 * versions (same element id, Form Schema §5.6) stays one column, titled with its newest key. Sensitive values
 * are masked unless revealed. Values that a spreadsheet would run as a formula are prefixed with
 * an apostrophe (CSV injection).
 */
@Service
public class SubmissionExport {

    private static final int PAGE = 500;
    private static final List<String> META = List.of("id", "version", "outcome", "submittedAt", "subject");

    /** A prepared export: the form exists and the access is audited; {@link #write} streams it. */
    /** The export to write; the workspace is captured because the body is written outside the request. */
    public record Prepared(UUID workspace, String key, boolean reveal) {}

    private final FormRepository repository;
    private final SubmissionData submissionData;
    private final AccountRepository audit;
    private final JsonMapper json;

    public SubmissionExport(FormRepository repository, SubmissionData submissionData, AccountRepository audit, JsonMapper json) {
        this.repository = repository;
        this.submissionData = submissionData;
        this.audit = audit;
        this.json = json;
    }

    public Prepared prepare(String key, boolean reveal, String actorType, UUID actorId, String ip) {
        if (repository.listVersions(key).isEmpty()) {
            throw new NotFoundException("Form " + key + " is not published");
        }
        audit.audit(actorType, actorId, "submissions.exported", key, ip, "{\"reveal\":" + reveal + "}");
        return new Prepared(WorkspaceContext.id(), key, reveal);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> canonical(String text) {
        return (Map<String, Object>) Values.normalize(json.readValue(text, Map.class));
    }

    public void write(Prepared export, OutputStream output) throws IOException {
        Map<Integer, FieldIndex> indexes = new HashMap<>();
        List<Column> columns = new ArrayList<>();
        Map<String, Column> byId = new HashMap<>();
        Map<String, Column> byKey = new HashMap<>();
        for (StoredVersion version : repository.listVersions(export.workspace(), export.key())) { // Newest first.
            FieldIndex index = FieldIndex.of(canonical(version.definitionJson()));
            indexes.put(version.version(), index);
            for (String key : index.answerKeys()) {
                // One column per field across versions: by element id when it has one (a renamed
                // field keeps its column, titled with its newest key), else by key.
                String id = index.idOf(key).orElse(null);
                Column column = id != null ? byId.get(id) : null;
                if (column == null) {
                    Column sameKey = byKey.get(key);
                    boolean free = sameKey != null && !sameKey.keys.containsKey(version.version())
                            && (id == null || sameKey.id == null);
                    column = free ? sameKey : new Column(key, id);
                    if (column != sameKey) {
                        columns.add(column);
                    }
                }
                column.keys.put(version.version(), key);
                if (id != null && column.id == null) {
                    column.id = id;
                }
                if (id != null) {
                    byId.put(id, column);
                }
                byKey.put(key, column);
            }
        }
        Writer writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        writer.write('﻿');
        List<String> header = new ArrayList<>(META);
        columns.forEach(column -> header.add(column.title));
        writeRow(writer, header.stream().map(h -> (Object) h).toList());

        Instant afterAt = null;
        UUID afterId = null;
        while (true) {
            List<StoredSubmission> page = repository.exportPage(export.workspace(), export.key(), afterAt, afterId, PAGE);
            for (StoredSubmission s : page) {
                FieldIndex index = indexes.get(s.version());
                Map<String, Object> stored = canonical(s.dataJson());
                Map<String, Object> data = export.reveal() ? submissionData.decrypt(stored, index) : submissionData.mask(stored, index);
                List<Object> row = new ArrayList<>(List.of(s.id().toString(), s.version(), nullable(s.outcome()),
                        s.submittedAt().toString(), nullable(s.subject())));
                for (Column column : columns) {
                    String key = column.keys.get(s.version());
                    row.add(key == null ? "" : cellValue(data.get(key)));
                }
                writeRow(writer, row);
            }
            if (page.size() < PAGE) {
                break;
            }
            StoredSubmission last = page.getLast();
            afterAt = last.submittedAt();
            afterId = last.id();
        }
        writer.flush();
    }

    /** An export column: its title (newest key) and the key it has in each version. */
    private static final class Column {
        final String title;
        String id;
        final Map<Integer, String> keys = new HashMap<>();

        Column(String title, String id) {
            this.title = title;
            this.id = id;
        }
    }

    private static Object nullable(String value) {
        return value == null ? "" : value;
    }

    /** Scalars as they are; file lists as their names; other structures as JSON. */
    private Object cellValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Double number) {
            return number; // Numbers are never formula-escaped.
        }
        if (value instanceof List<?> list && !list.isEmpty()
                && list.stream().allMatch(item -> item instanceof Map<?, ?> map && map.get("id") instanceof String && map.containsKey("contentType"))) {
            return String.join("; ", list.stream().map(item -> String.valueOf(((Map<?, ?>) item).get("name"))).toList());
        }
        return json.writeValueAsString(value);
    }

    static String number(Double value) {
        BigDecimal decimal = BigDecimal.valueOf(value).stripTrailingZeros();
        return decimal.scale() <= 0 ? decimal.toBigInteger().toString() : decimal.toPlainString();
    }

    /** Escapes text that a spreadsheet would evaluate (=, +, -, @, tab, carriage return). */
    static String neutralize(String text) {
        return !text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0 ? "'" + text : text;
    }

    static String quote(String text) {
        return text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")
                ? "\"" + text.replace("\"", "\"\"") + "\""
                : text;
    }

    private static void writeRow(Writer writer, List<Object> cells) throws IOException {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                writer.write(',');
            }
            Object cell = cells.get(i);
            String text = cell instanceof Double d ? number(d)
                    : cell instanceof Integer n ? n.toString()
                    : neutralize(String.valueOf(cell));
            writer.write(quote(text));
        }
        writer.write("\r\n");
    }
}
