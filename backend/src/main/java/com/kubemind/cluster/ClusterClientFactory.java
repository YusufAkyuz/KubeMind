package com.kubemind.cluster;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

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
    private final Map<Long, KubernetesClient> cache = new ConcurrentHashMap<>();

    public ClusterClientFactory(KubernetesClient defaultClient,
                                ClusterRepository repository,
                                CryptoService crypto) {
        this.defaultClient = defaultClient;
        this.repository = repository;
        this.crypto = crypto;
    }

    /**
     * Resolves credentials; authorizes nothing. Whether the caller may reach
     * this cluster at all was already decided upstream by
     * ClusterAccessInterceptor via {@link ClusterAccessService#canRead}.
     *
     * Cluster 0 is ADMIN-only there — it isn't anyone's kubeconfig, it's the
     * single identity this installation runs as, usually bound to
     * cluster-admin. That check lives in ClusterAccessService and nowhere else:
     * repeating it here would be a second copy to keep in sync, and dropping it
     * there would hand the management cluster to every account.
     */
    public KubernetesClient getClient(long clusterId) {
        if (clusterId == DEFAULT_CLUSTER_ID) {
            return defaultClient; // no stored credentials — the ambient/in-cluster identity
        }
        return cache.computeIfAbsent(clusterId, this::buildClient);
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
