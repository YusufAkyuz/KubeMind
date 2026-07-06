package com.kubemind.k8s;

import com.kubemind.audit.AuditService;
import com.kubemind.cluster.ClusterClientFactory;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
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
class ResourceEditServiceTest {

    KubernetesClient client; // injected by @EnableKubernetesMockClient

    private AuditService auditService;
    private ResourceEditService service;

    @BeforeEach
    void setUp() {
        auditService = mock(AuditService.class);
        var factory = mock(ClusterClientFactory.class);
        when(factory.getClient(0L)).thenReturn(client);
        service = new ResourceEditService(factory, auditService);
    }

    private void createConfigMap(String ns, String name, String value) {
        client.configMaps().inNamespace(ns).resource(new ConfigMapBuilder()
            .withNewMetadata().withName(name).withNamespace(ns).endMetadata()
            .addToData("key", value)
            .build()).create();
    }

    @Test
    void podIsNotEditable() {
        assertThat(ResourceEditService.EDITABLE_KINDS).doesNotContain("Pod");
        assertThatThrownBy(() -> service.getYaml(0L, "Pod", "default", "p1"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not supported");
    }

    @Test
    void getYamlReturns404ForMissingResource() {
        assertThatThrownBy(() -> service.getYaml(0L, "ConfigMap", "default", "ghost"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not found");
    }

    @Test
    void getYamlReturnsCurrentManifest() {
        createConfigMap("default", "app-config", "v1");

        String yaml = service.getYaml(0L, "ConfigMap", "default", "app-config");

        assertThat(yaml).contains("app-config").contains("v1");
    }

    @Test
    void applyYamlUpdatesAndAudits() {
        createConfigMap("default", "app-config", "v1");
        String yaml = """
            apiVersion: v1
            kind: ConfigMap
            metadata:
              name: app-config
              namespace: default
            data:
              key: v2
            """;

        service.applyYaml("admin", 0L, "ConfigMap", "default", "app-config", yaml);

        assertThat(client.configMaps().inNamespace("default").withName("app-config").get().getData())
            .containsEntry("key", "v2");
        verify(auditService).record(eq("admin"), eq(0L), eq("EDIT_RESOURCE_YAML"),
            eq("ConfigMap/default/app-config"), any(), eq(true), eq(null));
    }

    @Test
    void applyYamlRejectsKindChange() {
        createConfigMap("default", "app-config", "v1");
        String secretYaml = """
            apiVersion: v1
            kind: Secret
            metadata:
              name: app-config
              namespace: default
            """;

        assertThatThrownBy(() -> service.applyYaml("admin", 0L, "ConfigMap", "default", "app-config", secretYaml))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("kind");
        verify(auditService).record(eq("admin"), eq(0L), eq("EDIT_RESOURCE_YAML"),
            eq("ConfigMap/default/app-config"), any(), eq(false), anyString());
    }

    @Test
    void applyYamlRejectsRename() {
        createConfigMap("default", "app-config", "v1");
        String yaml = """
            apiVersion: v1
            kind: ConfigMap
            metadata:
              name: renamed
              namespace: default
            """;

        assertThatThrownBy(() -> service.applyYaml("admin", 0L, "ConfigMap", "default", "app-config", yaml))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("metadata.name");
    }

    @Test
    void deleteRemovesResourceAndAudits() {
        createConfigMap("default", "app-config", "v1");

        service.delete("admin", 0L, "ConfigMap", "default", "app-config");

        assertThat(client.configMaps().inNamespace("default").withName("app-config").get()).isNull();
        verify(auditService).record(eq("admin"), eq(0L), eq("DELETE_RESOURCE"),
            eq("ConfigMap/default/app-config"), any(), eq(true), eq(null));
    }

    @Test
    void deleteReturns404ForMissingResource() {
        assertThatThrownBy(() -> service.delete("admin", 0L, "ConfigMap", "default", "ghost"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not found");
        verify(auditService).record(eq("admin"), eq(0L), eq("DELETE_RESOURCE"),
            eq("ConfigMap/default/ghost"), any(), eq(false), anyString());
    }

    @Test
    void deleteRejectsNonEditableKind() {
        assertThatThrownBy(() -> service.delete("admin", 0L, "Pod", "default", "p1"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not supported");
    }

    // ── Cluster-scoped (Namespace) ──────────────────────────────────────────────

    @Test
    void clusterScopedGetYamlReturnsCurrentManifest() {
        client.namespaces().resource(new NamespaceBuilder()
            .withNewMetadata().withName("team-a").addToLabels("env", "dev").endMetadata()
            .build()).create();

        String yaml = service.getYamlClusterScoped(0L, "Namespace", "team-a");

        assertThat(yaml).contains("team-a").contains("env");
    }

    @Test
    void clusterScopedGetYamlReturns404ForMissing() {
        assertThatThrownBy(() -> service.getYamlClusterScoped(0L, "Namespace", "ghost"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not found");
    }

    @Test
    void clusterScopedRejectsNonClusterScopedKind() {
        assertThatThrownBy(() -> service.getYamlClusterScoped(0L, "ConfigMap", "app-config"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not supported");
    }

    @Test
    void clusterScopedApplyYamlUpdatesAndAudits() {
        client.namespaces().resource(new NamespaceBuilder()
            .withNewMetadata().withName("team-a").endMetadata()
            .build()).create();
        String yaml = """
            apiVersion: v1
            kind: Namespace
            metadata:
              name: team-a
              labels:
                env: prod
            """;

        service.applyYamlClusterScoped("admin", 0L, "Namespace", "team-a", yaml);

        assertThat(client.namespaces().withName("team-a").get().getMetadata().getLabels())
            .containsEntry("env", "prod");
        verify(auditService).record(eq("admin"), eq(0L), eq("EDIT_RESOURCE_YAML"),
            eq("Namespace/team-a"), any(), eq(true), eq(null));
    }

    @Test
    void applyYamlRejectsDisallowedKindOutright() {
        String roleBinding = """
            apiVersion: rbac.authorization.k8s.io/v1
            kind: ClusterRoleBinding
            metadata:
              name: escalate
              namespace: default
            """;

        assertThatThrownBy(() -> service.applyYaml("admin", 0L, "ClusterRoleBinding", "default", "escalate", roleBinding))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not supported");
    }
}
