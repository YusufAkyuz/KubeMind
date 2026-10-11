package com.kubemind.k8s;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseCookie;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Serves port-forward sessions on their own origins, {@code <session-id>.<domain>}
 * (see {@link PortForwardProperties}). Runs ahead of Spring Security and answers
 * every request for such a host itself, so none of KubeMind's API or UI exists
 * there: an app's own "/api/..." can only ever reach that app.
 *
 * KubeMind's login cookie is host-only on KubeMind's own host and never arrives
 * here, so a session's origin is authorized by a cookie of its own. KubeMind
 * hands the session's owner a one-time, short-lived link to
 * {@value #HANDOFF_PATH} on that origin; following it trades the token for an
 * HttpOnly cookie scoped to that one host. Requests without it get a plain
 * explanation, never the app.
 */
public class ForwardHostFilter extends OncePerRequestFilter {

    static final String HANDOFF_PATH = "/__kubemind/handoff";

    private final PortForwardProperties properties;
    private final PortForwardService service;
    private final PortForwardProxy proxy;

    public ForwardHostFilter(PortForwardProperties properties, PortForwardService service, PortForwardProxy proxy) {
        this.properties = properties;
        this.service = service;
        this.proxy = proxy;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String sessionId = properties.sessionIdFromHost(request.getServerName());
        if (sessionId == null) {
            chain.doFilter(request, response);
            return;
        }

        if (HANDOFF_PATH.equals(request.getRequestURI())) {
            handoff(sessionId, request, response);
            return;
        }

        PortForwardService.Target target = service.touchWithSecret(sessionId, cookieValue(request));
        if (target == null) {
            PortForwardProxy.writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                "This port-forward isn't open in this browser — it expired, was closed, or was opened "
                    + "somewhere else. Go back to KubeMind and use Forward again.");
            return;
        }
        proxy.forward(target, sessionId, request.getRequestURI(), request, response,
            PortForwardProxy.Mount.atOriginRoot());
    }

    private void handoff(String sessionId, HttpServletRequest request, HttpServletResponse response) throws IOException {
        // The token is in this URL: keep it out of caches and out of the Referer
        // the app's first page would otherwise see.
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Cache-Control", "no-store");
        if (!"GET".equals(request.getMethod())) {
            PortForwardProxy.writeError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "Use the link KubeMind opened.");
            return;
        }

        String secret = service.redeemHandoff(sessionId, request.getParameter("token"));
        if (secret == null) {
            PortForwardProxy.writeError(response, HttpServletResponse.SC_FORBIDDEN,
                "This forward link has expired or was already used. Go back to KubeMind and use Forward again.");
            return;
        }
        ResponseCookie cookie = ResponseCookie.from(cookieName(), secret)
            .httpOnly(true)
            .secure(properties.https())
            .sameSite("Lax")
            .path("/")
            .build();
        response.addHeader("Set-Cookie", cookie.toString());
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Location", "/");
    }

    // __Host- makes the browser itself refuse the cookie unless it's Secure,
    // host-only and Path=/ — only possible when the origin is HTTPS.
    private String cookieName() {
        return properties.https() ? PortForwardProxy.FORWARD_COOKIE_SECURE : PortForwardProxy.FORWARD_COOKIE;
    }

    private String cookieValue(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        String name = cookieName();
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
