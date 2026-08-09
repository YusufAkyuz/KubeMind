package com.kubemind.auth.oidc;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All optional, all blank by default. Deliberately NOT under
 * {@code spring.security.oauth2.client.*} — Spring Boot validates that tree
 * eagerly at context startup the moment any registration key is present,
 * even with blank values, even if OIDC login is never wired up. Binding to a
 * private namespace instead means "no env vars set" is a true no-op: see
 * OidcClientConfig, which only builds a {@link org.springframework.security.oauth2.client.registration.ClientRegistrationRepository}
 * bean once {@link #issuerUri()} is actually non-blank.
 */
@ConfigurationProperties(prefix = "kubemind.oidc")
public record KubemindOidcProperties(
    String issuerUri,
    String clientId,
    String clientSecret,
    /** Claim name carrying the caller's IdP groups, for the admin-group mapping below. */
    String groupsClaim,
    /** A group name in that claim that maps to ADMIN on first login. Blank = every new
     *  OIDC account starts as USER; an ADMIN promotes via the existing user-management UI. */
    String adminGroup
) {
    public KubemindOidcProperties {
        if (groupsClaim == null || groupsClaim.isBlank()) {
            groupsClaim = "groups";
        }
    }

    public boolean enabled() {
        return issuerUri != null && !issuerUri.isBlank();
    }
}
