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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class HelmChartService {

    private final HelmCliService cli;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final HelmInstallRepository installRepository;

    public HelmChartService(HelmCliService cli, AuditService auditService, ObjectMapper objectMapper,
                            HelmInstallRepository installRepository) {
        this.cli = cli;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.installRepository = installRepository;
    }

    /** Searches across every added repo. A blank query still works (helm treats it as "match everything"). */
    public List<HelmChartDto> search(long clusterId, String query) {
        String out = cli.runAllowingEmpty(clusterId, List.of("search", "repo", query, "-o", "json"));
        return HelmJson.parseArray(objectMapper, out, new TypeReference<>() {});
    }

    /** `helm show values <chartRef>` — the chart's own default values.yaml, downloaded
     *  (and cached by helm locally) fresh from the repo. Used to pre-fill the install
     *  modal's values editor so the user edits real defaults instead of a blank box. */
    public String defaultValues(long clusterId, String chartRef) {
        return cli.run(clusterId, List.of("show", "values", chartRef));
    }

    /**
     * `helm upgrade --install` — creates the release if it doesn't exist yet,
     * upgrades it in place if it does. One code path instead of separate
     * install/upgrade endpoints; the UI always just calls this "Install".
     */
    public void install(String username, long clusterId, String namespace, String releaseName,
                        String chartRef, String valuesYaml) {
        String ref = "HelmRelease/" + namespace + "/" + releaseName;
        Path valuesFile = null;
        try {
            List<String> args = new ArrayList<>(List.of(
                "upgrade", "--install", releaseName, chartRef,
                "-n", namespace, "--create-namespace"));
            if (valuesYaml != null && !valuesYaml.isBlank()) {
                valuesFile = Files.createTempFile("kubemind-helm-values-", ".yaml");
                Files.writeString(valuesFile, valuesYaml);
                args.add("-f");
                args.add(valuesFile.toString());
            }

            cli.run(clusterId, args);
            rememberChartRef(clusterId, namespace, releaseName, chartRef);
            auditService.record(username, clusterId, "INSTALL_HELM_RELEASE", ref, Map.of("chart", chartRef), true, null);
        } catch (ResponseStatusException e) {
            auditService.record(username, clusterId, "INSTALL_HELM_RELEASE", ref, Map.of("chart", chartRef), false, e.getReason());
            throw e;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not write values file: " + e.getMessage());
        } finally {
            if (valuesFile != null) {
                try {
                    Files.deleteIfExists(valuesFile);
                } catch (IOException ignored) {}
            }
        }
    }

    private void rememberChartRef(long clusterId, String namespace, String releaseName, String chartRef) {
        var existing = installRepository.findByClusterIdAndNamespaceAndReleaseName(clusterId, namespace, releaseName);
        if (existing.isPresent()) {
            existing.get().setChartRef(chartRef);
            installRepository.save(existing.get());
        } else {
            installRepository.save(new HelmInstall(clusterId, namespace, releaseName, chartRef));
        }
    }
}
