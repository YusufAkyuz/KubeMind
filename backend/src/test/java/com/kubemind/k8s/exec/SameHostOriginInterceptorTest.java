package com.kubemind.k8s.exec;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The browser attaches KubeMind's session cookie to any handshake, whichever
 * page opened it — so only the Origin can tell KubeMind's own UI apart from a
 * page trying to open a cluster-admin terminal on the user's behalf.
 */
class SameHostOriginInterceptorTest {

    private final SameHostOriginInterceptor interceptor = new SameHostOriginInterceptor();

    @Test
    void acceptsKubemindsOwnPage() {
        assertThat(handshake("kubemind.my.kubernetes", "http://kubemind.my.kubernetes")).isEqualTo(101);
    }

    @Test
    void acceptsAPortTheProxyDidNotPassOn() {
        // kubectl port-forward on :8080 — nginx's Host $host drops the port.
        assertThat(handshake("localhost", "http://localhost:8080")).isEqualTo(101);
    }

    @Test
    void refusesAnotherSite() {
        assertThat(handshake("kubemind.my.kubernetes", "https://evil.example.com")).isEqualTo(403);
    }

    @Test
    void refusesASiblingSubdomainEvenThoughItIsSameSite() {
        // A forwarded app or any other *.my.kubernetes service: same-site, so
        // SameSite=Lax doesn't keep the session cookie off its handshakes.
        assertThat(handshake("kubemind.my.kubernetes", "http://grafana.my.kubernetes")).isEqualTo(403);
        assertThat(handshake("kubemind.my.kubernetes",
            "http://0b1e2c3d-0000-4000-8000-000000000000.pf.kubemind.my.kubernetes")).isEqualTo(403);
    }

    @Test
    void refusesAnOpaqueOrigin() {
        assertThat(handshake("kubemind.my.kubernetes", "null")).isEqualTo(403);
    }

    @Test
    void letsNonBrowserClientsWithoutAnOriginThrough() {
        assertThat(handshake("kubemind.my.kubernetes", null)).isEqualTo(101);
    }

    /** @return 101 when the interceptor lets the handshake proceed, else the status it set. */
    private int handshake(String host, String origin) {
        var servletRequest = new MockHttpServletRequest("GET", "/ws/exec-cluster");
        servletRequest.setServerName(host);
        if (origin != null) {
            servletRequest.addHeader("Origin", origin);
        }
        var servletResponse = new MockHttpServletResponse();
        var response = new ServletServerHttpResponse(servletResponse);

        boolean proceed = interceptor.beforeHandshake(
            new ServletServerHttpRequest(servletRequest), response, null, new HashMap<>());
        return proceed ? 101 : servletResponse.getStatus();
    }
}
