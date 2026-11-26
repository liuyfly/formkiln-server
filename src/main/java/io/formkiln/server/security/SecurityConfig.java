package io.formkiln.server.security;

import io.formkiln.server.tokens.ServiceTokenPrincipal;
import io.formkiln.server.tokens.ServiceTokenService;
import io.formkiln.server.accounts.AccountRepository;
import io.formkiln.server.accounts.AccountService;
import io.formkiln.server.accounts.AccountService.AdminPrincipal;
import io.formkiln.server.web.AdminApi;
import io.formkiln.server.workspaces.WorkspaceContext;
import io.formkiln.server.workspaces.WorkspaceRepository;
import io.formkiln.server.workspaces.WorkspaceRepository.Workspace;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * HTTP security.
 *
 * <ul>
 *   <li>Runtime endpoints ({@code /v1/w/{workspace}/forms/**}) are public.
 *   <li>Members sign in with a session cookie. Roles belong to workspace memberships and are
 *       cumulative ({@code admin ⊃ editor ⊃ viewer}): {@link WorkspaceFilter} resolves the
 *       workspace named in the URL and grants the caller's role in it for that request only.
 *       Anyone who is not a member gets 404 for everything under the workspace, so the response
 *       does not reveal that a workspace or form exists.
 *   <li>Instance admins manage workspaces and accounts ({@code /v1/admin/instance/**}) and the
 *       members and audit log of any workspace, but read form data only as members.
 *   <li>Workspace service tokens ({@code fk_...}) act with their role (editor or viewer) in their
 *       workspace, on the management API only. The environment token
 *       ({@code FORMKILN_SERVICE_TOKEN}) acts as an editor of the {@code default} workspace.
 *       Neither reaches member administration.
 *   <li>Unsafe {@code /v1/admin/**} requests, and unsafe requests that carry the session cookie,
 *       must send {@code X-Requested-With: formkiln}: a cross-site page cannot set it without a
 *       CORS preflight, which this server never allows (CSRF defence in addition to the
 *       SameSite=Strict cookie). Service-token requests without the cookie are not affected.
 *   <li>The metrics token ({@code FORMKILN_METRICS_TOKEN}) only reads {@code /actuator/prometheus};
 *       the endpoint is closed when it is not set.
 *   <li>Authentication endpoints and runtime writes are rate-limited per client IP.
 * </ul>
 */
@Configuration
public class SecurityConfig {

    static final String SERVICE = "SERVICE";
    static final String METRICS = "METRICS";
    /** Every signed-in account. */
    static final String MEMBER = "member";
    static final String INSTANCE_ADMIN = "instanceAdmin";
    /** The workspace the environment service token belongs to. */
    static final String SERVICE_WORKSPACE = "default";
    public static final String CSRF_HEADER = "X-Requested-With";
    public static final String CSRF_VALUE = "formkiln";

    private static final Map<String, List<String>> ROLE_AUTHORITIES = Map.of(
            "admin", List.of("admin", "editor", "viewer"),
            "editor", List.of("editor", "viewer"),
            "viewer", List.of("viewer"));

    private static final String[] PUBLIC_ADMIN = {
        "/v1/admin/auth/login",
        "/v1/admin/auth/mfa",
        "/v1/admin/auth/password/forgot",
        "/v1/admin/auth/password/reset",
        "/v1/admin/invitations/inspect",
        "/v1/admin/invitations/accept",
    };

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AccountService accounts,
            WorkspaceRepository workspaces,
            AccountRepository audit,
            ServiceTokenService serviceTokens,
            Clock clock,
            @Value("${formkiln.service-token:}") String serviceToken,
            @Value("${formkiln.metrics-token:}") String metricsToken,
            @Value("${formkiln.auth-rate-limit:20}") int authRateLimit,
            @Value("${formkiln.runtime-rate-limit:60}") int runtimeRateLimit)
            throws Exception {
        return http
                .csrf(csrf -> csrf.disable()) // Replaced by CsrfHeaderFilter (custom header) + SameSite=Strict.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(new CsrfHeaderFilter(), BasicAuthenticationFilter.class)
                .addFilterBefore(new RateLimitFilter(authRateLimit, Duration.ofMinutes(1), Clock.systemUTC(), RateLimitFilter::isPublicAuth,
                        "Too many attempts. Try again later."), BasicAuthenticationFilter.class)
                .addFilterBefore(new RateLimitFilter(runtimeRateLimit, Duration.ofMinutes(1), Clock.systemUTC(), RateLimitFilter::isRuntimeWrite,
                        "Too many requests. Try again later."), BasicAuthenticationFilter.class)
                .addFilterBefore(new ServiceTokenFilter(serviceToken, metricsToken, serviceTokens), BasicAuthenticationFilter.class)
                .addFilterBefore(new SessionFilter(accounts), BasicAuthenticationFilter.class)
                .addFilterBefore(new WorkspaceFilter(workspaces, audit, clock), BasicAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // Streaming responses (CSV export) finish on an async dispatch of a request
                        // that was already authorized.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        // Error pages render the error of a request that was already authorized (or
                        // rejected); denying them turned every 500 into a 401.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/prometheus").hasAuthority(METRICS)
                        .requestMatchers("/v1/w/*/forms/**").permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_ADMIN).permitAll()
                        .requestMatchers("/v1/admin/instance/**").hasAuthority(INSTANCE_ADMIN)
                        .requestMatchers("/v1/admin/workspaces/*/members/**", "/v1/admin/workspaces/*/invitations/**",
                                "/v1/admin/workspaces/*/audit", "/v1/admin/workspaces/*/erasure",
                                "/v1/admin/workspaces/*/service-tokens/**").hasAuthority("admin")
                        .requestMatchers(HttpMethod.PATCH, "/v1/admin/workspaces/*").hasAuthority("admin")
                        .requestMatchers(HttpMethod.GET, "/v1/admin/workspaces/*").hasAuthority("viewer")
                        .requestMatchers("/v1/admin/workspaces/**").denyAll()
                        .requestMatchers("/v1/admin/**").hasAuthority(MEMBER)
                        // Archiving hides a form and stops serving it: workspace admins only.
                        .requestMatchers(HttpMethod.POST, "/v1/manage/workspaces/*/forms/*/archive",
                                "/v1/manage/workspaces/*/forms/*/restore").hasAuthority("admin")
                        .requestMatchers(HttpMethod.GET, "/v1/manage/workspaces/*/**").hasAuthority("viewer")
                        .requestMatchers("/v1/manage/workspaces/*/**").hasAuthority("editor")
                        .anyRequest().denyAll())
                .build();
    }

    private static void reject(HttpServletResponse response, HttpStatus status, String code, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"status\":" + status.value() + ",\"code\":\"" + code + "\",\"detail\":\"" + detail + "\"}");
    }

    /** Requires the custom header on unsafe admin requests and on unsafe cookie-authenticated requests. */
    static final class CsrfHeaderFilter extends OncePerRequestFilter {
        private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

        private static boolean hasSessionCookie(HttpServletRequest request) {
            Cookie[] cookies = request.getCookies();
            if (cookies == null) {
                return false;
            }
            for (Cookie cookie : cookies) {
                if (AdminApi.SESSION_COOKIE.equals(cookie.getName())) {
                    return true;
                }
            }
            return false;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if ((request.getRequestURI().startsWith("/v1/admin/") || hasSessionCookie(request))
                    && !SAFE.contains(request.getMethod())
                    && !CSRF_VALUE.equals(request.getHeader(CSRF_HEADER))) {
                reject(response, HttpStatus.FORBIDDEN, "csrf", "Missing X-Requested-With: formkiln header.");
                return;
            }
            chain.doFilter(request, response);
        }
    }

    /**
     * Fixed-window rate limit per client IP, for the requests a predicate selects (the public
     * authentication endpoints; runtime writes such as submissions, drafts and uploads).
     */
    static final class RateLimitFilter extends OncePerRequestFilter {
        private record Window(Instant start, int count) {}

        private final int limit;
        private final Duration window;
        private final Clock clock;
        private final Predicate<HttpServletRequest> applies;
        private final String message;
        private final Map<String, Window> windows = new ConcurrentHashMap<>();

        RateLimitFilter(int limit, Duration window, Clock clock, Predicate<HttpServletRequest> applies, String message) {
            this.limit = limit;
            this.window = window;
            this.clock = clock;
            this.applies = applies;
            this.message = message;
        }

        static boolean isPublicAuth(HttpServletRequest request) {
            return "POST".equals(request.getMethod()) && List.of(PUBLIC_ADMIN).contains(request.getRequestURI());
        }

        static boolean isRuntimeWrite(HttpServletRequest request) {
            return !"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())
                    && !"OPTIONS".equals(request.getMethod()) && request.getRequestURI().startsWith("/v1/w/");
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !applies.test(request);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            Instant now = clock.instant();
            if (windows.size() > 10_000) {
                windows.values().removeIf(w -> w.start().plus(window).isBefore(now));
            }
            Window current = windows.compute(request.getRemoteAddr(), (ip, w) ->
                    w == null || !w.start().plus(window).isAfter(now) ? new Window(now, 1) : new Window(w.start(), w.count() + 1));
            if (current.count() > limit) {
                response.setHeader("Retry-After", String.valueOf(window.toSeconds()));
                reject(response, HttpStatus.TOO_MANY_REQUESTS, "rateLimited", message);
                return;
            }
            chain.doFilter(request, response);
        }
    }

    /**
     * Authenticates requests that carry a workspace service token ({@code fk_...}), the
     * environment service token or the metrics token.
     */
    static final class ServiceTokenFilter extends OncePerRequestFilter {

        private final byte[] serviceToken;
        private final byte[] metricsToken;
        private final ServiceTokenService serviceTokens;

        ServiceTokenFilter(String serviceToken, String metricsToken, ServiceTokenService serviceTokens) {
            this.serviceToken = bytes(serviceToken);
            this.metricsToken = bytes(metricsToken);
            this.serviceTokens = serviceTokens;
        }

        private static byte[] bytes(String token) {
            return token == null || token.isBlank() ? null : token.getBytes(StandardCharsets.UTF_8);
        }

        private static boolean matches(byte[] presented, byte[] token) {
            return token != null && MessageDigest.isEqual(presented, token); // Constant-time comparison.
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer " + ServiceTokenService.PREFIX)) {
                serviceTokens.authenticate(header.substring("Bearer ".length())).ifPresent(token ->
                        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                                token, null, List.of(new SimpleGrantedAuthority(SERVICE)))));
            } else if (header != null && header.startsWith("Bearer ")) {
                byte[] presented = header.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
                String authority = matches(presented, serviceToken) ? SERVICE : matches(presented, metricsToken) ? METRICS : null;
                if (authority != null) {
                    SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                            authority.toLowerCase(), null, List.of(new SimpleGrantedAuthority(authority))));
                }
            }
            chain.doFilter(request, response);
        }
    }

    /** Authenticates admin members by their session cookie. */
    static final class SessionFilter extends OncePerRequestFilter {

        private final AccountService accounts;

        SessionFilter(AccountService accounts) {
            this.accounts = accounts;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            Cookie[] cookies = request.getCookies();
            if (cookies != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                for (Cookie cookie : cookies) {
                    if (AdminApi.SESSION_COOKIE.equals(cookie.getName())) {
                        accounts.authenticate(cookie.getValue()).ifPresent(SessionFilter::signIn);
                        break;
                    }
                }
            }
            chain.doFilter(request, response);
        }

        private static void signIn(AdminPrincipal principal) {
            List<GrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority(MEMBER));
            if (principal.instanceAdmin()) {
                authorities.add(new SimpleGrantedAuthority(INSTANCE_ADMIN));
            }
            SecurityContextHolder.getContext()
                    .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities));
        }
    }

    /**
     * Resolves the workspace named in the URL ({@code /v1/w/{slug}/...},
     * {@code /v1/manage/workspaces/{slug}/...}, {@code /v1/admin/workspaces/{slug}/...}), checks
     * that the caller may act in it, and grants their role in it for this request.
     *
     * <ul>
     *   <li>Public runtime: the workspace must exist and not be archived, else 404.
     *   <li>Members get their membership role. The service token is an editor of its workspace on
     *       {@code /v1/manage} only. Instance admins who are not members get the admin role on the
     *       workspace itself, its members, invitations and audit log. Form data they read only
     *       during a break-glass access window: as viewers, every write refused with 403
     *       ({@code readOnlyAccess}) and every read recorded in the workspace's audit log.
     *   <li>Everyone else gets 404. Anonymous requests continue and are refused with 401.
     *   <li>In an archived workspace every write is refused with 409 ({@code workspaceArchived}),
     *       except updating the workspace itself (to restore it) and erasure requests.
     * </ul>
     */
    static final class WorkspaceFilter extends OncePerRequestFilter {
        private static final Pattern PATH = Pattern.compile("^/v1/(w|manage/workspaces|admin/workspaces)/([^/]+)(/.*)?$");
        /** What an instance admin may manage in a workspace without being a member. */
        private static final Pattern INSTANCE_ADMIN_PATHS = Pattern.compile("^(/members(/.*)?|/invitations(/.*)?|/audit)?$");
        private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

        /** The caller's role for this request; {@code breakGlass} for an instance admin's read-only window. */
        private record Access(String role, boolean breakGlass) {}

        private final WorkspaceRepository workspaces;
        private final AccountRepository audit;
        private final Clock clock;

        WorkspaceFilter(WorkspaceRepository workspaces, AccountRepository audit, Clock clock) {
            this.workspaces = workspaces;
            this.audit = audit;
            this.clock = clock;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            Matcher path = PATH.matcher(request.getRequestURI());
            if (!path.matches()) {
                chain.doFilter(request, response);
                return;
            }
            String kind = path.group(1);
            String rest = path.group(3) == null ? "" : path.group(3);
            Optional<Workspace> found = workspaces.findBySlug(path.group(2));
            if ("w".equals(kind)) {
                if (found.isEmpty() || found.get().archived()) {
                    reject(response, HttpStatus.NOT_FOUND, "notFound", "Not found.");
                    return;
                }
                set(request, found.get(), null);
                chain.doFilter(request, response);
                return;
            }
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null) {
                chain.doFilter(request, response); // Refused with 401 by the authorization rules.
                return;
            }
            Access access = found.map(workspace -> access(auth, workspace, kind, rest)).orElse(null);
            String role = access == null ? null : access.role();
            if (role == null) {
                reject(response, HttpStatus.NOT_FOUND, "notFound", "Not found.");
                return;
            }
            Workspace workspace = found.get();
            // Restoring the workspace and erasure requests (a legal obligation) still work when archived.
            boolean allowedWhenArchived = "admin/workspaces".equals(kind) && (rest.isEmpty() || "/erasure".equals(rest));
            if (workspace.archived() && !SAFE.contains(request.getMethod()) && !allowedWhenArchived) {
                reject(response, HttpStatus.CONFLICT, "workspaceArchived", "This workspace is archived. Restore it to make changes.");
                return;
            }
            if (access.breakGlass()) {
                if (!SAFE.contains(request.getMethod())) {
                    reject(response, HttpStatus.FORBIDDEN, "readOnlyAccess", "Your access to this workspace is read-only.");
                    return;
                }
                AdminPrincipal principal = (AdminPrincipal) auth.getPrincipal();
                String target = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
                audit.auditIn(workspace.id(), "user", principal.userId(), "workspace.accessRead",
                        target.length() > 254 ? target.substring(0, 254) : target, request.getRemoteAddr(), null);
            }
            List<GrantedAuthority> authorities = new ArrayList<>(auth.getAuthorities());
            ROLE_AUTHORITIES.get(role).forEach(a -> authorities.add(new SimpleGrantedAuthority(a)));
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(auth.getPrincipal(), null, authorities));
            set(request, workspace, role);
            chain.doFilter(request, response);
        }

        /** The caller's access to the workspace for this request, or null when it has none. */
        private Access access(Authentication auth, Workspace workspace, String kind, String rest) {
            if (auth.getPrincipal() instanceof AdminPrincipal principal) {
                Optional<String> member = workspaces.role(workspace.id(), principal.userId());
                if (member.isPresent()) {
                    return new Access(member.get(), false);
                }
                if (!principal.instanceAdmin()) {
                    return null;
                }
                if ("admin/workspaces".equals(kind) && INSTANCE_ADMIN_PATHS.matcher(rest).matches()) {
                    return new Access("admin", false);
                }
                boolean open = "manage/workspaces".equals(kind)
                        && workspaces.activeAccess(workspace.id(), principal.userId(), clock.instant()).isPresent();
                return open ? new Access("viewer", true) : null;
            }
            if (auth.getPrincipal() instanceof ServiceTokenPrincipal token) {
                return "manage/workspaces".equals(kind) && token.workspaceId().equals(workspace.id())
                        ? new Access(token.role(), false) : null;
            }
            boolean service = auth.getAuthorities().stream().anyMatch(a -> SERVICE.equals(a.getAuthority()));
            return service && "manage/workspaces".equals(kind) && SERVICE_WORKSPACE.equals(workspace.slug())
                    ? new Access("editor", false) : null;
        }

        private static void set(HttpServletRequest request, Workspace workspace, String role) {
            WorkspaceContext.set(request, new WorkspaceContext.Current(workspace.id(), workspace.slug(), workspace.name(),
                    workspace.archived(), role));
        }
    }
}
