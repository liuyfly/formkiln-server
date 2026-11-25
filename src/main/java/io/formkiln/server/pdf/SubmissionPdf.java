package io.formkiln.server.pdf;

import io.formkiln.server.expression.Values;
import io.formkiln.server.form.FieldIndex;
import io.formkiln.server.form.FieldIndex.AnswerField;
import io.formkiln.server.forms.FormRepository.StoredSubmission;
import io.formkiln.server.notifications.AnswerText;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Renders a submission as a PDF: the admin export (all answers; sensitive ones masked unless
 * revealed) and the respondent's receipt (sensitive answers never shown).
 */
@Component
public class SubmissionPdf {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");

    private final Path font;

    public SubmissionPdf(@Value("${formkiln.pdf.font:}") String font) {
        this.font = font == null || font.isBlank() ? null : Path.of(font);
        if (this.font != null && !Files.isReadable(this.font)) {
            throw new IllegalStateException("FORMKILN_PDF_FONT is not a readable file: " + font);
        }
    }

    private static String locale(Map<String, Object> definition, String requested) {
        if (requested != null) {
            return requested;
        }
        return definition.get("settings") instanceof Map<?, ?> s && s.get("defaultLocale") instanceof String l ? l : "en";
    }

    private static String time(Map<String, Object> definition, Instant at) {
        // A region id prints "UTC" rather than the offset "Z".
        ZoneId zone = ZoneId.of("UTC");
        if (definition.get("settings") instanceof Map<?, ?> s && s.get("timeZone") instanceof String tz) {
            try {
                zone = ZoneId.of(tz);
            } catch (RuntimeException e) {
                // Keep UTC for an unknown zone.
            }
        }
        return TIME.format(at.atZone(zone));
    }

    @SuppressWarnings("unchecked")
    private static String outcomeMessage(Map<String, Object> definition, String outcome, String locale) {
        if (outcome == null || !(definition.get("submit") instanceof Map<?, ?> submit) || !(submit.get("outcomes") instanceof List<?> list)) {
            return null;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> o && outcome.equals(o.get("key")) && o.get("message") != null) {
                return AnswerText.text(o.get("message"), locale);
            }
        }
        return null;
    }

    private static String label(AnswerField field, String locale) {
        String text = AnswerText.text(field.label(), locale);
        return text.isBlank() ? field.key() : text;
    }

    /**
     * @param data the submission data, already masked or decrypted
     * @param receipt true for the respondent's copy: sensitive fields are left out
     */
    @SuppressWarnings("unchecked")
    public byte[] render(String formKey, Map<String, Object> definition, StoredSubmission submission, Map<String, Object> data,
            boolean receipt, String requestedLocale) {
        String locale = locale(definition, requestedLocale);
        FieldIndex fields = FieldIndex.of(definition);
        String title = AnswerText.text(definition.get("title"), locale);
        try (PdfWriter pdf = new PdfWriter(font, title + (receipt ? " — receipt" : "") + " · page")) {
            pdf.title(title.isBlank() ? formKey : title);
            pdf.meta((receipt ? "Receipt for your response" : "Submission") + " · reference " + submission.id());
            pdf.meta("Submitted " + time(definition, submission.submittedAt()) + " · form version " + submission.version()
                    + (submission.outcome() != null && !receipt ? " · outcome " + submission.outcome() : ""));
            if (!receipt && submission.subject() != null) {
                pdf.meta("Subject " + submission.subject());
            }
            String message = receipt ? outcomeMessage(definition, submission.outcome(), locale) : null;
            if (message != null) {
                pdf.gap(6).meta(message);
            }
            pdf.gap(14);
            for (AnswerField field : fields.answerFields()) {
                if (field.sensitive() && receipt) {
                    continue;
                }
                Object value = data.get(field.key());
                if (!"repeater".equals(field.type())) {
                    if (value != null) {
                        pdf.field(label(field, locale), AnswerText.value(field, value, locale), 0);
                    }
                    continue;
                }
                if (!(value instanceof List<?> items) || items.isEmpty()) {
                    continue;
                }
                pdf.heading(label(field, locale), 0);
                int n = 0;
                for (Object item : items) {
                    n++;
                    pdf.heading("#" + n, 12);
                    Map<String, Object> entry = item instanceof Map<?, ?> m ? (Map<String, Object>) Values.normalize(m) : Map.of();
                    for (AnswerField child : field.children()) {
                        if ((child.sensitive() && receipt) || entry.get(child.key()) == null) {
                            continue;
                        }
                        pdf.field(label(child, locale), AnswerText.value(child, entry.get(child.key()), locale), 12);
                    }
                }
            }
            return pdf.toBytes();
        }
    }
}
