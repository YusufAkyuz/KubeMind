package com.kubemind.ai;

import com.kubemind.cluster.ClusterClientFactory;
import com.kubemind.k8s.MetricsService;
import com.kubemind.k8s.NodeMetricsDto;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnableKubernetesMockClient(crud = true)
class PodContextCollectorTest {

    KubernetesClient client; // injected by @EnableKubernetesMockClient

    private MetricsService metricsService;
    private PodContextCollector collector;

    @BeforeEach
    void setUp() {
        var factory = mock(ClusterClientFactory.class);
        when(factory.getClient(0L)).thenReturn(client);
        metricsService = mock(MetricsService.class);
        when(metricsService.listNodeMetrics(0L)).thenReturn(List.of());
        collector = new PodContextCollector(factory, metricsService);
    }

    @Test
    void returnsNullForMissingPod() {
        assertThat(collector.collect(0L, "default", "ghost")).isNull();
    }

    @Test
    void resolvesOwnerChainThroughReplicaSetToDeployment() {
        client.apps().deployments().inNamespace("default").resource(new DeploymentBuilder()
            .withNewMetadata().withName("web").withNamespace("default").endMetadata()
            .build()).create();
        client.apps().replicaSets().inNamespace("default").resource(new ReplicaSetBuilder()
            .withNewMetadata().withName("web-abc123").withNamespace("default")
                .addNewOwnerReference().withKind("Deployment").withName("web").withApiVersion("apps/v1").endOwnerReference()
            .endMetadata()
            .build()).create();
        client.pods().inNamespace("default").resource(new PodBuilder()
            .withNewMetadata().withName("web-abc123-xyz").withNamespace("default")
                .addNewOwnerReference().withKind("ReplicaSet").withName("web-abc123").withApiVersion("apps/v1").endOwnerReference()
            .endMetadata()
            .withNewSpec().endSpec()
            .build()).create();

        String context = collector.collect(0L, "default", "web-abc123-xyz");

        assertThat(context).contains("ownerChain: ReplicaSet/web-abc123 <- Deployment/web");
    }

    @Test
    void reportsNodeConditionsInTheCachedContext() {
        client.nodes().resource(new NodeBuilder()
            .withNewMetadata().withName("node-1").endMetadata()
            .withNewStatus()
                .addNewCondition().withType("Ready").withStatus("True").endCondition()
                .addNewCondition().withType("MemoryPressure").withStatus("False").endCondition()
            .endStatus()
            .build()).create();
        client.pods().inNamespace("default").resource(new PodBuilder()
            .withNewMetadata().withName("busy-pod").withNamespace("default").endMetadata()
            .withNewSpec().withNodeName("node-1").endSpec()
            .build()).create();
        when(metricsService.listNodeMetrics(0L)).thenReturn(List.of(new NodeMetricsDto("node-1", "500m", "1024Mi")));

        String context = collector.collect(0L, "default", "busy-pod");

        // Conditions are stable and belong in the cached context; live usage numbers do not
        // (see liveNodeUsage) — putting fluctuating numbers here would bust the diagnosis cache
        // on every call for reasons unrelated to the pod's actual problem.
        assertThat(context).contains("=== NODE (node-1) ===")
            .contains("Ready=True").contains("MemoryPressure=False")
            .doesNotContain("usage: cpu=");
    }

    @Test
    void liveNodeUsageReturnsCurrentUsageWhenMetricsAvailable() {
        client.pods().inNamespace("default").resource(new PodBuilder()
            .withNewMetadata().withName("busy-pod").withNamespace("default").endMetadata()
            .withNewSpec().withNodeName("node-1").endSpec()
            .build()).create();
        when(metricsService.listNodeMetrics(0L)).thenReturn(List.of(new NodeMetricsDto("node-1", "500m", "1024Mi")));

        String usage = collector.liveNodeUsage(0L, "default", "busy-pod");

        assertThat(usage).contains("node-1").contains("cpu=500m").contains("memory=1024Mi");
    }

    @Test
    void liveNodeUsageReturnsNullWhenMetricsServerUnavailable() {
        client.pods().inNamespace("default").resource(new PodBuilder()
            .withNewMetadata().withName("busy-pod").withNamespace("default").endMetadata()
            .withNewSpec().withNodeName("node-1").endSpec()
            .build()).create();

        assertThat(collector.liveNodeUsage(0L, "default", "busy-pod")).isNull();
    }
}
