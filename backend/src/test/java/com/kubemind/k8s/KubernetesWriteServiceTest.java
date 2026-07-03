package com.kubemind.k8s;

import com.kubemind.audit.AuditService;
import com.kubemind.cluster.ClusterClientFactory;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyBoolean;
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

    // ── Scale ─────────────────────────────────────────────────────────────────

    @Test
    void scaleRejectsOutOfRangeReplicas() {
        assertThatThrownBy(() -> service.scaleDeployment("admin", 0L,"default", "web", -1))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("replicas");
        assertThatThrownBy(() -> service.scaleDeployment("admin", 0L,"default", "web", 501))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("replicas");
    }

    @Test
    void scaleUnknownDeploymentReturns404AndAuditsFailure() {
        assertThatThrownBy(() -> service.scaleDeployment("admin", 0L,"default", "ghost", 2))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not found");
        verify(auditService).record(eq("admin"), eq(0L), eq("SCALE_DEPLOYMENT"),
            eq("Deployment/default/ghost"), any(), eq(false), anyString());
    }

    @Test
    void scaleUpdatesReplicasAndAuditsSuccess() {
        client.apps().deployments().inNamespace("default")
            .resource(sampleDeployment("default", "web", 1)).create();

        var dto = service.scaleDeployment("admin", 0L,"default", "web", 3);

        assertThat(dto.desiredReplicas()).isEqualTo(3);
        verify(auditService).record(eq("admin"), eq(0L), eq("SCALE_DEPLOYMENT"),
            eq("Deployment/default/web"), any(), eq(true), eq(null));
    }

    // ── Delete pod ────────────────────────────────────────────────────────────

    @Test
    void deleteUnknownPodReturns404() {
        assertThatThrownBy(() -> service.deletePod("admin", 0L,"default", "ghost"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not found");
    }

    @Test
    void deletePodRemovesItAndAuditsSuccess() {
        client.pods().inNamespace("default").resource(new PodBuilder()
            .withNewMetadata().withName("p1").withNamespace("default").endMetadata()
            .build()).create();

        service.deletePod("admin", 0L,"default", "p1");

        assertThat(client.pods().inNamespace("default").withName("p1").get()).isNull();
        verify(auditService).record(eq("admin"), eq(0L), eq("DELETE_POD"),
            eq("Pod/default/p1"), eq(null), eq(true), eq(null));
    }

    // ── YAML apply ────────────────────────────────────────────────────────────

    @Test
    void applyYamlRejectsRenamedResource() {
        client.apps().deployments().inNamespace("default")
            .resource(sampleDeployment("default", "web", 1)).create();
        String yaml = Serialization.asYaml(sampleDeployment("default", "other-name", 1));

        assertThatThrownBy(() -> service.applyDeploymentYaml("admin", 0L,"default", "web", yaml))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("metadata.name");
        verify(auditService).record(eq("admin"), eq(0L), eq("EDIT_DEPLOYMENT_YAML"),
            eq("Deployment/default/web"), any(), eq(false), anyString());
    }

    @Test
    void applyYamlRejectsNamespaceChange() {
        String yaml = Serialization.asYaml(sampleDeployment("other-ns", "web", 1));

        assertThatThrownBy(() -> service.applyDeploymentYaml("admin", 0L,"default", "web", yaml))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("metadata.namespace");
    }

    @Test
    void applyYamlRejectsGarbageInput() {
        assertThatThrownBy(() -> service.applyDeploymentYaml("admin", 0L,"default", "web", "{not yaml:::"))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.applyDeploymentYaml("admin", 0L,"default", "web", "  "))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("empty");
    }

    @Test
    void applyYamlUpdatesDeploymentAndAuditsSuccess() {
        client.apps().deployments().inNamespace("default")
            .resource(sampleDeployment("default", "web", 1)).create();
        String yaml = Serialization.asYaml(sampleDeployment("default", "web", 4));

        var dto = service.applyDeploymentYaml("admin", 0L,"default", "web", yaml);

        assertThat(dto.desiredReplicas()).isEqualTo(4);
        verify(auditService).record(eq("admin"), eq(0L), eq("EDIT_DEPLOYMENT_YAML"),
            eq("Deployment/default/web"), any(), eq(true), eq(null));
    }

    // ── Audit never throws requirement is covered by AuditService itself; here we
    //    only assert every write path calls it (verified per test above). ──────
}
