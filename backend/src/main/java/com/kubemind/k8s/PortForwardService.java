package com.kubemind.k8s;

import com.kubemind.audit.AuditService;
import com.kubemind.cluster.ClusterClientFactory;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.LocalPortForward;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.event.LogoutSuccessEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * "Forward" a Service to the browser: resolves one Running pod behind the
 * Service, opens a Fabric8 {@link LocalPortForward} from this backend process
 * to that pod's port, and hands the caller where to open it. Same trust tier
 * as the Cluster Terminal / Node Shell (CLAUDE.md "two disclosed exceptions"):
 * gated on cluster access, audited at open/close, time-boxed by an idle sweep
 * rather than per-request auditing — proxied traffic inside the tunnel is
 * opaque to us by design (it's someone else's app).
 *
 * Where the browser opens it depends on {@link PortForwardProperties}:
 * <ul>
 *   <li>Under /api/port-forward/{id}/ on KubeMind's own origin (default). The
 *       app's JavaScript then runs with KubeMind's origin; stripping KubeMind's
 *       cookies keeps them from the app's server but not from its scripts —
 *       only forward apps you'd trust with your KubeMind session.</li>
 *   <li>On its own origin, {id}.{domain}, reached through a one-time handoff
 *       link (see {@link ForwardHostFilter}). The app's scripts are kept off
 *       KubeMind's origin.</li>
 * </ul>
 */
@Service
public class PortForwardService {

    private static final Logger log = LoggerFactory.getLogger(PortForwardService.class);
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration TLS_PROBE_TIMEOUT = Duration.ofSeconds(3);
    // Long enough for a new tab to load, short enough that a link copied out of
    // a log or browser history is useless.
    static final Duration HANDOFF_TTL = Duration.ofSeconds(60);
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * The owner is part of the session because /api/port-forward/{id}/** carries no
     * cluster id: without it there is nothing to authorize the proxy against, and
     * the session id alone would let any logged-in user ride someone else's tunnel.
     *
     * On a session's own origin, the owner's KubeMind login never arrives, so the
     * browser is authorized there by a cookie secret instead — minted only by
     * redeeming the handoff token, which was only ever given to the owner. Both
     * are kept as SHA-256 hashes.
     */
    private record Session(String owner, long clusterId, LocalPortForward forward, boolean tls,
                           AtomicReference<Instant> lastAccess,
                           AtomicReference<byte[]> handoffTokenHash, Instant handoffExpiresAt,
                           AtomicReference<byte[]> cookieSecretHash) {}

    /** Where the proxy should send a session's traffic, and in which scheme. */
    public record Target(InetAddress address, int port, boolean tls) {}

    /** @param url where the browser should open the session. */
    public record OpenedSession(String sessionId, String url) {}

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final ClusterClientFactory clientFactory;
    private final AuditService auditService;
    private final PortForwardProperties properties;
    private final Clock clock;

    @Autowired
    public PortForwardService(ClusterClientFactory clientFactory, AuditService auditService,
                              PortForwardProperties properties) {
        this(clientFactory, auditService, properties, Clock.systemUTC());
    }

    PortForwardService(ClusterClientFactory clientFactory, AuditService auditService,
                       PortForwardProperties properties, Clock clock) {
        this.clientFactory = clientFactory;
        this.auditService = auditService;
        this.properties = properties;
        this.clock = clock;
    }

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

            OpenedSession opened = registerSession(username, clusterId, forward, tls);

            auditService.record(username, clusterId, "OPEN_PORT_FORWARD", ref,
                Map.of("pod", pod.getMetadata().getName(), "port", targetPort, "tls", tls,
                       "ownOrigin", properties.subdomainsEnabled()), true, null);

