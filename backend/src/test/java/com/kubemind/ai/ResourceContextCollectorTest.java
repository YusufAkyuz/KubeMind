package com.kubemind.ai;

import com.kubemind.cluster.ClusterClientFactory;
import com.kubemind.k8s.ResourceEditService;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnableKubernetesMockClient(crud = true)
class ResourceContextCollectorTest {

    KubernetesClient client; // injected by @EnableKubernetesMockClient

    private ResourceContextCollector collector;

    @BeforeEach
    void setUp() {
        var factory = mock(ClusterClientFactory.class);
        when(factory.getClient(0L)).thenReturn(client);
        var editService = new ResourceEditService(factory, mock(com.kubemind.audit.AuditService.class));
        collector = new ResourceContextCollector(factory, editService);
    }

    @Test
    void secretValuesNeverEnterTheContextEvenBase64Encoded() {
        String secretValue = "hunter2-super-secret";
        client.secrets().inNamespace("default").resource(new SecretBuilder()
            .withNewMetadata().withName("db-creds").withNamespace("default").endMetadata()
            .withType("Opaque")
            .addToData("password", Base64.getEncoder().encodeToString(secretValue.getBytes()))
            .build()).create();

        String context = collector.collect(0L, "Secret", "default", "db-creds");

        assertThat(context).doesNotContain(secretValue);
        // Not even the base64 form should leak.
        assertThat(context).doesNotContain(Base64.getEncoder().encodeToString(secretValue.getBytes()));
        assertThat(context).contains("password"); // key names are fine to include
        assertThat(context).contains("values withheld");
    }

    @Test
    void returnsNullForMissingResource() {
        assertThat(collector.collect(0L, "ConfigMap", "default", "ghost")).isNull();
    }

    @Test
    void nonSecretResourcesIncludeTheirManifest() {
        client.configMaps().inNamespace("default").resource(new ConfigMapBuilder()
            .withNewMetadata().withName("app-config").withNamespace("default").endMetadata()
            .addToData("greeting", "hello")
            .build()).create();

        String context = collector.collect(0L, "ConfigMap", "default", "app-config");

        assertThat(context).contains("app-config").contains("hello");
    }

    @Test
    void serviceWithNoMatchingPodsFlagsDeadEndTraffic() {
        client.services().inNamespace("default").resource(new ServiceBuilder()
            .withNewMetadata().withName("orphan-svc").withNamespace("default").endMetadata()
            .withNewSpec().addToSelector("app", "ghost").endSpec()
            .build()).create();

        String context = collector.collect(0L, "Service", "default", "orphan-svc");

        assertThat(context).contains("matching pods: 0").contains("nowhere to go");
    }

    @Test
    void serviceReportsReadyEndpointCount() {
        client.services().inNamespace("default").resource(new ServiceBuilder()
            .withNewMetadata().withName("web-svc").withNamespace("default").endMetadata()
            .withNewSpec().addToSelector("app", "web").endSpec()
            .build()).create();
        client.pods().inNamespace("default").resource(new PodBuilder()
            .withNewMetadata().withName("web-1").withNamespace("default").addToLabels("app", "web").endMetadata()
            .withNewStatus().addNewCondition().withType("Ready").withStatus("True").endCondition().endStatus()
            .build()).create();

        String context = collector.collect(0L, "Service", "default", "web-svc");

        assertThat(context).contains("matching pods: 1").contains("ready: 1");
    }

    @Test
    void pvcNotYetBoundReportsStorageClassOnly() {
        client.persistentVolumeClaims().inNamespace("default").resource(new PersistentVolumeClaimBuilder()
            .withNewMetadata().withName("data-pvc").withNamespace("default").endMetadata()
            .withNewSpec().withStorageClassName("standard").endSpec()
            .build()).create();

        String context = collector.collect(0L, "PersistentVolumeClaim", "default", "data-pvc");

        assertThat(context).contains("not yet bound").contains("standard");
    }

    @Test
    void pvcBoundToPvIncludesVolumeDetails() {
        client.persistentVolumes().resource(new PersistentVolumeBuilder()
            .withNewMetadata().withName("pv-1").endMetadata()
            .withNewSpec().withStorageClassName("standard").withPersistentVolumeReclaimPolicy("Retain").endSpec()
            .withNewStatus().withPhase("Bound").endStatus()
            .build()).create();
        client.persistentVolumeClaims().inNamespace("default").resource(new PersistentVolumeClaimBuilder()
            .withNewMetadata().withName("data-pvc").withNamespace("default").endMetadata()
            .withNewSpec().withVolumeName("pv-1").withStorageClassName("standard").endSpec()
            .build()).create();

        String context = collector.collect(0L, "PersistentVolumeClaim", "default", "data-pvc");

        assertThat(context).contains("pv-1").contains("Bound").contains("Retain");
    }
}
