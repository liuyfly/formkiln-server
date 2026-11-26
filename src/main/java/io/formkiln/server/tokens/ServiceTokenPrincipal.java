package io.formkiln.server.tokens;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** A request authenticated by a workspace service token. */
public record ServiceTokenPrincipal(UUID tokenId, UUID workspaceId, String role, String name) {

    /** The id of the service token behind the current request, for the audit log; null otherwise. */
    public static UUID currentId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof ServiceTokenPrincipal token ? token.tokenId() : null;
    }
}
