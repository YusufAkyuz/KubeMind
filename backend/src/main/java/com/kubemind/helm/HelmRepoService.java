package com.kubemind.helm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubemind.audit.AuditService;
import com.kubemind.cluster.ClusterClientFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Chart repositories are a machine-wide `helm` CLI concept (stored in the
 * backend host's own repository config, not per-cluster) — clusterId here is
 * only used to pick which kubeconfig the underlying `helm` call runs with,
 * which repo commands don't actually need. Kept for symmetry with the other
 * Helm services and in case a future multi-tenant deployment wants to scope
 * repos differently.
 */
@Service
public class HelmRepoService {

    private final HelmCliService cli;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public HelmRepoService(HelmCliService cli, AuditService auditService, ObjectMapper objectMapper) {
        this.cli = cli;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    public List<HelmRepoDto> list(long clusterId) {
        String out = cli.runAllowingEmpty(clusterId, List.of("repo", "list", "-o", "json"));
        return HelmJson.parseArray(objectMapper, out, new TypeReference<>() {});
    }

    public void add(String username, long clusterId, String name, String url) {
        String ref = "HelmRepo/" + name;
        try {
            cli.run(clusterId, List.of("repo", "add", name, url));
            cli.run(clusterId, List.of("repo", "update", name));
            auditService.record(username, clusterId, "ADD_HELM_REPO", ref, java.util.Map.of("url", url), true, null);
        } catch (ResponseStatusException e) {
            auditService.record(username, clusterId, "ADD_HELM_REPO", ref, java.util.Map.of("url", url), false, e.getReason());
            throw e;
        }
    }

    public void remove(String username, long clusterId, String name) {
        String ref = "HelmRepo/" + name;
        try {
            cli.run(clusterId, List.of("repo", "remove", name));
            auditService.record(username, clusterId, "REMOVE_HELM_REPO", ref, null, true, null);
        } catch (ResponseStatusException e) {
            auditService.record(username, clusterId, "REMOVE_HELM_REPO", ref, null, false, e.getReason());
            throw e;
        }
    }
}
