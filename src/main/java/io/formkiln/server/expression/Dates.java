package io.formkiln.server.expression;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Calendar date helpers for {@code YYYY-MM-DD} values (operators-v1 §8). */
public final class Dates {

    private static final Pattern DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");

    private Dates() {}

    /** Parses a valid date between 0001-01-01 and 9999-12-31, or returns {@code null}. */
    public static LocalDate parse(Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        Matcher matcher = DATE.matcher(text);
        if (!matcher.matches()) {
            return null;
        }
        int year = Integer.parseInt(matcher.group(1));
        if (year < 1) {
            return null;
        }
        try {
            return LocalDate.of(
                    year, Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
        } catch (DateTimeException e) {
            return null;
        }
    }

    static String format(LocalDate date) {
        if (date.getYear() < 1 || date.getYear() > 9999) {
            return null;
        }
        return "%04d-%02d-%02d".formatted(date.getYear(), date.getMonthValue(), date.getDayOfMonth());
    }

    static ChronoUnit unit(Object value) {
        if (!(value instanceof String name)) {
            return null;
        }
        return switch (name) {
            case "day" -> ChronoUnit.DAYS;
            case "week" -> ChronoUnit.WEEKS;
            case "month" -> ChronoUnit.MONTHS;
            case "year" -> ChronoUnit.YEARS;
            default -> null;
        };
    }

    static String add(LocalDate date, long amount, ChronoUnit unit) {
        try {
            return format(date.plus(amount, unit));
        } catch (DateTimeException | ArithmeticException e) {
            return null;
        }
    }

    /** Complete units from {@code from} to {@code to} ({@link LocalDate#until}). */
    static double diff(LocalDate from, LocalDate to, ChronoUnit unit) {
        return Values.finiteOrNull(unit.between(from, to));
    }

    /** The calendar date of {@code instant} in {@code timeZone}, or {@code null} if unknown. */
    static String today(Instant instant, String timeZone) {
        try {
            return format(LocalDate.ofInstant(instant, ZoneId.of(timeZone)));
        } catch (DateTimeException e) {
            return null;
        }
    }
}
