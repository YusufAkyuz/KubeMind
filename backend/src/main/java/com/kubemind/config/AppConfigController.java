package com.kubemind.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Install-level capabilities the UI needs in order to render honestly — what this
 * deployment offers at all, as opposed to who you are (/api/auth/me) or what a
 * given cluster's credentials permit (/api/clusters/{id}/permissions).
 */
@RestController
@RequestMapping("/api/config")
public class AppConfigController {

    private final PrivilegedFeatures privilegedFeatures;
    private final ObjectProvider<ClientRegistrationRepository> oidcClientRegistrations;

    public AppConfigController(PrivilegedFeatures privilegedFeatures,
                               ObjectProvider<ClientRegistrationRepository> oidcClientRegistrations) {
        this.privilegedFeatures = privilegedFeatures;
        this.oidcClientRegistrations = oidcClientRegistrations;
    }

    /**
     * @param privilegedFeatures false when this install runs without cluster-admin:
     *                           the Cluster Terminal, Node Shell and RBAC-object
     *                           writes are switched off server-side, so the UI must
     *                           not offer them.
     * @param oidcEnabled        true when kubemind.oidc.issuer-uri is set — see
     *                           OidcClientConfig/SecurityConfig, which check the
     *                           same bean presence, so this can never disagree
     *                           with whether /oauth2/authorization/oidc actually works.
     */
    public record AppConfigDto(boolean privilegedFeatures, boolean oidcEnabled) {}

    @GetMapping
    public AppConfigDto config() {
        return new AppConfigDto(privilegedFeatures.isEnabled(), oidcClientRegistrations.getIfAvailable() != null);
    }
}
