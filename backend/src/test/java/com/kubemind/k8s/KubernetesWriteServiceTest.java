package com.kubemind.k8s;

import com.kubemind.audit.AuditService;
import com.kubemind.cluster.ClusterClientFactory;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@EnableKubernetesMockClient(crud = true)
class KubernetesWriteServiceTest {

    KubernetesClient client; // injected by @EnableKubernetesMockClient

    private AuditService auditService;
    private KubernetesWriteService service;

    @BeforeEach
    void setUp() {
        auditService = mock(AuditService.class);
        var factory = mock(ClusterClientFactory.class);
        when(factory.getClient(0L)).thenReturn(client);
        service = new KubernetesWriteService(factory, auditService);
    }

    private Deployment sampleDeployment(String ns, String name, int replicas) {
        return new DeploymentBuilder()
            .withNewMetadata().withName(name).withNamespace(ns).endMetadata()
            .withNewSpec()
                .withReplicas(replicas)
                .withNewSelector().addToMatchLabels("app", name).endSelector()
                .withNewTemplate()
                    .withNewMetadata().addToLabels("app", name).endMetadata()
                    .withNewSpec()
                        .addNewContainer().withName("main").withImage("nginx:1.27").endContainer()
                    .endSpec()
                .endTemplate()
            .endSpec()
            .build();
    }

    // ── Scale (Deployment) ──────────────────────────────────────────────────────

    @Test
    void scaleRejectsOutOfRangeReplicas() {
        assertThatThrownBy(() -> service.scaleDeployment("admin", 0L, "default", "web", -1))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("replicas");
        assertThatThrownBy(() -> service.scaleDeployment("admin", 0L, "default", "web", 501))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("replicas");
    }

    @Test
    void scaleUnknownDeploymentReturns404AndAuditsFailure() {
        assertThatThrownBy(() -> service.scaleDeployment("admin", 0L, "default", "ghost", 2))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not found");
        verify(auditService).record(eq("admin"), eq(0L), eq("SCALE_RESOURCE"),
            eq("Deployment/default/ghost"), any(), eq(false), anyString());
    }

    @Test
    void scaleUpdatesReplicasAndAuditsSuccess() {
        client.apps().deployments().inNamespace("default")
            .resource(sampleDeployment("default", "web", 1)).create();

        var dto = service.scaleDeployment("admin", 0L, "default", "web", 3);

        assertThat(dto.desiredReplicas()).isEqualTo(3);
        verify(auditService).record(eq("admin"), eq(0L), eq("SCALE_RESOURCE"),
            eq("Deployment/default/web"), any(), eq(true), eq(null));
    }

    @Test
    void restartDeploymentStampsAnnotationAndAudits() {
        client.apps().deployments().inNamespace("default")
            .resource(sampleDeployment("default", "web", 2)).create();

        service.restartDeployment("admin", 0L, "default", "web");

        var updated = client.apps().deployments().inNamespace("default").withName("web").get();
        assertThat(updated.getSpec().getTemplate().getMetadata().getAnnotations())
            .containsKey("kubectl.kubernetes.io/restartedAt");
        verify(auditService).record(eq("admin"), eq(0L), eq("RESTART_RESOURCE"),
            eq("Deployment/default/web"), eq(null), eq(true), eq(null));
    }

    // ── Scale + restart (StatefulSet) ───────────────────────────────────────────

    @Test
    void scaleStatefulSetUpdatesReplicasAndAudits() {
        client.apps().statefulSets().inNamespace("default").resource(
            new StatefulSetBuilder()
                .withNewMetadata().withName("db").withNamespace("default").endMetadata()
                .withNewSpec()
                    .withServiceName("db")
                    .withReplicas(1)
                    .withNewSelector().addToMatchLabels("app", "db").endSelector()
                    .withNewTemplate()
                        .withNewMetadata().addToLabels("app", "db").endMetadata()
                        .withNewSpec()
                            .addNewContainer().withName("main").withImage("postgres:16").endContainer()
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build()
        ).create();

        var dto = service.scaleStatefulSet("admin", 0L, "default", "db", 3);

        assertThat(dto.desiredReplicas()).isEqualTo(3);
        verify(auditService).record(eq("admin"), eq(0L), eq("SCALE_RESOURCE"),
            eq("StatefulSet/default/db"), any(), eq(true), eq(null));
    }

