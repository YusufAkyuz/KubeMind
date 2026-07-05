package com.kubemind.k8s;

import com.kubemind.audit.AuditService;
import com.kubemind.cluster.ClusterClientFactory;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
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
