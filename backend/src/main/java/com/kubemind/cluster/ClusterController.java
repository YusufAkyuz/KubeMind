package com.kubemind.cluster;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/clusters")
public class ClusterController {

    private final ClusterService clusterService;

    public ClusterController(ClusterService clusterService) {
        this.clusterService = clusterService;
    }

    public record CreateClusterRequest(
        @NotBlank @Size(max = 128) String name,
        @NotBlank String kubeconfig
    ) {}

    /** Self-service: everyone only ever sees the clusters they themselves
     *  registered, plus the built-in cluster (open to all — see ClusterService's
     *  javadoc for why that one's not scoped per-user). */
    @GetMapping
    public List<ClusterDto> list(Authentication auth) {
        return clusterService.list(auth.getName());
    }

    /** ADMIN-only, minimal view of requests awaiting a decision — see
     *  ClusterService#listPendingRequests. */
    @GetMapping("/pending")
    @PreAuthorize("hasRole('ADMIN')")
    public List<PendingClusterDto> pending() {
        return clusterService.listPendingRequests();
    }

    @PostMapping
    public ResponseEntity<ClusterDto> create(@Valid @RequestBody CreateClusterRequest request,
                                             Authentication auth) {
        var dto = clusterService.create(auth.getName(), isAdmin(auth), request.name().trim(), request.kubeconfig());
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, Authentication auth) {
        clusterService.delete(auth.getName(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    public ClusterDto test(@PathVariable long id, Authentication auth) {
        return clusterService.testConnection(auth.getName(), id);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ClusterDto approve(@PathVariable long id, Authentication auth) {
        return clusterService.approve(auth.getName(), id);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ClusterDto reject(@PathVariable long id, Authentication auth) {
        return clusterService.reject(auth.getName(), id);
    }

    private boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch("ROLE_ADMIN"::equals);
    }
}
