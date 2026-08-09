package com.kubemind.auth.oidc;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Wraps the delegate Spring gives us after a successful OIDC login and
 * overrides the two things the rest of this app actually depends on:
 *
 * - {@link #getName()}: Spring's default is the {@code sub} claim — an opaque
 *   IdP string. Everything downstream (ClusterAccessService's createdBy
 *   check, AuditService's actor field, UserController's auth.getName())
 *   assumes this is the same friendly username used everywhere else, so it
 *   returns the JIT-provisioned username instead.
 * - {@link #getAuthorities()}: Spring's default authorities are
 *   {@code OIDC_USER}-shaped, not the {@code ROLE_ADMIN}/{@code ROLE_USER}
 *   this app's {@code @PreAuthorize}/{@code hasRole(...)} checks expect.
 *
 * Once this is handed back, nothing else in the app needs to know OIDC
 * exists — it's a normal-shaped Authentication with a username and a role.
 */
final class KubemindOidcUser implements OidcUser {

    private final OidcUser delegate;
    private final String username;
    private final String role;

    KubemindOidcUser(OidcUser delegate, String username, String role) {
        this.delegate = delegate;
        this.username = username;
        this.role = role;
    }

    @Override
    public String getName() {
        return username;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public Map<String, Object> getClaims() {
        return delegate.getClaims();
    }

    @Override
    public OidcUserInfo getUserInfo() {
        return delegate.getUserInfo();
    }

    @Override
    public OidcIdToken getIdToken() {
        return delegate.getIdToken();
    }

    @Override
    public Map<String, Object> getAttributes() {
        return delegate.getAttributes();
    }
}
