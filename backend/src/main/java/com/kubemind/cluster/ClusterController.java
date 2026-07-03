package com.kubemind.cluster;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
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

    /** Every authenticated user needs the list for the cluster switcher. */
    @GetMapping
    public List<ClusterDto> list() {
        return clusterService.list();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ClusterDto> create(@Valid @RequestBody CreateClusterRequest request,
                                             Authentication auth) {
        var dto = clusterService.create(auth.getName(), request.name().trim(), request.kubeconfig());
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable long id, Authentication auth) {
        clusterService.delete(auth.getName(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    @PreAuthorize("hasRole('ADMIN')")
    public ClusterDto test(@PathVariable long id) {
        return clusterService.testConnection(id);
    }
}
