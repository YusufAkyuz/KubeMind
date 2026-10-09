package com.kubemind.k8s;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives the reverse proxy against real local servers standing in for a pod at
 * the far end of a tunnel: plain HTTP, HTTPS with a self-signed certificate for
 * a name that isn't 127.0.0.1 (what in-cluster pods like argocd-server serve),
 * and a port that doesn't speak HTTP at all.
 */
class PortForwardProxyTest {

    private static final String SESSION = "s1";
    private static final String PREFIX = "/api/port-forward/" + SESSION;
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private static final Authentication ALICE = new TestingAuthenticationToken("alice", null);

    @TempDir
    static Path tempDir;
    private static SSLContext serverTls;

    private final PortForwardService service = mock(PortForwardService.class);
    private final AtomicReference<Headers> received = new AtomicReference<>();
    private HttpServer server;
    private PortForwardController controller;

    @BeforeAll
    static void selfSignedCertificate() throws Exception {
        Path keystore = tempDir.resolve("pod.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process p = new ProcessBuilder(keytool, "-genkeypair", "-alias", "pod", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=pod.internal", "-validity", "2", "-storetype", "PKCS12",
            "-keystore", keystore.toString(), "-storepass", "changeit", "-keypass", "changeit")
            .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).isZero();

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            ks.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());
        serverTls = SSLContext.getInstance("TLS");
        serverTls.init(kmf.getKeyManagers(), null, null);
    }

    @BeforeEach
    void setUp() {
        controller = new PortForwardController(service, Duration.ofMillis(800));
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void forwardsToAPlainHttpPodWithoutLeakingKubemindsCredentials() throws Exception {
        startServer(false, exchange -> {
            received.set(exchange.getRequestHeaders());
            respond(exchange, 200, "text/html; charset=utf-8", "<a href=\"/next\">next</a>");
        });

        var request = request("/page");
        request.addHeader("Cookie", "XSRF-TOKEN=csrf; JSESSIONID=SECRET; app=1");
        request.addHeader("X-XSRF-TOKEN", "csrf");
        request.addHeader("Accept-Encoding", "gzip, deflate");
        request.addHeader("X-Forwarded-Host", "kubemind.my.kubernetes");
        request.addHeader("Accept", "text/html");
        var response = proxy(request);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("<a href=\"" + PREFIX + "/next\">next</a>");
        Headers upstream = received.get();
        assertThat(upstream.get("Cookie")).containsExactly("app=1");
        assertThat(upstream.containsKey("X-xsrf-token")).isFalse();
        assertThat(upstream.containsKey("Accept-encoding")).isFalse();
        assertThat(upstream.containsKey("X-forwarded-host")).isFalse();
        assertThat(upstream.getFirst("Accept")).isEqualTo("text/html");
    }

    @Test
    void forwardsToAnHttpsPodWithASelfSignedCertificateForAnotherName() throws Exception {
        startServer(true, exchange -> respond(exchange, 200, "application/json", "{\"ok\":true}"));
        assertThat(TunnelTls.speaksTls(LOOPBACK, server.getAddress().getPort(), Duration.ofSeconds(3))).isTrue();

        var response = proxy(request("/api/v1/session"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("{\"ok\":true}");
    }

    @Test
    void detectsThatAPlainHttpPodDoesNotSpeakTls() throws Exception {
        startServer(false, exchange -> respond(exchange, 200, "text/plain", "hi"));

        assertThat(TunnelTls.speaksTls(LOOPBACK, server.getAddress().getPort(), Duration.ofSeconds(3))).isFalse();
    }

    @Test
    void keepsRedirectsBackToTheTunnelInsideTheSession() throws Exception {
        startServer(false, exchange -> {
            int port = server.getAddress().getPort();
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + port + "/login?next=%2F");
            exchange.getResponseHeaders().add("Set-Cookie", "app=1; Path=/; Domain=127.0.0.1");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        var response = proxy(request("/"));

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getHeader("Location")).isEqualTo(PREFIX + "/login?next=%2F");
        assertThat(response.getHeader("Set-Cookie")).isEqualTo("app=1; Path=" + PREFIX);
    }

    @Test
    void streamsALongResponseInsteadOfTimingItOut() throws Exception {
        // Headers arrive at once, the body trickles in for longer than the
        // proxy's 800ms timeout — what Argo CD's live-update streams look like.
        startServer(false, exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (int i = 1; i <= 3; i++) {
                    out.write(("data: " + i + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    sleep(500);
                }
            }
        });

        var response = proxy(request("/api/v1/stream/applications"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("data: 1\n\ndata: 2\n\ndata: 3\n\n");
    }

    @Test
    void answersWithAnExplanationWhenThePodNeverSendsResponseHeaders() throws Exception {
        startServer(false, exchange -> {
            sleep(2_000);
            respond(exchange, 200, "text/plain", "too late");
        });

        var response = proxy(request("/"));

        assertThat(response.getStatus()).isEqualTo(504);
        assertThat(response.getContentAsString()).contains("didn't answer");
    }

    @Test
    void explainsAPortThatDoesNotSpeakHttp() throws Exception {
        // argocd-repo-server style: accepts the connection, never answers HTTP.
        // Every connection, in case the client retries the GET once.
        ServerSocket grpcLike = new ServerSocket(0, 50, LOOPBACK);
        Thread acceptor = new Thread(() -> {
            while (!grpcLike.isClosed()) {
                try (Socket s = grpcLike.accept()) {
                    s.getInputStream().read();
                } catch (Exception e) {
                    return;
                }
            }
        });
        acceptor.start();
        when(service.touch(SESSION, "alice"))
            .thenReturn(new PortForwardService.Target(LOOPBACK, grpcLike.getLocalPort(), false));

        try {
            var response = proxy(request("/"));

            assertThat(response.getStatus()).isEqualTo(502);
            assertThat(response.getContentType()).startsWith("text/plain");
            assertThat(response.getContentAsString()).contains("doesn't speak HTTP");
        } finally {
            grpcLike.close();
            acceptor.join(5_000);
        }
    }

    @Test
    void anUnknownOrForeignSessionGetsAPlainExplanation() throws Exception {
        when(service.touch(SESSION, "alice")).thenReturn(null);

        var response = new MockHttpServletResponse();
        controller.proxy(SESSION, request("/"), response, ALICE);

        assertThat(response.getStatus()).isEqualTo(410);
        assertThat(response.getContentAsString()).contains("expired");
    }

    @Test
    void redirectsTheBareSessionPathToItsTrailingSlash() throws Exception {
        startServer(false, exchange -> respond(exchange, 200, "text/plain", "unused"));

        var response = new MockHttpServletResponse();
        controller.proxy(SESSION, new MockHttpServletRequest("GET", PREFIX), response, ALICE);

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo(PREFIX + "/");
    }

    private void startServer(boolean tls, HttpHandler handler) throws Exception {
        if (tls) {
            HttpsServer https = HttpsServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
            https.setHttpsConfigurator(new HttpsConfigurator(serverTls));
            server = https;
        } else {
            server = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
        }
        server.createContext("/", handler);
        server.start();
        when(service.touch(SESSION, "alice"))
            .thenReturn(new PortForwardService.Target(LOOPBACK, server.getAddress().getPort(), tls));
    }

    private MockHttpServletResponse proxy(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        controller.proxy(SESSION, request, response, ALICE);
        return response;
    }

    private static MockHttpServletRequest request(String path) {
        return new MockHttpServletRequest("GET", PREFIX + path);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String contentType, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
