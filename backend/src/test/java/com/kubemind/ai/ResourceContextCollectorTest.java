package com.kubemind.ai;

import com.kubemind.cluster.ClusterClientFactory;
import com.kubemind.k8s.ResourceEditService;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
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
}
