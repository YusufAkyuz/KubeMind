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
import java.time.Duration;
import java.util.Collections;
import java.util.Set;

/**
 * Opens Service port-forward sessions (ADMIN, audited) and reverse-proxies
 * arbitrary HTTP traffic to them. See PortForwardService's class comment for
 * the trust model this sits in — same tier as the Cluster Terminal.
 */
@RestController
public class PortForwardController {

    private static final Logger log = LoggerFactory.getLogger(PortForwardController.class);

    // Headers that must not be copied verbatim between the browser and the proxied
    // target — connection-management headers are per-hop, not end-to-end.
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
        "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
        "te", "trailers", "transfer-encoding", "upgrade", "host", "content-length");

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
            byte[] body = request.getInputStream().readAllBytes();
            builder.method(request.getMethod(), body.length > 0
                ? HttpRequest.BodyPublishers.ofByteArray(body) : HttpRequest.BodyPublishers.noBody());

            HttpResponse<byte[]> upstream = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());

            response.setStatus(upstream.statusCode());
            upstream.headers().map().forEach((h, values) -> {
                if (!HOP_BY_HOP_HEADERS.contains(h.toLowerCase())) {
                    values.forEach(v -> response.addHeader(h, v));
                }
            });
            response.getOutputStream().write(upstream.body());
        } catch (Exception e) {
            log.debug("Port-forward proxy error for session {}: {}", sessionId, e.getMessage());
            if (!response.isCommitted()) {
                response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "Port-forward target unreachable: " + e.getMessage());
            }
        }
    }
}
