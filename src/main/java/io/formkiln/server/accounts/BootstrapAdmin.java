package io.formkiln.server.accounts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Invites FORMKILN_BOOTSTRAP_ADMIN_EMAIL as the first admin when there are no members yet. The
 * invitation link is only emailed, never logged.
 */
@Component
public class BootstrapAdmin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

    private final AccountService accounts;
    private final String email;

    public BootstrapAdmin(AccountService accounts, @Value("${formkiln.bootstrap-admin-email:}") String email) {
        this.accounts = accounts;
        this.email = email;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email == null || email.isBlank()) {
            return;
        }
        try {
            if (accounts.bootstrap(email)) {
                log.info("Sent the first admin invitation to the bootstrap email address");
            }
        } catch (RuntimeException e) {
            log.warn("Could not send the first admin invitation: {}", e.getMessage());
        }
    }
}
