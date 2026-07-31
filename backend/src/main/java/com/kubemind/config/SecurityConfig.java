package com.kubemind.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.function.Supplier;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** Denies unconditionally — used to close a route off entirely. */
    private static final AuthorizationManager<RequestAuthorizationContext> DENY =
        (authentication, context) -> new AuthorizationDecision(false);

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   PrivilegedFeatures privilegedFeatures) throws Exception {
        // Built outside the authorizeHttpRequests lambda on purpose: these depend on
        // an install-level flag, and inlining them there would shadow its parameter.
        var clusterTerminalRule = privilegedFeatures.isEnabled()
            ? AuthorityAuthorizationManager.<RequestAuthorizationContext>hasRole("ADMIN")
            : DENY;
        var nodeShellRule = privilegedFeatures.isEnabled()
            ? AuthenticatedAuthorizationManager.<RequestAuthorizationContext>authenticated()
            : DENY;

        http
            // SPA CSRF setup (Spring Security 6 reference recipe): the token lives in a
            // readable XSRF-TOKEN cookie, axios echoes it back as X-XSRF-TOKEN on every
            // mutating request. BREACH protection stays on for server-rendered use.
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                // The port-forward proxy (see PortForwardController) passes through arbitrary
                // HTTP traffic to a Service inside the cluster — that traffic's own POST/PUT
                // forms have no idea about our XSRF cookie scheme, so our CSRF check can't
                // apply to it. The session id in the path is itself an unguessable capability
                // token, and the route is ADMIN-gated + audited at session-open time.
                .ignoringRequestMatchers("/api/port-forward/**"))
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                // Every SSE/streaming endpoint (watches, log tail, AI streams) finishes as an
                // ASYNC dispatch back through this chain. Spring Security re-authorizes those
                // by default, and when it denies one the response is already committed — which
                // surfaced as a stack trace pair in the logs: "AuthorizationDeniedException:
                // Access Denied" followed by "response is already committed". The initial
                // REQUEST dispatch was already fully authorized; ASYNC/ERROR are the container
                // resuming or erroring out that same request, not new entry points (a client
                // hitting /error directly is a REQUEST dispatch and still authenticated).
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                .requestMatchers("/api/auth/login").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                // Cluster Terminal provisions its own ServiceAccount bound to
                // cluster-admin for the session, unconditionally — that's full
                // access regardless of the caller's own kubeconfig, so it stays
                // the one deliberate ADMIN-only exception among the terminals.
                // Node shell schedules a privileged, host-mounted debug pod.
                // Neither can work — or be safely offered — on a deployment that
                // isn't cluster-admin, so both are closed off entirely there
                // rather than left to fail somewhere deeper with a confusing error.
                .requestMatchers("/ws/exec-cluster").access(clusterTerminalRule)
                .requestMatchers("/ws/exec-node").access(nodeShellRule)
                // Pod exec just uses the target cluster's own kubeconfig — real
                // Kubernetes RBAC decides what it can do, same as every other
                // action against a registered cluster. No extra gate here
                // (enforced at the WebSocket handshake, an HTTP GET upgrade).
                .requestMatchers("/ws/exec").authenticated()
                .anyRequest().authenticated())
            .exceptionHandling(ex -> ex.authenticationEntryPoint(
                (request, response, authException) ->
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication required")))
            .logout(logout -> logout
                .logoutUrl("/api/auth/logout")
                .logoutSuccessHandler((request, response, authentication) ->
                    response.setStatus(HttpServletResponse.SC_OK)));
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * Resolves the raw token when it arrives via the X-XSRF-TOKEN header (SPA path)
     * and falls back to XOR-encoded resolution for form parameters.
     */
    static final class SpaCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {
        private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response,
                           Supplier<CsrfToken> csrfToken) {
            xor.handle(request, response, csrfToken);
        }

        @Override
        public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
            return StringUtils.hasText(request.getHeader(csrfToken.getHeaderName()))
                ? super.resolveCsrfTokenValue(request, csrfToken)
                : xor.resolveCsrfTokenValue(request, csrfToken);
        }
    }

    /**
     * Forces the deferred CsrfToken to load on every request so the XSRF-TOKEN
     * cookie is actually written — otherwise the SPA never receives a token.
     */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute("_csrf");
            if (csrfToken != null) {
                csrfToken.getToken();
            }
            filterChain.doFilter(request, response);
        }
    }
}
