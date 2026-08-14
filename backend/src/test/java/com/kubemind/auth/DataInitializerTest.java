package com.kubemind.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataInitializerTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    private DataInitializer initializer(String password) {
        return new DataInitializer(userRepository, encoder, "admin", password);
    }

    @Test
    void seedsTheAdminWhenNoneExists() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        initializer("hunter2").run();

        verify(userRepository).save(org.mockito.ArgumentMatchers.argThat(u ->
            u.getUsername().equals("admin")
                && u.getRole().equals("ADMIN")
                && encoder.matches("hunter2", u.getPasswordHash())));
    }

    /** No password configured must never mean "ship a default credential". */
    @Test
    void createsNothingWhenNoPasswordIsConfigured() {
        initializer("").run();

        verify(userRepository, never()).save(any(User.class));
    }

    /**
     * The lockout this exists to prevent: a database that outlived its
     * configuration (restored from backup, moved to another machine) used to
     * keep its old password forever, leaving nobody able to log in.
     */
    @Test
    void resetsAStaleAdminPasswordToTheConfiguredOne() {
        User admin = new User("admin", encoder.encode("password-from-an-old-backup"), "ADMIN");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));

        initializer("hunter2").run();

        assertThat(encoder.matches("hunter2", admin.getPasswordHash())).isTrue();
        verify(userRepository).save(admin);
    }

    @Test
    void leavesAMatchingPasswordUntouched() {
        User admin = new User("admin", encoder.encode("hunter2"), "ADMIN");
        String originalHash = admin.getPasswordHash();
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));

        initializer("hunter2").run();

        // Same password must not be re-hashed on every boot — that would churn
        // the row and invalidate nothing, for no gain.
        assertThat(admin.getPasswordHash()).isEqualTo(originalHash);
        verify(userRepository, never()).save(any(User.class));
    }

    /** Handing a local password to an SSO account would create a login the IdP
     *  cannot revoke — the same hole UserService.resetPassword refuses. */
    @Test
    void refusesToGiveAnSsoAccountOfTheSameNameALocalPassword() {
        User ssoAdmin = User.oidcProvisioned("admin", "ADMIN");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(ssoAdmin));

        initializer("hunter2").run();

        assertThat(ssoAdmin.getPasswordHash()).isNull();
        verify(userRepository, never()).save(any(User.class));
    }
}
