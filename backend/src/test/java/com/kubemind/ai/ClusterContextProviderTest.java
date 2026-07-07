package com.kubemind.ai;

import com.kubemind.cluster.ClusterClientFactory;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnableKubernetesMockClient(crud = true)
class ClusterContextProviderTest {

    KubernetesClient client; // injected by @EnableKubernetesMockClient

    private ClusterContextProvider provider;

    @BeforeEach
    void setUp() {
        var factory = mock(ClusterClientFactory.class);
        when(factory.getClient(0L)).thenReturn(client);
        provider = new ClusterContextProvider(factory);
    }

    @Test
    void summaryIncludesServiceCountAndDetail() {
        client.services().inNamespace("default").resource(new ServiceBuilder()
            .withNewMetadata().withName("web-svc").withNamespace("default").endMetadata()
            .withNewSpec().withType("ClusterIP").endSpec()
            .build()).create();
        client.services().inNamespace("default").resource(new ServiceBuilder()
            .withNewMetadata().withName("api-svc").withNamespace("default").endMetadata()
            .withNewSpec().withType("NodePort").endSpec()
            .build()).create();

        String summary = provider.summarize(0L);

        assertThat(summary).contains("=== SERVICES (total: 2) ===")
            .contains("default/web-svc: ClusterIP")
            .contains("default/api-svc: NodePort");
    }

    @Test
    void summaryIncludesDeploymentReadyCounts() {
        client.apps().deployments().inNamespace("default").resource(new DeploymentBuilder()
            .withNewMetadata().withName("web").withNamespace("default").endMetadata()
            .withNewSpec().withReplicas(3).endSpec()
            .withNewStatus().withReadyReplicas(2).endStatus()
            .build()).create();

        String summary = provider.summarize(0L);

        assertThat(summary).contains("=== DEPLOYMENTS (total: 1) ===")
            .contains("default/web: 2/3 ready");
    }

    @Test
    void summaryReportsZeroServicesWhenNoneExist() {
        String summary = provider.summarize(0L);

        assertThat(summary).contains("=== SERVICES (total: 0) ===");
    }
}
