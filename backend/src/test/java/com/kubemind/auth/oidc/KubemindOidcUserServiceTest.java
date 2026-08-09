package com.kubemind.auth.oidc;

import com.kubemind.auth.User;
import com.kubemind.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * loadUser()'s HTTP round-trip to the IdP's userinfo endpoint (via the
 * inherited OidcUserService.loadUser) is Spring Security library code, not
 * worth a mock-IdP integration test — these exercise the actual risk surface
 * this class adds on top of it: username resolution and JIT provisioning,
 * called directly with hand-built OidcUser fixtures instead.
 */
class KubemindOidcUserServiceTest {

    private UserRepository userRepository;
    private KubemindOidcUserService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
    }

    private void withProperties(String adminGroup) {
        service = new KubemindOidcUserService(userRepository,
            new KubemindOidcProperties(null, null, null, "groups", adminGroup));
    }

    private OidcUser oidcUser(String preferredUsername, String email, List<String> groups) {
        OidcIdToken.Builder token = OidcIdToken.withTokenValue("token-value")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .claim("sub", "idp-subject-123");
        if (preferredUsername != null) token.claim("preferred_username", preferredUsername);
        if (email != null) token.claim("email", email);
        if (groups != null) token.claim("groups", groups);
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")), token.build());
    }

    // --- resolveUsername ---

    @Test
    void resolveUsernamePrefersPreferredUsernameOverEmailAndSub() {
        withProperties(null);
        assertThat(service.resolveUsername(oidcUser("alice", "alice@example.com", null))).isEqualTo("alice");
    }

    @Test
    void resolveUsernameFallsBackToEmailWhenNoPreferredUsername() {
        withProperties(null);
        assertThat(service.resolveUsername(oidcUser(null, "alice@example.com", null))).isEqualTo("alice@example.com");
    }

    @Test
    void resolveUsernameFallsBackToSubWhenNothingElsePresent() {
        withProperties(null);
        assertThat(service.resolveUsername(oidcUser(null, null, null))).isEqualTo("idp-subject-123");
    }

    // --- findOrProvision ---

    @Test
    void firstLoginProvisionsAUserRoleUser() {
        withProperties(null);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User user = service.findOrProvision("alice", oidcUser("alice", null, null));

        assertThat(user.getUsername()).isEqualTo("alice");
        assertThat(user.getRole()).isEqualTo("USER");
        assertThat(user.getIdentityProvider()).isEqualTo("oidc");
        assertThat(user.getPasswordHash()).isNull();
    }

    @Test
    void firstLoginMapsToAdminWhenGroupsClaimContainsTheConfiguredAdminGroup() {
        withProperties("kubemind-admins");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User user = service.findOrProvision("alice", oidcUser("alice", null, List.of("kubemind-admins", "other")));

        assertThat(user.getRole()).isEqualTo("ADMIN");
    }

    @Test
    void firstLoginDoesNotGrantAdminWhenGroupsClaimDoesNotMatch() {
        withProperties("kubemind-admins");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User user = service.findOrProvision("alice", oidcUser("alice", null, List.of("some-other-group")));

        assertThat(user.getRole()).isEqualTo("USER");
    }

    @Test
    void secondLoginReusesTheExistingRowAndNeverRederivesRole() {
        withProperties("kubemind-admins");
        User existing = User.oidcProvisioned("alice", "USER");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(existing));

        // Groups claim NOW contains the admin group, but role must stay USER —
        // role is app state after first login, not re-derived every time.
        User user = service.findOrProvision("alice", oidcUser("alice", null, List.of("kubemind-admins")));

        assertThat(user.getRole()).isEqualTo("USER");
        verify(userRepository, never()).save(any());
    }

    @Test
    void rejectsAUsernameThatAlreadyBelongsToALocalAccount() {
        withProperties(null);
        User localUser = new User("alice", "some-bcrypt-hash", "USER");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(localUser));

        assertThatThrownBy(() -> service.findOrProvision("alice", oidcUser("alice", null, null)))
            .isInstanceOf(OAuth2AuthenticationException.class);
        verify(userRepository, never()).save(any());
    }
}
