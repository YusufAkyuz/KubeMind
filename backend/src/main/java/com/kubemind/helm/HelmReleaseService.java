package com.kubemind.helm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubemind.audit.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Service
public class HelmReleaseService {

    private final HelmCliService cli;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final HelmInstallRepository installRepository;
    private final HelmReleaseChartExtractor extractor;

    public HelmReleaseService(HelmCliService cli, AuditService auditService, ObjectMapper objectMapper,
                              HelmInstallRepository installRepository, HelmReleaseChartExtractor extractor) {
        this.cli = cli;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.installRepository = installRepository;
        this.extractor = extractor;
    }

    public List<HelmReleaseDto> list(long clusterId, String namespace) {
        List<String> args = "all".equals(namespace)
            ? List.of("list", "--all-namespaces", "-o", "json")
            : List.of("list", "-n", namespace, "-o", "json");
        String out = cli.runAllowingEmpty(clusterId, args);
        return HelmJson.parseArray(objectMapper, out, new TypeReference<>() {});
    }

    /**
     * Revision log for one release.
     *
     * Reads purely from what Helm stored in the cluster, so it works for a
     * release installed from a terminal by someone who never told KubeMind
     * about a chart repository.
     */
    public List<HelmRevisionDto> history(long clusterId, String namespace, String name) {
        String out = cli.run(clusterId, List.of("history", name, "-n", namespace, "-o", "json"));
        return HelmJson.parseArray(objectMapper, out, new TypeReference<>() {});
    }

    /**
     * Re-applies a previous revision.
     *
     * Deliberately takes no chart reference: Helm replays the chart and values
     * it stored alongside that revision, which is why this is the one repair
     * action that works even when values editing is still locked behind a
     * missing chartRef (see {@link #resolveChartRef}).
     */
    public void rollback(String username, long clusterId, String namespace, String name, int revision) {
        String ref = "HelmRelease/" + namespace + "/" + name;
        Map<String, Object> payload = Map.of("revision", revision);
        try {
            cli.run(clusterId, List.of("rollback", name, String.valueOf(revision), "-n", namespace));
            auditService.record(username, clusterId, "ROLLBACK_HELM_RELEASE", ref, payload, true, null);
        } catch (ResponseStatusException e) {
            auditService.record(username, clusterId, "ROLLBACK_HELM_RELEASE", ref, payload, false, e.getReason());
            throw e;
        }
    }

    /**
     * @param chartRef      null when no repository chart could be resolved for this release
     * @param valuesEditable whether "Save & Upgrade" has any chart to work from — either the
     *                       one Helm stored in the cluster or a resolved chartRef. Availability
     *                       only; whether the stored chart is <em>complete</em> is settled at
     *                       upgrade time by the verification gate, which is the expensive check.
     */
    public record ReleaseDetail(String values, String manifest, String notes, String chartRef,
                                boolean valuesEditable) {}

    public ReleaseDetail detail(long clusterId, String namespace, String name) {
        // `-o yaml` matters: the default output prefixes a "USER-SUPPLIED VALUES:"
        // header, and this string is what the editor round-trips back into a
        // values file on save — where that header parses as a junk top-level key.
        String values = cli.run(clusterId, List.of("get", "values", name, "-n", namespace, "-o", "yaml"));
        String manifest = cli.run(clusterId, List.of("get", "manifest", name, "-n", namespace));
        String notes = cli.runAllowingEmpty(clusterId, List.of("get", "notes", name, "-n", namespace));
        String chartRef = installRepository.findByClusterIdAndNamespaceAndReleaseName(clusterId, namespace, name)
            .map(HelmInstall::getChartRef)
            .orElseGet(() -> resolveChartRef(clusterId, namespace, name));

        // Cheap: one API read plus a gunzip, no helm process.
        var stored = extractor.extract(clusterId, namespace, name);
        stored.ifPresent(c -> HelmReleaseChartExtractor.deleteRecursively(c.chartDir()));

        return new ReleaseDetail(values, manifest, notes, chartRef, stored.isPresent() || chartRef != null);
    }

    /**
     * What a chart repository is now good for.
     *
     * Registering a repo used to be the price of editing a release at all. It
     * buys something narrower and more honest now: knowing whether a newer chart
     * version exists. Without a repo everything else still works.
     *
     * @param chartRef null when no repository chart matches this release
     */
    public record ChartUpdate(String chartRef, String currentVersion, String latestVersion,
                              boolean updateAvailable) {}

