package com.kubemind.auth.oidc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OidcClientConfigTest {

    private static final KubemindOidcProperties OFF =
        new KubemindOidcProperties(null, null, null, "groups", null);
    private static final KubemindOidcProperties ON =
        new KubemindOidcProperties("https://idp.example.com/realm", "kubemind", "secret", "groups", null);

    /**
     * Regression guard for a bug that broke the default install: with OIDC
     * unconfigured this used to return null, and Spring Security's own
     * OAuth2ClientConfiguration — which arrives with the oauth2-client jar
     * whether or not SSO is used — requires the bean unconditionally. The
     * context failed to start, so every deployment without SSO crash-looped.
     */
    @Test
    void theRepositoryBeanExistsEvenWhenOidcIsNotConfigured() {
        var repository = new OidcClientConfig().clientRegistrationRepository(OFF);

        assertThat(repository).isNotNull();
        assertThat(repository.findByRegistrationId("oidc")).isNull();
    }

    /**
     * And it must not reach out to the IdP while building the bean — that call
     * belongs on the first SSO click, or an unreachable provider stops the
     * application booting. A bogus issuer would throw here if discovery ran now.
     */
    @Test
    void configuringOidcDoesNotContactTheProviderAtStartup() {
        var repository = new OidcClientConfig().clientRegistrationRepository(ON);

        assertThat(repository).isNotNull();
    }
}
