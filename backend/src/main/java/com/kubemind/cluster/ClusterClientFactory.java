package com.kubemind.cluster;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.RequestConfig;
import io.fabric8.kubernetes.client.RequestConfigBuilder;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a clusterId to a live KubernetesClient.
 *
 * Cluster 0 is the built-in "local" cluster (in-cluster ServiceAccount or the
 * ambient kubeconfig) and needs no stored credentials. Remote clusters are
 * built from their decrypted kubeconfig and cached until evicted.
 */
@Component
public class ClusterClientFactory {

    public static final long DEFAULT_CLUSTER_ID = 0L;
    public static final String DEFAULT_CLUSTER_NAME = "local";

    private static final Logger log = LoggerFactory.getLogger(ClusterClientFactory.class);
    private static final int CONNECT_TIMEOUT_MS = 4_000;
    private static final int REQUEST_TIMEOUT_MS = 10_000;

    private final KubernetesClient defaultClient;
    private final ClusterRepository repository;
    private final CryptoService crypto;
    private final ImpersonationResolver impersonationResolver;
    private final ImpersonationProperties impersonation;
    private final Map<Long, KubernetesClient> cache = new ConcurrentHashMap<>();

    public ClusterClientFactory(KubernetesClient defaultClient,
                                ClusterRepository repository,
                                CryptoService crypto,
                                ImpersonationResolver impersonationResolver,
                                ImpersonationProperties impersonation) {
        this.defaultClient = defaultClient;
        this.repository = repository;
        this.crypto = crypto;
        this.impersonationResolver = impersonationResolver;
        this.impersonation = impersonation;
    }

    /**
     * Resolves credentials; authorizes nothing. Whether the caller may reach
     * this cluster at all was already decided upstream by
     * ClusterAccessInterceptor via {@link ClusterAccessService#canRead}.
     *
     * For the built-in cluster (id 0) with impersonation enabled, the returned
     * client acts as the caller rather than as this installation's
     * ServiceAccount — so Kubernetes RBAC, not the app, decides what the call
     * may do. Registered clusters (id > 0) are untouched: each already runs
     * under the kubeconfig its owner supplied, which is per-user by
     * construction and almost never holds impersonate rights anyway.
     */
    public KubernetesClient getClient(long clusterId) {
        if (clusterId != DEFAULT_CLUSTER_ID) {
            return cache.computeIfAbsent(clusterId, this::buildClient);
        }
        if (!impersonation.enabled()) {
            return defaultClient; // no stored credentials — the ambient/in-cluster identity
        }
        // Fail closed. Falling back to the ServiceAccount here would hand the
        // caller cluster-admin precisely when we failed to identify them —
        // the exact leak impersonation exists to prevent. Identity-less
        // callers that are legitimate (scheduled jobs) say so explicitly via
        // getSystemClient.
        ImpersonationResolver.Identity identity = impersonationResolver.currentIdentity()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Impersonation is enabled but no caller identity is available on this thread"));
        return impersonating(identity);
    }

    /**
     * Caller passed explicitly, for threads SecurityContextHolder knows nothing
     * about — the WebSocket handlers, which carry the user on
     * {@code session.getPrincipal()} instead. Fails closed on an unusable
     * principal for the same reason the no-arg form does.
     */
    public KubernetesClient getClient(long clusterId, Principal principal) {
        if (clusterId != DEFAULT_CLUSTER_ID || !impersonation.enabled()) {
            return getSystemClient(clusterId);
        }
        ImpersonationResolver.Identity identity = impersonationResolver.resolve(principal)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Impersonation is enabled but the caller could not be identified"));
        return impersonating(identity);
    }

    /**
     * The installation's own identity, never impersonated — for scheduled
     * background work that runs on nobody's behalf (cluster profile refresh,
     * ephemeral session reaping). Deliberately a separate method rather than a
     * flag: every bypass of impersonation should be greppable in one search.
     */
    public KubernetesClient getSystemClient(long clusterId) {
        if (clusterId == DEFAULT_CLUSTER_ID) {
            return defaultClient;
        }
        return cache.computeIfAbsent(clusterId, this::buildClient);
    }

    /**
     * Derived from the cached default client via {@code newClient(RequestConfig)},
     * which shares its HTTP client and connection pool — so this is cheap enough
     * to do per call and there is no per-user cache to bound or evict.
     *
     * The returned client must NOT be closed: closing it would tear down the
     * shared HTTP client and break every other caller.
     */
    private KubernetesClient impersonating(ImpersonationResolver.Identity identity) {
        // Derived from the existing request config, not a fresh one: a blank
        // RequestConfig would silently reset the client's timeouts, watch
        // reconnect behaviour and retry limits back to Fabric8's defaults.
        RequestConfig requestConfig = new RequestConfigBuilder(defaultClient.getConfiguration().getRequestConfig())
            .withImpersonateUsername(identity.username())
            .withImpersonateGroups(identity.groups().toArray(String[]::new))
            .build();
        return defaultClient.newClient(requestConfig).adapt(KubernetesClient.class);
    }

    private KubernetesClient buildClient(long clusterId) {
        Cluster cluster = repository.findById(clusterId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Cluster " + clusterId + " not found"));
        // Single choke point every K8s call passes through — a USER-submitted cluster
        // stays unusable until an ADMIN approves it, no matter which controller asks.
        if (!"APPROVED".equals(cluster.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "This cluster is " + cluster.getStatus().toLowerCase(Locale.ROOT) + " and not yet usable");
        }
        String kubeconfig = crypto.decrypt(cluster.getKubeconfigEncrypted());
        return buildFromKubeconfig(kubeconfig);
    }

    /** Also used by ClusterService for pre-registration connection tests. */
    KubernetesClient buildFromKubeconfig(String kubeconfig) {
        Config config = Config.fromKubeconfig(kubeconfig);
        config.setConnectionTimeout(CONNECT_TIMEOUT_MS);
        config.setRequestTimeout(REQUEST_TIMEOUT_MS);
        // Fabric8 defaults to 10 retries with exponential backoff; against an
        // unreachable cluster each attempt burns the full 4s connect timeout, so a
        // single failed list() blocked its caller for ~45s+ before any error
        // surfaced — the UI just showed "Loading…" the whole time. Fail once, fast;
        // callers (pages, watch streams) have their own refresh/reconnect behavior.
        config.setRequestRetryBackoffLimit(0);
        return new KubernetesClientBuilder().withConfig(config).build();
    }

    /**
     * Raw kubeconfig text for a cluster, or null for the built-in local cluster
     * (id 0) — callers should fall back to whatever ambient config the process
     * already runs under (in-cluster ServiceAccount / ~/.kube/config) rather
     * than writing one. Used by HelmCliService to point the `helm` subprocess
     * at the right cluster via a temp --kubeconfig file.
     */
    public String getKubeconfig(long clusterId) {
        if (clusterId == DEFAULT_CLUSTER_ID) return null;
        Cluster cluster = repository.findById(clusterId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Cluster " + clusterId + " not found"));
        return crypto.decrypt(cluster.getKubeconfigEncrypted());
    }

    /** Closes and drops the cached client (cluster deleted or kubeconfig replaced). */
    public void evict(long clusterId) {
        KubernetesClient client = cache.remove(clusterId);
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("Failed to close client for cluster {}: {}", clusterId, e.getMessage());
            }
        }
    }

    @PreDestroy
    void closeAll() {
        cache.keySet().forEach(this::evict);
        // defaultClient is a Spring bean with its own destroyMethod.
    }
}
