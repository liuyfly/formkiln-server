package io.formkiln.server.expression;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Evaluates Formkiln expressions (operators-v1). Never throws for any input expression: invalid
 * operations evaluate to {@code null}.
 *
 * <p>Expressions and data must be canonical values (see {@link Values#normalize(Object)}).
 */
public final class ExpressionEvaluator {

    /** Options for one evaluation. All fields are optional. */
    public record Options(Map<String, Object> data, Map<String, Object> scope, Instant now, String timeZone) {

        public static Options empty() {
            return new Options(null, null, null, null);
        }
    }

    private record Context(Map<String, Object> data, Map<String, Object> scope, Supplier<Instant> now, String timeZone) {

        Context withElement(Object element, int index) {
            Map<String, Object> inner = new HashMap<>(scope);
            inner.put("$this", element);
            inner.put("$thisIndex", (double) index);
            return new Context(data, inner, now, timeZone);
        }
    }

    @FunctionalInterface
    private interface Operation extends BiFunction<List<Object>, Context, Object> {}

    private static final Pattern INDEX = Pattern.compile("0|[1-9]\\d*");
    private static final Map<String, Operation> OPERATIONS = new HashMap<>();

    private ExpressionEvaluator() {}

    /** Evaluates {@code expression} with the given options. */
    public static Object evaluate(Object expression, Options options) {
        Instant[] instant = new Instant[1];
        Supplier<Instant> now = () -> {
            if (instant[0] == null) {
                instant[0] = options.now() != null ? options.now() : Instant.now();
            }
            return instant[0];
        };
        Context ctx = new Context(
                options.data() != null ? options.data() : Map.of(),
                options.scope() != null ? options.scope() : Map.of(),
                now,
                options.timeZone() != null ? options.timeZone() : "UTC");
        return eval(expression, ctx);
    }

    /** A condition holds only when it evaluates to {@code true} (operators-v1 §1.2). */
    public static boolean isTrue(Object value) {
        return Boolean.TRUE.equals(value);
    }

    private static Object eval(Object expression, Context ctx) {
        if (expression instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(eval(item, ctx));
            }
            return result;
        }
        if (expression instanceof Map<?, ?> map) {
            if (map.size() != 1) {
                return null;
            }
            Map.Entry<?, ?> entry = map.entrySet().iterator().next();
            Operation operation = OPERATIONS.get(String.valueOf(entry.getKey()));
            if (operation == null) {
                return null;
            }
            List<Object> args = entry.getValue() instanceof List<?> raw
                    ? new ArrayList<>(raw)
                    : Collections.singletonList(entry.getValue());
            return operation.apply(args, ctx);
        }
        return expression;
    }

    private static List<Object> evalAll(List<Object> args, Context ctx) {
        List<Object> values = new ArrayList<>(args.size());
        for (Object arg : args) {
            values.add(eval(arg, ctx));
        }
        return values;
    }

    private static Object arg(List<Object> values, int index) {
        return index < values.size() ? values.get(index) : null;
    }

    private static void register(String name, Operation operation) {
        OPERATIONS.put(name, operation);
    }

    /** Registers an operation that evaluates all arguments eagerly. */
    private static void eager(String name, Function<List<Object>, Object> fn) {
        register(name, (args, ctx) -> fn.apply(evalAll(args, ctx)));
    }

    private static double[] numbers(List<Object> values) {
        if (values.isEmpty()) {
            return null;
        }
        double[] result = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            if (!Values.isNumber(values.get(i))) {
                return null;
            }
            result[i] = (Double) values.get(i);
        }
        return result;
    }

    private static void arithmetic(String name, Function<double[], Double> fn) {
        eager(name, values -> {
            double[] nums = numbers(values);
            if (nums == null) {
                return null;
            }
            Double result = fn.apply(nums);
            return result == null ? null : Values.finiteOrNull(result);
        });
    }

    private interface DoubleComparison {
        boolean test(double a, double b);
    }

    private static void ordering(String name, DoubleComparison compare) {
        eager(name, values -> {
            if (values.size() != 2 && values.size() != 3) {
                return false;
            }
            double[] n = numbers(values);
            if (n == null) {
                return false;
            }
            return compare.test(n[0], n[1]) && (n.length == 2 || compare.test(n[1], n[2]));
        });
    }

    private static Object resolvePath(String path, Context ctx) {
        if (path.isEmpty()) {
            return null;
        }
        String[] segments = path.split("\\.", -1);
        Object current = segments[0].startsWith("$") ? ctx.scope().get(segments[0]) : ctx.data().get(segments[0]);
        for (int i = 1; i < segments.length; i++) {
            String segment = segments[i];
            if (current instanceof List<?> list && INDEX.matcher(segment).matches()) {
                int index;
                try {
                    index = Integer.parseInt(segment);
                } catch (NumberFormatException e) {
                    return null;
                }
                current = index < list.size() ? list.get(index) : null;
            } else if (current instanceof Map<?, ?> map && map.containsKey(segment)) {
                current = map.get(segment);
            } else {
                return null;
            }
        }
        return current;
    }

    /** Evaluates {@code body} once per element, binding {@code $this} and {@code $thisIndex}. */
    private static List<Object> eachElement(List<?> list, Object body, Context ctx) {
        List<Object> results = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            results.add(eval(body, ctx.withElement(list.get(i), i)));
        }
        return results;
    }

    private static void quantifier(String name, boolean onNonArray, Function<List<Boolean>, Boolean> test) {
        register(name, (args, ctx) -> {
            if (args.size() != 2) {
                return null;
            }
            if (!(eval(args.get(0), ctx) instanceof List<?> list)) {
                return onNonArray;
            }
            return test.apply(eachElement(list, args.get(1), ctx).stream().map(ExpressionEvaluator::isTrue).toList());
        });
    }

    static {
        // §2 Data access
        register("var", (args, ctx) -> {
            List<Object> values = evalAll(args, ctx);
            Object value = arg(values, 0) instanceof String path ? resolvePath(path, ctx) : null;
            return value != null ? value : arg(values, 1);
        });

        // §3 Logic
        register("and", (args, ctx) -> {
            if (args.isEmpty()) {
                return false;
            }
            for (Object arg : args) {
                if (!isTrue(eval(arg, ctx))) {
                    return false;
                }
            }
            return true;
        });
        register("or", (args, ctx) -> {
            for (Object arg : args) {
                if (isTrue(eval(arg, ctx))) {
                    return true;
                }
            }
            return false;
        });
        eager("!", values -> values.size() == 1 && !isTrue(values.get(0)));
        register("if", (args, ctx) -> {
            int i = 0;
            for (; i + 1 < args.size(); i += 2) {
                if (isTrue(eval(args.get(i), ctx))) {
                    return eval(args.get(i + 1), ctx);
                }
            }
            return i < args.size() ? eval(args.get(i), ctx) : null;
        });

        // §4 Comparison
        eager("==", values -> values.size() == 2 && Values.deepEqual(values.get(0), values.get(1)));
        eager("!=", values -> values.size() == 2 && !Values.deepEqual(values.get(0), values.get(1)));
        ordering("<", (a, b) -> a < b);
        ordering("<=", (a, b) -> a <= b);
        ordering(">", (a, b) -> a > b);
        ordering(">=", (a, b) -> a >= b);

        // §5 Arithmetic
        arithmetic("+", n -> {
            double sum = 0;
            for (double x : n) {
                sum += x;
            }
            return sum;
        });
        arithmetic("-", n -> n.length == 1 ? -n[0] : n.length == 2 ? n[0] - n[1] : null);
        arithmetic("*", n -> {
            double product = 1;
            for (double x : n) {
                product *= x;
            }
            return product;
        });
        arithmetic("/", n -> n.length == 2 && n[1] != 0 ? n[0] / n[1] : null);
        arithmetic("%", n -> n.length == 2 && n[1] != 0 ? n[0] % n[1] : null);
        arithmetic("min", n -> {
            double min = n[0];
            for (double x : n) {
                min = Math.min(min, x);
            }
            return min;
        });
        arithmetic("max", n -> {
            double max = n[0];
            for (double x : n) {
                max = Math.max(max, x);
            }
            return max;
        });
        eager("round", values -> {
            if (values.isEmpty() || values.size() > 2 || !Values.isNumber(values.get(0))) {
                return null;
            }
            Object digits = values.size() == 2 ? values.get(1) : 0.0;
            if (!(digits instanceof Double d) || d != Math.rint(d) || d < 0 || d > 10) {
                return null;
            }
            return Numbers.roundHalfAwayFromZero((Double) values.get(0), d.intValue());
        });

        // §6 Emptiness
        eager("empty", values -> values.size() == 1 && Values.isEmpty(values.get(0)));

        // §7 Strings
        eager("cat", values -> {
            StringBuilder text = new StringBuilder();
            for (Object value : values) {
                if (value instanceof String s) {
                    text.append(s);
                } else if (Values.isNumber(value)) {
                    text.append(Numbers.format((Double) value));
                } else if (value != null) {
                    return null;
                }
            }
            return text.toString();
        });
        eager("length", values -> {
            if (values.size() > 1) {
                return null;
            }
            Object value = arg(values, 0);
            if (value == null) {
                return 0.0;
            }
            if (value instanceof String s) {
                return (double) s.codePointCount(0, s.length());
            }
            if (value instanceof List<?> list) {
                return (double) list.size();
            }
            return null;
        });
        eager("lower", values -> arg(values, 0) instanceof String s ? s.toLowerCase(Locale.ROOT) : null);
        eager("upper", values -> arg(values, 0) instanceof String s ? s.toUpperCase(Locale.ROOT) : null);
        eager("matches", values -> {
            if (!(arg(values, 1) instanceof String pattern)) {
                return null;
            }
            PortableRegex.Result compiled = PortableRegex.compile(pattern);
            if (!compiled.ok()) {
                return null;
            }
            return arg(values, 0) instanceof String text && compiled.matches(text);
        });

        // §8 Dates
        register("date.today", (args, ctx) -> args.isEmpty() ? Dates.today(ctx.now().get(), ctx.timeZone()) : null);
        eager("date.add", values -> {
            LocalDate date = Dates.parse(arg(values, 0));
            ChronoUnit unit = Dates.unit(arg(values, 2));
            if (date == null || unit == null || !(arg(values, 1) instanceof Double amount)
                    || amount != Math.rint(amount) || Math.abs(amount) > 1e7) {
                return null;
            }
            return Dates.add(date, amount.longValue(), unit);
        });
        eager("date.diff", values -> {
            LocalDate from = Dates.parse(arg(values, 0));
            LocalDate to = Dates.parse(arg(values, 1));
            ChronoUnit unit = Dates.unit(arg(values, 2));
            return from == null || to == null || unit == null ? null : Dates.diff(from, to, unit);
        });

        // §9 Arrays
        eager("in", values -> {
            Object needle = arg(values, 0);
            Object haystack = arg(values, 1);
            if (haystack instanceof List<?> list) {
                return list.stream().anyMatch(item -> Values.deepEqual(item, needle));
            }
            return haystack instanceof String text && needle instanceof String part && text.contains(part);
        });
        quantifier("some", false, results -> results.contains(true));
        quantifier("all", false, results -> !results.contains(false));
        quantifier("none", true, results -> !results.contains(true));
        register("map", (args, ctx) -> {
            if (args.size() != 2 || !(eval(args.get(0), ctx) instanceof List<?> list)) {
                return null;
            }
            return eachElement(list, args.get(1), ctx);
        });
        register("filter", (args, ctx) -> {
            if (args.size() != 2 || !(eval(args.get(0), ctx) instanceof List<?> list)) {
                return null;
            }
            List<Object> results = eachElement(list, args.get(1), ctx);
            List<Object> kept = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                if (isTrue(results.get(i))) {
                    kept.add(list.get(i));
                }
            }
            return kept;
        });
        eager("sum", values -> {
            if (values.size() != 1 || !(values.get(0) instanceof List<?> list)) {
                return null;
            }
            double sum = 0;
            for (Object item : list) {
                if (!Values.isNumber(item)) {
                    return null;
                }
                sum += (Double) item;
            }
            return Values.finiteOrNull(sum);
        });
    }
}
