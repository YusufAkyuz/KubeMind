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

/** Chart repositories — machine-wide (see HelmRepoService), ADMIN-only to mutate. */
@RestController
@RequestMapping("/api/clusters/{clusterId}/helm/repos")
public class HelmRepoController {

    private final HelmRepoService service;

    public HelmRepoController(HelmRepoService service) {
        this.service = service;
    }

    public record AddRepoRequest(@NotBlank String name, @NotBlank String url) {}

    @GetMapping
    public List<HelmRepoDto> list(@PathVariable long clusterId) {
        return service.list(clusterId);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> add(@PathVariable long clusterId, @Valid @RequestBody AddRepoRequest request, Authentication auth) {
        service.add(auth.getName(), clusterId, request.name(), request.url());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{name}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> remove(@PathVariable long clusterId, @PathVariable String name, Authentication auth) {
        service.remove(auth.getName(), clusterId, name);
        return ResponseEntity.noContent().build();
    }
}
