package com.kubemind.k8s;

import com.kubemind.audit.AuditService;
import com.kubemind.cluster.ClusterClientFactory;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@EnableKubernetesMockClient(crud = true)
class SecretServiceTest {

    KubernetesClient client; // injected by @EnableKubernetesMockClient

    private AuditService auditService;
    private SecretService secretService;
    private KubernetesService kubernetesService;

    @BeforeEach
    void setUp() {
        auditService = mock(AuditService.class);
        var factory = mock(ClusterClientFactory.class);
        when(factory.getClient(0L)).thenReturn(client);
        secretService = new SecretService(factory, auditService);
        kubernetesService = new KubernetesService(factory);
    }

    private void createSecret() {
        client.secrets().inNamespace("default").resource(new SecretBuilder()
            .withNewMetadata().withName("db-creds").withNamespace("default").endMetadata()
            .withType("Opaque")
            .addToData("password", Base64.getEncoder().encodeToString("hunter2".getBytes()))
            .addToData("username", Base64.getEncoder().encodeToString("admin".getBytes()))
            .build()).create();
    }

    @Test
    void listNeverContainsSecretValues() {
        createSecret();

        var secrets = kubernetesService.listSecrets(0L, "default");

        assertThat(secrets).hasSize(1);
        assertThat(secrets.get(0).keys()).containsExactly("password", "username");
        // The DTO type carries no value field at all — belt and braces via toString.
        assertThat(secrets.get(0).toString()).doesNotContain("hunter2");
    }

    @Test
    void revealDecodesValuesAndAudits() {
        createSecret();

        var revealed = secretService.reveal("admin", 0L, "default", "db-creds");

        assertThat(revealed).containsEntry("password", "hunter2").containsEntry("username", "admin");
        verify(auditService).record(eq("admin"), eq(0L), eq("REVEAL_SECRET"),
            eq("Secret/default/db-creds"), any(), eq(true), eq(null));
    }

    @Test
    void revealUnknownSecretReturns404AndAuditsFailure() {
        assertThatThrownBy(() -> secretService.reveal("admin", 0L, "default", "ghost"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not found");
        verify(auditService).record(eq("admin"), eq(0L), eq("REVEAL_SECRET"),
            eq("Secret/default/ghost"), eq(null), eq(false), anyString());
    }

    @Test
    void revealMarksBinaryPayloads() {
        byte[] binary = {0x00, 0x01, 0x02, (byte) 0xFF};
        client.secrets().inNamespace("default").resource(new SecretBuilder()
            .withNewMetadata().withName("tls").withNamespace("default").endMetadata()
            .addToData("cert.p12", Base64.getEncoder().encodeToString(binary))
            .build()).create();

        var revealed = secretService.reveal("admin", 0L, "default", "tls");

        assertThat(revealed.get("cert.p12")).startsWith("(binary");
    }
}
