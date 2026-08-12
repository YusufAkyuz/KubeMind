package com.kubemind.cluster;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Opt-in, default off — same idiom as {@code KUBEMIND_PRIVILEGED_FEATURES} and
 * {@code KUBEMIND_OIDC_ISSUER_URI}: absent config means the feature simply
 * isn't there.
 *
 * Off by default on purpose, not out of caution-by-habit:
 * <ul>
 *   <li>a restricted-mode install (Helm {@code rbac.clusterAdmin: false}) holds
 *       no impersonate permission at all, so switching this on silently would
 *       break every call against the built-in cluster;</li>
 *   <li>turning it on makes the local admin subject to cluster RBAC like
 *       everyone else, which locks them out of cluster 0 until a matching
 *       binding exists (see the chart's bootstrapAdminBinding).</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "kubemind.impersonation")
public record ImpersonationProperties(boolean enabled) {
}
