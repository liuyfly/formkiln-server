package io.formkiln.server.platform;

import io.formkiln.server.workspaces.WorkspaceContext;
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
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Short-lived tokens that let an embedding application's users access a {@code token}-mode form:
 * {@code base64url(payload).base64url(HMAC-SHA256)}, signed with a key derived from
 * FORMKILN_ENCRYPTION_KEY. The payload holds the workspace id, the form key, an optional subject and the expiry; a
 * token is only valid in the workspace of the request that issued it.
 */
@Component
public class EmbedTokens {

    public static final Duration DEFAULT_TTL = Duration.ofMinutes(15);
    public static final Duration MAX_TTL = Duration.ofHours(1);
    private static final String LABEL = "formkiln-embed-token-v1";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    /** A verified token: the subject (may be null) it was issued for. */
    public record Claims(String formKey, String subject, Instant expiresAt) {}

    private final Secrets secrets;
    private final JsonMapper json;
    private final Clock clock;

    public EmbedTokens(Secrets secrets, JsonMapper json, Clock clock) {
        this.secrets = secrets;
        this.json = json;
        this.clock = clock;
    }

    public Claims issue(String formKey, String subject, Duration ttl) {
        Instant expiresAt = clock.instant().plus(ttl);
        return new Claims(formKey, subject, expiresAt);
    }

    public String encode(Claims claims) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("w", WorkspaceContext.id().toString());
        payload.put("f", claims.formKey());
        if (claims.subject() != null) {
            payload.put("s", claims.subject());
        }
        payload.put("exp", claims.expiresAt().getEpochSecond());
        String body = ENCODER.encodeToString(json.writeValueAsBytes(payload));
        String signature = ENCODER.encodeToString(secrets.mac(LABEL, body.getBytes(StandardCharsets.US_ASCII)));
        return body + "." + signature;
    }

    /** The claims of a valid, unexpired token for {@code formKey}. */
    @SuppressWarnings("unchecked")
    public Optional<Claims> verify(String token, String formKey) {
        if (token == null) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.')) {
            return Optional.empty();
        }
        String body = token.substring(0, dot);
        byte[] expected = secrets.mac(LABEL, body.getBytes(StandardCharsets.US_ASCII));
        byte[] presented;
        try {
            presented = Base64.getUrlDecoder().decode(token.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(expected, presented)) {
            return Optional.empty();
        }
        Map<String, Object> payload;
        try {
            payload = json.readValue(Base64.getUrlDecoder().decode(body), Map.class);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        Instant expiresAt = Instant.ofEpochSecond(((Number) payload.get("exp")).longValue());
        if (!WorkspaceContext.id().toString().equals(payload.get("w")) || !formKey.equals(payload.get("f"))
                || !expiresAt.isAfter(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(new Claims(formKey, (String) payload.get("s"), expiresAt));
    }
}
