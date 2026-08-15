package com.kubemind.config;

import com.kubemind.cluster.ImpersonationProperties;
import com.kubemind.auth.oidc.KubemindOidcProperties;
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
    private final KubemindOidcProperties oidcProperties;
    private final ImpersonationProperties impersonation;

    public AppConfigController(PrivilegedFeatures privilegedFeatures,
                               KubemindOidcProperties oidcProperties,
                               ImpersonationProperties impersonation) {
        this.privilegedFeatures = privilegedFeatures;
        this.oidcProperties = oidcProperties;
        this.impersonation = impersonation;
    }

    /**
     * @param privilegedFeatures false when this install runs without cluster-admin:
     *                           the Cluster Terminal, Node Shell and RBAC-object
     *                           writes are switched off server-side, so the UI must
     *                           not offer them.
     * @param oidcEnabled        true when kubemind.oidc.issuer-uri is set —
     *                           SecurityConfig reads the same properties object,
     *                           so this can never disagree with whether
     *                           /oauth2/authorization/oidc is actually wired up.
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
            oidcProperties.enabled(),
            impersonation.enabled());
    }
}
