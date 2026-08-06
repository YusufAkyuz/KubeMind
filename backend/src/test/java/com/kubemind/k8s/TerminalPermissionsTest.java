package com.kubemind.k8s;

import com.kubemind.cluster.ClusterClientFactory;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Node Shell and Cluster Terminal don't fit the resource-CRUD permission
 * model — each is answered by a single targeted SelfSubjectAccessReview
 * instead. The real request/response shape is what matters here (unlike
 * PermissionsServiceTest's pure rule-matching logic), so these stub the raw
 * HTTP response the way the mock Kubernetes API server would send it.
 */
@EnableKubernetesMockClient(crud = false)
class TerminalPermissionsTest {

    KubernetesClient client; // injected by @EnableKubernetesMockClient
    io.fabric8.kubernetes.client.server.mock.KubernetesMockServer server; // injected alongside client

    private PermissionsService serviceAllowing(boolean allowed) {
        server.expect().post()
            .withPath("/apis/authorization.k8s.io/v1/selfsubjectaccessreviews")
            .andReturn(201, """
                {"apiVersion":"authorization.k8s.io/v1","kind":"SelfSubjectAccessReview",
                 "status":{"allowed":%s}}""".formatted(allowed))
            .always();

        var factory = mock(ClusterClientFactory.class);
        when(factory.getClient(0L)).thenReturn(client);
        return new PermissionsService(factory);
    }

    @Test
    void nodeShellAllowedWhenTheClusterSaysSo() {
        assertThat(serviceAllowing(true).canOpenNodeShell(0L)).isTrue();
    }

    @Test
    void nodeShellRefusedWhenTheClusterSaysSo() {
        assertThat(serviceAllowing(false).canOpenNodeShell(0L)).isFalse();
    }

    @Test
    void clusterTerminalAllowedWhenTheClusterSaysSo() {
        assertThat(serviceAllowing(true).canOpenClusterTerminal(0L)).isTrue();
    }

    @Test
    void clusterTerminalRefusedWhenTheClusterSaysSo() {
        assertThat(serviceAllowing(false).canOpenClusterTerminal(0L)).isFalse();
    }

    /** Same fail-open policy as the namespace-scoped checks: an API error must
     *  never hide a control the caller actually has. */
    @Test
    void failsOpenWhenTheClusterCannotAnswer() {
        var factory = mock(ClusterClientFactory.class);
        var brokenClient = mock(KubernetesClient.class);
        when(factory.getClient(0L)).thenReturn(brokenClient);
        when(brokenClient.authorization()).thenThrow(new RuntimeException("boom"));

        var service = new PermissionsService(factory);

        assertThat(service.canOpenNodeShell(0L)).isTrue();
        assertThat(service.canOpenClusterTerminal(0L)).isTrue();
    }
}
