package io.formkiln.server.workspaces;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The workspace a request acts on, resolved from the slug in its URL by the security filter
 * chain. Every query that finds form data by key reads it, and fails without it, so a request can
 * never reach another workspace's forms by naming their key.
 */
public final class WorkspaceContext {

    /** The workspace of the request and the caller's role in it (null on the public runtime). */
    public record Current(UUID id, String slug, String name, boolean archived, String role) {}

    private static final String ATTRIBUTE = WorkspaceContext.class.getName();

    private WorkspaceContext() {}

    public static void set(HttpServletRequest request, Current current) {
        request.setAttribute(ATTRIBUTE, current);
    }

    public static Optional<Current> current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        Object value = attributes == null ? null : attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return Optional.ofNullable((Current) value);
    }

    /** The workspace of the current request; fails closed outside one. */
    public static Current require() {
        return current().orElseThrow(() -> new IllegalStateException("No workspace in this request"));
    }

    public static UUID id() {
        return require().id();
    }
}
