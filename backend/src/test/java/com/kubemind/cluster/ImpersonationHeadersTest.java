package com.kubemind.cluster;

import com.kubemind.auth.oidc.KubemindOidcProperties;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The test that actually proves the feature rather than the wiring: it drives a
 * real request through a real Fabric8 client and inspects what the API server
 * received. Everything else about impersonation is bookkeeping — if these
 * headers aren't on the wire, Kubernetes never applies the caller's RBAC.
 */
@EnableKubernetesMockClient(crud = true)
class ImpersonationHeadersTest {

    KubernetesClient client; // injected — talks to the mock API server below
    KubernetesMockServer server;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private ClusterClientFactory factory() {
        var oidcProperties = new KubemindOidcProperties(null, null, null, "groups", null);
        return new ClusterClientFactory(client, mock(ClusterRepository.class), mock(CryptoService.class),
            new ImpersonationResolver(oidcProperties), new ImpersonationProperties(true));
    }

    @Test
    void aLocalUsersRequestCarriesImpersonateUser() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        factory().getClient(ClusterClientFactory.DEFAULT_CLUSTER_ID).namespaces().list();

        var request = server.getLastRequest();
        assertThat(request.getHeader("Impersonate-User")).isEqualTo("admin");
    }

    @Test
    void anOidcUsersRequestCarriesBothUserAndGroups() throws Exception {
        // nameAttributeKey "preferred_username" so getName() yields the username
        // rather than the opaque sub — mirroring production, where
        // KubemindOidcUser overrides getName() for exactly that reason.
        var principal = new DefaultOidcUser(
            List.of(new SimpleGrantedAuthority("OIDC_USER")),
            OidcIdToken.withTokenValue("t")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
                .claim("sub", "s").claim("preferred_username", "alice")
                .claim("groups", List.of("platform-team", "oncall"))
                .build(),
            "preferred_username");
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken(principal, "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        factory().getClient(ClusterClientFactory.DEFAULT_CLUSTER_ID).namespaces().list();

        var request = server.getLastRequest();
        assertThat(request.getHeader("Impersonate-User")).isEqualTo("alice");
        // Repeated header, one entry per group — hence headers(name), not getHeader(name).
        assertThat(request.getHeaders().headers("Impersonate-Group"))
            .contains("platform-team", "oncall");
    }

    /** The escape hatch must genuinely escape: scheduled work stays the
     *  installation's own identity, with no impersonation headers at all. */
    @Test
    void theSystemClientSendsNoImpersonationHeaders() throws Exception {
        factory().getSystemClient(ClusterClientFactory.DEFAULT_CLUSTER_ID).namespaces().list();

        var request = server.getLastRequest();
        assertThat(request.getHeader("Impersonate-User")).isNull();
        assertThat(request.getHeader("Impersonate-Group")).isNull();
    }
}
