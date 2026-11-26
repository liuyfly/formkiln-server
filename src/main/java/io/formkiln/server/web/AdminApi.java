package io.formkiln.server.web;

import io.formkiln.server.tokens.ServiceTokenService;
import io.formkiln.server.accounts.AccountRepository.AuditEntry;
import io.formkiln.server.accounts.AccountService.Actor;
import io.formkiln.server.accounts.AccountService.AddResult;
import io.formkiln.server.accounts.AccountService.AdminPrincipal;
import io.formkiln.server.accounts.AccountService.InvitationDetails;
import io.formkiln.server.accounts.AccountService.InvitationView;
import io.formkiln.server.accounts.AccountService.LoginChallenge;
import io.formkiln.server.accounts.AccountService.MemberView;
import io.formkiln.server.accounts.AccountService.NewSession;
import io.formkiln.server.accounts.AccountService.SessionView;
import io.formkiln.server.accounts.AccountService.UserView;
import io.formkiln.server.accounts.AccountService;
import io.formkiln.server.workspaces.WorkspaceContext;
import io.formkiln.server.workspaces.WorkspaceRepository;
import io.formkiln.server.workspaces.WorkspaceService.WorkspaceView;
import io.formkiln.server.workspaces.WorkspaceService;
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
import org.springframework.http.HttpStatus;
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

    public record MemberUpdate(String role) {}

    public record AccountUpdate(String status, Boolean instanceAdmin) {}

    public record WorkspaceRequest(String slug, String name) {}

    public record AccessRequest(String reason) {}

    public record ServiceTokenRequest(String name, String role, Integer expiresInDays) {}

    private final AccountService accounts;
    private final WorkspaceService workspaces;
    private final ServiceTokenService serviceTokens;
    private final boolean secureCookie;

    public AdminApi(AccountService accounts, WorkspaceService workspaces, ServiceTokenService serviceTokens,
            @Value("${formkiln.cookie-secure:true}") boolean secureCookie) {
        this.accounts = accounts;
        this.workspaces = workspaces;
        this.serviceTokens = serviceTokens;
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

    // ---------------------------------------------------------------- workspace administration

    /** The workspace and the caller's role in it. */
    @GetMapping("/v1/admin/workspaces/{workspace}")
    public Map<String, Object> workspace(@PathVariable String workspace) {
        WorkspaceContext.Current current = WorkspaceContext.require();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workspace", workspaces.get(workspace));
        body.put("role", current.role());
        return body;
    }

    /** Renames, archives or restores the workspace (its admins, or instance admins). */
    @PatchMapping("/v1/admin/workspaces/{workspace}")
    public WorkspaceView updateWorkspace(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable String workspace,
            @RequestBody WorkspaceService.Update body, HttpServletRequest request) {
        return workspaces.update(actor(principal, request), workspace, body);
    }

    @GetMapping("/v1/admin/workspaces/{workspace}/members")
    public List<MemberView> members(@PathVariable String workspace) {
        return accounts.members();
    }

    /** Adds an existing account at once, or invites a new one. */
    @PostMapping("/v1/admin/workspaces/{workspace}/members")
    public ResponseEntity<AddResult> addMember(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable String workspace,
            @RequestBody InviteRequest body, HttpServletRequest request) {
        return ResponseEntity.status(201).body(accounts.addMember(actor(principal, request), body.email(), body.role()));
    }

    @PatchMapping("/v1/admin/workspaces/{workspace}/members/{id}")
    public MemberView updateMember(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable String workspace,
            @PathVariable UUID id, @RequestBody MemberUpdate body, HttpServletRequest request) {
        if (body.role() == null) {
            throw new AccountService.AccountException(HttpStatus.BAD_REQUEST, "invalidRole", "Role must be admin, editor or viewer.");
        }
        return accounts.changeRole(actor(principal, request), id, body.role());
    }

    @DeleteMapping("/v1/admin/workspaces/{workspace}/members/{id}")
    public ResponseEntity<Void> removeMember(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable String workspace,
            @PathVariable UUID id, HttpServletRequest request) {
        accounts.removeMember(actor(principal, request), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/v1/admin/workspaces/{workspace}/invitations")
    public List<InvitationView> invitations(@PathVariable String workspace) {
        return accounts.openInvitations();
    }

    @PostMapping("/v1/admin/workspaces/{workspace}/invitations/{id}/resend")
    public InvitationView resend(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable String workspace,
            @PathVariable UUID id, HttpServletRequest request) {
        return accounts.resendInvitation(actor(principal, request), id);
    }

    @DeleteMapping("/v1/admin/workspaces/{workspace}/invitations/{id}")
    public ResponseEntity<Void> revokeInvitation(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable String workspace,
            @PathVariable UUID id, HttpServletRequest request) {
        accounts.revokeInvitation(actor(principal, request), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/v1/admin/workspaces/{workspace}/audit")
    public List<AuditEntry> workspaceAudit(@PathVariable String workspace,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before,
            @RequestParam(defaultValue = "100") int limit) {
        return accounts.workspaceAuditLog(before, Math.clamp(limit, 1, 500));
    }

    @GetMapping("/v1/admin/workspaces/{workspace}/service-tokens")
    public List<ServiceTokenService.TokenView> serviceTokens(@PathVariable String workspace) {
        return serviceTokens.list();
    }

    /** Creates a token for automation; the response is the only time the token is shown. */
    @PostMapping("/v1/admin/workspaces/{workspace}/service-tokens")
    public ResponseEntity<ServiceTokenService.CreatedToken> createServiceToken(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable String workspace, @RequestBody ServiceTokenRequest body, HttpServletRequest request) {
        return ResponseEntity.status(201)
                .body(serviceTokens.create(actor(principal, request), body.name(), body.role(), body.expiresInDays()));
    }

    @DeleteMapping("/v1/admin/workspaces/{workspace}/service-tokens/{id}")
    public ResponseEntity<Void> revokeServiceToken(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable String workspace, @PathVariable UUID id, HttpServletRequest request) {
        serviceTokens.revoke(actor(principal, request), id);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- instance administration

    @GetMapping("/v1/admin/instance/workspaces")
    public List<WorkspaceRepository.WorkspaceSummary> allWorkspaces() {
        return workspaces.list();
    }

    @PostMapping("/v1/admin/instance/workspaces")
    public ResponseEntity<WorkspaceView> createWorkspace(@AuthenticationPrincipal AdminPrincipal principal,
            @RequestBody WorkspaceRequest body, HttpServletRequest request) {
        return ResponseEntity.status(201).body(workspaces.create(actor(principal, request), body.slug(), body.name()));
    }

    @PatchMapping("/v1/admin/instance/workspaces/{slug}")
    public WorkspaceView updateAnyWorkspace(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable String slug,
            @RequestBody WorkspaceService.Update body, HttpServletRequest request) {
        return workspaces.update(actor(principal, request), slug, body);
    }

    /** Break-glass: opens the workspace read-only for an hour, with a reason (audited in the workspace). */
    @PostMapping("/v1/admin/instance/workspaces/{slug}/access")
    public ResponseEntity<WorkspaceService.AccessGranted> openAccess(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable String slug, @RequestBody AccessRequest body, HttpServletRequest request) {
        return ResponseEntity.status(201).body(workspaces.openAccess(actor(principal, request), slug, body.reason()));
    }

    @GetMapping("/v1/admin/instance/accounts")
    public List<UserView> allAccounts() {
        return accounts.accounts();
    }

    @PatchMapping("/v1/admin/instance/accounts/{id}")
    public UserView updateAccount(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID id,
            @RequestBody AccountUpdate body, HttpServletRequest request) {
        UserView result = null;
        if (body.instanceAdmin() != null) {
            result = accounts.setInstanceAdmin(actor(principal, request), id, body.instanceAdmin());
        }
        if (body.status() != null) {
            result = accounts.changeStatus(actor(principal, request), id, body.status());
        }
        if (result == null) {
            throw new AccountService.AccountException(HttpStatus.BAD_REQUEST, "noChanges", "Send status or instanceAdmin.");
        }
        return result;
    }

    @PostMapping("/v1/admin/instance/accounts/{id}/reset-totp")
    public ResponseEntity<Void> resetTotp(
            @AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID id, HttpServletRequest request) {
        accounts.resetTotp(actor(principal, request), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/v1/admin/instance/audit")
    public List<AuditEntry> instanceAudit(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before,
            @RequestParam(defaultValue = "100") int limit) {
        return accounts.instanceAuditLog(before, Math.clamp(limit, 1, 500));
    }
}
