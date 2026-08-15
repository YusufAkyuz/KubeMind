package com.kubemind.auth.oidc;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

import java.util.List;

/**
 * Builds the OIDC provider registration when kubemind.oidc.issuer-uri is set,
 * and an empty repository when it isn't.
 *
 * "Is OIDC configured" is answered by {@link KubemindOidcProperties#enabled()},
 * not by whether this bean exists. An earlier version returned null to signal
 * "off" and had SecurityConfig/AppConfigController check bean presence — which
 * looked tidy and broke every install without SSO, because Spring Security
 * requires this bean to exist whenever its oauth2-client jar is present.
 *
 * Building the ClientRegistration here (instead of via Spring Boot's
 * spring.security.oauth2.client.registration.* properties) is deliberate:
 * that property tree is validated eagerly at context startup the moment any
 * key under it is present — even blank — throwing "Client id must not be
 * empty" whether or not oauth2Login() ever runs. See KubemindOidcProperties.
 *
 * Gated in code rather than with @ConditionalOnProperty: that annotation's
 * default matching only fails when the property is genuinely ABSENT — an empty
 * string (which is exactly what application.yml's `${KUBEMIND_OIDC_ISSUER_URI:}`
 * resolves to when unset) counts as "present" and would still pass, defeating
 * the whole point. KubemindOidcProperties.enabled() is the one place that
 * distinction is made correctly.
 *
 * The registration itself is resolved lazily. Discovery is an HTTP call to the
 * IdP, and making it here — while the context is still coming up — meant an
 * unreachable IdP failed this bean, failed the context, and stopped the
 * application booting at all, local password login included. See
 * LazyClientRegistrationRepository.
 */
@Configuration
@EnableConfigurationProperties(KubemindOidcProperties.class)
public class OidcClientConfig {

    private static final String REGISTRATION_ID = "oidc";

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(KubemindOidcProperties properties) {
        if (!properties.enabled()) {
            // Must still be a bean, not null. Spring Security's own
            // OAuth2ClientConfiguration comes along with
            // spring-security-oauth2-client on the classpath and requires a
            // ClientRegistrationRepository unconditionally — returning null
            // here failed the context for every install WITHOUT SSO, which is
            // the default install. Empty is the honest shape: the repository
            // exists and knows about no providers.
            return registrationId -> null;
        }
        return new LazyClientRegistrationRepository(REGISTRATION_ID, () ->
            ClientRegistrations.fromIssuerLocation(properties.issuerUri())
                .registrationId(REGISTRATION_ID)
                .clientId(properties.clientId())
                .clientSecret(properties.clientSecret())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .scope(List.of("openid", "profile", "email"))
                .build());
    }
}
