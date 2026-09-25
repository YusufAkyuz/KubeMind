package com.kubemind.k8s;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opens Service port-forward sessions (ADMIN, audited) and reverse-proxies
 * arbitrary HTTP traffic to them. See PortForwardService's class comment for
 * the trust model this sits in — same tier as the Cluster Terminal.
 *
 * The proxied app has no idea it's mounted under a /api/port-forward/{id}
 * prefix instead of being served from "/". Root-relative references it
 * emits — redirects, cookies, and HTML/CSS asset URLs — would otherwise
 * resolve against KubeMind's own origin instead of staying inside the
 * tunnel, which looks exactly like KubeMind itself misbehaving (see
 * rewriteLocation/rewriteSetCookiePath/rewriteHtmlUrls/rewriteCssUrls).
 * This is a subpath rewrite, not subdomain-per-session isolation: it needs
 * no wildcard DNS/TLS, which fits self-hosted installs that don't control
 * their own DNS zone, but it can't catch a URL an app's own JavaScript
 * builds at runtime (e.g. `fetch('/api/x')` inside a bundled script) —
 * only HTML attributes and CSS url() references are rewritten.
 */
@RestController
public class PortForwardController {

    private static final Logger log = LoggerFactory.getLogger(PortForwardController.class);

    // Headers that must not be copied verbatim between the browser and the proxied
    // target — connection-management headers are per-hop, not end-to-end.
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
        "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
        "te", "trailers", "transfer-encoding", "upgrade", "host", "content-length");

    // href="/x", src="/x", action="/x", formaction="/x" — deliberately not
    // srcset (comma-separated url+descriptor pairs, rare enough on admin/
    // monitoring UIs to not be worth the parsing complexity here).
    private static final Pattern HTML_URL_ATTR = Pattern.compile(
        "(?i)\\b(href|src|action|formaction)(\\s*=\\s*)([\"'])(/(?!/)[^\"'>]*)\\3");
    private static final Pattern CSS_URL = Pattern.compile(
        "url\\(\\s*([\"']?)(/(?!/)[^)\"']*)\\1\\s*\\)");

    private final PortForwardService portForwardService;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    public PortForwardController(PortForwardService portForwardService) {
        this.portForwardService = portForwardService;
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
     * to the tunnel's local port, method/headers/body/query preserved. Never throws
     * out to Spring's default error handling — a proxied backend's own error
     * responses (404, 500, whatever) get passed through as-is instead.
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
        Integer localPort = portForwardService.touch(sessionId, auth.getName());
        if (localPort == null) {
            throw new ResponseStatusException(HttpStatus.GONE, "Port-forward session expired or not found");
        }

        String prefix = "/api/port-forward/" + sessionId;
        String remainder = request.getRequestURI().substring(prefix.length());
        String query = request.getQueryString();
        String target = "http://127.0.0.1:" + localPort + remainder + (query != null ? "?" + query : "");

        try {
            var builder = HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofSeconds(30));
            Collections.list(request.getHeaderNames()).forEach(h -> {
                if (!HOP_BY_HOP_HEADERS.contains(h.toLowerCase())) {
                    Collections.list(request.getHeaders(h)).forEach(v -> builder.header(h, v));
                }
            });
            byte[] requestBody = request.getInputStream().readAllBytes();
            builder.method(request.getMethod(), requestBody.length > 0
                ? HttpRequest.BodyPublishers.ofByteArray(requestBody) : HttpRequest.BodyPublishers.noBody());

            HttpResponse<byte[]> upstream = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());

            response.setStatus(upstream.statusCode());
            upstream.headers().map().forEach((h, values) -> {
                String lower = h.toLowerCase();
                if (HOP_BY_HOP_HEADERS.contains(lower)) {
                    return;
                }
                // The proxied app doesn't know it's mounted under our session
                // prefix. Its own root-relative redirects and cookies would
                // otherwise resolve against KubeMind's real origin instead —
                // e.g. a target app's "not logged in, go to /login" redirect
                // lands the browser on KubeMind's own /login, which looks
                // indistinguishable from KubeMind itself kicking you out.
                if (lower.equals("location")) {
                    values.forEach(v -> response.addHeader(h, rewriteLocation(v, prefix)));
                } else if (lower.equals("set-cookie")) {
                    values.forEach(v -> response.addHeader(h, rewriteSetCookiePath(v, prefix)));
                } else {
                    values.forEach(v -> response.addHeader(h, v));
                }
            });

            String contentType = upstream.headers().firstValue("content-type")
                .orElse("").toLowerCase(Locale.ROOT);
            byte[] responseBody = upstream.body();
            if (contentType.startsWith("text/html")) {
                responseBody = rewriteHtmlUrls(responseBody, prefix, extractCharset(contentType));
            } else if (contentType.startsWith("text/css")) {
                responseBody = rewriteCssUrls(responseBody, prefix, extractCharset(contentType));
            }
            response.getOutputStream().write(responseBody);
        } catch (Exception e) {
            log.debug("Port-forward proxy error for session {}: {}", sessionId, e.getMessage());
            if (!response.isCommitted()) {
                response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "Port-forward target unreachable: " + e.getMessage());
            }
        }
    }

    /**
     * Root-relative Location values ("/login", not "//other-host/..." or an
     * absolute URL) are the proxied app redirecting within itself — those
     * need the session prefix so the browser stays inside the tunnel instead
     * of landing on KubeMind's own route of the same name.
     */
    static String rewriteLocation(String location, String prefix) {
        if (location.startsWith("/") && !location.startsWith("//")) {
            return prefix + location;
        }
        return location;
    }

    /**
     * Confines a proxied app's cookies to this session's own path so they
     * can't collide with KubeMind's own cookies on the same origin, and so
     * the browser keeps sending them back on later requests here (which all
     * live under `prefix`).
     */
    static String rewriteSetCookiePath(String setCookie, String prefix) {
        String[] parts = setCookie.split(";");
        StringBuilder result = new StringBuilder(parts[0]);
        boolean sawPath = false;
        for (int i = 1; i < parts.length; i++) {
            String trimmed = parts[i].strip();
            if (trimmed.regionMatches(true, 0, "Path=", 0, 5)) {
                String originalPath = trimmed.substring(5);
                result.append("; Path=").append("/".equals(originalPath) ? prefix : prefix + originalPath);
                sawPath = true;
            } else {
                result.append(";").append(parts[i]);
            }
        }
        if (!sawPath) {
            result.append("; Path=").append(prefix);
        }
        return result.toString();
    }

    /**
     * Rewrites root-relative href/src/action/formaction attribute values in
     * an HTML response so the proxied app's own links and asset references
     * stay under the session prefix. Document-relative references ("css/x.css")
     * and references with a scheme or protocol-relative host ("//cdn...")
     * are left alone — they already resolve correctly (the browser's address
     * bar is already under the prefix) or point somewhere else on purpose.
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
            return Charset.forName(cs.strip());
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }
}
