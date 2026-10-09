package com.kubemind.k8s;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Opens Service port-forward sessions (audited) and reverse-proxies browser
 * HTTP traffic to them. See PortForwardService's class comment for the trust
 * model this sits in — same tier as the Cluster Terminal.
 *
 * The proxied app has no idea it's mounted under a /api/port-forward/{id}
 * prefix instead of being served from "/". Root-relative references it
 * emits — redirects, cookies, and HTML/CSS asset URLs — would otherwise
 * resolve against KubeMind's own origin instead of staying inside the
 * tunnel, which looks exactly like KubeMind itself misbehaving. This is a
 * subpath rewrite, not subdomain-per-session isolation: it needs no wildcard
 * DNS/TLS, which fits self-hosted installs that don't control their own DNS
 * zone, but it can't catch a URL an app's own JavaScript builds at runtime
 * (e.g. `fetch('/api/x')` inside a bundled script) — only HTML attributes and
 * CSS url() references are rewritten. WebSockets aren't proxied.
 */
@RestController
public class PortForwardController {

    private static final Logger log = LoggerFactory.getLogger(PortForwardController.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    // Connection-management headers are per-hop, not end-to-end.
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
        "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
        "te", "trailers", "transfer-encoding", "upgrade", "host", "content-length");

    // Never sent on to the proxied app:
    // - x-xsrf-token: KubeMind's own CSRF token.
    // - accept-encoding: HTML/CSS may need rewriting, so ask for them uncompressed.
    // - expect: refused by java.net.http.
    // - the Forwarded/X-Forwarded-* set: describes KubeMind's public address. An
    //   app that trusts them builds absolute URLs to KubeMind's root; without
    //   them it uses the tunnel address, which rewriteLocation recognises.
    private static final Set<String> REQUEST_HEADERS_NOT_FORWARDED = Set.of(
        "x-xsrf-token", "accept-encoding", "expect",
        "forwarded", "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto",
        "x-forwarded-port", "x-forwarded-prefix", "x-real-ip");

    // KubeMind's session and CSRF cookies ride along on every request to this
    // origin; forwarding them would hand the proxied app a live KubeMind login.
    private static final Set<String> KUBEMIND_COOKIES = Set.of("JSESSIONID", "XSRF-TOKEN");

    // Would act on KubeMind's whole origin rather than just the proxied app:
    // wiping our cookies/storage, widening a service worker's scope past the
    // session prefix, or pinning HSTS for our host.
    private static final Set<String> RESPONSE_HEADERS_NOT_FORWARDED = Set.of(
        "clear-site-data", "service-worker-allowed", "strict-transport-security");

    // href="/x", src="/x", action="/x", formaction="/x" — deliberately not
    // srcset (comma-separated url+descriptor pairs, rare enough on admin/
    // monitoring UIs to not be worth the parsing complexity here).
    private static final Pattern HTML_URL_ATTR = Pattern.compile(
        "(?i)\\b(href|src|action|formaction)(\\s*=\\s*)([\"'])(/(?!/)[^\"'>]*)\\3");
    private static final Pattern CSS_URL = Pattern.compile(
        "url\\(\\s*([\"']?)(/(?!/)[^)\"']*)\\1\\s*\\)");

    private final PortForwardService portForwardService;
    // Time to the upstream's response headers only: bodies are streamed, so a
    // long-lived server-sent-events response isn't cut off by this.
    private final Duration responseHeadersTimeout;
    // HTTP/1.1 only: for plain targets the default HTTP/2 attempt adds an h2c
    // Upgrade some servers mishandle, and the browser side is HTTP/1.1 anyway.
    private final HttpClient plainClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    private final HttpClient tlsClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .sslContext(TunnelTls.context())
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    @Autowired
    public PortForwardController(PortForwardService portForwardService) {
        this(portForwardService, Duration.ofSeconds(30));
    }

    PortForwardController(PortForwardService portForwardService, Duration responseHeadersTimeout) {
        this.portForwardService = portForwardService;
        this.responseHeadersTimeout = responseHeadersTimeout;
    }

    public record OpenedSessionDto(String sessionId, String proxyPath) {}

