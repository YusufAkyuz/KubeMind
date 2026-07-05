package com.kubemind.k8s;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Generic view/edit YAML for any {@link ResourceEditService#EDITABLE_KINDS} kind. ADMIN-only, audited. */
@RestController
@RequestMapping("/api/clusters/{clusterId}/namespaces/{ns}/resources/{kind}/{name}/yaml")
@PreAuthorize("hasRole('ADMIN')")
public class ResourceEditController {

    private final ResourceEditService resourceEditService;

    public ResourceEditController(ResourceEditService resourceEditService) {
        this.resourceEditService = resourceEditService;
    }

    @GetMapping(produces = "application/yaml")
    public String getYaml(@PathVariable long clusterId, @PathVariable String ns,
                          @PathVariable String kind, @PathVariable String name) {
        return resourceEditService.getYaml(clusterId, kind, ns, name);
    }

    @PutMapping(consumes = {org.springframework.http.MediaType.TEXT_PLAIN_VALUE, "application/yaml"})
    public ResourceCreationService.CreatedResourceDto applyYaml(
        @PathVariable long clusterId, @PathVariable String ns,
        @PathVariable String kind, @PathVariable String name,
        @RequestBody String yaml, Authentication auth
    ) {
        return resourceEditService.applyYaml(auth.getName(), clusterId, kind, ns, name, yaml);
    }
}
