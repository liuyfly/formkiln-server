package io.formkiln.server.notifications;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain-text templates with {@code {{name}}} placeholders. Unknown placeholders become empty.
 * No HTML: messages are plain text, so values need no escaping, except that subjects are kept
 * on one line (no header injection).
 */
public final class MailTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.]+)\\s*}}");
    public static final int MAX_SUBJECT = 200;

    private MailTemplate() {}

    public static String render(String template, Map<String, String> values) {
        if (template == null) {
            return "";
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(values.getOrDefault(matcher.group(1), "")));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** A rendered subject on one line, at most {@link #MAX_SUBJECT} characters. */
    public static String subject(String template, Map<String, String> values) {
        String line = render(template, values).replaceAll("[\\r\\n\\t]+", " ").strip();
        return line.length() > MAX_SUBJECT ? line.substring(0, MAX_SUBJECT - 1) + "…" : line;
    }

    /** a***@example.com */
    public static String mask(String address) {
        int at = address.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return address.charAt(0) + "***" + address.substring(at);
    }
}
