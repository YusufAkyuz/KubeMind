package com.kubemind.cluster;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClusterClientFactoryTest {

    private ClusterRepository repository;
    private CryptoService crypto;
    private ClusterClientFactory factory;

    @BeforeEach
    void setUp() {
        repository = mock(ClusterRepository.class);
        crypto = mock(CryptoService.class);
        factory = new ClusterClientFactory(mock(KubernetesClient.class), repository, crypto);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void pendingClusterIsRejectedAtTheChokePoint() {
        when(repository.findById(5L)).thenReturn(
            Optional.of(new Cluster("dev", "enc", "bob", "PENDING")));

        assertThatThrownBy(() -> factory.getClient(5L))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> {
                var rse = (ResponseStatusException) e;
                assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(rse.getReason()).contains("pending");
            });
    }

    @Test
    void rejectedClusterIsRejectedAtTheChokePoint() {
        when(repository.findById(5L)).thenReturn(
            Optional.of(new Cluster("dev", "enc", "bob", "REJECTED")));

        assertThatThrownBy(() -> factory.getClient(5L))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void builtInClusterBypassesTheApprovalCheckEntirely() {
        KubernetesClient defaultClient = mock(KubernetesClient.class);
        var f = new ClusterClientFactory(defaultClient, repository, crypto);

        assertThat(f.getClient(ClusterClientFactory.DEFAULT_CLUSTER_ID)).isSameAs(defaultClient);
    }

    @Test
    void builtInClusterAllowsSystemCallsWithNoAuthentication() {
        // A @Scheduled job (e.g. ClusterProfileService.refreshAll) has no HTTP
        // request, so no Authentication is ever set.
        KubernetesClient defaultClient = mock(KubernetesClient.class);
        var f = new ClusterClientFactory(defaultClient, repository, crypto);

        assertThat(f.getClient(ClusterClientFactory.DEFAULT_CLUSTER_ID)).isSameAs(defaultClient);
    }

    @Test
    void builtInClusterAllowsAdmin() {
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        KubernetesClient defaultClient = mock(KubernetesClient.class);
        var f = new ClusterClientFactory(defaultClient, repository, crypto);

        assertThat(f.getClient(ClusterClientFactory.DEFAULT_CLUSTER_ID)).isSameAs(defaultClient);
    }

    @Test
    void builtInClusterAlsoAllowsAPlainUser() {
        // Maintainer-confirmed trade-off: the built-in cluster isn't scoped by a
        // per-user credential the way registered clusters are (it's the single
        // identity this KubeMind install runs as), so it's open to every
        // authenticated user, same as any other cluster's own access rules.
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("bob", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        KubernetesClient defaultClient = mock(KubernetesClient.class);
        var f = new ClusterClientFactory(defaultClient, repository, crypto);

        assertThat(f.getClient(ClusterClientFactory.DEFAULT_CLUSTER_ID)).isSameAs(defaultClient);
    }
}
