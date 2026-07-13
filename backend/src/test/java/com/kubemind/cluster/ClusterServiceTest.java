package com.kubemind.cluster;

import com.kubemind.audit.AuditService;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@EnableKubernetesMockClient(crud = true)
class ClusterServiceTest {

    KubernetesClient client; // injected by @EnableKubernetesMockClient

    private ClusterRepository repository;
    private CryptoService crypto;
    private ClusterClientFactory clientFactory;
    private AuditService auditService;
    private ClusterService service;

    private static final String FAKE_KUBECONFIG = """
        apiVersion: v1
        kind: Config
        clusters:
        - cluster:
            server: https://10.0.0.5:6443
          name: dev
        contexts:
        - context:
            cluster: dev
            user: dev
          name: dev
        current-context: dev
        users:
        - name: dev
          user:
            token: fake-token
        """;

    @BeforeEach
    void setUp() {
        repository = mock(ClusterRepository.class);
        crypto = mock(CryptoService.class);
        clientFactory = mock(ClusterClientFactory.class);
        auditService = mock(AuditService.class);
        service = new ClusterService(repository, crypto, clientFactory, auditService);

        when(crypto.isAvailable()).thenReturn(true);
        when(crypto.encrypt(anyString())).thenReturn("encrypted-blob");
        when(clientFactory.buildFromKubeconfig(anyString())).thenReturn(client);
        when(repository.save(any(Cluster.class))).thenAnswer(inv -> {
            Cluster c = inv.getArgument(0);
            if (c.getId() == null) c.setId(1L);
            return c;
        });
    }

    /** Stands in for the id JPA would assign on insert. */
    private static Cluster withId(long id, Cluster cluster) {
        cluster.setId(id);
        return cluster;
    }

    @Test
    void userCreationStartsPending() {
        when(repository.findByCreatedByAndName("bob", "dev")).thenReturn(Optional.empty());

        ClusterDto dto = service.create("bob", false, "dev", FAKE_KUBECONFIG);

        assertThat(dto.status()).isEqualTo("PENDING");
        verify(auditService).record(eq("bob"), any(), eq("REQUEST_CLUSTER"), eq("Cluster/dev"),
            any(), eq(true), eq(null));
    }

    @Test
    void adminCreationIsAutoApproved() {
        when(repository.findByCreatedByAndName("admin", "prod")).thenReturn(Optional.empty());

        ClusterDto dto = service.create("admin", true, "prod", FAKE_KUBECONFIG);

        assertThat(dto.status()).isEqualTo("APPROVED");
        verify(auditService).record(eq("admin"), any(), eq("ADD_CLUSTER"), eq("Cluster/prod"),
            any(), eq(true), eq(null));
    }

    @Test
    void differentUsersCanUseTheSameClusterName() {
        // Regression: name uniqueness used to be global (DB-level UNIQUE on
        // `name` alone), so a second user registering "eba_ex2" got a false
        // 409 CONFLICT even though it was a completely different owner's cluster.
        when(repository.findByCreatedByAndName("carol", "eba_ex2")).thenReturn(Optional.empty());

        ClusterDto dto = service.create("carol", false, "eba_ex2", FAKE_KUBECONFIG);

        assertThat(dto.status()).isEqualTo("PENDING");
    }

