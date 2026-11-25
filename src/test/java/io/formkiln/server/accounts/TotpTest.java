package io.formkiln.server.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TotpTest {

    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    /** RFC 6238 Appendix B test vectors (SHA-1, 8 digits). */
    @ParameterizedTest(name = "T={0} -> {1}")
    @CsvSource({
        "59, 94287082",
        "1111111109, 07081804",
        "1111111111, 14050471",
        "1234567890, 89005924",
        "2000000000, 69279037",
        "20000000000, 65353130",
    })
    void matchesRfc6238(long seconds, String expected) {
        assertThat(Totp.code(RFC_SECRET, seconds / 30, 8)).isEqualTo(expected);
    }

    @Test
    void acceptsOneStepOfDriftOnly() {
        Instant now = Instant.parse("2026-11-25T10:00:15Z");
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, now), now)).isTrue();
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, now.minusSeconds(30)), now)).isTrue();
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, now.plusSeconds(30)), now)).isTrue();
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, now.minusSeconds(90)), now)).isFalse();
        assertThat(Totp.verify(RFC_SECRET, "12a456", now)).isFalse();
        assertThat(Totp.verify(RFC_SECRET, null, now)).isFalse();
    }

    @Test
    void encodesBase32AndOtpauthUris() {
        assertThat(Totp.base32("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
        assertThat(Totp.uri("Formkiln", "ada@example.com", "foobar".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo("otpauth://totp/Formkiln%3Aada%40example.com?secret=MZXW6YTBOI&issuer=Formkiln"
                        + "&algorithm=SHA1&digits=6&period=30");
    }
}
