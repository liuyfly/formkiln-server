package io.formkiln.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * HTTP security. Runtime endpoints are public. The management API requires the service token
 * ({@code Authorization: Bearer <FORMKILN_SERVICE_TOKEN>}) and is disabled when no token is set.
 */
@Configuration
public class SecurityConfig {

    static final String SERVICE = "SERVICE";

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, @Value("${formkiln.service-token:}") String serviceToken)
            throws Exception {
        return http
                .csrf(csrf -> csrf.disable()) // Stateless token API: no cookies, so no CSRF exposure.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(new ServiceTokenFilter(serviceToken), BasicAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v1/forms/**").permitAll()
                        .requestMatchers("/v1/manage/**").hasAuthority(SERVICE)
                        .anyRequest().denyAll())
                .build();
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
                // Constant-time comparison.
                if (MessageDigest.isEqual(presented, token)) {
                    SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                            "service", null, List.of(new SimpleGrantedAuthority(SERVICE))));
                }
            }
            chain.doFilter(request, response);
        }
    }
}
