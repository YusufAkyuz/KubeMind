package com.kubemind.helm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubemind.audit.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class HelmReleaseService {

    private final HelmCliService cli;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final HelmInstallRepository installRepository;

    public HelmReleaseService(HelmCliService cli, AuditService auditService, ObjectMapper objectMapper,
                              HelmInstallRepository installRepository) {
        this.cli = cli;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.installRepository = installRepository;
    }

    public List<HelmReleaseDto> list(long clusterId, String namespace) {
        List<String> args = "all".equals(namespace)
            ? List.of("list", "--all-namespaces", "-o", "json")
            : List.of("list", "-n", namespace, "-o", "json");
        String out = cli.runAllowingEmpty(clusterId, args);
        return HelmJson.parseArray(objectMapper, out, new TypeReference<>() {});
    }

    /** chartRef is null when this release wasn't installed through KubeMind (or predates
     *  this feature) — the frontend disables editing values in that case, since there's
     *  no chart reference to `helm upgrade` against without one. */
    public record ReleaseDetail(String values, String manifest, String notes, String chartRef) {}

    public ReleaseDetail detail(long clusterId, String namespace, String name) {
        String values = cli.run(clusterId, List.of("get", "values", name, "-n", namespace));
        String manifest = cli.run(clusterId, List.of("get", "manifest", name, "-n", namespace));
        String notes = cli.runAllowingEmpty(clusterId, List.of("get", "notes", name, "-n", namespace));
        String chartRef = installRepository.findByClusterIdAndNamespaceAndReleaseName(clusterId, namespace, name)
            .map(HelmInstall::getChartRef)
            .orElse(null);
        return new ReleaseDetail(values, manifest, notes, chartRef);
    }

    public void uninstall(String username, long clusterId, String namespace, String name) {
        String ref = "HelmRelease/" + namespace + "/" + name;
        try {
            cli.run(clusterId, List.of("uninstall", name, "-n", namespace));
            auditService.record(username, clusterId, "UNINSTALL_HELM_RELEASE", ref, null, true, null);
        } catch (ResponseStatusException e) {
            auditService.record(username, clusterId, "UNINSTALL_HELM_RELEASE", ref, null, false, e.getReason());
            throw e;
        }
    }

    /**
     * Manually associates a release installed outside KubeMind (raw `helm install` on the
     * server) with a chart reference, unlocking values editing for it — same table
     * {@link HelmChartService#install} writes to automatically after an in-app install.
     * Validates the reference actually resolves (`helm show chart`) before trusting it,
     * so a typo doesn't silently wire up a bogus "Save & Upgrade" that fails later.
     */
    public void linkChartRef(String username, long clusterId, String namespace, String name, String chartRef) {
        String ref = "HelmRelease/" + namespace + "/" + name;
        try {
            cli.run(clusterId, List.of("show", "chart", chartRef));

            var existing = installRepository.findByClusterIdAndNamespaceAndReleaseName(clusterId, namespace, name);
            if (existing.isPresent()) {
                existing.get().setChartRef(chartRef);
                installRepository.save(existing.get());
            } else {
                installRepository.save(new HelmInstall(clusterId, namespace, name, chartRef));
            }
            auditService.record(username, clusterId, "LINK_HELM_CHART_REF", ref, java.util.Map.of("chart", chartRef), true, null);
        } catch (ResponseStatusException e) {
            auditService.record(username, clusterId, "LINK_HELM_CHART_REF", ref, java.util.Map.of("chart", chartRef), false, e.getReason());
            throw e;
        }
    }
}
