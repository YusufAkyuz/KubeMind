package com.kubemind.k8s;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Generic delete for any {@link ResourceEditService#EDITABLE_KINDS} kind.
 * ADMIN can delete anywhere; a USER only on a cluster they registered
 * themselves (see ClusterAccessService). Audited either way.
 */
@RestController
@RequestMapping("/api/clusters/{clusterId}/namespaces/{ns}/resources/{kind}/{name}")
public class ResourceDeleteController {

    private final ResourceEditService resourceEditService;

    public ResourceDeleteController(ResourceEditService resourceEditService) {
        this.resourceEditService = resourceEditService;
    }

    @DeleteMapping
    @PreAuthorize("@clusterAccessService.canWrite(authentication, #clusterId)")
    public ResponseEntity<Void> delete(@PathVariable long clusterId, @PathVariable String ns,
                                       @PathVariable String kind, @PathVariable String name,
                                       Authentication auth) {
        resourceEditService.delete(auth.getName(), clusterId, kind, ns, name);
        return ResponseEntity.noContent().build();
    }
}
