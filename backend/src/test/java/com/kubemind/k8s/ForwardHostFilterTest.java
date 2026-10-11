package com.kubemind.k8s;

import com.kubemind.audit.AuditService;
import com.kubemind.cluster.ClusterClientFactory;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.fabric8.kubernetes.client.LocalPortForward;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A session on its own origin, end to end, against a local server standing in
 * for an app like Grafana — one that builds its URLs in JavaScript from "/" and
 * so breaks under a path prefix. Here it's served exactly as it would be on its
 * own host, and nothing reaches it without the handoff.
 */
class ForwardHostFilterTest {

    private static final String DOMAIN = "pf.kubemind.example.test";
    private static final String GRAFANA_LIKE_PAGE =
        "<html><head><base href=\"/\"></head><body><script>"
            + "window.grafanaBootData={settings:{appSubUrl:\"\"}};fetch(\"/api/user\")</script></body></html>";

    private final AtomicReference<Headers> received = new AtomicReference<>();
    private HttpServer app;
    private PortForwardService service;
    private ForwardHostFilter filter;
    private PortForwardService.OpenedSession opened;

    @BeforeEach
    void setUp() throws IOException {
        app = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        app.createContext("/", exchange -> {
            received.set(exchange.getRequestHeaders());
            switch (exchange.getRequestURI().getPath()) {
                case "/" -> respond(exchange, "text/html; charset=utf-8", GRAFANA_LIKE_PAGE);
                case "/login" -> {
                    exchange.getResponseHeaders().add("Set-Cookie", "grafana_session=abc; Path=/; HttpOnly");
                    exchange.getResponseHeaders().add("Set-Cookie", "kubemind-pf=planted; Path=/");
                    respond(exchange, "text/plain", "logged in");
                }
                default -> respond(exchange, "application/json", "{\"login\":\"admin\"}");
            }
        });
        app.start();

        LocalPortForward forward = mock(LocalPortForward.class);
        when(forward.getLocalAddress()).thenReturn(InetAddress.getLoopbackAddress());
        when(forward.getLocalPort()).thenReturn(app.getAddress().getPort());
        filter = filterFor(new PortForwardProperties(DOMAIN, "http", null));
        opened = service.registerSession("alice", 0L, forward, false);
    }

    @AfterEach
    void stop() {
        app.stop(0);
    }

