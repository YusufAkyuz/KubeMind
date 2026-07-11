package com.kubemind.helm;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Chart search + install. Search is read-only; install is ADMIN-only, audited (HelmChartService). */
@RestController
@RequestMapping("/api/clusters/{clusterId}")
public class HelmChartController {

    private final HelmChartService service;

    public HelmChartController(HelmChartService service) {
        this.service = service;
    }

    public record InstallRequest(
        @NotBlank String releaseName,
        @NotBlank String chartRef,
        String valuesYaml
    ) {}

    @GetMapping("/helm/charts/search")
    public List<HelmChartDto> search(@PathVariable long clusterId, @RequestParam(defaultValue = "") String q) {
        return service.search(clusterId, q);
    }

    /** The chart's own default values.yaml — pre-fills the install modal's editor. */
    @GetMapping(value = "/helm/charts/values", produces = "application/yaml")
    public String defaultValues(@PathVariable long clusterId, @RequestParam String chartRef) {
        return service.defaultValues(clusterId, chartRef);
    }

    @PostMapping("/namespaces/{ns}/helm/install")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> install(@PathVariable long clusterId, @PathVariable String ns,
                                        @Valid @RequestBody InstallRequest request, Authentication auth) {
        service.install(auth.getName(), clusterId, ns, request.releaseName(), request.chartRef(), request.valuesYaml());
        return ResponseEntity.noContent().build();
    }
}