    public ChartUpdate chartUpdate(long clusterId, String namespace, String name) {
        String currentVersion = null;
        try {
            String metaJson = cli.run(clusterId, List.of("get", "metadata", name, "-n", namespace, "-o", "json"));
            currentVersion = objectMapper.readValue(metaJson, HelmMetadataDto.class).version();
        } catch (Exception e) {
            // Version unknown — the caller just gets a card with nothing to offer.
        }

        String chartRef = installRepository.findByClusterIdAndNamespaceAndReleaseName(clusterId, namespace, name)
            .map(HelmInstall::getChartRef)
            .orElseGet(() -> resolveChartRef(clusterId, namespace, name));
        if (chartRef == null) {
            return new ChartUpdate(null, currentVersion, null, false);
        }

        String latestVersion = null;
        try {
            String searchOut = cli.runAllowingEmpty(clusterId, List.of("search", "repo", chartRef, "-o", "json"));
            latestVersion = HelmJson.parseArray(objectMapper, searchOut, new TypeReference<List<HelmChartDto>>() {})
                .stream()
                .filter(c -> chartRef.equals(c.name()))
                .map(HelmChartDto::version)
                .findFirst().orElse(null);
        } catch (Exception e) {
            // Repo unreachable or removed: report what we know, claim no update.
        }

        return new ChartUpdate(chartRef, currentVersion, latestVersion,
            ChartVersions.isNewer(latestVersion, currentVersion));
    }

    /**
     * Moves a release onto a different chart version — the one thing that
     * genuinely needs a repository, and now the only thing.
     *
     * Carries the release's current values across explicitly. A bare
     * `helm upgrade` would reset the release to the chart's defaults, which on a
     * production release is indistinguishable from wiping its configuration.
     */
    public void upgradeChartVersion(String username, long clusterId, String namespace, String name, String version) {
        String ref = "HelmRelease/" + namespace + "/" + name;
        Map<String, Object> payload = Map.of("version", version);
        Path valuesFile = null;
        try {
            String chartRef = installRepository.findByClusterIdAndNamespaceAndReleaseName(clusterId, namespace, name)
                .map(HelmInstall::getChartRef)
                .orElseGet(() -> resolveChartRef(clusterId, namespace, name));
            if (chartRef == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No chart repository is linked to this release, so there is no newer version to move to.");
            }

            String currentValues = cli.run(clusterId,
                List.of("get", "values", name, "-n", namespace, "-o", "yaml"));
            valuesFile = Files.createTempFile("kubemind-helm-values-", ".yaml");
            Files.writeString(valuesFile, currentValues == null ? "" : currentValues);

            cli.run(clusterId, List.of("upgrade", name, chartRef, "--version", version,
                "-n", namespace, "-f", valuesFile.toString()));
            auditService.record(username, clusterId, "UPGRADE_HELM_CHART", ref, payload, true, null);
        } catch (ResponseStatusException e) {
            auditService.record(username, clusterId, "UPGRADE_HELM_CHART", ref, payload, false, e.getReason());
            throw e;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Could not write values file: " + e.getMessage());
        } finally {
            if (valuesFile != null) {
                try {
                    Files.deleteIfExists(valuesFile);
                } catch (IOException ignored) {
                    // Best effort.
                }
            }
        }
    }

    /**
     * Re-applies a release with edited values.
     *
     * Prefers the chart Helm stored alongside the release, which makes this work
     * with no repository registered and — just as importantly — guarantees that
     * editing a value cannot also move the release onto a newer chart version
     * the way upgrading against a repository reference silently does.
     *
     * The stored chart is only used once it has been proven to reproduce the
     * running manifest; see HelmReleaseChartExtractor for the case that check
     * exists to catch. Otherwise this falls back to the repository reference,
     * and without one it refuses rather than guessing.
     */
    public void upgradeValues(String username, long clusterId, String namespace, String name, String valuesYaml) {
        String ref = "HelmRelease/" + namespace + "/" + name;
        var extracted = extractor.extract(clusterId, namespace, name);
        Path valuesFile = null;
        String source = null;
        try {
            String chartArg;
            if (extracted.isPresent()
                && extractor.rendersTheSameManifest(cli, clusterId, namespace, name, extracted.get())) {
                chartArg = extracted.get().chartDir().toString();
                source = "stored-chart";
            } else {
                chartArg = installRepository.findByClusterIdAndNamespaceAndReleaseName(clusterId, namespace, name)
                    .map(HelmInstall::getChartRef)
                    .orElseGet(() -> resolveChartRef(clusterId, namespace, name));
                if (chartArg == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This release's chart could not be recovered from the cluster — charts with subcharts "
                        + "are not stored in full. Link its chart from a repository to edit values.");
                }
                source = chartArg;
            }

            valuesFile = Files.createTempFile("kubemind-helm-values-", ".yaml");
            Files.writeString(valuesFile, valuesYaml == null ? "" : valuesYaml);

            cli.run(clusterId, List.of("upgrade", name, chartArg, "-n", namespace, "-f", valuesFile.toString()));
            auditService.record(username, clusterId, "UPGRADE_HELM_VALUES", ref,
                Map.of("chartSource", source), true, null);
        } catch (ResponseStatusException e) {
            auditService.record(username, clusterId, "UPGRADE_HELM_VALUES", ref,
                Map.of("chartSource", source == null ? "none" : source), false, e.getReason());
            throw e;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Could not write values file: " + e.getMessage());
        } finally {
            extracted.ifPresent(c -> HelmReleaseChartExtractor.deleteRecursively(c.chartDir()));
            if (valuesFile != null) {
                try {
                    Files.deleteIfExists(valuesFile);
                } catch (IOException ignored) {
                    // Best effort.
                }
            }
        }
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