    @PostMapping("/api/clusters/{clusterId}/namespaces/{ns}/services/{name}/forward")
    @PreAuthorize("@clusterAccessService.canWrite(authentication, #clusterId)")
    public OpenedSessionDto open(@PathVariable long clusterId, @PathVariable String ns, @PathVariable String name,
                                 @RequestParam(required = false) Integer port, Authentication auth) {
        var opened = portForwardService.open(auth.getName(), clusterId, ns, name, port);
        return new OpenedSessionDto(opened.sessionId(), opened.proxyPath());
    }

    // No cluster id in this path, so authorization is by session ownership:
    // PortForwardService ignores a session that isn't the caller's.
    @DeleteMapping("/api/port-forward/{sessionId}")
    public void close(@PathVariable String sessionId, Authentication auth) {
        portForwardService.close(auth.getName(), sessionId);
    }

    /**
     * Reverse proxy: everything under /api/port-forward/{sessionId}/** is forwarded
     * to the tunnel, method/headers/body/query preserved (minus what would leak
     * KubeMind's own credentials). Failures are answered here with a plain-text
     * explanation — this is a page the user opened in a tab, not an API call.
     */
    @RequestMapping(value = "/api/port-forward/{sessionId}/**", method = {
        RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE,
        RequestMethod.PATCH, RequestMethod.HEAD, RequestMethod.OPTIONS
    })
    public void proxy(@PathVariable String sessionId, HttpServletRequest request,
                      HttpServletResponse response, Authentication auth) throws IOException {
        // Authorized by ownership, not by role: the session id is the only thing
        // this path carries, so someone else's tunnel must look exactly like one
        // that expired.
        PortForwardService.Target target = portForwardService.touch(sessionId, auth.getName());
        if (target == null) {
            writeError(response, HttpServletResponse.SC_GONE,
                "This port-forward session has expired or doesn't exist. Close this tab and use Forward again.");
            return;
        }

        String prefix = "/api/port-forward/" + sessionId;
        String remainder = request.getRequestURI().substring(prefix.length());
        String query = request.getQueryString();
        if (remainder.isEmpty()) {
            // Without the trailing slash, the app's relative links would resolve
            // one level above the session prefix.
            response.sendRedirect(prefix + "/" + (query != null ? "?" + query : ""));
            return;
        }

        String origin = (target.tls() ? "https://" : "http://") + hostLiteral(target.address()) + ":" + target.port();
        HttpRequest upstreamRequest;
        try {
            var builder = HttpRequest.newBuilder(URI.create(origin + remainder + (query != null ? "?" + query : "")));
            copyRequestHeaders(request, builder);
            byte[] requestBody = request.getInputStream().readAllBytes();
            builder.method(request.getMethod(), requestBody.length > 0
                ? HttpRequest.BodyPublishers.ofByteArray(requestBody) : HttpRequest.BodyPublishers.noBody());
            upstreamRequest = builder.build();
        } catch (IllegalArgumentException e) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "This request can't be forwarded: " + e.getMessage());
            return;
        }

        CompletableFuture<HttpResponse<InputStream>> pending = (target.tls() ? tlsClient : plainClient)
            .sendAsync(upstreamRequest, HttpResponse.BodyHandlers.ofInputStream());
        HttpResponse<InputStream> upstream;
        try {
            // Bounds the wait for response headers only. HttpRequest.timeout()
            // would also cut a streamed body off mid-way, killing SSE.
            upstream = pending.get(responseHeadersTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            log.warn("Port-forward {}: {} {} timed out waiting for {}", sessionId, request.getMethod(), remainder, origin);
            writeError(response, HttpServletResponse.SC_GATEWAY_TIMEOUT,
                "The forwarded service didn't answer within " + responseHeadersTimeout.toSeconds() + " seconds.");
            return;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("Port-forward {}: {} {} to {} failed: {}", sessionId, request.getMethod(), remainder, origin, cause.toString());
            if (cause instanceof HttpTimeoutException) {
                writeError(response, HttpServletResponse.SC_GATEWAY_TIMEOUT, "Timed out connecting through the tunnel.");
            } else {
                writeError(response, HttpServletResponse.SC_BAD_GATEWAY, explainFailure(cause));
            }
            return;
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            writeError(response, HttpServletResponse.SC_BAD_GATEWAY, "Interrupted while waiting for the forwarded service.");
            return;
        }

        try (InputStream body = upstream.body()) {
            response.setStatus(upstream.statusCode());
            copyResponseHeaders(upstream, response, prefix, browserOnHttps(request));

            String contentType = upstream.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
            // We asked for identity encoding, but a server may compress anyway —
            // rewriting compressed bytes as text would corrupt them, so don't.
            boolean encoded = upstream.headers().firstValue("content-encoding")
                .map(v -> !v.isBlank() && !"identity".equalsIgnoreCase(v.strip())).orElse(false);
            OutputStream out = response.getOutputStream();
            if (!encoded && contentType.startsWith("text/html")) {
                out.write(rewriteHtmlUrls(body.readAllBytes(), prefix, extractCharset(contentType)));
            } else if (!encoded && contentType.startsWith("text/css")) {
                out.write(rewriteCssUrls(body.readAllBytes(), prefix, extractCharset(contentType)));
            } else {
                stream(body, out);
            }
        } catch (IOException e) {
            // Headers are already out, so all that's left is to stop. Usually the
            // browser went away mid-stream (tab closed, page navigated).
            log.debug("Port-forward {}: response stream ended early: {}", sessionId, e.toString());
        }
    }

    private static void copyRequestHeaders(HttpServletRequest request, HttpRequest.Builder builder) {
        for (String name : Collections.list(request.getHeaderNames())) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP_HEADERS.contains(lower) || REQUEST_HEADERS_NOT_FORWARDED.contains(lower)) {
                continue;
            }
            for (String value : Collections.list(request.getHeaders(name))) {
                if (lower.equals("cookie")) {
                    value = stripKubemindCookies(value);
                    if (value.isEmpty()) {
                        continue;
                    }
                }
                builder.header(name, value);
            }
        }
    }

    private static void copyResponseHeaders(HttpResponse<?> upstream, HttpServletResponse response,
                                            String prefix, boolean browserOnHttps) {
        upstream.headers().map().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP_HEADERS.contains(lower) || RESPONSE_HEADERS_NOT_FORWARDED.contains(lower)) {
                return;
            }
            for (String value : values) {
                switch (lower) {
                    case "location" -> response.addHeader(name, rewriteLocation(value, prefix));
                    case "set-cookie" -> response.addHeader(name, rewriteSetCookie(value, prefix, browserOnHttps));
                    default -> response.addHeader(name, value);
                }
            }
        });
    }

    private static void stream(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
            // Per read, so server-sent events reach the browser as they arrive.
            out.flush();
        }
    }

    /**
     * Whether the browser is talking to KubeMind over HTTPS. Behind the bundled
     * nginx this reads "http" even when an outer ingress terminates TLS, which
     * only means a proxied app's Secure cookies lose that flag — they still work.
     */
    private static boolean browserOnHttps(HttpServletRequest request) {
        return request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
    }

    private static String hostLiteral(InetAddress address) {
        String host = address.getHostAddress();
        return host.contains(":") ? "[" + host + "]" : host;
    }

    private static String explainFailure(Throwable e) {
        if (e instanceof ConnectException) {
            return "Could not connect through the tunnel. Close this tab and use Forward again.";
        }
        return "The forwarded service closed the connection without a usable HTTP response ("
            + e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "") + ").\n\n"
            + "Either this port doesn't speak HTTP — gRPC, a database, or another binary protocol, which a "
            + "browser can't open — or the pod behind the tunnel went away. Close this tab and use Forward "
            + "again to retry.";
    }

    private static void writeError(HttpServletResponse response, int status, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(message);
    }

    /** Drops KubeMind's own cookies from a Cookie header, keeping the app's. */
    static String stripKubemindCookies(String cookieHeader) {
        return Arrays.stream(cookieHeader.split(";"))
            .map(String::strip)
            .filter(c -> !c.isEmpty())
            .filter(c -> {
                int eq = c.indexOf('=');
                return !KUBEMIND_COOKIES.contains((eq < 0 ? c : c.substring(0, eq)).strip());
            })
            .collect(Collectors.joining("; "));
    }

    /**
     * Keeps the proxied app's redirects inside the tunnel. Root-relative values
     * ("/login") get the session prefix. Absolute URLs back to the tunnel's own
     * loopback address — the Host the app saw, so e.g. an http→https hop —
     * are rewritten the same way. Anything else ("//cdn...", another site) is
     * a real redirect elsewhere and passes through.
     */
    static String rewriteLocation(String location, String prefix) {
        if (location.startsWith("//")) {
            return location;
        }
        if (location.startsWith("/")) {
            return prefix + location;
        }
        try {
            URI uri = URI.create(location);
            if (uri.isAbsolute() && uri.getHost() != null && isLoopback(uri.getHost())) {
                String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
                return prefix + path
                    + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "")
                    + (uri.getRawFragment() != null ? "#" + uri.getRawFragment() : "");
            }
        } catch (IllegalArgumentException e) {
            // Not a URI we can parse — pass it through untouched.
        }
        return location;
    }

    private static boolean isLoopback(String host) {
        String h = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        return h.equals("127.0.0.1") || h.equalsIgnoreCase("localhost") || h.equals("::1") || h.equals("0:0:0:0:0:0:0:1");
    }

    /**
     * Confines a proxied app's cookie to this session's path, so it can't
     * collide with KubeMind's own cookies and keeps being sent on later requests
     * here. Domain is dropped (it names the app's host, not ours, so the browser
     * would reject the cookie). Secure is dropped when the browser reaches us
     * over plain HTTP — an HTTPS pod's session cookie would otherwise be thrown
     * away, and the app would bounce you back to its login forever.
     */
    static String rewriteSetCookie(String setCookie, String prefix, boolean browserOnHttps) {
        String[] parts = setCookie.split(";");
        List<String> attributes = new ArrayList<>();
        boolean sawPath = false;
        boolean droppedSecure = false;
        for (int i = 1; i < parts.length; i++) {
            String attribute = parts[i].strip();
            String lower = attribute.toLowerCase(Locale.ROOT);
            if (attribute.isEmpty() || lower.startsWith("domain=")) {
                continue;
            }
            if (lower.equals("secure") && !browserOnHttps) {
                droppedSecure = true;
                continue;
            }
            if (lower.startsWith("path=")) {
                attributes.add("Path=" + prefixedPath(attribute.substring("path=".length()).strip(), prefix));
                sawPath = true;
                continue;
            }
            attributes.add(attribute);
        }
        if (droppedSecure) {
            // Browsers reject SameSite=None without Secure outright; Lax keeps the cookie.
            attributes.replaceAll(a -> a.replace(" ", "").equalsIgnoreCase("samesite=none") ? "SameSite=Lax" : a);
        }
        if (!sawPath) {
            attributes.add("Path=" + prefix);
        }
        StringBuilder result = new StringBuilder(parts[0].strip());
        attributes.forEach(a -> result.append("; ").append(a));
        return result.toString();
    }

    private static String prefixedPath(String original, String prefix) {
        if (original.isEmpty() || original.equals("/")) {
            return prefix;
        }
        return prefix + (original.startsWith("/") ? original : "/" + original);
    }

    /**
     * Rewrites root-relative href/src/action/formaction attribute values in
     * an HTML response so the proxied app's own links and asset references
     * stay under the session prefix — including a `<base href="/">`, which
     * then carries every document-relative reference along with it.
     * References with a scheme or a protocol-relative host ("//cdn...") are
     * left alone; they point somewhere else on purpose.
     */
    static byte[] rewriteHtmlUrls(byte[] body, String prefix, Charset charset) {
        String html = new String(body, charset);
        Matcher m = HTML_URL_ATTR.matcher(html);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(
                m.group(1) + m.group(2) + m.group(3) + prefix + m.group(4) + m.group(3)));
        }
        m.appendTail(out);
        return out.toString().getBytes(charset);
    }

    /** Same idea as {@link #rewriteHtmlUrls}, for CSS url(...) references. */
    static byte[] rewriteCssUrls(byte[] body, String prefix, Charset charset) {
        String css = new String(body, charset);
        Matcher m = CSS_URL.matcher(css);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String quote = m.group(1) == null ? "" : m.group(1);
            m.appendReplacement(out, Matcher.quoteReplacement(
                "url(" + quote + prefix + m.group(2) + quote + ")"));
        }
        m.appendTail(out);
        return out.toString().getBytes(charset);
    }

    /** Defaults to UTF-8 — the overwhelming majority of modern web content —
     *  when the Content-Type header doesn't name a charset explicitly. */
    static Charset extractCharset(String contentType) {
        int idx = contentType.indexOf("charset=");
        if (idx < 0) {
            return StandardCharsets.UTF_8;
        }
        String cs = contentType.substring(idx + "charset=".length());
        int semi = cs.indexOf(';');
        if (semi >= 0) {
            cs = cs.substring(0, semi);
        }
        try {
            return Charset.forName(cs.strip().replace("\"", ""));
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }
}
