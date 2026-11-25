package io.formkiln.server.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretsTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void roundTripsAndUsesAFreshIv() {
        Secrets secrets = new Secrets(KEY);
        byte[] plain = "totp-secret".getBytes(StandardCharsets.UTF_8);
        byte[] a = secrets.encrypt(plain);
        byte[] b = secrets.encrypt(plain);
        assertThat(a).isNotEqualTo(b);
        assertThat(secrets.decrypt(a)).isEqualTo(plain);
    }

    @Test
    void detectsTampering() {
        Secrets secrets = new Secrets(KEY);
        byte[] sealed = secrets.encrypt(new byte[] {1, 2, 3});
        sealed[sealed.length - 1] ^= 1;
        assertThatThrownBy(() -> secrets.decrypt(sealed)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requiresA32ByteKey() {
        assertThatThrownBy(() -> new Secrets("")).hasMessageContaining("FORMKILN_ENCRYPTION_KEY");
        assertThatThrownBy(() -> new Secrets(Base64.getEncoder().encodeToString(new byte[16])))
                .hasMessageContaining("32 bytes");
    }

    @Test
    void tokensAreUniqueAndHashesStable() {
        assertThat(Secrets.newToken()).isNotEqualTo(Secrets.newToken()).hasSize(43);
        assertThat(Secrets.hash("abc")).isEqualTo(Secrets.hash("abc"));
    }
}
