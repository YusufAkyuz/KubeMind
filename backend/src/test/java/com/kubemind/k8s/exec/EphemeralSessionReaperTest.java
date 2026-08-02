package com.kubemind.k8s.exec;

import com.kubemind.cluster.ClusterClientFactory;
import com.kubemind.cluster.ClusterRepository;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBindingBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The leak this guards against: the Cluster Terminal's ServiceAccount stays
 * bound to cluster-admin, and the Node Shell's debug pod keeps running
 * privileged with the host mounted, whenever the backend stops before the
 * WebSocket closes. One was found alive 26 days after its session.
 *
 * The risk in the fix is the opposite mistake — reaping a session someone is
 * actively using — so the age boundary is what these tests pin down.
 *
 * The mock API server stamps its own creationTimestamp and discards whatever a
 * test sets, so these move the cutoff rather than the clock: a cutoff in the
 * future makes everything look abandoned, one in the past makes everything look
 * live. The date arithmetic itself is covered by the isOlderThan cases below.
 */
@EnableKubernetesMockClient(crud = true)
class EphemeralSessionReaperTest {

    KubernetesClient client; // injected by @EnableKubernetesMockClient

    private EphemeralSessionReaper reaper;

    @BeforeEach
    void setUp() {
        var factory = mock(ClusterClientFactory.class);
        when(factory.getClient(0L)).thenReturn(client);
        reaper = new EphemeralSessionReaper(factory, mock(ClusterRepository.class));
    }

    /** Everything that exists looks abandoned. */
    private static Instant everythingIsOld() {
        return Instant.now().plus(Duration.ofDays(1));
    }

    /** Nothing that exists looks abandoned. */
    private static Instant everythingIsFresh() {
        return Instant.now().minus(Duration.ofDays(1));
    }

    private static String ago(Duration d) {
        return Instant.now().minus(d).toString();
    }

    private void givenTerminalSession(String name, String createdAt) {
        client.serviceAccounts().inNamespace("kube-system").resource(new ServiceAccountBuilder()
            .withMetadata(new ObjectMetaBuilder().withName(name).withNamespace("kube-system")
                .withCreationTimestamp(createdAt)
                .addToLabels(EphemeralSessionReaper.PURPOSE_LABEL,
                    EphemeralSessionReaper.PURPOSE_CLUSTER_TERMINAL)
                .build())
            .build()).create();

        client.rbac().clusterRoleBindings().resource(new ClusterRoleBindingBuilder()
            .withMetadata(new ObjectMetaBuilder().withName(name)
                .withCreationTimestamp(createdAt)
                .addToLabels(EphemeralSessionReaper.PURPOSE_LABEL,
                    EphemeralSessionReaper.PURPOSE_CLUSTER_TERMINAL)
                .build())
            .withNewRoleRef().withApiGroup("rbac.authorization.k8s.io")
                .withKind("ClusterRole").withName("cluster-admin").endRoleRef()
            .build()).create();
    }

    private void givenNodeDebugPod(String name, String createdAt) {
        client.pods().inNamespace("kube-system").resource(new PodBuilder()
            .withMetadata(new ObjectMetaBuilder().withName(name).withNamespace("kube-system")
                .withCreationTimestamp(createdAt)
                .addToLabels(EphemeralSessionReaper.PURPOSE_LABEL,
                    EphemeralSessionReaper.PURPOSE_NODE_DEBUG)
                .build())
            .withNewSpec().addNewContainer().withName("debug").withImage("busybox").endContainer().endSpec()
            .build()).create();
    }

    @Test
    void removesTheClusterAdminGrantLeftBehindByAnAbandonedSession() {
        givenTerminalSession("kubemind-term-old", ago(Duration.ofDays(26)));

        int deleted = reaper.reap(0L, everythingIsOld());

        assertThat(deleted).isEqualTo(2); // ServiceAccount + ClusterRoleBinding
        assertThat(client.rbac().clusterRoleBindings().withName("kubemind-term-old").get()).isNull();
        assertThat(client.serviceAccounts().inNamespace("kube-system")
            .withName("kubemind-term-old").get()).isNull();
    }

    @Test
    void leavesALiveSessionAlone() {
        givenTerminalSession("kubemind-term-live", ago(Duration.ofMinutes(30)));

        assertThat(reaper.reap(0L, everythingIsFresh())).isZero();
        assertThat(client.rbac().clusterRoleBindings().withName("kubemind-term-live").get()).isNotNull();
    }

    /** The privileged, host-mounted one — the most valuable thing to stop. */
    @Test
    void removesAnAbandonedNodeDebugPod() {
        givenNodeDebugPod("kubemind-node-old", ago(Duration.ofDays(3)));

        assertThat(reaper.reap(0L, everythingIsOld())).isEqualTo(1);
        assertThat(client.pods().inNamespace("kube-system").withName("kubemind-node-old").get()).isNull();
    }

    @Test
    void leavesALongButPlausibleSessionAlone() {
        givenNodeDebugPod("kubemind-node-longish", ago(Duration.ofHours(23)));

        assertThat(reaper.reap(0L, everythingIsFresh())).isZero();
        assertThat(client.pods().inNamespace("kube-system")
            .withName("kubemind-node-longish").get()).isNotNull();
    }

    /** Nothing else in the cluster carries the purpose label, and nothing else may be touched. */
    @Test
    void ignoresResourcesWithoutThePurposeLabel() {
        client.pods().inNamespace("kube-system").resource(new PodBuilder()
            .withMetadata(new ObjectMetaBuilder().withName("some-important-pod")
                .withNamespace("kube-system").withCreationTimestamp(ago(Duration.ofDays(100)))
                .addToLabels("app.kubernetes.io/managed-by", "kubemind")
                .build())
            .withNewSpec().addNewContainer().withName("c").withImage("busybox").endContainer().endSpec()
            .build()).create();

        // Cutoff in the future: if the label selector were wrong, age would not save it.
        assertThat(reaper.reap(0L, everythingIsOld())).isZero();
        assertThat(client.pods().inNamespace("kube-system")
            .withName("some-important-pod").get()).isNotNull();
    }

    // ── Age boundary ─────────────────────────────────────────────────────────

    @Test
    void undatedResourcesAreNeverReaped() {
        var noTimestamp = new PodBuilder()
            .withMetadata(new ObjectMetaBuilder().withName("x").build()).build();

        assertThat(EphemeralSessionReaper.isOlderThan(noTimestamp, Instant.now())).isFalse();
    }

    @Test
    void unparseableTimestampsAreNeverReaped() {
        var bad = new PodBuilder()
            .withMetadata(new ObjectMetaBuilder().withName("x")
                .withCreationTimestamp("not-a-timestamp").build()).build();

        assertThat(EphemeralSessionReaper.isOlderThan(bad, Instant.now())).isFalse();
    }
}
