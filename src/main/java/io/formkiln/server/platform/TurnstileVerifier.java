package io.formkiln.server.platform;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cloudflare Turnstile. Enabled when FORMKILN_TURNSTILE_SECRET and FORMKILN_TURNSTILE_SITE_KEY are
 * set. Only the token, the secret and the client IP are sent to Cloudflare, never form data.
 */
@Component
public class TurnstileVerifier implements CaptchaVerifier {

    private static final Logger log = LoggerFactory.getLogger(TurnstileVerifier.class);
    private static final URI VERIFY = URI.create("https://challenges.cloudflare.com/turnstile/v0/siteverify");

    private final String secret;
    private final String siteKey;
    private final JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public TurnstileVerifier(
            @Value("${formkiln.turnstile.secret:}") String secret,
            @Value("${formkiln.turnstile.site-key:}") String siteKey,
            JsonMapper json) {
        this.secret = secret;
        this.siteKey = siteKey;
        this.json = json;
    }

    @Override
    public boolean enabled() {
        return !secret.isBlank() && !siteKey.isBlank();
    }

    @Override
    public String provider() {
        return "turnstile";
    }

    @Override
    public String siteKey() {
        return siteKey;
    }

    @Override
    public boolean verify(String token, String clientIp) {
        if (token == null || token.isBlank() || token.length() > 2048) {
            return false;
        }
        String form = "secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8)
                + "&response=" + URLEncoder.encode(token, StandardCharsets.UTF_8)
                + (clientIp == null ? "" : "&remoteip=" + URLEncoder.encode(clientIp, StandardCharsets.UTF_8));
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(VERIFY)
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            Map<?, ?> result = json.readValue(response.body(), Map.class);
            return Boolean.TRUE.equals(result.get("success"));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // Fail closed: without a verdict the submission is rejected and the user can retry.
            log.warn("Turnstile verification failed: {}", e.getClass().getSimpleName());
            return false;
        }
    }
}
