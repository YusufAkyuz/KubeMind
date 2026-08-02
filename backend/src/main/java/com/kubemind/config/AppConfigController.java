package com.kubemind.config;

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

    public AppConfigController(PrivilegedFeatures privilegedFeatures) {
        this.privilegedFeatures = privilegedFeatures;
    }

    /**
     * @param privilegedFeatures false when this install runs without cluster-admin:
     *                           the Cluster Terminal, Node Shell and RBAC-object
     *                           writes are switched off server-side, so the UI must
     *                           not offer them.
     */
    public record AppConfigDto(boolean privilegedFeatures) {}

    @GetMapping
    public AppConfigDto config() {
        return new AppConfigDto(privilegedFeatures.isEnabled());
    }
}
