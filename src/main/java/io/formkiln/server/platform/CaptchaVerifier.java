package io.formkiln.server.platform;

/** Verifies human-verification tokens on public submissions. Disabled unless configured. */
public interface CaptchaVerifier {

    boolean enabled();

    /** Provider name shown to renderers, for example {@code turnstile}. */
    String provider();

    /** Public site key for the widget. */
    String siteKey();

    boolean verify(String token, String clientIp);
}