            return opened;
        } catch (ResponseStatusException e) {
            auditService.record(username, clusterId, "OPEN_PORT_FORWARD", ref, null, false, e.getReason());
            throw e;
        } catch (Exception e) {
            auditService.record(username, clusterId, "OPEN_PORT_FORWARD", ref, null, false, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not open port-forward: " + e.getMessage());
        }
    }

    /** Registers an already-open tunnel as a session and says where the browser should open it. */
    OpenedSession registerSession(String username, long clusterId, LocalPortForward forward, boolean tls) {
        String sessionId = UUID.randomUUID().toString();
        Instant now = clock.instant();
        String handoffToken = properties.subdomainsEnabled() ? randomSecret() : null;
        sessions.put(sessionId, new Session(username, clusterId, forward, tls,
            new AtomicReference<>(now),
            new AtomicReference<>(handoffToken == null ? null : sha256(handoffToken)),
            now.plus(HANDOFF_TTL),
            new AtomicReference<>()));

        String url = handoffToken != null
            ? properties.originFor(sessionId) + ForwardHostFilter.HANDOFF_PATH + "?token=" + handoffToken
            : "/api/port-forward/" + sessionId + "/";
        return new OpenedSession(sessionId, url);
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
        s.lastAccess().set(clock.instant());
        return target(s);
    }

    /**
     * Trades a session's one-time handoff token for the cookie secret that
     * authorizes this browser on the session's own origin.
     *
     * @return the secret, or null for an unknown session, a wrong, expired or
     *         already-redeemed token — indistinguishable on purpose.
     */
    public String redeemHandoff(String sessionId, String token) {
        Session s = sessions.get(sessionId);
        if (s == null || token == null || token.isBlank()) return null;
        byte[] expected = s.handoffTokenHash().get();
        if (expected == null || clock.instant().isAfter(s.handoffExpiresAt())) return null;
        if (!MessageDigest.isEqual(expected, sha256(token))) return null;
        // Single use, even when two requests race with the same link.
        if (!s.handoffTokenHash().compareAndSet(expected, null)) return null;

        String secret = randomSecret();
        s.cookieSecretHash().set(sha256(secret));
        s.lastAccess().set(clock.instant());
        return secret;
    }

    /** @return where to proxy to for a browser holding the session's cookie secret, else null. */
    public Target touchWithSecret(String sessionId, String secret) {
        Session s = sessions.get(sessionId);
        if (s == null || secret == null || secret.isEmpty()) return null;
        byte[] expected = s.cookieSecretHash().get();
        if (expected == null || !MessageDigest.isEqual(expected, sha256(secret))) return null;
        s.lastAccess().set(clock.instant());
        return target(s);
    }

    /** Silently does nothing for an unknown or someone else's session. */
    public void close(String username, String sessionId) {
        Session s = sessions.get(sessionId);
        if (s == null || !s.owner().equals(username)) return;
        if (sessions.remove(sessionId) == null) return;
        closeQuietly(s);
        auditService.record(username, s.clusterId(), "CLOSE_PORT_FORWARD", "session/" + sessionId, null, true, null);
    }

    /**
     * Signing out of KubeMind closes that user's tunnels. On a session's own
     * origin the browser holds a cookie of its own, which would otherwise outlive
     * the KubeMind login it was handed out under. Every tunnel of the user's goes,
     * not just this browser's — there's no way to tell which tab opened which.
     */
    @EventListener
    void onLogout(LogoutSuccessEvent event) {
        if (event.getAuthentication() != null) {
            closeAllFor(event.getAuthentication().getName());
        }
    }

    void closeAllFor(String username) {
        List<String> owned = sessions.entrySet().stream()
            .filter(e -> e.getValue().owner().equals(username))
            .map(Map.Entry::getKey)
            .toList();
        owned.forEach(id -> close(username, id));
    }

    @Scheduled(fixedDelay = 5 * 60 * 1000)
    void sweepIdleSessions() {
        Instant cutoff = clock.instant().minus(IDLE_TIMEOUT);
        sessions.entrySet().removeIf(entry -> {
            boolean idle = entry.getValue().lastAccess().get().isBefore(cutoff);
            if (idle) {
                log.info("Closing idle port-forward session {}", entry.getKey());
                closeQuietly(entry.getValue());
            }
            return idle;
        });
    }

    private static Target target(Session s) {
        return new Target(s.forward().getLocalAddress(), s.forward().getLocalPort(), s.tls());
    }

    private void closeQuietly(Session s) {
        try {
            s.forward().close();
        } catch (Exception e) {
            log.warn("Failed to close port-forward: {}", e.getMessage());
        }
    }

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
