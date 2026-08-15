package com.kubemind.cluster;

import com.kubemind.auth.oidc.KubemindOidcProperties;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.security.Principal;
import java.util.List;
import java.util.Optional;

/**
 * Turns "who is calling" into the identity Kubernetes should be told to act as.
 * The single place that knows how an OIDC login differs from a local one, so
 * ClusterClientFactory doesn't have to.
 */
@Component
public class ImpersonationResolver {

    /**
     * @param username sent as {@code Impersonate-User}
     * @param groups   sent as {@code Impersonate-Group}; empty for local accounts
     */
    public record Identity(String username, List<String> groups) {}

    private final KubemindOidcProperties oidcProperties;

    public ImpersonationResolver(KubemindOidcProperties oidcProperties) {
        this.oidcProperties = oidcProperties;
    }

    /** The caller on this thread, if there is an authenticated one. */
    public Optional<Identity> currentIdentity() {
        return resolve(SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * For the WebSocket handlers, which carry the caller on
     * {@code session.getPrincipal()} rather than in a thread-local. Spring
     * propagates the {@link Authentication} itself as that principal; anything
     * else is not an identity we can impersonate.
     */
    public Optional<Identity> resolve(Principal principal) {
        return principal instanceof Authentication auth ? resolve(auth) : Optional.empty();
    }

    /**
     * Empty when there is no real caller — an anonymous token, or a thread with
     * no security context at all (scheduled jobs, Fabric8 watch callbacks).
     * Callers must decide what that means; ClusterClientFactory treats it as a
     * hard error rather than a quiet fallback to the ServiceAccount.
     */
    public Optional<Identity> resolve(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        String username = auth.getName();
        if (!StringUtils.hasText(username)) {
            return Optional.empty();
        }
        return Optional.of(new Identity(username, groupsOf(auth)));
    }

    /**
     * IdP groups only — deliberately NOT KubeMind's own ROLE_ADMIN/ROLE_USER.
     * The app's roles and the cluster's RBAC are separate authorities; feeding
     * one into the other would let an app-level role quietly confer cluster
     * power that no Kubernetes binding ever granted.
     */
    private List<String> groupsOf(Authentication auth) {
        if (!(auth.getPrincipal() instanceof OidcUser oidcUser)) {
            return List.of(); // local account — no IdP groups exist to forward
        }
        List<String> groups = oidcUser.getClaimAsStringList(oidcProperties.groupsClaim());
        return groups == null ? List.of() : groups;
    }
}
