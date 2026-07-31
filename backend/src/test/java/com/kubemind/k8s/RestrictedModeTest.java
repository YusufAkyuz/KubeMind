package com.kubemind.k8s;

import com.kubemind.audit.AuditService;
import com.kubemind.cluster.ClusterClientFactory;
import com.kubemind.config.PrivilegedFeatures;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A deployment running without cluster-admin must not merely hide the
 * escalation-capable features in the UI — the write paths themselves have to
 * refuse. Anyone who can create a RoleBinding can bind themselves to
 * cluster-admin, so "restricted mode that still writes RBAC" would be no
 * restriction at all.
 */
@EnableKubernetesMockClient(crud = true)
class RestrictedModeTest {

    KubernetesClient client; // injected by @EnableKubernetesMockClient

    private static final PrivilegedFeatures RESTRICTED = new PrivilegedFeatures(false);
    private static final PrivilegedFeatures FULL = new PrivilegedFeatures(true);

    private AuditService auditService;
    private ClusterClientFactory factory;

    @BeforeEach
    void setUp() {
        auditService = mock(AuditService.class);
        factory = mock(ClusterClientFactory.class);
        when(factory.getClient(0L)).thenReturn(client);
    }

    private static final String ROLE_YAML = """
        apiVersion: rbac.authorization.k8s.io/v1
        kind: Role
        metadata:
          name: escalate-me
          namespace: default
        rules:
          - apiGroups: ["*"]
            resources: ["*"]
            verbs: ["*"]
        """;

    private static final String CONFIGMAP_YAML = """
        apiVersion: v1
        kind: ConfigMap
        metadata:
          name: app-config
          namespace: default
        data:
          key: value
        """;

    private static final String CLUSTERROLEBINDING_YAML = """
        apiVersion: rbac.authorization.k8s.io/v1
        kind: ClusterRoleBinding
        metadata:
          name: make-me-admin
        roleRef:
          apiGroup: rbac.authorization.k8s.io
          kind: ClusterRole
          name: cluster-admin
        subjects:
          - kind: ServiceAccount
            name: default
            namespace: default
        """;

    // ── Namespaced creation ──────────────────────────────────────────────────

    @Test
    void restrictedModeDropsRbacKindsFromTheAllowlist() {
        var service = new ResourceCreationService(factory, auditService, RESTRICTED);

        assertThat(service.effectiveAllowedKinds())
            .doesNotContain("Role", "RoleBinding", "ServiceAccount")
            .contains("Deployment", "ConfigMap", "Service");
    }

    @Test
    void fullModeKeepsRbacKinds() {
        var service = new ResourceCreationService(factory, auditService, FULL);

        assertThat(service.effectiveAllowedKinds()).contains("Role", "RoleBinding", "ServiceAccount");
    }

    @Test
    void restrictedModeRefusesToCreateARoleAndAuditsTheAttempt() {
        var service = new ResourceCreationService(factory, auditService, RESTRICTED);

        assertThatThrownBy(() -> service.create("admin", 0L, "default", ROLE_YAML))
            .isInstanceOf(ResponseStatusException.class);

        assertThat(client.rbac().roles().inNamespace("default").withName("escalate-me").get()).isNull();
        verify(auditService).record(eq("admin"), eq(0L), eq("CREATE_RESOURCE"),
            eq("Role/default/escalate-me"), any(), eq(false), any());
    }

    @Test
    void restrictedModeStillCreatesOrdinaryWorkloadKinds() {
        var service = new ResourceCreationService(factory, auditService, RESTRICTED);

        var created = service.create("admin", 0L, "default", CONFIGMAP_YAML);

        assertThat(created.kind()).isEqualTo("ConfigMap");
        assertThat(client.configMaps().inNamespace("default").withName("app-config").get()).isNotNull();
    }

    // ── Cluster-scoped creation (RBAC only) ──────────────────────────────────

    @Test
    void restrictedModeOffersNoClusterScopedKinds() {
        var service = new ClusterResourceCreationService(factory, auditService, RESTRICTED);

        assertThat(service.allowedKinds()).isEmpty();
    }

    @Test
    void restrictedModeRefusesClusterRoleBindingWith403() {
        var service = new ClusterResourceCreationService(factory, auditService, RESTRICTED);

        assertThatThrownBy(() -> service.create("admin", 0L, CLUSTERROLEBINDING_YAML))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));

        verify(auditService).record(eq("admin"), eq(0L), eq("CREATE_RESOURCE"),
            eq("ClusterRoleBinding/make-me-admin"), any(), eq(false), any());
    }

    // ── Editing ──────────────────────────────────────────────────────────────

    @Test
    void restrictedModeLeavesOnlyNamespaceClusterScopedEditable() {
        var service = new ResourceEditService(factory, auditService, RESTRICTED);

        assertThat(service.effectiveClusterScopedEditableKinds())
            .containsExactly("Namespace");
    }

    @Test
    void restrictedModeRemovesRbacKindsFromEditableSet() {
        var service = new ResourceEditService(factory, auditService, RESTRICTED);

        assertThat(service.effectiveEditableKinds())
            .doesNotContain("Role", "RoleBinding", "ServiceAccount")
            .contains("Deployment", "ConfigMap");
    }
}
