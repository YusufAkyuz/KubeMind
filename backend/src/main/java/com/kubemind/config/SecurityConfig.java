package com.kubemind.config;

import com.kubemind.auth.oidc.KubemindOidcUserService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
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
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
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
                                                   PrivilegedFeatures privilegedFeatures,
                                                   ObjectProvider<ClientRegistrationRepository> oidcClientRegistrations,
                                                   KubemindOidcUserService oidcUserService) throws Exception {
        // Built outside the authorizeHttpRequests lambda on purpose: these depend on
        // an install-level flag, and inlining them there would shadow its parameter.
        var clusterTerminalRule = privilegedFeatures.isEnabled()
            ? AuthorityAuthorizationManager.<RequestAuthorizationContext>hasRole("ADMIN")
            : DENY;
        var nodeShellRule = privilegedFeatures.isEnabled()
            ? AuthenticatedAuthorizationManager.<RequestAuthorizationContext>authenticated()
            : DENY;

        // Non-null only when kubemind.oidc.issuer-uri is set — see
        // OidcClientConfig. This is the single "is OIDC configured" check;
        // AppConfigController asks the same ObjectProvider so the two never
        // disagree about it.
        ClientRegistrationRepository oidcRegistrations = oidcClientRegistrations.getIfAvailable();

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
                // Spring's own fixed OIDC endpoints (authorization redirect + callback —
                // see OidcClientConfig). Harmless to permit even when OIDC isn't
                // configured: with no oauth2Login() wired below, nothing is
                // registered to handle them and they simply 404 unauthenticated,
                // same as any other nonexistent route.
                .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                // LoginPage needs to know whether to offer an SSO link BEFORE
                // the user is authenticated. Only ever exposes install-level
                // booleans (privilegedFeatures, oidcEnabled) — no per-user or
                // per-cluster data lives here.
                .requestMatchers("/api/config").permitAll()
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
                .logoutSuccessHandler(logoutSuccessHandler(oidcRegistrations)));

        if (oidcRegistrations != null) {
            http.oauth2Login(oauth2 -> oauth2
                .clientRegistrationRepository(oidcRegistrations)
                .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUserService))
                // Lands back on the SPA's root, exactly like a successful
                // password login does today; AuthContext's GET /auth/me on
                // mount resolves the new session the same way either path.
                .defaultSuccessUrl("/", true));
        }

        return http.build();
    }

    /**
     * Ends the identity provider's session too, not just ours.
     *
     * Without this, signing out of KubeMind only drops the local session: the
     * IdP's own SSO cookie survives, so the next "Sign in with SSO" click is
     * authorized silently and lands straight back in the previous user's
     * account — on a shared machine, the person after you is you.
     *
     * The URL is handed back as JSON rather than sent as a 302 because the SPA
     * calls logout with axios; a redirect there would be followed by the XHR
     * and the browser would never leave the page. Local (password) sessions
     * have no IdP session to end, so they keep the plain 200 they always had.
     */
    private LogoutSuccessHandler logoutSuccessHandler(ClientRegistrationRepository oidcRegistrations) {
        if (oidcRegistrations == null) {
            return (request, response, authentication) -> response.setStatus(HttpServletResponse.SC_OK);
        }
        var oidcLogout = new OidcClientInitiatedLogoutSuccessHandler(oidcRegistrations);
        // Where the IdP sends the browser once it has ended its own session.
        // {baseUrl} resolves from the request, so this follows the deployment
        // (nginx origin in production, the dev server's origin locally).
        oidcLogout.setPostLogoutRedirectUri("{baseUrl}/login");
        oidcLogout.setRedirectStrategy((request, response, url) -> {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"logoutUrl\":\"" + url.replace("\"", "\\\"") + "\"}");
        });
        return (request, response, authentication) -> {
            boolean fromIdp = authentication instanceof OAuth2AuthenticationToken
                && authentication.getPrincipal() instanceof OidcUser;
            if (fromIdp) {
                oidcLogout.onLogoutSuccess(request, response, authentication);
            } else {
                response.setStatus(HttpServletResponse.SC_OK);
            }
        };
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