    @Test
    void restartStatefulSetStampsAnnotationAndAudits() {
        client.apps().statefulSets().inNamespace("default").resource(
            new StatefulSetBuilder()
                .withNewMetadata().withName("db").withNamespace("default").endMetadata()
                .withNewSpec()
                    .withServiceName("db")
                    .withReplicas(1)
                    .withNewSelector().addToMatchLabels("app", "db").endSelector()
                    .withNewTemplate()
                        .withNewMetadata().addToLabels("app", "db").endMetadata()
                        .withNewSpec()
                            .addNewContainer().withName("main").withImage("postgres:16").endContainer()
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build()
        ).create();

        service.restartStatefulSet("admin", 0L, "default", "db");

        var updated = client.apps().statefulSets().inNamespace("default").withName("db").get();
        assertThat(updated.getSpec().getTemplate().getMetadata().getAnnotations())
            .containsKey("kubectl.kubernetes.io/restartedAt");
        verify(auditService).record(eq("admin"), eq(0L), eq("RESTART_RESOURCE"),
            eq("StatefulSet/default/db"), eq(null), eq(true), eq(null));
    }

    @Test
    void restartStatefulSetUnknownReturns404AndAudits() {
        assertThatThrownBy(() -> service.restartStatefulSet("admin", 0L, "default", "ghost"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not found");
        verify(auditService).record(eq("admin"), eq(0L), eq("RESTART_RESOURCE"),
            eq("StatefulSet/default/ghost"), eq(null), eq(false), anyString());
    }

    // ── Restart (DaemonSet) ──────────────────────────────────────────────────────

    @Test
    void restartDaemonSetStampsAnnotationAndAudits() {
        client.apps().daemonSets().inNamespace("default").resource(
            new DaemonSetBuilder()
                .withNewMetadata().withName("agent").withNamespace("default").endMetadata()
                .withNewSpec()
                    .withNewSelector().addToMatchLabels("app", "agent").endSelector()
                    .withNewTemplate()
                        .withNewMetadata().addToLabels("app", "agent").endMetadata()
                        .withNewSpec()
                            .addNewContainer().withName("main").withImage("fluentd:1.16").endContainer()
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build()
        ).create();

        service.restartDaemonSet("admin", 0L, "default", "agent");

        var updated = client.apps().daemonSets().inNamespace("default").withName("agent").get();
        assertThat(updated.getSpec().getTemplate().getMetadata().getAnnotations())
            .containsKey("kubectl.kubernetes.io/restartedAt");
        verify(auditService).record(eq("admin"), eq(0L), eq("RESTART_RESOURCE"),
            eq("DaemonSet/default/agent"), eq(null), eq(true), eq(null));
    }

    // ── Delete pod ────────────────────────────────────────────────────────────

    @Test
    void deleteUnknownPodReturns404() {
        assertThatThrownBy(() -> service.deletePod("admin", 0L, "default", "ghost"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not found");
    }

    @Test
    void deletePodRemovesItAndAuditsSuccess() {
        client.pods().inNamespace("default").resource(new PodBuilder()
            .withNewMetadata().withName("p1").withNamespace("default").endMetadata()
            .build()).create();

        service.deletePod("admin", 0L, "default", "p1");

        assertThat(client.pods().inNamespace("default").withName("p1").get()).isNull();
        verify(auditService).record(eq("admin"), eq(0L), eq("DELETE_POD"),
            eq("Pod/default/p1"), eq(null), eq(true), eq(null));
    }
}
