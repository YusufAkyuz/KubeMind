package com.kubemind.cluster;

import io.fabric8.kubernetes.client.Client;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.RequestConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClusterClientFactoryTest {

    private ClusterRepository repository;
    private CryptoService crypto;
    private KubernetesClient defaultClient;
    private ClusterClientFactory factory;

    @BeforeEach
    void setUp() {
        repository = mock(ClusterRepository.class);
        crypto = mock(CryptoService.class);
        defaultClient = mock(KubernetesClient.class);
        factory = factoryWithImpersonation(false);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private ClusterClientFactory factoryWithImpersonation(boolean enabled) {
        var oidcProperties = new com.kubemind.auth.oidc.KubemindOidcProperties(null, null, null, "groups", null);
        return new ClusterClientFactory(defaultClient, repository, crypto,
            new ImpersonationResolver(oidcProperties), new ImpersonationProperties(enabled));
    }

    private void loggedInAs(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken(username, "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    // ── Approval choke point (unchanged behaviour) ───────────────────────────

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

    // ── Impersonation off: the pre-existing behaviour, byte for byte ─────────

    @Test
    void withoutImpersonationTheBuiltInClusterIsTheAmbientIdentity() {
        loggedInAs("bob", "USER");

        assertThat(factory.getClient(ClusterClientFactory.DEFAULT_CLUSTER_ID)).isSameAs(defaultClient);
    }

    @Test
    void withoutImpersonationNoAuthenticationIsStillFine() {
        // A @Scheduled job has no HTTP request, so no Authentication is ever set.
        assertThat(factory.getClient(ClusterClientFactory.DEFAULT_CLUSTER_ID)).isSameAs(defaultClient);
    }

    // ── Impersonation on ────────────────────────────────────────────────────

    /** The whole point of the feature: a caller must never silently inherit the
     *  ServiceAccount's rights because we failed to work out who they are. */
    @Test
    void withImpersonationAnUnidentifiedCallerFailsClosed() {
        var f = factoryWithImpersonation(true);

        assertThatThrownBy(() -> f.getClient(ClusterClientFactory.DEFAULT_CLUSTER_ID))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getReason())
                .contains("no caller identity"));
    }

    @Test
    void withImpersonationTheCallerIsSentAsImpersonateUser() {
        var derived = mock(KubernetesClient.class);
        var asClient = mock(Client.class);
        when(defaultClient.getConfiguration()).thenReturn(new ConfigBuilder().build());
        when(defaultClient.newClient(any(RequestConfig.class))).thenReturn(asClient);
        when(asClient.adapt(KubernetesClient.class)).thenReturn(derived);
        loggedInAs("alice", "USER");

        var result = factoryWithImpersonation(true).getClient(ClusterClientFactory.DEFAULT_CLUSTER_ID);

        assertThat(result).isSameAs(derived).isNotSameAs(defaultClient);
        var captor = ArgumentCaptor.forClass(RequestConfig.class);
        org.mockito.Mockito.verify(defaultClient).newClient(captor.capture());
        assertThat(captor.getValue().getImpersonateUsername()).isEqualTo("alice");
    }

    /** Scheduled work runs on nobody's behalf, so it must keep reaching the
     *  cluster as the installation itself even with impersonation on. */
    @Test
    void systemClientIsNeverImpersonatedEvenWithImpersonationOn() {
        var f = factoryWithImpersonation(true);

        assertThat(f.getSystemClient(ClusterClientFactory.DEFAULT_CLUSTER_ID)).isSameAs(defaultClient);
    }

    /** Registered clusters already run under their owner's own kubeconfig —
     *  impersonation is a built-in-cluster concern only. */
    @Test
    void registeredClustersAreUnaffectedByImpersonation() {
        when(repository.findById(5L)).thenReturn(
            Optional.of(new Cluster("dev", "enc", "bob", "PENDING")));
        var f = factoryWithImpersonation(true);

        assertThatThrownBy(() -> f.getClient(5L))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
    }
}
