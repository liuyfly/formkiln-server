package io.formkiln.server.expression;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Portable regular expressions (operators-v1 §7.1).
 *
 * <p>Patterns are parsed against the portable subset and translated into an equivalent {@code
 * java.util.regex} pattern, so that {@code \s}, {@code .} and {@code $} mean exactly the same as
 * in the TypeScript engine. Anything outside the subset is rejected. Matching is always a full
 * match.
 */
public final class PortableRegex {

    /** The result of compiling a portable pattern: either a pattern or an error message. */
    public record Result(Pattern pattern, String error) {

        public boolean ok() {
            return pattern != null;
        }

        /** Full match of the whole input. */
        public boolean matches(String input) {
            return pattern.matcher(input).matches();
        }
    }

    private static final String WHITESPACE = " \\t\\n\\r\\f\\x0B";
    private static final Map<Integer, String> CLASS_ESCAPES =
            Map.of((int) 'd', "0-9", (int) 'w', "A-Za-z0-9_", (int) 's', WHITESPACE);
    private static final Map<Integer, String> SHORTHANDS = Map.of(
            (int) 'd', "[0-9]",
            (int) 'D', "[^0-9]",
            (int) 'w', "[A-Za-z0-9_]",
            (int) 'W', "[^A-Za-z0-9_]",
            (int) 's', "[" + WHITESPACE + "]",
            (int) 'S', "[^" + WHITESPACE + "]");
    private static final Set<Integer> LITERAL_ESCAPES = codePoints("\\.+*?()[]{}|^$/-");
    private static final Set<Integer> CLASS_LITERAL_ESCAPES = codePoints("\\]^-[");
    private static final Set<Integer> SYNTAX = codePoints("\\^$.|?*+()[]{}");
    private static final Set<Integer> CLASS_SYNTAX = codePoints("\\]^-[&");
    private static final Pattern BOUNDED = Pattern.compile("\\{(\\d+)(,(\\d*))?}");
    private static final Map<String, Result> CACHE = new ConcurrentHashMap<>();

    private PortableRegex() {}

    /** Compiles a portable pattern. Results are cached. */
    public static Result compile(String pattern) {
        return CACHE.computeIfAbsent(pattern, PortableRegex::translate);
    }

    private static Set<Integer> codePoints(String chars) {
        return Set.copyOf(chars.codePoints().boxed().toList());
    }

    private static String literal(int c) {
        String text = Character.toString(c);
        return SYNTAX.contains(c) ? "\\" + text : text;
    }

    private static String classLiteral(int c) {
        String text = Character.toString(c);
        return CLASS_SYNTAX.contains(c) ? "\\" + text : text;
    }

    private static Result fail(String pattern, int position, String message) {
        return new Result(null, message + " at position " + position + " in pattern \"" + pattern + "\"");
    }

    private static Result translate(String pattern) {
        int[] chars = pattern.codePoints().toArray();
        StringBuilder out = new StringBuilder();
        int i = 0;
        int depth = 0;
        boolean quantifiable = false;

        while (i < chars.length) {
            int c = chars[i];
            switch (c) {
                case '\\' -> {
                    if (i + 1 >= chars.length) {
                        return fail(pattern, i, "Trailing backslash");
                    }
                    int next = chars[i + 1];
                    if (SHORTHANDS.containsKey(next)) {
                        out.append(SHORTHANDS.get(next));
                    } else if (LITERAL_ESCAPES.contains(next)) {
                        out.append('\\').appendCodePoint(next);
                    } else {
                        return fail(pattern, i, "Unsupported escape \\" + Character.toString(next));
                    }
                    i += 2;
                    quantifiable = true;
                }
                case '[' -> {
                    ClassResult parsed = parseClass(chars, i);
                    if (parsed.error() != null) {
                        return fail(pattern, i, parsed.error());
                    }
                    out.append(parsed.source());
                    i = parsed.end();
                    quantifiable = true;
                }
                case '(' -> {
                    if (i + 1 < chars.length && chars[i + 1] == '?') {
                        if (i + 2 >= chars.length || chars[i + 2] != ':') {
                            return fail(pattern, i, "Only (?:...) groups are supported");
                        }
                        out.append("(?:");
                        i += 3;
                    } else {
                        out.append('(');
                        i += 1;
                    }
                    depth++;
                    quantifiable = false;
                }
                case ')' -> {
                    if (depth == 0) {
                        return fail(pattern, i, "Unbalanced )");
                    }
                    depth--;
                    out.append(')');
                    i += 1;
                    quantifiable = true;
                }
                case '|' -> {
                    out.append('|');
                    i += 1;
                    quantifiable = false;
                }
                case '^' -> {
                    out.append('^');
                    i += 1;
                    quantifiable = false;
                }
                case '$' -> {
                    // Very end of the input, never before a trailing line terminator.
                    out.append("\\z");
                    i += 1;
                    quantifiable = false;
                }
                case '.' -> {
                    out.append("[^\\n\\r]");
                    i += 1;
                    quantifiable = true;
                }
                case '*', '+', '?', '{' -> {
                    if (!quantifiable) {
                        return fail(pattern, i, "Nothing to repeat before " + Character.toString(c));
                    }
                    String quantifier = Character.toString(c);
                    int end = i + 1;
                    if (c == '{') {
                        Matcher matcher = BOUNDED.matcher(new String(chars, i, chars.length - i));
                        if (!matcher.lookingAt()) {
                            return fail(pattern, i, "Invalid {n,m} quantifier");
                        }
                        String max = matcher.group(3);
                        if (max != null && !max.isEmpty()
                                && Long.parseLong(max) < Long.parseLong(matcher.group(1))) {
                            return fail(pattern, i, "Quantifier maximum is smaller than its minimum");
                        }
                        quantifier = matcher.group();
                        end = i + quantifier.length();
                    }
                    if (end < chars.length && (chars[end] == '?' || chars[end] == '+')) {
                        return fail(pattern, i, "Lazy and possessive quantifiers are not supported");
                    }
                    out.append(quantifier);
                    i = end;
                    quantifiable = false;
                }
                case ']', '}' -> {
                    return fail(pattern, i, "Unescaped " + Character.toString(c));
                }
                default -> {
                    out.append(literal(c));
                    i += 1;
                    quantifiable = true;
                }
            }
        }
        if (depth != 0) {
            return fail(pattern, i, "Unbalanced (");
        }
        try {
            return new Result(Pattern.compile("(?:" + out + ")"), null);
        } catch (PatternSyntaxException e) {
            return fail(pattern, i, "Invalid pattern (" + e.getDescription() + ")");
        }
    }

