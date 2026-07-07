package com.kubemind.k8s;

import com.kubemind.cluster.ClusterClientFactory;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the "all" namespace special-case across a representative sample of list
 * methods (one per DTO-building shape) — the pattern is mechanically identical
 * across all 12 namespaced list methods, so this isn't exhaustive per-kind.
 */
@EnableKubernetesMockClient(crud = true)
class KubernetesServiceTest {

    KubernetesClient client; // injected by @EnableKubernetesMockClient

    private KubernetesService service;

    @BeforeEach
    void setUp() {
        var factory = mock(ClusterClientFactory.class);
        when(factory.getClient(0L)).thenReturn(client);
        service = new KubernetesService(factory);
    }

    @Test
    void listConfigMapsWithAllReturnsAcrossNamespaces() {
        client.configMaps().inNamespace("ns-a").resource(new ConfigMapBuilder()
            .withNewMetadata().withName("cm-a").withNamespace("ns-a").endMetadata().build()).create();
        client.configMaps().inNamespace("ns-b").resource(new ConfigMapBuilder()
            .withNewMetadata().withName("cm-b").withNamespace("ns-b").endMetadata().build()).create();

        var all = service.listConfigMaps(0L, "all");
        var scoped = service.listConfigMaps(0L, "ns-a");

        assertThat(all).extracting(ConfigMapDto::name).containsExactlyInAnyOrder("cm-a", "cm-b");
        assertThat(scoped).extracting(ConfigMapDto::name).containsExactly("cm-a");
    }

    @Test
    void listServicesWithAllReturnsAcrossNamespaces() {
        client.services().inNamespace("ns-a").resource(new ServiceBuilder()
            .withNewMetadata().withName("svc-a").withNamespace("ns-a").endMetadata()
            .withNewSpec().withType("ClusterIP").endSpec().build()).create();
        client.services().inNamespace("ns-b").resource(new ServiceBuilder()
            .withNewMetadata().withName("svc-b").withNamespace("ns-b").endMetadata()
            .withNewSpec().withType("ClusterIP").endSpec().build()).create();

        var all = service.listServices(0L, "all");

        assertThat(all).extracting(ServiceDto::name).containsExactlyInAnyOrder("svc-a", "svc-b");
    }

    @Test
    void listStatefulSetsWithAllReturnsAcrossNamespaces() {
        client.apps().statefulSets().inNamespace("ns-a").resource(new StatefulSetBuilder()
            .withNewMetadata().withName("sts-a").withNamespace("ns-a").endMetadata().build()).create();
        client.apps().statefulSets().inNamespace("ns-b").resource(new StatefulSetBuilder()
            .withNewMetadata().withName("sts-b").withNamespace("ns-b").endMetadata().build()).create();

        var all = service.listStatefulSets(0L, "all");

        assertThat(all).extracting(StatefulSetDto::name).containsExactlyInAnyOrder("sts-a", "sts-b");
    }

    @Test
    void listPersistentVolumeClaimsWithAllReturnsAcrossNamespaces() {
        client.persistentVolumeClaims().inNamespace("ns-a").resource(new PersistentVolumeClaimBuilder()
            .withNewMetadata().withName("pvc-a").withNamespace("ns-a").endMetadata().build()).create();
        client.persistentVolumeClaims().inNamespace("ns-b").resource(new PersistentVolumeClaimBuilder()
            .withNewMetadata().withName("pvc-b").withNamespace("ns-b").endMetadata().build()).create();

        var all = service.listPersistentVolumeClaims(0L, "all");

        assertThat(all).extracting(PvcDto::name).containsExactlyInAnyOrder("pvc-a", "pvc-b");
    }
}
