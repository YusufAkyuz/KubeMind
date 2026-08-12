package com.kubemind.cluster;

import com.kubemind.auth.oidc.KubemindOidcProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImpersonationResolverTest {

    private final ImpersonationResolver resolver =
        new ImpersonationResolver(new KubemindOidcProperties(null, null, null, "groups", null));

    private OidcUser oidcUser(String username, List<String> groups) {
        OidcIdToken.Builder token = OidcIdToken.withTokenValue("token-value")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .claim("sub", "idp-subject-123")
            .claim("preferred_username", username);
        if (groups != null) token.claim("groups", groups);
        // nameAttributeKey "preferred_username" so getName() is the username and
        // not the opaque sub — production gets the same effect from
        // KubemindOidcUser, which overrides getName() deliberately.
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")),
            token.build(), "preferred_username");
    }

    @Test
    void aLocalAccountIsImpersonatedByUsernameWithNoGroups() {
        var auth = new TestingAuthenticationToken("admin", "n/a",
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        var identity = resolver.resolve(auth).orElseThrow();

        assertThat(identity.username()).isEqualTo("admin");
        assertThat(identity.groups()).isEmpty();
    }

    /**
     * The important negative: KubeMind's own ADMIN role must never leak into the
     * impersonated groups. App roles and cluster RBAC are separate authorities —
     * forwarding one as the other would let an app-level role silently confer
     * cluster power no Kubernetes binding ever granted.
     */
    @Test
    void appRolesAreNeverForwardedAsImpersonatedGroups() {
        var auth = new TestingAuthenticationToken("admin", "n/a",
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        assertThat(resolver.resolve(auth).orElseThrow().groups())
            .doesNotContain("ROLE_ADMIN", "ADMIN");
    }

    @Test
    void anOidcLoginForwardsTheIdpGroupsClaim() {
        var principal = oidcUser("alice", List.of("platform-team", "oncall"));
        var auth = new TestingAuthenticationToken(principal, "n/a",
            List.of(new SimpleGrantedAuthority("ROLE_USER")));

        var identity = resolver.resolve(auth).orElseThrow();

        // Username, not the opaque IdP subject — everything downstream (audit
        // actor, cluster ownership checks) assumes these are the same string.
        assertThat(identity.username()).isEqualTo("alice");
        assertThat(identity.groups()).containsExactly("platform-team", "oncall");
    }

    @Test
    void anOidcLoginWithNoGroupsClaimYieldsNoGroups() {
        var principal = oidcUser("alice", null);
        var auth = new TestingAuthenticationToken(principal, "n/a",
            List.of(new SimpleGrantedAuthority("ROLE_USER")));

        assertThat(resolver.resolve(auth).orElseThrow().groups()).isEmpty();
    }

    @Test
    void noAuthenticationYieldsNoIdentity() {
        assertThat(resolver.resolve((org.springframework.security.core.Authentication) null)).isEmpty();
    }

    @Test
    void anAnonymousTokenIsNotAnIdentity() {
        var anonymous = new AnonymousAuthenticationToken("key", "anonymousUser",
            List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));

        assertThat(resolver.resolve(anonymous)).isEmpty();
    }

    /** WebSocket handlers hand us session.getPrincipal(); anything that isn't an
     *  Authentication is not something we can impersonate. */
    @Test
    void aNonAuthenticationPrincipalYieldsNoIdentity() {
        java.security.Principal bare = () -> "alice";

        assertThat(resolver.resolve(bare)).isEmpty();
    }
}
