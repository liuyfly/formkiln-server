package io.formkiln.server.platform;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * SSRF protection for webhook URLs: HTTPS only, and no private, loopback, link-local, carrier-grade
 * NAT or multicast destinations. Both can be relaxed for development.
 */
@Component
public class UrlGuard {

    private final boolean allowHttp;
    private final boolean allowPrivate;

    public UrlGuard(
            @Value("${formkiln.webhooks.allow-http:false}") boolean allowHttp,
            @Value("${formkiln.webhooks.allow-private-networks:false}") boolean allowPrivate) {
        this.allowHttp = allowHttp;
        this.allowPrivate = allowPrivate;
    }

    /** Checks the URL's shape when an endpoint is created; returns a reason or null. */
    public String checkSyntax(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return "The URL is not valid.";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("https") && !(allowHttp && scheme.equals("http"))) {
            return "Webhook URLs must use https.";
        }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            return "The URL must have a host and no credentials or fragment.";
        }
        if (url.length() > 2048) {
            return "The URL is too long.";
        }
        return null;
    }

    /** Resolves the host at delivery time; returns a reason when any address is not allowed. */
    public String checkDestination(URI uri) {
        if (allowPrivate) {
            return null;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (isInternal(address)) {
                    return "The webhook host resolves to a private or reserved address.";
                }
            }
            return null;
        } catch (UnknownHostException e) {
            return "The webhook host cannot be resolved.";
        }
    }

    static boolean isInternal(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first == 0 // "this" network
                    || (first == 100 && second >= 64 && second <= 127) // carrier-grade NAT
                    || (first == 192 && second == 0 && (bytes[2] & 0xff) == 0) // IETF protocol assignments
                    || (first == 198 && (second == 18 || second == 19)) // benchmarking
                    || first >= 240; // reserved and broadcast
        }
        if (address instanceof Inet6Address) {
            return (bytes[0] & 0xfe) == 0xfc; // unique local fc00::/7
        }
        return false;
    }
}
