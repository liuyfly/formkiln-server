package io.formkiln.server.expression;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Mirrors packages/core/src/expression/regex.test.ts in the formkiln repository. */
class PortableRegexTest {

    @ParameterizedTest(name = "{0} on {1} is {2}")
    @CsvSource(delimiter = ';', value = {
        "\\d{2}-?\\d{2}-?\\d{2};12-34-56;true",
        "\\d{6};123456x;false",
        "[A-Z]{2}\\d;AB1;true",
        "[a-c\\-]+;a-c;true",
        "[-a];-;true",
        "[\\d.]+;1.5;true",
        "[^\\s]+;a\u00a0b;true",
        "\\s;\u00a0;false",
        "a.c;a\u0085c;true",
        "(?:ab)+;abab;true",
        "(a|b)c;bc;true",
        "\\$\\d+(\\.\\d{2})?;$10.50;true",
        ".;\uD83D\uDE00;true",
        "&;&;true",
    })
    void matches(String pattern, String input, boolean expected) {
        PortableRegex.Result result = PortableRegex.compile(pattern);
        assertThat(result.error()).isNull();
        assertThat(result.matches(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "\\b", "(?=a)", "(?<name>a)", "a+?", "a++", "(a)\\1", "[a[b]]", "[a&&b]", "[\\S]",
        "[z-a]", "a{3,1}", "(a", "a]", "*a", "\\u0041", "[]",
    })
    void rejects(String pattern) {
        assertThat(PortableRegex.compile(pattern).ok()).isFalse();
    }

    @org.junit.jupiter.api.Test
    void newlineIsNotMatchedByDotOrDollar() {
        assertThat(PortableRegex.compile("a.c").matches("a\nc")).isFalse();
        assertThat(PortableRegex.compile("ab$").matches("ab\n")).isFalse();
    }
}
