package com.kubemind.k8s;

import com.kubemind.audit.AuditService;
import com.kubemind.cluster.ClusterClientFactory;
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
class ResourceCreationServiceTest {

    KubernetesClient client; // injected by @EnableKubernetesMockClient

    private AuditService auditService;
    private ResourceCreationService service;

    @BeforeEach
    void setUp() {
        auditService = mock(AuditService.class);
        var factory = mock(ClusterClientFactory.class);
        when(factory.getClient(0L)).thenReturn(client);
        service = new ResourceCreationService(factory, auditService);
    }

    private static final String CONFIGMAP_YAML = """
        apiVersion: v1
        kind: ConfigMap
        metadata:
          name: app-config
          namespace: default
        data:
          key: value
        """;

    @Test
    void createsAllowedKindAndAudits() {
        var created = service.create("admin", 0L, "default", CONFIGMAP_YAML);

        assertThat(created.kind()).isEqualTo("ConfigMap");
        assertThat(created.name()).isEqualTo("app-config");
        assertThat(client.configMaps().inNamespace("default").withName("app-config").get()).isNotNull();
        verify(auditService).record(eq("admin"), eq(0L), eq("CREATE_RESOURCE"),
            eq("ConfigMap/default/app-config"), any(), eq(true), eq(null));
    }

    private static final String CRONJOB_YAML = """
        apiVersion: batch/v1
        kind: CronJob
        metadata:
          name: nightly-cleanup
          namespace: default
        spec:
          schedule: "0 2 * * *"
          jobTemplate:
            spec:
              template:
                spec:
                  restartPolicy: OnFailure
                  containers:
                  - name: cleanup
                    image: busybox:1.36
        """;

    @Test
    void createsCronJobAndAudits() {
        var created = service.create("admin", 0L, "default", CRONJOB_YAML);

        assertThat(created.kind()).isEqualTo("CronJob");
        assertThat(client.batch().v1().cronjobs().inNamespace("default").withName("nightly-cleanup").get())
            .isNotNull();
        verify(auditService).record(eq("admin"), eq(0L), eq("CREATE_RESOURCE"),
            eq("CronJob/default/nightly-cleanup"), any(), eq(true), eq(null));
    }

    @Test
    void rejectsDisallowedKind() {
        String roleBinding = """
            apiVersion: rbac.authorization.k8s.io/v1
            kind: ClusterRoleBinding
            metadata:
              name: escalate
            """;

        assertThatThrownBy(() -> service.create("admin", 0L, "default", roleBinding))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not supported");
        // Even a blocked attempt is audited, with whatever identity could be parsed.
        verify(auditService).record(eq("admin"), eq(0L), eq("CREATE_RESOURCE"),
            eq("ClusterRoleBinding/default/escalate"), any(), eq(false), anyString());
    }

    @Test
    void rejectsMissingName() {
        String yaml = """
            apiVersion: v1
            kind: ConfigMap
            metadata:
              namespace: default
            """;

        assertThatThrownBy(() -> service.create("admin", 0L, "default", yaml))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("metadata.name");
    }

    @Test
    void rejectsNamespaceMismatch() {
        String yaml = """
            apiVersion: v1
            kind: ConfigMap
            metadata:
              name: app-config
              namespace: other-ns
            """;

        assertThatThrownBy(() -> service.create("admin", 0L, "default", yaml))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("metadata.namespace");
    }

    @Test
    void rejectsBlankManifest() {
        assertThatThrownBy(() -> service.create("admin", 0L, "default", "  "))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("empty");
    }

    @Test
    void rejectsGarbageYaml() {
        assertThatThrownBy(() -> service.create("admin", 0L, "default", "{not: yaml:::"))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void allowedKindsAreSortedAndStable() {
        assertThat(service.allowedKinds()).containsExactly(
            "ConfigMap", "CronJob", "DaemonSet", "Deployment", "Ingress", "Job",
            "PersistentVolumeClaim", "Pod", "Secret", "Service", "StatefulSet"
        );
    }
}
