package com.kubemind.helm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubemind.audit.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

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

    /** chartRef is null when the chart couldn't be auto-resolved (see {@link #resolveChartRef})
     *  and nobody linked one manually — the frontend disables editing values in that case,
     *  since there's no chart reference to `helm upgrade` against without one. */
    public record ReleaseDetail(String values, String manifest, String notes, String chartRef) {}

    public ReleaseDetail detail(long clusterId, String namespace, String name) {
        String values = cli.run(clusterId, List.of("get", "values", name, "-n", namespace));
        String manifest = cli.run(clusterId, List.of("get", "manifest", name, "-n", namespace));
        String notes = cli.runAllowingEmpty(clusterId, List.of("get", "notes", name, "-n", namespace));
        String chartRef = installRepository.findByClusterIdAndNamespaceAndReleaseName(clusterId, namespace, name)
            .map(HelmInstall::getChartRef)
            .orElseGet(() -> resolveChartRef(clusterId, namespace, name));
        return new ReleaseDetail(values, manifest, notes, chartRef);
    }

    /**
     * Auto-resolves a release's chart reference without asking the user:
     * `helm get metadata` gives the bare chart name (e.g. "grafana", never
     * concatenated with a version — unlike `helm list`'s "chart" field, which is
     * "grafana-10.5.15" and would need fragile string-splitting), then that name is
     * searched across every repo the user has already added. If it matches exactly one
     * repo, that's unambiguous enough to trust and persist automatically. If it matches
     * zero or several, we back off and let the user pick manually (see linkChartRef) —
     * guessing wrong here would silently point "Save & Upgrade" at the wrong chart.
     */
    private String resolveChartRef(long clusterId, String namespace, String releaseName) {
        try {
            String metaJson = cli.run(clusterId, List.of("get", "metadata", releaseName, "-n", namespace, "-o", "json"));
            HelmMetadataDto meta = objectMapper.readValue(metaJson, HelmMetadataDto.class);
            if (meta.chart() == null || meta.chart().isBlank()) return null;

            String searchOut = cli.runAllowingEmpty(clusterId, List.of("search", "repo", meta.chart(), "-o", "json"));
            List<HelmChartDto> matches = HelmJson.parseArray(objectMapper, searchOut, new TypeReference<List<HelmChartDto>>() {}).stream()
                .filter(c -> {
                    int slash = c.name().indexOf('/');
                    return slash >= 0 && c.name().substring(slash + 1).equals(meta.chart());
                })
                .toList();

            if (matches.size() != 1) return null; // none or ambiguous — don't guess

            String chartRef = matches.get(0).name();
            installRepository.save(new HelmInstall(clusterId, namespace, releaseName, chartRef));
            return chartRef;
        } catch (Exception e) {
            return null; // best-effort — editing just stays locked if this fails for any reason
        }
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
     * Manually associates a release with a chart reference when auto-resolution
     * ({@link #resolveChartRef}) couldn't find an unambiguous match — unlocks values
     * editing for it. Validates the reference actually resolves (`helm show chart`)
     * before trusting it, so a typo doesn't silently wire up a bogus "Save & Upgrade"
     * that fails later.
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
            auditService.record(username, clusterId, "LINK_HELM_CHART_REF", ref, Map.of("chart", chartRef), true, null);
        } catch (ResponseStatusException e) {
            auditService.record(username, clusterId, "LINK_HELM_CHART_REF", ref, Map.of("chart", chartRef), false, e.getReason());
            throw e;
        }
    }
}
