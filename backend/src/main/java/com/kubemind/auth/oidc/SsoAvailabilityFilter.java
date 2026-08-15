package com.kubemind.auth.oidc;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Turns "the identity provider is unreachable" into a message on the login page
 * instead of a stack trace.
 *
 * Sits in front of Spring's OAuth2AuthorizationRequestRedirectFilter, which
 * assumes the registration resolves and throws when it doesn't — and an
 * exception out of a servlet filter reaches the user as a blank whitelabel
 * error page with no hint that the password form right there still works.
 */
public class SsoAvailabilityFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_PATH = "/oauth2/authorization/";

    private final ClientRegistrationRepository clientRegistrations;

    public SsoAvailabilityFilter(ClientRegistrationRepository clientRegistrations) {
        this.clientRegistrations = clientRegistrations;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.startsWith(AUTHORIZATION_PATH)) {
            String registrationId = path.substring(AUTHORIZATION_PATH.length());
            // Null here is the lazy repository reporting a failed discovery —
            // it logs the cause, so this only has to decide where the browser
            // lands. Not cached, so simply clicking again retries the IdP.
            if (clientRegistrations.findByRegistrationId(registrationId) == null) {
                response.sendRedirect(request.getContextPath() + "/login?sso=unavailable");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
