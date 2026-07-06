package com.kubemind.k8s;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Generic view/edit YAML for cluster-scoped kinds (currently just Namespace). ADMIN-only, audited. */
@RestController
@RequestMapping("/api/clusters/{clusterId}/resources/{kind}/{name}/yaml")
@PreAuthorize("hasRole('ADMIN')")
public class ClusterScopedResourceController {

    private final ResourceEditService resourceEditService;

    public ClusterScopedResourceController(ResourceEditService resourceEditService) {
        this.resourceEditService = resourceEditService;
    }

    @GetMapping(produces = "application/yaml")
    public String getYaml(@PathVariable long clusterId, @PathVariable String kind, @PathVariable String name) {
        return resourceEditService.getYamlClusterScoped(clusterId, kind, name);
    }

    @PutMapping(consumes = {MediaType.TEXT_PLAIN_VALUE, "application/yaml"})
    public ResourceCreationService.CreatedResourceDto applyYaml(
        @PathVariable long clusterId, @PathVariable String kind, @PathVariable String name,
        @RequestBody String yaml, Authentication auth
    ) {
        return resourceEditService.applyYamlClusterScoped(auth.getName(), clusterId, kind, name, yaml);
    }
}
