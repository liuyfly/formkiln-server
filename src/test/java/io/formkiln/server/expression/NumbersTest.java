package io.formkiln.server.expression;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Number formatting must equal ECMAScript Number.prototype.toString(). */
class NumbersTest {

    @ParameterizedTest(name = "format({0}) = {1}")
    @CsvSource({
        "1.0, 1",
        "-1.5, -1.5",
        "0.1, 0.1",
        "100, 100",
        "123456789012345680000, 123456789012345680000",
        "1e21, 1e+21",
        "1.5e21, 1.5e+21",
        "0.000001, 0.000001",
        "1e-7, 1e-7",
        "1.25e-7, 1.25e-7",
        "0.30000000000000004, 0.30000000000000004",
        "-0.0, 0",
        "5e-324, 5e-324",
        "1.7976931348623157e308, 1.7976931348623157e+308",
    })
    void formatsLikeEcmaScript(double value, String expected) {
        assertThat(Numbers.format(value)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "round({0}, {1}) = {2}")
    @CsvSource({
        "1.005, 2, 1.01",
        "2.5, 0, 3",
        "-2.5, 0, -3",
        "0.5, 0, 1",
        "-0.4, 0, 0",
        "9.995, 2, 10",
        "99.5, 0, 100",
        "0.0004, 2, 0",
        "1e-7, 2, 0",
        "1.5e-7, 7, 2e-7",
        "123.456, 1, 123.5",
        "1e21, 2, 1e21",
        "42, 3, 42",
    })
    void roundsHalfAwayFromZero(double value, int digits, double expected) {
        assertThat(Numbers.roundHalfAwayFromZero(value, digits)).isEqualTo(expected);
    }
}
