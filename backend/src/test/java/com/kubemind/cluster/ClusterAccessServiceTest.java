package com.kubemind.cluster;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Guards the write-authorization rule every cluster-scoped write endpoint hangs
 * off via @PreAuthorize("@clusterAccessService.canWrite(...)").
 */
class ClusterAccessServiceTest {

    private ClusterRepository repository;
    private ClusterAccessService service;

    @BeforeEach
    void setUp() {
        repository = mock(ClusterRepository.class);
        service = new ClusterAccessService(repository);
    }

    private static Authentication auth(String username, String role) {
        return new UsernamePasswordAuthenticationToken(username, "n/a",
            List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static Cluster cluster(String owner, String status) {
        return new Cluster("staging", "encrypted-kubeconfig", owner, status);
    }

    @Test
    void adminCanWriteAnywhere() {
        when(repository.findById(7L)).thenReturn(Optional.of(cluster("someone-else", "APPROVED")));

        assertThat(service.canWrite(auth("admin", "ADMIN"), 7L)).isTrue();
    }

    /** An ADMIN is not blocked by a cluster row that doesn't exist at all. */
    @Test
    void adminCanWriteWithoutTouchingTheRepository() {
        assertThat(service.canWrite(auth("admin", "ADMIN"), 999L)).isTrue();
    }

    @Test
    void userCanWriteOnTheirOwnApprovedCluster() {
        when(repository.findById(7L)).thenReturn(Optional.of(cluster("bob", "APPROVED")));

        assertThat(service.canWrite(auth("bob", "USER"), 7L)).isTrue();
    }

    @Test
    void userCannotWriteOnSomeoneElsesCluster() {
        when(repository.findById(7L)).thenReturn(Optional.of(cluster("alice", "APPROVED")));

        assertThat(service.canWrite(auth("bob", "USER"), 7L)).isFalse();
    }

    /** Registered-but-unreviewed clusters are unusable until an ADMIN approves them. */
    @Test
    void userCannotWriteOnTheirOwnPendingCluster() {
        when(repository.findById(7L)).thenReturn(Optional.of(cluster("bob", "PENDING")));

        assertThat(service.canWrite(auth("bob", "USER"), 7L)).isFalse();
    }

    @Test
    void userCannotWriteOnRejectedCluster() {
        when(repository.findById(7L)).thenReturn(Optional.of(cluster("bob", "REJECTED")));

        assertThat(service.canWrite(auth("bob", "USER"), 7L)).isFalse();
    }

    /**
     * Cluster 0 is the built-in "local" cluster — one identity shared by everyone,
     * not a per-user credential, so it has no owner row and stays ADMIN-only for writes.
     */
    @Test
    void userCannotWriteOnTheBuiltInLocalCluster() {
        when(repository.findById(ClusterClientFactory.DEFAULT_CLUSTER_ID)).thenReturn(Optional.empty());

        assertThat(service.canWrite(auth("bob", "USER"), ClusterClientFactory.DEFAULT_CLUSTER_ID)).isFalse();
    }

    @Test
    void userCannotWriteOnAClusterThatDoesNotExist() {
        when(repository.findById(404L)).thenReturn(Optional.empty());

        assertThat(service.canWrite(auth("bob", "USER"), 404L)).isFalse();
    }
}