    private record ClassResult(String source, int end, String error) {

        static ClassResult error(String message) {
            return new ClassResult(null, -1, message);
        }
    }

    /** One element of a character class body. */
    private record Atom(Kind kind, int value, String source) {

        enum Kind { CHAR, SHORTHAND, HYPHEN }
    }

    private static ClassResult parseClass(int[] chars, int start) {
        int i = start + 1;
        boolean negated = i < chars.length && chars[i] == '^';
        if (negated) {
            i++;
        }

        // 1. Tokenise the class body.
        List<Atom> atoms = new ArrayList<>();
        boolean closed = false;
        while (i < chars.length) {
            int c = chars[i];
            if (c == ']') {
                if (atoms.isEmpty()) {
                    return ClassResult.error("Empty character class");
                }
                closed = true;
                i++;
                break;
            }
            if (c == '[') {
                return ClassResult.error("Nested [ inside a character class");
            }
            if (c == '&' && i + 1 < chars.length && chars[i + 1] == '&') {
                return ClassResult.error("&& inside a character class");
            }
            if (c == '\\') {
                if (i + 1 >= chars.length) {
                    return ClassResult.error("Trailing backslash");
                }
                int next = chars[i + 1];
                if (CLASS_ESCAPES.containsKey(next)) {
                    atoms.add(new Atom(Atom.Kind.SHORTHAND, 0, CLASS_ESCAPES.get(next)));
                } else if (CLASS_LITERAL_ESCAPES.contains(next)) {
                    atoms.add(new Atom(Atom.Kind.CHAR, next, null));
                } else {
                    return ClassResult.error(
                            "Unsupported escape \\" + Character.toString(next) + " inside a character class");
                }
                i += 2;
            } else {
                atoms.add(c == '-' ? new Atom(Atom.Kind.HYPHEN, 0, null) : new Atom(Atom.Kind.CHAR, c, null));
                i++;
            }
        }
        if (!closed) {
            return ClassResult.error("Unterminated character class");
        }

        // 2. Emit, turning char-hyphen-char into ranges and other hyphens into literals.
        StringBuilder source = new StringBuilder(negated ? "[^" : "[");
        for (int k = 0; k < atoms.size(); k++) {
            Atom atom = atoms.get(k);
            Atom hyphen = k + 1 < atoms.size() ? atoms.get(k + 1) : null;
            Atom high = k + 2 < atoms.size() ? atoms.get(k + 2) : null;
            if (atom.kind() == Atom.Kind.CHAR && hyphen != null && hyphen.kind() == Atom.Kind.HYPHEN
                    && high != null && high.kind() == Atom.Kind.CHAR) {
                if (atom.value() > high.value()) {
                    return ClassResult.error("Range out of order");
                }
                source.append(classLiteral(atom.value())).append('-').append(classLiteral(high.value()));
                k += 2;
            } else if (atom.kind() == Atom.Kind.HYPHEN) {
                source.append("\\-");
            } else if (atom.kind() == Atom.Kind.SHORTHAND) {
                source.append(atom.source());
            } else {
                source.append(classLiteral(atom.value()));
            }
        }
        return new ClassResult(source.append(']').toString(), i, null);
    }
}
