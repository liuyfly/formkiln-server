package io.formkiln.server.web;

import io.formkiln.server.accounts.AccountRepository.AuditEntry;
import io.formkiln.server.accounts.AccountService;
import io.formkiln.server.accounts.AccountService.Actor;
import io.formkiln.server.accounts.AccountService.AdminPrincipal;
import io.formkiln.server.accounts.AccountService.InvitationDetails;
import io.formkiln.server.accounts.AccountService.InvitationView;
import io.formkiln.server.accounts.AccountService.LoginChallenge;
import io.formkiln.server.accounts.AccountService.NewSession;
import io.formkiln.server.accounts.AccountService.SessionView;
import io.formkiln.server.accounts.AccountService.UserView;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin accounts API: authentication, invitations, members, own sessions and the audit log. */
@RestController
public class AdminApi {

    /** Name of the session cookie (HttpOnly, SameSite=Strict, path /v1). */
    public static final String SESSION_COOKIE = "formkiln_session";

    public record LoginRequest(String email, String password) {}

    public record MfaRequest(String challenge, String code, String recoveryCode) {}

    public record TokenRequest(String token) {}

    public record AcceptRequest(String token, String password, String code) {}

    public record EmailRequest(String email) {}

    public record ResetRequest(String token, String password) {}

    public record PasswordChangeRequest(String currentPassword, String newPassword) {}

    public record CodeRequest(String code) {}

    public record InviteRequest(String email, String role) {}

    public record MemberUpdate(String role, String status) {}

    private final AccountService accounts;
    private final boolean secureCookie;

    public AdminApi(AccountService accounts, @Value("${formkiln.cookie-secure:true}") boolean secureCookie) {
        this.accounts = accounts;
        this.secureCookie = secureCookie;
    }

    private static String ip(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    private static Actor actor(AdminPrincipal principal, HttpServletRequest request) {
        return new Actor("user", principal.userId(), ip(request));
    }

    private String cookie(String token, Duration maxAge) {
        return ResponseCookie.from(SESSION_COOKIE, token)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Strict")
                .path("/v1")
                .maxAge(maxAge)
                .build()
                .toString();
    }

    private ResponseEntity<Map<String, Object>> signedIn(NewSession session) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user", session.user());
        if (session.recoveryCodes() != null) {
            body.put("recoveryCodes", session.recoveryCodes());
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie(session.token(), Duration.ofHours(12)))
                .body(body);
    }

    // ---------------------------------------------------------------- public authentication

    @PostMapping("/v1/admin/auth/login")
    public LoginChallenge login(@RequestBody LoginRequest body, HttpServletRequest request) {
        return accounts.login(body.email(), body.password(), ip(request));
    }

    @PostMapping("/v1/admin/auth/mfa")
    public ResponseEntity<Map<String, Object>> mfa(@RequestBody MfaRequest body, HttpServletRequest request) {
        return signedIn(accounts.completeLogin(
                body.challenge(), body.code(), body.recoveryCode(), ip(request), request.getHeader(HttpHeaders.USER_AGENT)));
    }

    @PostMapping("/v1/admin/invitations/inspect")
    public InvitationDetails inspect(@RequestBody TokenRequest body) {
        return accounts.inspectInvitation(body.token());
    }

    @PostMapping("/v1/admin/invitations/accept")
    public ResponseEntity<Map<String, Object>> accept(@RequestBody AcceptRequest body, HttpServletRequest request) {
        return signedIn(accounts.acceptInvitation(
                body.token(), body.password(), body.code(), ip(request), request.getHeader(HttpHeaders.USER_AGENT)));
    }

    @PostMapping("/v1/admin/auth/password/forgot")
    public ResponseEntity<Void> forgot(@RequestBody EmailRequest body, HttpServletRequest request) {
        accounts.forgotPassword(body.email(), ip(request));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/v1/admin/auth/password/reset")
    public ResponseEntity<Void> reset(@RequestBody ResetRequest body, HttpServletRequest request) {
        accounts.resetPassword(body.token(), body.password(), ip(request));
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- signed-in member

    @GetMapping("/v1/admin/auth/me")
    public UserView me(@AuthenticationPrincipal AdminPrincipal principal) {
        return accounts.me(principal);
    }

    @PostMapping("/v1/admin/auth/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal AdminPrincipal principal, HttpServletRequest request) {
        accounts.logout(principal, ip(request));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO)).build();
    }

    @PostMapping("/v1/admin/me/password")
    public ResponseEntity<Void> changePassword(
            @AuthenticationPrincipal AdminPrincipal principal, @RequestBody PasswordChangeRequest body, HttpServletRequest request) {
        accounts.changePassword(principal, body.currentPassword(), body.newPassword(), ip(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/v1/admin/me/recovery-codes")
    public Map<String, List<String>> recoveryCodes(
            @AuthenticationPrincipal AdminPrincipal principal, @RequestBody CodeRequest body, HttpServletRequest request) {
        return Map.of("recoveryCodes", accounts.regenerateRecoveryCodes(principal, body.code(), ip(request)));
    }

    @GetMapping("/v1/admin/me/sessions")
    public List<SessionView> sessions(@AuthenticationPrincipal AdminPrincipal principal) {
        return accounts.sessions(principal);
    }

    @DeleteMapping("/v1/admin/me/sessions/{id}")
    public ResponseEntity<Void> revokeSession(
            @AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID id, HttpServletRequest request) {
        accounts.revokeSession(principal, id, ip(request));
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- administration (admin role)

    @GetMapping("/v1/admin/members")
    public List<UserView> members() {
        return accounts.members();
    }

    @PatchMapping("/v1/admin/members/{id}")
    public UserView updateMember(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID id,
            @RequestBody MemberUpdate body,
            HttpServletRequest request) {
        UserView result = null;
        if (body.role() != null) {
            result = accounts.changeRole(actor(principal, request), id, body.role());
        }
        if (body.status() != null) {
            result = accounts.changeStatus(actor(principal, request), id, body.status());
        }
        return result != null ? result : accounts.members().stream().filter(m -> m.id().equals(id.toString())).findFirst()
                .orElseThrow(() -> new AccountService.AccountException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "notFound", "No such member."));
    }

    @PostMapping("/v1/admin/members/{id}/reset-totp")
    public ResponseEntity<Void> resetTotp(
            @AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID id, HttpServletRequest request) {
        accounts.resetTotp(actor(principal, request), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/v1/admin/invitations")
    public List<InvitationView> invitations() {
        return accounts.openInvitations();
    }

    @PostMapping("/v1/admin/invitations")
    public ResponseEntity<InvitationView> invite(
            @AuthenticationPrincipal AdminPrincipal principal, @RequestBody InviteRequest body, HttpServletRequest request) {
        return ResponseEntity.status(201).body(accounts.invite(actor(principal, request), body.email(), body.role()));
    }

    @PostMapping("/v1/admin/invitations/{id}/resend")
    public InvitationView resend(
            @AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID id, HttpServletRequest request) {
        return accounts.resendInvitation(actor(principal, request), id);
    }

    @DeleteMapping("/v1/admin/invitations/{id}")
    public ResponseEntity<Void> revokeInvitation(
            @AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID id, HttpServletRequest request) {
        accounts.revokeInvitation(actor(principal, request), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/v1/admin/audit")
    public List<AuditEntry> audit(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before,
            @RequestParam(defaultValue = "100") int limit) {
        return accounts.auditLog(before, Math.clamp(limit, 1, 500));
    }
}
