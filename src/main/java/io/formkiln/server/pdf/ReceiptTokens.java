package io.formkiln.server.pdf;

import io.formkiln.server.accounts.Secrets;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Short-lived tokens that let the person who just submitted download their receipt:
 * {@code base64url(json{f, s, exp}).base64url(HMAC)}, bound to one form and submission.
 */
@Component
public class ReceiptTokens {

    public static final Duration TTL = Duration.ofHours(1);
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final Secrets secrets;
    private final JsonMapper json;
    private final Clock clock;

    public ReceiptTokens(Secrets secrets, JsonMapper json, Clock clock) {
        this.secrets = secrets;
        this.json = json;
        this.clock = clock;
    }

    private byte[] mac(String body) {
        return secrets.mac("receipt-token", body.getBytes(StandardCharsets.US_ASCII));
    }

    public String issue(String formKey, UUID submissionId) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("f", formKey);
        claims.put("s", submissionId.toString());
        claims.put("exp", clock.instant().plus(TTL).getEpochSecond());
        String body = ENCODER.encodeToString(json.writeValueAsBytes(claims));
        return body + "." + ENCODER.encodeToString(mac(body));
    }

    /** The submission id when the token is valid for {@code formKey} and not expired. */
    public Optional<UUID> verify(String token, String formKey) {
        if (token == null || token.length() > 512) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0) {
            return Optional.empty();
        }
        String body = token.substring(0, dot);
        try {
            byte[] presented = Base64.getUrlDecoder().decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(presented, mac(body))) {
                return Optional.empty();
            }
            Map<?, ?> claims = json.readValue(Base64.getUrlDecoder().decode(body), Map.class);
            Instant expires = Instant.ofEpochSecond(((Number) claims.get("exp")).longValue());
            if (!formKey.equals(claims.get("f")) || !clock.instant().isBefore(expires)) {
                return Optional.empty();
            }
            return Optional.of(UUID.fromString((String) claims.get("s")));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
