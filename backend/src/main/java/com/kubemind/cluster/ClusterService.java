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

    /**
     * Self-service for anything a user registered themselves: everyone (ADMIN
     * included) only ever sees the clusters they themselves added. Once a
     * cluster is APPROVED it is private to its owner forever — an ADMIN's only
     * touchpoint with someone else's registered cluster is the one-time
     * approve/reject decision on the PENDING request (see
     * {@link #listPendingRequests()}), never the cluster itself.
     *
     * The one exception is the built-in cluster (id 0): it isn't a per-user
     * credential, it's the single identity this KubeMind install itself runs
     * as, so there's no per-user scope to gate it by — every authenticated
     * user sees and uses it exactly the same way (maintainer-confirmed
     * trade-off; see ClusterClientFactory.getClient). See UserService's
     * javadoc for the matching authorization note on everything else.
     */
    public List<ClusterDto> list(String username) {
        List<ClusterDto> result = new ArrayList<>();
        result.add(new ClusterDto(ClusterClientFactory.DEFAULT_CLUSTER_ID,
            ClusterClientFactory.DEFAULT_CLUSTER_NAME, true, null, null, null, null, "APPROVED"));
        repository.findByCreatedBy(username).forEach(c -> result.add(toDto(c)));
        return result;
    }

    /**
     * ADMIN-only, minimal-exposure view of cluster requests awaiting a decision.
     * Deliberately a stripped DTO — no kubeconfig, no health data, no way to
     * reach the cluster's actual resources — approving a request is a one-time
     * "yes, this person may register this cluster" decision, not a grant of
     * ongoing visibility into it.
     */
    public List<PendingClusterDto> listPendingRequests() {
        return repository.findByStatus("PENDING").stream()
            .map(c -> new PendingClusterDto(c.getId(), c.getName(), c.getCreatedBy(), c.getCreatedAt()))
            .toList();
    }

    /**
     * ADMIN submissions are auto-approved (unchanged behavior). A USER's own
     * kubeconfig is registered PENDING — reachable-but-unusable until an ADMIN
     * approves it (see ClusterClientFactory's approval gate).
     */
    public ClusterDto create(String username, boolean isAdmin, String name, String kubeconfig) {
        try {
            validateName(name);
            validateKubeconfig(kubeconfig);
            if (!crypto.isAvailable()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "KUBEMIND_ENCRYPTION_KEY is not configured on the server - "
                    + "remote clusters cannot be stored securely.");
            }
            // Scoped to the owner, not global — different users may each have a
            // cluster called "staging" (see V10 migration for the matching DB constraint).
            if (repository.findByCreatedByAndName(username, name).isPresent()
                || ClusterClientFactory.DEFAULT_CLUSTER_NAME.equals(name)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "You already have a cluster named '" + name + "'");
            }

            // Reject unreachable clusters at registration time, not at first click.
            testKubeconfig(kubeconfig);

            String status = isAdmin ? "APPROVED" : "PENDING";
            var cluster = new Cluster(name, crypto.encrypt(kubeconfig), username, status);
            cluster.recordCheck(true);
            cluster = repository.save(cluster);

            String action = isAdmin ? "ADD_CLUSTER" : "REQUEST_CLUSTER";
            auditService.record(username, cluster.getId(), action,
                "Cluster/" + name, Map.of("name", name), true, null);
            return toDto(cluster);
        } catch (Exception e) {
            auditService.record(username, null, isAdmin ? "ADD_CLUSTER" : "REQUEST_CLUSTER",
                "Cluster/" + name, Map.of("name", name != null ? name : ""), false, e.getMessage());
            throw e;
        }
    }

    public void delete(String username, long id) {
        if (id == ClusterClientFactory.DEFAULT_CLUSTER_ID) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "The built-in local cluster cannot be deleted");
        }
        var cluster = requireOwned(username, id);
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
    public ClusterDto testConnection(String username, long id) {
        if (id == ClusterClientFactory.DEFAULT_CLUSTER_ID) {
            probe(clientFactory.getClient(id));
            return new ClusterDto(id, ClusterClientFactory.DEFAULT_CLUSTER_NAME,
                true, null, null, null, true, "APPROVED");
        }
        var cluster = requireOwned(username, id);
        try {
            probe(clientFactory.buildFromKubeconfig(crypto.decrypt(cluster.getKubeconfigEncrypted())));
            cluster.recordCheck(true);
        } catch (Exception e) {
            cluster.recordCheck(false);
            repository.save(cluster);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Cluster unreachable: " + e.getMessage());
        }
        return toDto(repository.save(cluster));
    }

    /** ADMIN-only: makes a PENDING cluster usable. */
    public ClusterDto approve(String reviewer, long id) {
        var cluster = repository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Cluster " + id + " not found"));
        cluster.approve(reviewer);
        cluster = repository.save(cluster);
        auditService.record(reviewer, id, "APPROVE_CLUSTER", "Cluster/" + cluster.getName(),
            Map.of("owner", cluster.getCreatedBy()), true, null);
        return toDto(cluster);
    }

    /** ADMIN-only: marks a cluster request rejected. Left in place (not deleted)
     *  so the requesting user sees why — they can re-submit under a new name. */
    public ClusterDto reject(String reviewer, long id) {
        var cluster = repository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Cluster " + id + " not found"));
        cluster.reject(reviewer);
        cluster = repository.save(cluster);
        auditService.record(reviewer, id, "REJECT_CLUSTER", "Cluster/" + cluster.getName(),
            Map.of("owner", cluster.getCreatedBy()), true, null);
        return toDto(cluster);
    }

    /** Used by the scheduled health checker. Never throws. Skips clusters that
     *  aren't APPROVED yet — nothing to health-check for a request no one's reviewed. */
    public void refreshHealth(Cluster cluster) {
        if (!"APPROVED".equals(cluster.getStatus())) return;
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

    /** No ADMIN bypass, deliberately — once a cluster is APPROVED it's private
     *  to its owner, full stop (see {@link #list} javadoc). */
    private Cluster requireOwned(String username, long id) {
        var cluster = repository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Cluster " + id + " not found"));
        if (!cluster.getCreatedBy().equals(username)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "You can only manage clusters you registered yourself");
        }
        return cluster;
    }

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
            c.getCreatedAt(), c.getLastCheckedAt(), c.getLastCheckOk(), c.getStatus());
    }
}
