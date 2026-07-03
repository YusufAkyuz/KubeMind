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

    public KubernetesClient getClient(long clusterId) {
        if (clusterId == DEFAULT_CLUSTER_ID) {
            return defaultClient;
        }
        return cache.computeIfAbsent(clusterId, this::buildClient);
    }

    private KubernetesClient buildClient(long clusterId) {
        Cluster cluster = repository.findById(clusterId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Cluster " + clusterId + " not found"));
        String kubeconfig = crypto.decrypt(cluster.getKubeconfigEncrypted());
        return buildFromKubeconfig(kubeconfig);
    }

    /** Also used by ClusterService for pre-registration connection tests. */
    KubernetesClient buildFromKubeconfig(String kubeconfig) {
        Config config = Config.fromKubeconfig(kubeconfig);
        config.setConnectionTimeout(CONNECT_TIMEOUT_MS);
        config.setRequestTimeout(REQUEST_TIMEOUT_MS);
        return new KubernetesClientBuilder().withConfig(config).build();
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
