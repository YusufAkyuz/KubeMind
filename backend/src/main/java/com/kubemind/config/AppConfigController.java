package com.kubemind.config;

import com.kubemind.cluster.ImpersonationProperties;
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
    private final ImpersonationProperties impersonation;

    public AppConfigController(PrivilegedFeatures privilegedFeatures,
                               ObjectProvider<ClientRegistrationRepository> oidcClientRegistrations,
                               ImpersonationProperties impersonation) {
        this.privilegedFeatures = privilegedFeatures;
        this.oidcClientRegistrations = oidcClientRegistrations;
        this.impersonation = impersonation;
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
     * @param impersonationEnabled true when calls against the built-in cluster
     *                           carry the caller's own identity. The UI needs it
     *                           to explain why a non-ADMIN can now see that
     *                           cluster, and why what they see inside it is the
     *                           cluster's decision rather than the app's.
     */
    public record AppConfigDto(boolean privilegedFeatures, boolean oidcEnabled, boolean impersonationEnabled) {}

    @GetMapping
    public AppConfigDto config() {
        return new AppConfigDto(privilegedFeatures.isEnabled(),
            oidcClientRegistrations.getIfAvailable() != null,
            impersonation.enabled());
    }
}
