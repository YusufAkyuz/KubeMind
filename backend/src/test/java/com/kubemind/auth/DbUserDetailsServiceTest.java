package com.kubemind.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DbUserDetailsServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final DbUserDetailsService service = new DbUserDetailsService(userRepository);

    @Test
    void loadsALocalUserNormally() {
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(new User("bob", "bcrypt-hash", "ADMIN")));

        UserDetails details = service.loadUserByUsername("bob");

        assertThat(details.getUsername()).isEqualTo("bob");
        assertThat(details.getPassword()).isEqualTo("bcrypt-hash");
        assertThat(details.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_ADMIN");
    }

    // Regression guard: an OIDC-provisioned row has passwordHash == null.
    // UserDetails.builder().password(null) NPEs — this must fail the same
    // clean way as "no such user" instead, both to avoid the NPE and to not
    // leak via a different error which accounts are OIDC-only.
    @Test
    void rejectsAnOidcOnlyAccountInsteadOfNpeing() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(User.oidcProvisioned("alice", "USER")));

        assertThatThrownBy(() -> service.loadUserByUsername("alice"))
            .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void rejectsAnUnknownUsername() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("ghost"))
            .isInstanceOf(UsernameNotFoundException.class);
    }
}
