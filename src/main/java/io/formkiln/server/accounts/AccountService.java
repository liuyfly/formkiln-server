package io.formkiln.server.accounts;

import io.formkiln.server.accounts.AccountRepository.AuditEntry;
import io.formkiln.server.accounts.AccountRepository.Challenge;
import io.formkiln.server.accounts.AccountRepository.Invitation;
import io.formkiln.server.accounts.AccountRepository.Reset;
import io.formkiln.server.accounts.AccountRepository.Session;
import io.formkiln.server.accounts.AccountRepository.User;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Invitation-only admin accounts: email + password (Argon2id) + mandatory TOTP, server-side
 * sessions, lockout, password reset, member management and an audit log.
 */
@Service
public class AccountService {

    public static final Set<String> ROLES = Set.of("admin", "editor", "viewer");

    static final Duration INVITATION_TTL = Duration.ofHours(72);
    static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);
    static final Duration RESET_TTL = Duration.ofMinutes(30);
    static final Duration SESSION_IDLE = Duration.ofMinutes(30);
    static final Duration SESSION_MAX = Duration.ofHours(12);
    static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);
    static final int MAX_CHALLENGE_ATTEMPTS = 5;
    static final int FAILURES_PER_LOCK = 5;
    static final Duration FIRST_LOCK = Duration.ofMinutes(15);
    static final Duration MAX_LOCK = Duration.ofHours(24);
    static final int RECOVERY_CODES = 10;
    private static final String ISSUER = "Formkiln";
    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
    private static final String RECOVERY_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";

    /** A failure reported to the client with an HTTP status and a stable code. */
    public static class AccountException extends RuntimeException {
        private final HttpStatus status;
        private final String code;

        public AccountException(HttpStatus status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public HttpStatus status() {
            return status;
        }

        public String code() {
            return code;
        }
    }

    /** Who performs an action, for authorisation and the audit log. */
    public record Actor(String type, UUID id, String ip) {
        public static Actor anonymous(String ip) {
            return new Actor("anonymous", null, ip);
        }

        public static Actor system() {
            return new Actor("system", null, null);
        }
    }

    /** The authenticated admin behind a session. */
    public record AdminPrincipal(UUID userId, String email, String role, UUID sessionId) {}

    public record UserView(String id, String email, String role, String status, Instant lastLoginAt, Instant createdAt) {
        static UserView of(User user) {
            return new UserView(user.id().toString(), user.email(), user.role(), user.status(), user.lastLoginAt(), user.createdAt());
        }
    }

    public record InvitationView(String id, String email, String role, Instant createdAt, Instant expiresAt) {
        static InvitationView of(Invitation invitation) {
            return new InvitationView(
                    invitation.id().toString(), invitation.email(), invitation.role(), invitation.createdAt(), invitation.expiresAt());
        }
    }

    /** TOTP enrollment material: show the URI as a QR code, or the secret for manual entry. */
    public record TotpEnrollment(String secret, String otpauthUri) {}

    public record InvitationDetails(String email, String role, Instant expiresAt, TotpEnrollment totp) {}

    /** The first login step succeeded; {@code enrollTotp} is set when TOTP must be set up again. */
    public record LoginChallenge(String challenge, TotpEnrollment enrollTotp) {}

    /** A new session; {@code recoveryCodes} is set when new codes were generated (shown once). */
    public record NewSession(String token, UserView user, List<String> recoveryCodes) {}

    public record SessionView(String id, Instant createdAt, Instant lastSeenAt, String ip, String userAgent, boolean current) {}

    private final AccountRepository repository;
    private final Secrets secrets;
    private final Mailer mailer;
    private final Clock clock;
    private final JsonMapper json;
    private final String adminUrl;
    private final io.formkiln.server.metrics.FormkilnMetrics metrics;
    private final Argon2PasswordEncoder passwords = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    /** Compared against when the email is unknown, so timing does not reveal accounts. */
    private final String dummyHash = passwords.encode(Secrets.newToken());

    public AccountService(
            AccountRepository repository,
            Secrets secrets,
            Mailer mailer,
            Clock clock,
            JsonMapper json,
            @Value("${formkiln.admin-url}") String adminUrl,
            io.formkiln.server.metrics.FormkilnMetrics metrics) {
        this.repository = repository;
        this.secrets = secrets;
        this.mailer = mailer;
        this.clock = clock;
        this.json = json;
        this.adminUrl = adminUrl.replaceAll("/+$", "");
        this.metrics = metrics;
    }

    // ---------------------------------------------------------------- helpers

    private static AccountException invalid(String code, String message) {
        return new AccountException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static AccountException invalidToken() {
        return invalid("invalidToken", "This link is invalid or has expired.");
    }

    private static AccountException loginFailed() {
        return new AccountException(HttpStatus.UNAUTHORIZED, "loginFailed", "The email, password or code is not correct.");
    }

    static String normalizeEmail(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 254 || !EMAIL.matcher(normalized).matches()) {
            throw invalid("invalidEmail", "Enter a valid email address.");
        }
        return normalized;
    }

    static void checkPassword(String password, String email) {
        if (password == null) {
            throw invalid("weakPassword", "Use a password of 12 to 128 characters.");
        }
        int length = password.codePointCount(0, password.length());
        if (length < 12 || length > 128) {
            throw invalid("weakPassword", "Use a password of 12 to 128 characters.");
        }
        if (password.equalsIgnoreCase(email)) {
            throw invalid("weakPassword", "The password must not be your email address.");
        }
    }

    private static void checkRole(String role) {
        if (!ROLES.contains(role)) {
            throw invalid("invalidRole", "Role must be admin, editor or viewer.");
        }
    }

    private void audit(Actor actor, String action, String target, Map<String, Object> details) {
        metrics.accountAction(action);
        repository.audit(actor.type(), actor.id(), action, target, actor.ip(),
                details == null || details.isEmpty() ? null : json.writeValueAsString(details));
    }

    private TotpEnrollment enrollment(String email, byte[] secret) {
        return new TotpEnrollment(Totp.base32(secret), Totp.uri(ISSUER, email, secret));
    }

    /** Generates and stores new recovery codes; returns them in plain text (shown once). */
    private List<String> newRecoveryCodes(UUID userId) {
        List<String> codes = new ArrayList<>();
        List<byte[]> hashes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            byte[] random = Secrets.randomBytes(10);
            StringBuilder code = new StringBuilder();
            for (int j = 0; j < 10; j++) {
                code.append(RECOVERY_ALPHABET.charAt((random[j] & 0xff) % RECOVERY_ALPHABET.length()));
                if (j == 4) {
                    code.append('-');
                }
            }
            codes.add(code.toString());
            hashes.add(Secrets.hash(code.toString()));
        }
        repository.replaceRecoveryCodes(userId, hashes);
        return codes;
    }

    private static String normalizeRecoveryCode(String code) {
        return code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
    }

    private NewSession openSession(User user, String ip, String userAgent, List<String> recoveryCodes) {
        Instant now = clock.instant();
        String token = Secrets.newToken();
        repository.insertSession(Secrets.hash(token), user.id(), now, now.plus(SESSION_MAX), truncate(ip, 64), truncate(userAgent, 256));
        repository.recordLoginSuccess(user.id(), now);
        return new NewSession(token, UserView.of(repository.findUser(user.id()).orElseThrow()), recoveryCodes);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private User requireUser(UUID id) {
        return repository.lockUser(id).orElseThrow(() -> new AccountException(HttpStatus.NOT_FOUND, "notFound", "No such member."));
    }

    private void requireAnotherActiveAdmin(User target) {
        if ("admin".equals(target.role()) && target.active() && repository.countActiveAdmins() <= 1) {
            throw new AccountException(HttpStatus.CONFLICT, "lastAdmin", "There must always be at least one active admin.");
        }
    }

    // ---------------------------------------------------------------- invitations

    /** Creates an invitation (replacing any open one for the email) and emails the link. */
    @Transactional
    public InvitationView invite(Actor actor, String email, String role) {
        String normalized = normalizeEmail(email);
        checkRole(role);
        if (repository.findUserByEmail(normalized).isPresent()) {
            throw new AccountException(HttpStatus.CONFLICT, "memberExists", "This email already belongs to a member.");
        }
        Instant now = clock.instant();
        repository.findOpenInvitationByEmail(normalized).ifPresent(open -> repository.revokeInvitation(open.id(), now));
        String token = Secrets.newToken();
        UUID id = repository.insertInvitation(normalized, role, Secrets.hash(token), actor.id(), now.plus(INVITATION_TTL));
        mailer.send(new Mailer.Message(normalized, "You are invited to Formkiln", """
                You have been invited to Formkiln as %s.

                Accept the invitation within 72 hours:
                %s/invite#token=%s

                If you did not expect this email, you can ignore it.
                """.formatted(role, adminUrl, token)));
        audit(actor, "invitation.created", normalized, Map.of("role", role));
        return InvitationView.of(repository.findOpenInvitation(id).orElseThrow());
    }

    @Transactional
    public InvitationView resendInvitation(Actor actor, UUID id) {
        Invitation invitation = repository.findOpenInvitation(id)
                .orElseThrow(() -> new AccountException(HttpStatus.NOT_FOUND, "notFound", "No such invitation."));
        return invite(actor, invitation.email(), invitation.role());
    }

    @Transactional
    public void revokeInvitation(Actor actor, UUID id) {
        Invitation invitation = repository.findOpenInvitation(id)
                .orElseThrow(() -> new AccountException(HttpStatus.NOT_FOUND, "notFound", "No such invitation."));
        repository.revokeInvitation(id, clock.instant());
        audit(actor, "invitation.revoked", invitation.email(), null);
    }

    @Transactional(readOnly = true)
    public List<InvitationView> openInvitations() {
        return repository.listOpenInvitations().stream().map(InvitationView::of).toList();
    }

    private Invitation openInvitation(String token) {
        if (token == null || token.isBlank()) {
            throw invalidToken();
        }
        Invitation invitation = repository.findOpenInvitationByToken(Secrets.hash(token)).orElseThrow(AccountService::invalidToken);
        if (!invitation.expiresAt().isAfter(clock.instant())) {
            throw invalidToken();
        }
        return invitation;
    }

    /** Validates an invitation link and returns the TOTP enrollment for it (stable across calls). */
    @Transactional
    public InvitationDetails inspectInvitation(String token) {
        Invitation invitation = openInvitation(token);
        byte[] secret;
        if (invitation.totpSecret() == null) {
            secret = Totp.newSecret();
            repository.setInvitationTotp(invitation.id(), secrets.encrypt(secret));
        } else {
            secret = secrets.decrypt(invitation.totpSecret());
        }
        return new InvitationDetails(invitation.email(), invitation.role(), invitation.expiresAt(), enrollment(invitation.email(), secret));
    }

    /** Creates the account: password plus a verified TOTP code. Signs the new member in. */
    @Transactional
    public NewSession acceptInvitation(String token, String password, String code, String ip, String userAgent) {
        Invitation invitation = openInvitation(token);
        checkPassword(password, invitation.email());
        if (invitation.totpSecret() == null) {
            throw invalid("totpNotStarted", "Open the invitation link again to set up the authenticator app.");
        }
        byte[] secret = secrets.decrypt(invitation.totpSecret());
        if (!Totp.verify(secret, code, clock.instant())) {
            throw invalid("invalidCode", "The code is not correct. Check the time on your device and try again.");
        }
        if (repository.findUserByEmail(invitation.email()).isPresent()) {
            throw invalidToken();
        }
        UUID userId = repository.insertUser(invitation.email(), passwords.encode(password), secrets.encrypt(secret), invitation.role());
        repository.acceptInvitation(invitation.id(), clock.instant());
        List<String> codes = newRecoveryCodes(userId);
        audit(new Actor("user", userId, ip), "invitation.accepted", invitation.email(), Map.of("role", invitation.role()));
        return openSession(repository.findUser(userId).orElseThrow(), ip, userAgent, codes);
    }

    /** Invites {@code email} as the first admin when there are no members and no open invitation. */
    @Transactional
    public boolean bootstrap(String email) {
        if (email == null || email.isBlank() || repository.countUsers() > 0) {
            return false;
        }
        String normalized = normalizeEmail(email);
        Optional<Invitation> open = repository.findOpenInvitationByEmail(normalized);
        if (open.isPresent() && open.get().expiresAt().isAfter(clock.instant())) {
            return false;
        }
        invite(Actor.system(), normalized, "admin");
        return true;
    }

    // ---------------------------------------------------------------- login

    /** First step: email and password. Every failure looks the same to the client. */
    @Transactional(noRollbackFor = AccountException.class)
    public LoginChallenge login(String email, String password, String ip) {
        Instant now = clock.instant();
        String normalized;
        try {
            normalized = normalizeEmail(email);
        } catch (AccountException e) {
            passwords.matches(password == null ? "" : password, dummyHash);
            throw loginFailed();
        }
        Optional<User> found = repository.findUserByEmail(normalized).flatMap(u -> repository.lockUser(u.id()));
        if (found.isEmpty()) {
            passwords.matches(password == null ? "" : password, dummyHash);
            audit(Actor.anonymous(ip), "login.failed", normalized, Map.of("reason", "unknownEmail"));
            throw loginFailed();
        }
        User user = found.get();
        boolean locked = user.lockedUntil() != null && user.lockedUntil().isAfter(now);
        boolean passwordOk = passwords.matches(password == null ? "" : password, user.passwordHash());
        if (locked || !user.active() || !passwordOk) {
            if (!locked && !passwordOk) {
                int failures = user.failedLogins() + 1;
                Instant lockedUntil = null;
                if (failures % FAILURES_PER_LOCK == 0) {
                    long factor = 1L << Math.min(failures / FAILURES_PER_LOCK - 1, 10);
                    Duration lock = FIRST_LOCK.multipliedBy(factor);
                    lockedUntil = now.plus(lock.compareTo(MAX_LOCK) > 0 ? MAX_LOCK : lock);
                    audit(Actor.anonymous(ip), "login.locked", normalized, Map.of("until", lockedUntil.toString()));
                }
                repository.recordLoginFailure(user.id(), failures, lockedUntil);
            }
            audit(Actor.anonymous(ip), "login.failed", normalized,
                    Map.of("reason", locked ? "locked" : !user.active() ? "disabled" : "password"));
            throw loginFailed();
        }
        String challenge = Secrets.newToken();
        byte[] enrollSecret = user.totpSecret() == null ? Totp.newSecret() : null;
        repository.insertChallenge(Secrets.hash(challenge), user.id(),
                enrollSecret == null ? null : secrets.encrypt(enrollSecret), now.plus(CHALLENGE_TTL));
        return new LoginChallenge(challenge, enrollSecret == null ? null : enrollment(user.email(), enrollSecret));
    }

    /** Second step: a TOTP code (or a recovery code) for the challenge. Opens a session. */
    @Transactional(noRollbackFor = AccountException.class)
    public NewSession completeLogin(String challengeToken, String code, String recoveryCode, String ip, String userAgent) {
        if (challengeToken == null || challengeToken.isBlank()) {
            throw loginFailed();
        }
        byte[] hash = Secrets.hash(challengeToken);
        Challenge challenge = repository.lockChallenge(hash).orElseThrow(AccountService::loginFailed);
        Instant now = clock.instant();
        if (!challenge.expiresAt().isAfter(now) || challenge.attempts() >= MAX_CHALLENGE_ATTEMPTS) {
            repository.deleteChallenge(hash);
            throw loginFailed();
        }
        User user = repository.lockUser(challenge.userId()).filter(User::active).orElseThrow(AccountService::loginFailed);

        List<String> newCodes = null;
        boolean ok;
        if (challenge.totpSecret() != null) {
            // Re-enrollment after an administrator reset: the code proves the new authenticator works.
            byte[] secret = secrets.decrypt(challenge.totpSecret());
            ok = Totp.verify(secret, code, now);
            if (ok) {
                repository.updateTotp(user.id(), secrets.encrypt(secret));
                newCodes = newRecoveryCodes(user.id());
                audit(new Actor("user", user.id(), ip), "totp.enrolled", user.email(), null);
            }
        } else if (recoveryCode != null && !recoveryCode.isBlank()) {
            ok = repository.useRecoveryCode(user.id(), Secrets.hash(normalizeRecoveryCode(recoveryCode)), now);
            if (ok) {
                audit(new Actor("user", user.id(), ip), "recoveryCode.used", user.email(),
                        Map.of("remaining", repository.countUnusedRecoveryCodes(user.id())));
            }
        } else {
            ok = user.totpSecret() != null && Totp.verify(secrets.decrypt(user.totpSecret()), code, now);
        }
        if (!ok) {
            repository.incrementChallengeAttempts(hash);
            audit(new Actor("anonymous", null, ip), "mfa.failed", user.email(), null);
            throw loginFailed();
        }
        repository.deleteChallenge(hash);
        audit(new Actor("user", user.id(), ip), "login.succeeded", user.email(), null);
        return openSession(user, ip, userAgent, newCodes);
    }

    /**
     * Resolves a session token: live, not expired, not idle for too long, and the member active.
     * Refreshes the last-seen time at most once a minute.
     */
    @Transactional
    public Optional<AdminPrincipal> authenticate(String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Optional<Session> found = repository.findLiveSession(Secrets.hash(sessionToken));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Session session = found.get();
        if (!session.expiresAt().isAfter(now) || !session.lastSeenAt().plus(SESSION_IDLE).isAfter(now)) {
            repository.revokeSession(session.id(), session.userId(), now);
            return Optional.empty();
        }
        Optional<User> user = repository.findUser(session.userId()).filter(User::active);
        if (user.isEmpty()) {
            return Optional.empty();
        }
        if (session.lastSeenAt().plus(TOUCH_INTERVAL).isBefore(now)) {
            repository.touchSession(session.id(), now);
        }
        return Optional.of(new AdminPrincipal(user.get().id(), user.get().email(), user.get().role(), session.id()));
    }

    @Transactional
    public void logout(AdminPrincipal principal, String ip) {
        repository.revokeSession(principal.sessionId(), principal.userId(), clock.instant());
        audit(new Actor("user", principal.userId(), ip), "logout", principal.email(), null);
    }

    @Transactional(readOnly = true)
    public UserView me(AdminPrincipal principal) {
        return UserView.of(repository.findUser(principal.userId()).orElseThrow());
    }

    // ---------------------------------------------------------------- passwords

    /** Emails a reset link when the address belongs to an active member; silent otherwise. */
    @Transactional
    public void forgotPassword(String email, String ip) {
        String normalized;
        try {
            normalized = normalizeEmail(email);
        } catch (AccountException e) {
            return;
        }
        Optional<User> user = repository.findUserByEmail(normalized).filter(User::active);
        audit(Actor.anonymous(ip), "password.resetRequested", normalized, Map.of("known", user.isPresent()));
        if (user.isEmpty()) {
            return;
        }
        String token = Secrets.newToken();
        repository.insertReset(user.get().id(), Secrets.hash(token), clock.instant().plus(RESET_TTL));
        mailer.send(new Mailer.Message(normalized, "Reset your Formkiln password", """
                Someone asked to reset the password of your Formkiln account.

                Choose a new password within 30 minutes:
                %s/reset-password#token=%s

                If this was not you, ignore this email. Your password stays the same.
                """.formatted(adminUrl, token)));
    }

    /** Sets a new password from a reset link and signs out every session. */
    @Transactional
    public void resetPassword(String token, String password, String ip) {
        if (token == null || token.isBlank()) {
            throw invalidToken();
        }
        Reset reset = repository.lockOpenReset(Secrets.hash(token)).orElseThrow(AccountService::invalidToken);
        Instant now = clock.instant();
        if (!reset.expiresAt().isAfter(now)) {
            throw invalidToken();
        }
        User user = repository.lockUser(reset.userId()).filter(User::active).orElseThrow(AccountService::invalidToken);
        checkPassword(password, user.email());
        repository.updatePassword(user.id(), passwords.encode(password), now);
        repository.useReset(reset.id(), now);
        repository.revokeUserSessions(user.id(), null, now);
        audit(new Actor("user", user.id(), ip), "password.reset", user.email(), null);
    }

    /** Changes the password of the signed-in member and signs out their other sessions. */
    @Transactional
    public void changePassword(AdminPrincipal principal, String current, String password, String ip) {
        User user = requireUser(principal.userId());
        if (current == null || !passwords.matches(current, user.passwordHash())) {
            throw new AccountException(HttpStatus.BAD_REQUEST, "wrongPassword", "The current password is not correct.");
        }
        checkPassword(password, user.email());
        Instant now = clock.instant();
        repository.updatePassword(user.id(), passwords.encode(password), now);
        repository.revokeUserSessions(user.id(), principal.sessionId(), now);
        audit(new Actor("user", user.id(), ip), "password.changed", user.email(), null);
    }

    /** New recovery codes, after proving possession of the authenticator. */
    @Transactional
    public List<String> regenerateRecoveryCodes(AdminPrincipal principal, String code, String ip) {
        User user = requireUser(principal.userId());
        if (user.totpSecret() == null || !Totp.verify(secrets.decrypt(user.totpSecret()), code, clock.instant())) {
            throw invalid("invalidCode", "The code is not correct.");
        }
        List<String> codes = newRecoveryCodes(user.id());
        audit(new Actor("user", user.id(), ip), "recoveryCodes.regenerated", user.email(), null);
        return codes;
    }

    // ---------------------------------------------------------------- sessions

    @Transactional(readOnly = true)
    public List<SessionView> sessions(AdminPrincipal principal) {
        return repository.listLiveSessions(principal.userId(), clock.instant()).stream()
                .map(s -> new SessionView(s.id().toString(), s.createdAt(), s.lastSeenAt(), s.ip(), s.userAgent(),
                        s.id().equals(principal.sessionId())))
                .toList();
    }

    @Transactional
    public void revokeSession(AdminPrincipal principal, UUID sessionId, String ip) {
        if (repository.revokeSession(sessionId, principal.userId(), clock.instant()) == 0) {
            throw new AccountException(HttpStatus.NOT_FOUND, "notFound", "No such session.");
        }
        audit(new Actor("user", principal.userId(), ip), "session.revoked", principal.email(), Map.of("session", sessionId.toString()));
    }

    // ---------------------------------------------------------------- members

    @Transactional(readOnly = true)
    public List<UserView> members() {
        return repository.listUsers().stream().map(UserView::of).toList();
    }

    @Transactional
    public UserView changeRole(Actor actor, UUID id, String role) {
        checkRole(role);
        User user = requireUser(id);
        if (!role.equals(user.role())) {
            if (!"admin".equals(role)) {
                requireAnotherActiveAdmin(user);
            }
            repository.updateRole(id, role);
            audit(actor, "member.roleChanged", user.email(), Map.of("from", user.role(), "to", role));
        }
        return UserView.of(repository.findUser(id).orElseThrow());
    }

    /** Disables (signing out every session) or re-enables a member. */
    @Transactional
    public UserView changeStatus(Actor actor, UUID id, String status) {
        if (!"active".equals(status) && !"disabled".equals(status)) {
            throw invalid("invalidStatus", "Status must be active or disabled.");
        }
        User user = requireUser(id);
        if (!status.equals(user.status())) {
            if ("disabled".equals(status)) {
                requireAnotherActiveAdmin(user);
                repository.revokeUserSessions(id, null, clock.instant());
            }
            repository.updateStatus(id, status);
            audit(actor, "disabled".equals(status) ? "member.disabled" : "member.enabled", user.email(), null);
        }
        return UserView.of(repository.findUser(id).orElseThrow());
    }

    /** Removes a member's authenticator; they enroll a new one at their next login. */
    @Transactional
    public void resetTotp(Actor actor, UUID id) {
        User user = requireUser(id);
        if (id.equals(actor.id())) {
            throw new AccountException(HttpStatus.CONFLICT, "ownTotp", "Ask another admin to reset your authenticator.");
        }
        repository.updateTotp(id, null);
        repository.replaceRecoveryCodes(id, List.of());
        repository.revokeUserSessions(id, null, clock.instant());
        audit(actor, "member.totpReset", user.email(), null);
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> auditLog(Instant before, int limit) {
        return repository.listAudit(before, limit);
    }
}