    @Test
    void theHandoffLinkSetsAnHttpOnlyCookieForThisHostAndLandsOnTheAppsRoot() throws Exception {
        var response = handoff(token());

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getHeader("Location")).isEqualTo("/");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        String setCookie = response.getHeader("Set-Cookie");
        assertThat(setCookie).startsWith("kubemind-pf=").contains("Path=/", "HttpOnly", "SameSite=Lax")
            .doesNotContain("Domain=").doesNotContain("Secure");
    }

    @Test
    void theHandoffLinkWorksOnce() throws Exception {
        handoff(token());

        var replay = handoff(token());

        assertThat(replay.getStatus()).isEqualTo(403);
        assertThat(replay.getContentAsString()).contains("expired or was already used");
    }

    @Test
    void servesTheAppUntouchedAtTheRootOfItsOwnOrigin() throws Exception {
        String secret = secretFrom(handoff(token()));

        var response = request("/", secret);

        assertThat(response.getStatus()).isEqualTo(200);
        // Nothing rewritten: on its own host the app's "/" paths are already right.
        assertThat(response.getContentAsString()).isEqualTo(GRAFANA_LIKE_PAGE);
    }

    @Test
    void passesTheAppsOwnCookiesAndHeadersButNeverOurs() throws Exception {
        String secret = secretFrom(handoff(token()));
        var req = new MockHttpServletRequest("GET", "/api/user");
        req.setServerName(opened.sessionId() + "." + DOMAIN);
        req.setCookies(new Cookie("kubemind-pf", secret), new Cookie("JSESSIONID", "the-apps-own"));
        req.addHeader("X-XSRF-TOKEN", "the-apps-own-csrf");

        var response = run(req);

        assertThat(response.getContentAsString()).isEqualTo("{\"login\":\"admin\"}");
        Headers upstream = received.get();
        // On its own origin a JSESSIONID can only be the app's — KubeMind's never comes here.
        assertThat(upstream.getFirst("Cookie")).isEqualTo("JSESSIONID=the-apps-own");
        assertThat(upstream.getFirst("X-xsrf-token")).isEqualTo("the-apps-own-csrf");
    }

    @Test
    void theAppKeepsItsCookiePathsButCannotPlantOurCookie() throws Exception {
        String secret = secretFrom(handoff(token()));

        var response = request("/login", secret);

        assertThat(response.getHeaders("Set-Cookie")).containsExactly("grafana_session=abc; Path=/; HttpOnly");
    }

    @Test
    void withoutTheCookieTheAppIsNeverReached() throws Exception {
        var response = request("/", null);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Go back to KubeMind");
        assertThat(received.get()).isNull();
    }

    @Test
    void aWrongCookieOrAnotherSessionsHostIsTurnedAway() throws Exception {
        String secret = secretFrom(handoff(token()));

        assertThat(request("/", "forged").getStatus()).isEqualTo(401);

        var otherHost = new MockHttpServletRequest("GET", "/");
        otherHost.setServerName("00000000-0000-4000-8000-000000000000." + DOMAIN);
        otherHost.setCookies(new Cookie("kubemind-pf", secret));
        assertThat(run(otherHost).getStatus()).isEqualTo(401);
        assertThat(received.get()).isNull();
    }

    @Test
    void kubemindsOwnHostGoesOnToTheRestOfTheApp() throws Exception {
        var req = new MockHttpServletRequest("GET", "/api/clusters");
        req.setServerName("kubemind.example.test");
        var chain = new MockFilterChain();

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(req);
    }

    @Test
    void withNoDomainConfiguredNothingIsTreatedAsASessionsOrigin() throws Exception {
        var disabled = filterFor(new PortForwardProperties("", "http", null));
        var req = new MockHttpServletRequest("GET", "/");
        req.setServerName(opened.sessionId() + "." + DOMAIN);
        var chain = new MockFilterChain();

        disabled.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(req);
    }

    @Test
    void overHttpsTheCookieIsSecureAndHostLocked() throws Exception {
        var https = filterFor(new PortForwardProperties(DOMAIN, "https", null));
        LocalPortForward forward = mock(LocalPortForward.class);
        when(forward.getLocalAddress()).thenReturn(InetAddress.getLoopbackAddress());
        when(forward.getLocalPort()).thenReturn(app.getAddress().getPort());
        var session = service.registerSession("alice", 0L, forward, false);
        String token = URI.create(session.url()).getQuery().substring("token=".length());

        var req = new MockHttpServletRequest("GET", ForwardHostFilter.HANDOFF_PATH);
        req.setServerName(session.sessionId() + "." + DOMAIN);
        req.addParameter("token", token);
        var response = new MockHttpServletResponse();
        https.doFilter(req, response, new MockFilterChain());

        assertThat(URI.create(session.url()).getScheme()).isEqualTo("https");
        assertThat(response.getHeader("Set-Cookie")).startsWith("__Host-kubemind-pf=").contains("Secure", "Path=/");
    }

    private ForwardHostFilter filterFor(PortForwardProperties properties) {
        service = new PortForwardService(mock(ClusterClientFactory.class), mock(AuditService.class), properties);
        return new ForwardHostFilter(properties, service, new PortForwardProxy(Duration.ofSeconds(5)));
    }

    private String token() {
        return URI.create(opened.url()).getQuery().substring("token=".length());
    }

    private MockHttpServletResponse handoff(String token) throws Exception {
        var req = new MockHttpServletRequest("GET", ForwardHostFilter.HANDOFF_PATH);
        req.setServerName(opened.sessionId() + "." + DOMAIN);
        req.setQueryString("token=" + token);
        req.addParameter("token", token);
        return run(req);
    }

    private MockHttpServletResponse request(String path, String secret) throws Exception {
        var req = new MockHttpServletRequest("GET", path);
        req.setServerName(opened.sessionId() + "." + DOMAIN);
        if (secret != null) {
            req.setCookies(new Cookie("kubemind-pf", secret));
        }
        return run(req);
    }

    private MockHttpServletResponse run(MockHttpServletRequest req) throws Exception {
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(req, response, chain);
        assertThat(chain.getRequest()).as("a session's own origin must never fall through to KubeMind").isNull();
        return response;
    }

    private static String secretFrom(MockHttpServletResponse handoff) {
        String setCookie = handoff.getHeader("Set-Cookie");
        return setCookie.substring("kubemind-pf=".length(), setCookie.indexOf(';'));
    }

    private static void respond(HttpExchange exchange, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
