package io.formkiln.server.expression;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** Number formatting and rounding that match the TypeScript engine exactly. */
final class Numbers {

    private Numbers() {}

    /**
     * Formats a finite double like ECMAScript {@code Number.prototype.toString()} (operators-v1
     * §1.4): {@code 1} not {@code 1.0}, {@code 1e+21}, {@code 1e-7}.
     *
     * <p>{@link Double#toString(double)} yields the shortest decimal that round-trips (JDK 19+),
     * which provides the same digits as ECMAScript; only the layout differs.
     */
    static String format(double value) {
        if (value == 0) {
            return "0";
        }
        BigDecimal decimal = shortest(Math.abs(value));
        String digits = decimal.unscaledValue().toString();
        int k = digits.length();
        // value = 0.d1d2...dk × 10^n
        int n = k - decimal.scale();
        StringBuilder out = new StringBuilder(value < 0 ? "-" : "");
        if (k <= n && n <= 21) {
            out.append(digits).append("0".repeat(n - k));
        } else if (0 < n && n <= 21) {
            out.append(digits, 0, n).append('.').append(digits, n, k);
        } else if (-6 < n && n <= 0) {
            out.append("0.").append("0".repeat(-n)).append(digits);
        } else {
            int exponent = n - 1;
            out.append(digits.charAt(0));
            if (k > 1) {
                out.append('.').append(digits, 1, k);
            }
            out.append('e').append(exponent >= 0 ? "+" : "-").append(Math.abs(exponent));
        }
        return out.toString();
    }

    /**
     * The shortest decimal that round-trips to {@code value}. {@link Double#toString(double)} is
     * shortest except that it always prints at least two digits, so a one-digit candidate is
     * tried first (ECMAScript picks the closest one-digit decimal when one round-trips).
     */
    private static BigDecimal shortest(double value) {
        BigDecimal oneDigit = new BigDecimal(value).round(new MathContext(1, RoundingMode.HALF_EVEN));
        if (oneDigit.doubleValue() == value) {
            return oneDigit.stripTrailingZeros();
        }
        return new BigDecimal(Double.toString(value)).stripTrailingZeros();
    }

    /**
     * Rounds half away from zero on the shortest decimal representation (operators-v1 §5), so
     * {@code round(1.005, 2)} is {@code 1.01}.
     */
    static double roundHalfAwayFromZero(double value, int digits) {
        double result = shortest(Math.abs(value))
                .multiply(value < 0 ? BigDecimal.ONE.negate() : BigDecimal.ONE)
                .setScale(digits, RoundingMode.HALF_UP)
                .doubleValue();
        return result == 0 ? 0.0 : result;
    }
}
