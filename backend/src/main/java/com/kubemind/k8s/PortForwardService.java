package com.kubemind.k8s;

import com.kubemind.audit.AuditService;
import com.kubemind.cluster.ClusterClientFactory;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.LocalPortForward;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Forward" a Service to the browser: resolves one Running pod behind the
 * Service, opens a Fabric8 {@link LocalPortForward} from this backend process
 * to that pod's port, and hands the caller a session id that
 * {@link PortForwardController}'s reverse proxy uses to bridge browser HTTP
 * traffic to it. Same trust tier as the Cluster Terminal / Node Shell
 * (CLAUDE.md "two disclosed exceptions"): gated on cluster access, audited at
 * open/close, time-boxed by an idle sweep rather than per-request auditing —
 * proxied traffic inside the tunnel is opaque to us by design (it's someone
 * else's app).
 *
 * That app's pages are served from KubeMind's own origin (under the session
 * prefix), so its JavaScript runs with the same origin as KubeMind itself.
 * Stripping KubeMind's cookies from what we forward keeps them away from the
 * app's server, but not from its scripts in the browser — only forward apps
 * you'd trust with your KubeMind session. A per-session origin (wildcard DNS)
 * would close that; this design doesn't need one.
 */
@Service
public class PortForwardService {

    private static final Logger log = LoggerFactory.getLogger(PortForwardService.class);
    private static final long IDLE_TIMEOUT_MINUTES = 15;
    private static final Duration TLS_PROBE_TIMEOUT = Duration.ofSeconds(3);

    /** The owner is part of the session because /api/port-forward/{id}/** carries no
     *  cluster id: without it there is nothing to authorize the proxy against, and
     *  the session id alone would let any logged-in user ride someone else's tunnel. */
    private record Session(String owner, long clusterId, LocalPortForward forward, boolean tls,
                           java.util.concurrent.atomic.AtomicReference<Instant> lastAccess) {}

    /** Where the proxy should send a session's traffic, and in which scheme. */
    public record Target(InetAddress address, int port, boolean tls) {}

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final ClusterClientFactory clientFactory;
    private final AuditService auditService;

    public PortForwardService(ClusterClientFactory clientFactory, AuditService auditService) {
        this.clientFactory = clientFactory;
        this.auditService = auditService;
    }

    public record OpenedSession(String sessionId, String proxyPath) {}

    public OpenedSession open(String username, long clusterId, String ns, String serviceName, Integer requestedPort) {
        String ref = "Service/" + ns + "/" + serviceName;
        var client = clientFactory.getClient(clusterId);

        try {
            io.fabric8.kubernetes.api.model.Service svc = client.services().inNamespace(ns).withName(serviceName).get();
            if (svc == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Service '" + serviceName + "' not found");
            }
            Map<String, String> selector = svc.getSpec() != null ? svc.getSpec().getSelector() : null;
            if (selector == null || selector.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Service has no selector — can't resolve a backing pod (headless/manual-endpoints services aren't supported)");
            }

            Pod pod = client.pods().inNamespace(ns).withLabels(selector).list().getItems().stream()
                .filter(p -> p.getStatus() != null && "Running".equals(p.getStatus().getPhase()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No Running pod found behind this service"));

            int targetPort = resolveTargetPort(svc, pod, requestedPort);

            // Loopback explicitly: Fabric8's portForward(int) binds the wildcard
            // address, which leaves the tunnel open on this pod's own IP — any
            // workload in the cluster could connect to it and reach the target
            // without logging in or owning the session.
            LocalPortForward forward = client.pods().inNamespace(ns).withName(pod.getMetadata().getName())
                .portForward(targetPort, InetAddress.getLoopbackAddress(), 0);

            // Probed once here rather than guessed from port names: plenty of
            // pods (argocd-server, dashboards) serve TLS on a port named "http".
            boolean tls = TunnelTls.speaksTls(forward.getLocalAddress(), forward.getLocalPort(), TLS_PROBE_TIMEOUT);

            String sessionId = UUID.randomUUID().toString();
            sessions.put(sessionId, new Session(username, clusterId, forward, tls,
                new java.util.concurrent.atomic.AtomicReference<>(Instant.now())));

            auditService.record(username, clusterId, "OPEN_PORT_FORWARD", ref,
                Map.of("pod", pod.getMetadata().getName(), "port", targetPort, "tls", tls), true, null);

            return new OpenedSession(sessionId, "/api/port-forward/" + sessionId + "/");
        } catch (ResponseStatusException e) {
            auditService.record(username, clusterId, "OPEN_PORT_FORWARD", ref, null, false, e.getReason());
            throw e;
        } catch (Exception e) {
            auditService.record(username, clusterId, "OPEN_PORT_FORWARD", ref, null, false, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not open port-forward: " + e.getMessage());
        }
    }

    /** Prefers an explicit ?port= request; otherwise the service's first port, resolving a named targetPort against the pod's containers. */
    private int resolveTargetPort(io.fabric8.kubernetes.api.model.Service svc, Pod pod, Integer requestedPort) {
        var ports = svc.getSpec().getPorts();
        if (ports == null || ports.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Service defines no ports");
        }
        var chosen = requestedPort != null
            ? ports.stream().filter(p -> p.getPort() != null && p.getPort().equals(requestedPort)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Service has no port " + requestedPort))
            : ports.get(0);

        IntOrString target = chosen.getTargetPort();
        if (target == null) return chosen.getPort();
        if (target.getIntVal() != null) return target.getIntVal();

        // Named targetPort — resolve against the pod's container ports.
        String name = target.getStrVal();
        return pod.getSpec().getContainers().stream()
            .filter(c -> c.getPorts() != null)
            .flatMap(c -> c.getPorts().stream())
            .filter(p -> name.equals(p.getName()))
            .map(p -> p.getContainerPort())
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Could not resolve named targetPort '" + name + "' against the pod's containers"));
    }

    /**
     * @return where to proxy to, touching the session's idle clock. Null when the
     *         session doesn't exist (expired or bogus id) or belongs to someone
     *         else — the caller turns both into the same response, so a probe
     *         can't tell a live session it doesn't own from one that never existed.
     */
    public Target touch(String sessionId, String username) {
        Session s = sessions.get(sessionId);
        if (s == null || !s.owner().equals(username)) return null;
        s.lastAccess().set(Instant.now());
        return new Target(s.forward().getLocalAddress(), s.forward().getLocalPort(), s.tls());
    }

    /** Silently does nothing for an unknown or someone else's session. */
    public void close(String username, String sessionId) {
        Session s = sessions.get(sessionId);
        if (s == null || !s.owner().equals(username)) return;
        sessions.remove(sessionId);
        closeQuietly(s);
        auditService.record(username, s.clusterId(), "CLOSE_PORT_FORWARD", "session/" + sessionId, null, true, null);
    }

    @Scheduled(fixedDelay = 5 * 60 * 1000)
    void sweepIdleSessions() {
        Instant cutoff = Instant.now().minus(IDLE_TIMEOUT_MINUTES, ChronoUnit.MINUTES);
        sessions.entrySet().removeIf(entry -> {
            boolean idle = entry.getValue().lastAccess().get().isBefore(cutoff);
            if (idle) {
                log.info("Closing idle port-forward session {}", entry.getKey());
                closeQuietly(entry.getValue());
            }
            return idle;
        });
    }

    private void closeQuietly(Session s) {
        try {
            s.forward().close();
        } catch (Exception e) {
            log.warn("Failed to close port-forward: {}", e.getMessage());
        }
    }
}
