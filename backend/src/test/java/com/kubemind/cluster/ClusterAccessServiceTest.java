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
 * Guards which door opens for whom: you reach the clusters you registered
 * yourself, and nothing else. Every cluster-scoped route runs through this via
 * ClusterAccessInterceptor (reads) and @PreAuthorize (writes).
 */
class ClusterAccessServiceTest {

    private ClusterRepository repository;
    private ClusterAccessService service;

    @BeforeEach
    void setUp() {
        repository = mock(ClusterRepository.class);
        service = new ClusterAccessService(repository, new ImpersonationProperties(false));
    }

    private static Authentication auth(String username, String role) {
        return new UsernamePasswordAuthenticationToken(username, "n/a",
            List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static Cluster cluster(String owner, String status) {
        return new Cluster("staging", "encrypted-kubeconfig", owner, status);
    }

    @Test
    void adminReachesAnyCluster() {
        when(repository.findById(7L)).thenReturn(Optional.of(cluster("someone-else", "APPROVED")));

        assertThat(service.canRead(auth("admin", "ADMIN"), 7L)).isTrue();
        assertThat(service.canWrite(auth("admin", "ADMIN"), 7L)).isTrue();
    }

    @Test
    void userReachesTheirOwnApprovedCluster() {
        when(repository.findById(7L)).thenReturn(Optional.of(cluster("bob", "APPROVED")));

        assertThat(service.canRead(auth("bob", "USER"), 7L)).isTrue();
    }

    @Test
    void userCannotReachSomeoneElsesCluster() {
        when(repository.findById(7L)).thenReturn(Optional.of(cluster("alice", "APPROVED")));

        assertThat(service.canRead(auth("bob", "USER"), 7L)).isFalse();
        assertThat(service.canWrite(auth("bob", "USER"), 7L)).isFalse();
    }

    /** Registered-but-unreviewed clusters stay unusable until an ADMIN approves. */
    @Test
    void userCannotReachTheirOwnPendingCluster() {
        when(repository.findById(7L)).thenReturn(Optional.of(cluster("bob", "PENDING")));

        assertThat(service.canRead(auth("bob", "USER"), 7L)).isFalse();
    }

    @Test
    void userCannotReachRejectedCluster() {
        when(repository.findById(7L)).thenReturn(Optional.of(cluster("bob", "REJECTED")));

        assertThat(service.canRead(auth("bob", "USER"), 7L)).isFalse();
    }

    /**
     * The built-in cluster is the installation's own identity — usually
     * cluster-admin — so it is never handed to a non-ADMIN.
     */
    @Test
    void userNeverReachesTheBuiltInLocalCluster() {
        assertThat(service.canRead(auth("bob", "USER"), ClusterClientFactory.DEFAULT_CLUSTER_ID)).isFalse();
        assertThat(service.canWrite(auth("bob", "USER"), ClusterClientFactory.DEFAULT_CLUSTER_ID)).isFalse();
    }

    @Test
    void adminStillReachesTheBuiltInLocalCluster() {
        assertThat(service.canRead(auth("admin", "ADMIN"), ClusterClientFactory.DEFAULT_CLUSTER_ID)).isTrue();
    }

    @Test
    void nobodyReachesAClusterThatDoesNotExist() {
        when(repository.findById(404L)).thenReturn(Optional.empty());

        assertThat(service.canRead(auth("bob", "USER"), 404L)).isFalse();
    }
}
