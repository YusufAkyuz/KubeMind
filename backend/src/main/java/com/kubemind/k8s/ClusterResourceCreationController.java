package com.kubemind.k8s;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Direct cluster-scoped object creation (ClusterRole/ClusterRoleBinding). ADMIN-only, audited. */
@RestController
@RequestMapping("/api/clusters/{clusterId}/resources")
@PreAuthorize("hasRole('ADMIN')")
public class ClusterResourceCreationController {

    private final ClusterResourceCreationService service;

    public ClusterResourceCreationController(ClusterResourceCreationService service) {
        this.service = service;
    }

    public record CreateRequest(@NotBlank String yaml) {}

    @GetMapping("/allowed-kinds")
    public List<String> allowedKinds() {
        return service.allowedKinds();
    }

    @PostMapping
    public ResponseEntity<ResourceCreationService.CreatedResourceDto> create(
        @PathVariable long clusterId, @Valid @RequestBody CreateRequest request, Authentication auth
    ) {
        var created = service.create(auth.getName(), clusterId, request.yaml());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
