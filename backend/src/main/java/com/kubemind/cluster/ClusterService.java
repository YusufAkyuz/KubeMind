package com.kubemind.cluster;

import com.kubemind.audit.AuditService;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ClusterService {

    private static final Logger log = LoggerFactory.getLogger(ClusterService.class);
    private static final int MAX_KUBECONFIG_BYTES = 512 * 1024;

    private final ClusterRepository repository;
    private final CryptoService crypto;
    private final ClusterClientFactory clientFactory;
    private final AuditService auditService;

    public ClusterService(ClusterRepository repository,
                          CryptoService crypto,
                          ClusterClientFactory clientFactory,
                          AuditService auditService) {
        this.repository = repository;
        this.crypto = crypto;
        this.clientFactory = clientFactory;
        this.auditService = auditService;
    }

    public List<ClusterDto> list() {
        List<ClusterDto> result = new ArrayList<>();
        result.add(new ClusterDto(ClusterClientFactory.DEFAULT_CLUSTER_ID,
            ClusterClientFactory.DEFAULT_CLUSTER_NAME, true, null, null, null, null));
        repository.findAll().forEach(c -> result.add(toDto(c)));
        return result;
    }

    public ClusterDto create(String username, String name, String kubeconfig) {
        try {
            validateName(name);
            validateKubeconfig(kubeconfig);
            if (!crypto.isAvailable()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "KUBEMIND_ENCRYPTION_KEY is not configured on the server - "
                    + "remote clusters cannot be stored securely.");
            }
            if (repository.findByName(name).isPresent()
                || ClusterClientFactory.DEFAULT_CLUSTER_NAME.equals(name)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A cluster named '" + name + "' already exists");
            }

            // Reject unreachable clusters at registration time, not at first click.
            testKubeconfig(kubeconfig);

            var cluster = new Cluster(name, crypto.encrypt(kubeconfig), username);
            cluster.recordCheck(true);
            cluster = repository.save(cluster);

            auditService.record(username, cluster.getId(), "ADD_CLUSTER",
                "Cluster/" + name, Map.of("name", name), true, null);
            return toDto(cluster);
        } catch (Exception e) {
            auditService.record(username, null, "ADD_CLUSTER",
                "Cluster/" + name, Map.of("name", name != null ? name : ""), false, e.getMessage());
            throw e;
        }
    }

    public void delete(String username, long id) {
        if (id == ClusterClientFactory.DEFAULT_CLUSTER_ID) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "The built-in local cluster cannot be deleted");
        }
        var cluster = repository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Cluster " + id + " not found"));
        try {
            clientFactory.evict(id);
            repository.delete(cluster);
            auditService.record(username, id, "DELETE_CLUSTER",
                "Cluster/" + cluster.getName(), null, true, null);
        } catch (Exception e) {
            auditService.record(username, id, "DELETE_CLUSTER",
                "Cluster/" + cluster.getName(), null, false, e.getMessage());
            throw e;
        }
    }

    /** On-demand connectivity check; also refreshes the stored health state. */
    public ClusterDto testConnection(long id) {
        if (id == ClusterClientFactory.DEFAULT_CLUSTER_ID) {
            probe(clientFactory.getClient(id));
            return new ClusterDto(id, ClusterClientFactory.DEFAULT_CLUSTER_NAME,
                true, null, null, null, true);
        }
        var cluster = repository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Cluster " + id + " not found"));
        try {
            probe(clientFactory.getClient(id));
            cluster.recordCheck(true);
        } catch (Exception e) {
            cluster.recordCheck(false);
            repository.save(cluster);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Cluster unreachable: " + e.getMessage());
        }
        return toDto(repository.save(cluster));
    }

    /** Used by the scheduled health checker. Never throws. */
    public void refreshHealth(Cluster cluster) {
        boolean ok;
        try {
            probe(clientFactory.getClient(cluster.getId()));
            ok = true;
        } catch (Exception e) {
            ok = false;
            log.debug("Health check failed for cluster {}: {}", cluster.getName(), e.getMessage());
        }
        cluster.recordCheck(ok);
        repository.save(cluster);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void probe(KubernetesClient client) {
        client.getKubernetesVersion(); // cheap authenticated round-trip
    }

    private void testKubeconfig(String kubeconfig) {
        try (KubernetesClient candidate = clientFactory.buildFromKubeconfig(kubeconfig)) {
            probe(candidate);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Could not connect with the provided kubeconfig: " + e.getMessage());
        }
    }

    private void validateName(String name) {
        if (name == null || name.isBlank() || name.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Cluster name must be 1-128 characters");
        }
    }

    private void validateKubeconfig(String kubeconfig) {
        if (kubeconfig == null || kubeconfig.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "kubeconfig is required");
        }
        if (kubeconfig.length() > MAX_KUBECONFIG_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "kubeconfig is too large");
        }
        Config config;
        try {
            config = Config.fromKubeconfig(kubeconfig);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "kubeconfig could not be parsed");
        }
        validateApiServerUrl(config.getMasterUrl());
    }

    /**
     * SSRF guard: block cloud metadata endpoints. Private/localhost ranges stay
     * ALLOWED on purpose — self-hosted users legitimately point at in-network
     * API servers (kind on 127.0.0.1, on-prem clusters on RFC1918 addresses).
     */
    private void validateApiServerUrl(String masterUrl) {
        String host;
        try {
            host = URI.create(masterUrl).getHost();
        } catch (Exception e) {
            host = null;
        }
        if (host == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "kubeconfig has no valid API server URL");
        }
        String h = host.toLowerCase();
        if (h.startsWith("169.254.") || h.equals("metadata.google.internal")
            || h.equals("metadata") || h.equals("[fd00:ec2::254]")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "API server address is not allowed");
        }
    }

    private ClusterDto toDto(Cluster c) {
        return new ClusterDto(c.getId(), c.getName(), false, c.getCreatedBy(),
            c.getCreatedAt(), c.getLastCheckedAt(), c.getLastCheckOk());
    }
}
