package com.kubemind.auth.oidc;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

import java.util.List;

/**
 * The whole point of this class is that a ClientRegistrationRepository bean
 * simply doesn't exist unless kubemind.oidc.issuer-uri is set. SecurityConfig
 * and AppConfigController both check that via ObjectProvider — one source of
 * truth for "is OIDC configured", and "no env vars set" behaves as a true
 * no-op rather than something that merely looks disabled.
 *
 * Building the ClientRegistration here (instead of via Spring Boot's
 * spring.security.oauth2.client.registration.* properties) is deliberate:
 * that property tree is validated eagerly at context startup the moment any
 * key under it is present — even blank — throwing "Client id must not be
 * empty" whether or not oauth2Login() ever runs. See KubemindOidcProperties.
 *
 * Gated with a plain null-return, not @ConditionalOnProperty: that
 * annotation's default matching only fails when the property is genuinely
 * ABSENT — an empty string (which is exactly what
 * application.yml's `${KUBEMIND_OIDC_ISSUER_URI:}` resolves to when unset)
 * counts as "present" and would still pass, defeating the whole point.
 * KubemindOidcProperties.enabled() is the one place that distinction is made
 * correctly.
 */
@Configuration
@EnableConfigurationProperties(KubemindOidcProperties.class)
public class OidcClientConfig {

    private static final String REGISTRATION_ID = "oidc";

    @Bean
    @Nullable
    public ClientRegistrationRepository clientRegistrationRepository(KubemindOidcProperties properties) {
        if (!properties.enabled()) {
            return null;
        }
        ClientRegistration registration = ClientRegistrations.fromIssuerLocation(properties.issuerUri())
            .registrationId(REGISTRATION_ID)
            .clientId(properties.clientId())
            .clientSecret(properties.clientSecret())
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .scope(List.of("openid", "profile", "email"))
            .build();
        return new InMemoryClientRegistrationRepository(registration);
    }
}
