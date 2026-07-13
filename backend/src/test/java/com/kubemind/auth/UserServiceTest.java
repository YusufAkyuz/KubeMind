package com.kubemind.auth;

import com.kubemind.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceTest {

    private UserRepository userRepository;
    private AuditService auditService;
    private UserService userService;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        auditService = mock(AuditService.class);
        userService = new UserService(userRepository, encoder, auditService);
    }

    @Test
    void createRejectsUnknownRole() {
        assertThatThrownBy(() -> userService.create("admin", "eve", "password123", "SUPERUSER"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));
        verify(userRepository, never()).save(any());
    }

    @Test
    void createRejectsDuplicateUsername() {
        when(userRepository.findByUsername("bob"))
            .thenReturn(Optional.of(new User("bob", "hash", "USER")));

        assertThatThrownBy(() -> userService.create("admin", "bob", "password123", "USER"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void createHashesPasswordAndAudits() {
        when(userRepository.findByUsername("bob")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserDto dto = userService.create("admin", "bob", "password123", "USER");

        assertThat(dto.username()).isEqualTo("bob");
        assertThat(dto.role()).isEqualTo("USER");
        verify(userRepository).save(org.mockito.ArgumentMatchers.argThat(u ->
            !u.getPasswordHash().equals("password123") && encoder.matches("password123", u.getPasswordHash())));
        verify(auditService).record(eq("admin"), eq(null), eq("CREATE_USER"), eq("User/bob"),
            any(), eq(true), eq(null));
    }

    @Test
    void deleteRejectsSelf() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(new User("admin", "hash", "ADMIN")));

        assertThatThrownBy(() -> userService.delete("admin", 1L))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT));
        verify(userRepository, never()).delete(any(User.class));
    }

    @Test
    void deleteRejectsLastAdmin() {
        when(userRepository.findById(2L)).thenReturn(Optional.of(new User("other-admin", "hash", "ADMIN")));
        when(userRepository.countByRole("ADMIN")).thenReturn(1L);

        assertThatThrownBy(() -> userService.delete("admin", 2L))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT));
        verify(userRepository, never()).delete(any(User.class));
    }

    @Test
    void deleteRemovesPlainUserAndAudits() {
        User bob = new User("bob", "hash", "USER");
        when(userRepository.findById(3L)).thenReturn(Optional.of(bob));

        userService.delete("admin", 3L);

        verify(userRepository).delete(bob);
        verify(auditService).record(eq("admin"), eq(null), eq("DELETE_USER"), eq("User/bob"),
            eq(null), eq(true), eq(null));
    }

    @Test
    void resetPasswordRehashesAndAudits() {
        User bob = new User("bob", encoder.encode("oldpassword"), "USER");
        when(userRepository.findById(3L)).thenReturn(Optional.of(bob));

        userService.resetPassword("admin", 3L, "newpassword1");

        assertThat(encoder.matches("newpassword1", bob.getPasswordHash())).isTrue();
        verify(auditService).record(eq("admin"), eq(null), eq("RESET_USER_PASSWORD"), eq("User/bob"),
            eq(null), eq(true), eq(null));
    }

    @Test
    void missingUserIs404() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.delete("admin", 99L))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
    }
}