    @Test
    void sameOwnerCannotReuseAName() {
        when(repository.findByCreatedByAndName("bob", "dev")).thenReturn(
            Optional.of(new Cluster("dev", "enc", "bob", "APPROVED")));

        assertThatThrownBy(() -> service.create("bob", false, "dev", FAKE_KUBECONFIG))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void userSeesOwnClustersPlusTheBuiltInOne() {
        // The built-in cluster is open to everyone (see ClusterService.list
        // javadoc) — a USER isn't limited to only their own registered clusters.
        when(repository.findByCreatedBy("bob")).thenReturn(
            List.of(withId(1L, new Cluster("dev", "enc", "bob", "PENDING"))));

        List<ClusterDto> result = service.list("bob");

        assertThat(result).hasSize(2);
        assertThat(result).anyMatch(ClusterDto::builtIn);
        assertThat(result).filteredOn(c -> !c.builtIn()).allMatch(c -> "bob".equals(c.createdBy()));
    }

    @Test
    void adminSeesOnlyBuiltInPlusTheirOwnClusters() {
        // No exception for ADMIN: an admin never sees other users' clusters,
        // only the built-in one plus whatever they themselves registered.
        when(repository.findByCreatedBy("admin")).thenReturn(
            List.of(withId(2L, new Cluster("prod", "enc", "admin", "APPROVED"))));

        List<ClusterDto> result = service.list("admin");

        assertThat(result).hasSize(2);
        assertThat(result).anyMatch(ClusterDto::builtIn);
        assertThat(result).filteredOn(c -> !c.builtIn()).allMatch(c -> "admin".equals(c.createdBy()));
    }

    @Test
    void listPendingRequestsReturnsMinimalDtoOnly() {
        when(repository.findByStatus("PENDING")).thenReturn(List.of(
            withId(7L, new Cluster("eba_ex2", "super-secret-encrypted-blob", "yusuf.akyuz", "PENDING"))));

        List<PendingClusterDto> result = service.listPendingRequests();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("eba_ex2");
        assertThat(result.get(0).createdBy()).isEqualTo("yusuf.akyuz");
        // PendingClusterDto has no kubeconfig/health fields at all — nothing to
        // assert "isn't leaked" beyond the type itself only exposing id/name/owner/date.
    }

    @Test
    void nonOwnerCannotDeleteSomeoneElsesCluster() {
        Cluster carolsCluster = withId(5L, new Cluster("staging", "enc", "carol", "APPROVED"));
        when(repository.findById(5L)).thenReturn(Optional.of(carolsCluster));

        assertThatThrownBy(() -> service.delete("bob", 5L))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
        verify(repository, never()).delete(any(Cluster.class));
    }

    @Test
    void ownerCanDeleteTheirOwnCluster() {
        Cluster bobsCluster = withId(5L, new Cluster("dev", "enc", "bob", "PENDING"));
        when(repository.findById(5L)).thenReturn(Optional.of(bobsCluster));

        service.delete("bob", 5L);

        verify(repository).delete(bobsCluster);
        verify(clientFactory).evict(5L);
    }

    @Test
    void adminCannotDeleteSomeoneElsesCluster() {
        // No ADMIN bypass, deliberately: once approved, a cluster is private to
        // its owner, full stop — not even an admin can reach in and remove it.
        Cluster carolsCluster = withId(5L, new Cluster("staging", "enc", "carol", "APPROVED"));
        when(repository.findById(5L)).thenReturn(Optional.of(carolsCluster));

        assertThatThrownBy(() -> service.delete("admin", 5L))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
        verify(repository, never()).delete(any(Cluster.class));
    }

    @Test
    void approveFlipsStatusAndAudits() {
        Cluster pending = withId(5L, new Cluster("dev", "enc", "bob", "PENDING"));
        when(repository.findById(5L)).thenReturn(Optional.of(pending));

        ClusterDto dto = service.approve("admin", 5L);

        assertThat(dto.status()).isEqualTo("APPROVED");
        assertThat(pending.getReviewedBy()).isEqualTo("admin");
        verify(auditService).record(eq("admin"), eq(5L), eq("APPROVE_CLUSTER"), eq("Cluster/dev"),
            any(), eq(true), eq(null));
    }

    @Test
    void rejectFlipsStatusAndAudits() {
        Cluster pending = withId(5L, new Cluster("dev", "enc", "bob", "PENDING"));
        when(repository.findById(5L)).thenReturn(Optional.of(pending));

        ClusterDto dto = service.reject("admin", 5L);

        assertThat(dto.status()).isEqualTo("REJECTED");
        verify(auditService).record(eq("admin"), eq(5L), eq("REJECT_CLUSTER"), eq("Cluster/dev"),
            any(), eq(true), eq(null));
    }

    @Test
    void builtInClusterCannotBeDeleted() {
        assertThatThrownBy(() -> service.delete("admin", ClusterClientFactory.DEFAULT_CLUSTER_ID))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
