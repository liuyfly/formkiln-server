package io.formkiln.server.accounts;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Time-based one-time passwords (RFC 6238: HMAC-SHA1, 6 digits, 30-second steps). */
public final class Totp {

    public static final int SECRET_BYTES = 20;
    private static final int DIGITS = 6;
    private static final long STEP_SECONDS = 30;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Totp() {}

    public static byte[] newSecret() {
        return Secrets.randomBytes(SECRET_BYTES);
    }

    /** The code for a given time step counter. */
    static String code(byte[] secret, long counter, int digits) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hmac = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int offset = hmac[hmac.length - 1] & 0x0f;
            int binary = ((hmac[offset] & 0x7f) << 24)
                    | ((hmac[offset + 1] & 0xff) << 16)
                    | ((hmac[offset + 2] & 0xff) << 8)
                    | (hmac[offset + 3] & 0xff);
            int modulo = (int) Math.pow(10, digits);
            return String.format("%0" + digits + "d", binary % modulo);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String code(byte[] secret, Instant at) {
        return code(secret, Math.floorDiv(at.getEpochSecond(), STEP_SECONDS), DIGITS);
    }

    /** Accepts the code of the current step and of one step on either side (clock drift). */
    public static boolean verify(byte[] secret, String code, Instant now) {
        if (code == null) {
            return false;
        }
        String normalized = code.replace(" ", "");
        if (!normalized.matches("\\d{6}")) {
            return false;
        }
        long counter = Math.floorDiv(now.getEpochSecond(), STEP_SECONDS);
        boolean match = false;
        for (long step = counter - 1; step <= counter + 1; step++) {
            // Compare every candidate in constant time; do not stop early.
            match |= MessageDigest.isEqual(
                    code(secret, step, DIGITS).getBytes(StandardCharsets.US_ASCII),
                    normalized.getBytes(StandardCharsets.US_ASCII));
        }
        return match;
    }

    public static String base32(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 0x1f));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 0x1f));
        }
        return out.toString();
    }

    /** {@code otpauth://} URI for authenticator apps (rendered as a QR code by the admin UI). */
    public static String uri(String issuer, String account, byte[] secret) {
        String label = URLEncoder.encode(issuer + ":" + account, StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/" + label
                + "?secret=" + base32(secret)
                + "&issuer=" + URLEncoder.encode(issuer, StandardCharsets.UTF_8).replace("+", "%20")
                + "&algorithm=SHA1&digits=6&period=30";
    }
}
