package io.formkiln.server.security;

import io.formkiln.server.accounts.AccountService;
import io.formkiln.server.accounts.AccountService.AdminPrincipal;
import io.formkiln.server.web.AdminApi;
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
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
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
 *   <li>Runtime endpoints ({@code /v1/forms/**}) are public.
 *   <li>Admin members sign in with a session cookie; roles are cumulative
 *       ({@code admin ⊃ editor ⊃ viewer}).
 *   <li>The service token ({@code FORMKILN_SERVICE_TOKEN}) acts as an editor on the management API
 *       only, and never on member administration.
 *   <li>Unsafe {@code /v1/admin/**} requests, and unsafe requests that carry the session cookie,
 *       must send {@code X-Requested-With: formkiln}: a cross-site page cannot set it without a
 *       CORS preflight, which this server never allows (CSRF defence in addition to the
 *       SameSite=Strict cookie). Service-token requests without the cookie are not affected.
 *   <li>Authentication endpoints and runtime writes are rate-limited per client IP.
 * </ul>
 */
@Configuration
public class SecurityConfig {

    static final String SERVICE = "SERVICE";
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
            @Value("${formkiln.service-token:}") String serviceToken,
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
                .addFilterBefore(new ServiceTokenFilter(serviceToken), BasicAuthenticationFilter.class)
                .addFilterBefore(new SessionFilter(accounts), BasicAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // Streaming responses (CSV export) finish on an async dispatch of a request
                        // that was already authorized.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v1/forms/**").permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_ADMIN).permitAll()
                        .requestMatchers("/v1/admin/members/**", "/v1/admin/audit/**", "/v1/admin/erasure").hasAuthority("admin")
                        .requestMatchers("/v1/admin/invitations", "/v1/admin/invitations/*", "/v1/admin/invitations/*/resend")
                                .hasAuthority("admin")
                        .requestMatchers("/v1/admin/**").hasAuthority("viewer")
                        .requestMatchers(HttpMethod.GET, "/v1/manage/**").hasAnyAuthority("viewer", SERVICE)
                        .requestMatchers("/v1/manage/**").hasAnyAuthority("editor", SERVICE)
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
                    && !"OPTIONS".equals(request.getMethod()) && request.getRequestURI().startsWith("/v1/forms/");
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

    /** Authenticates requests that carry the configured service token. */
    static final class ServiceTokenFilter extends OncePerRequestFilter {

        private final byte[] token;

        ServiceTokenFilter(String token) {
            this.token = token == null || token.isBlank() ? null : token.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String header = request.getHeader("Authorization");
            if (token != null && header != null && header.startsWith("Bearer ")) {
                byte[] presented = header.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
                if (MessageDigest.isEqual(presented, token)) { // Constant-time comparison.
                    SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                            "service", null, List.of(new SimpleGrantedAuthority(SERVICE))));
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
            ROLE_AUTHORITIES.getOrDefault(principal.role(), List.of()).forEach(a -> authorities.add(new SimpleGrantedAuthority(a)));
            SecurityContextHolder.getContext()
                    .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities));
        }
    }
}
