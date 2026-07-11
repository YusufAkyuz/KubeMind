package com.kubemind.helm;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Installed Helm releases — see HelmCliService for the trust model (same tier as Cluster Terminal). */
@RestController
@RequestMapping("/api/clusters/{clusterId}")
public class HelmReleaseController {

    private final HelmReleaseService service;

    public HelmReleaseController(HelmReleaseService service) {
        this.service = service;
    }

    @GetMapping("/namespaces/{ns}/helm/releases")
    public List<HelmReleaseDto> list(@PathVariable long clusterId, @PathVariable String ns) {
        return service.list(clusterId, ns);
    }

    @GetMapping("/namespaces/{ns}/helm/releases/{name}")
    public HelmReleaseService.ReleaseDetail detail(@PathVariable long clusterId, @PathVariable String ns, @PathVariable String name) {
        return service.detail(clusterId, ns, name);
    }

    @DeleteMapping("/namespaces/{ns}/helm/releases/{name}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> uninstall(@PathVariable long clusterId, @PathVariable String ns, @PathVariable String name,
                                          Authentication auth) {
        service.uninstall(auth.getName(), clusterId, ns, name);
        return ResponseEntity.noContent().build();
    }

    public record LinkChartRefRequest(@NotBlank String chartRef) {}

    /** Unlocks values editing for a release installed outside KubeMind — see HelmReleaseService.linkChartRef. */
    @PostMapping("/namespaces/{ns}/helm/releases/{name}/chart-ref")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> linkChartRef(@PathVariable long clusterId, @PathVariable String ns, @PathVariable String name,
                                             @Valid @RequestBody LinkChartRefRequest request, Authentication auth) {
        service.linkChartRef(auth.getName(), clusterId, ns, name, request.chartRef());
        return ResponseEntity.noContent().build();
    }
}
