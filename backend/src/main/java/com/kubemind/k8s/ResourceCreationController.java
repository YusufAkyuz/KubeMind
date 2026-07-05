package com.kubemind.k8s;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Direct object creation (Phase: Create Resource). ADMIN-only, allowlisted kinds, audited. */
@RestController
@RequestMapping("/api/clusters/{clusterId}/namespaces/{ns}/resources")
@PreAuthorize("hasRole('ADMIN')")
public class ResourceCreationController {

    private final ResourceCreationService resourceCreationService;

    public ResourceCreationController(ResourceCreationService resourceCreationService) {
        this.resourceCreationService = resourceCreationService;
    }

    public record CreateRequest(@NotBlank String yaml) {}

    @GetMapping("/allowed-kinds")
    public List<String> allowedKinds() {
        return resourceCreationService.allowedKinds();
    }

    @PostMapping
    public ResponseEntity<ResourceCreationService.CreatedResourceDto> create(
        @PathVariable long clusterId, @PathVariable String ns,
        @Valid @RequestBody CreateRequest request, Authentication auth
    ) {
        var created = resourceCreationService.create(auth.getName(), clusterId, ns, request.yaml());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
