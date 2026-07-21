package com.kubemind.auth;

import com.kubemind.audit.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Local user management (ADMIN-only, enforced at the controller).
 *
 * Authorization model note: ADMIN can write anywhere and manage users/terminals.
 * A USER can write only on clusters they registered themselves — those already
 * run under that user's own kubeconfig (see ClusterClientFactory), so real
 * Kubernetes RBAC on that kubeconfig is the actual ceiling, not an app-level
 * check (see ClusterAccessService). The built-in shared cluster (id 0) stays
 * ADMIN-only for writes since it's one identity shared by everyone, not a
 * per-user credential.
 */
@Service
public class UserService {

    private static final Set<String> VALID_ROLES = Set.of("ADMIN", "USER");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       AuditService auditService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    public List<UserDto> list() {
        return userRepository.findAll().stream().map(UserDto::from).toList();
    }

    @Transactional
    public UserDto create(String actor, String username, String password, String role) {
        String ref = "User/" + username;
        try {
            if (!VALID_ROLES.contains(role)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Role must be ADMIN or USER");
            }
            if (userRepository.findByUsername(username).isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
            }
            User user = userRepository.save(new User(username, passwordEncoder.encode(password), role));
            auditService.record(actor, null, "CREATE_USER", ref, Map.of("role", role), true, null);
            return UserDto.from(user);
        } catch (ResponseStatusException e) {
            auditService.record(actor, null, "CREATE_USER", ref, Map.of("role", role), false, e.getReason());
            throw e;
        }
    }

    @Transactional
    public void delete(String actor, long id) {
        User target = userRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        String ref = "User/" + target.getUsername();
        try {
            if (target.getUsername().equals(actor)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "You cannot delete your own account");
            }
            if ("ADMIN".equals(target.getRole()) && userRepository.countByRole("ADMIN") <= 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot delete the last ADMIN — the app would become unmanageable");
            }
            userRepository.delete(target);
            auditService.record(actor, null, "DELETE_USER", ref, null, true, null);
        } catch (ResponseStatusException e) {
            auditService.record(actor, null, "DELETE_USER", ref, null, false, e.getReason());
            throw e;
        }
    }

    @Transactional
    public void resetPassword(String actor, long id, String newPassword) {
        User target = userRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        String ref = "User/" + target.getUsername();
        target.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(target);
        auditService.record(actor, null, "RESET_USER_PASSWORD", ref, null, true, null);
    }

    @Transactional
    public UserDto changeRole(String actor, long id, String newRole) {
        User target = userRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        String ref = "User/" + target.getUsername();
        try {
            if (!VALID_ROLES.contains(newRole)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Role must be ADMIN or USER");
            }
            if ("ADMIN".equals(target.getRole()) && !"ADMIN".equals(newRole)
                && userRepository.countByRole("ADMIN") <= 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot demote the last ADMIN — the app would become unmanageable");
            }
            target.setRole(newRole);
            userRepository.save(target);
            auditService.record(actor, null, "CHANGE_USER_ROLE", ref, Map.of("role", newRole), true, null);
            return UserDto.from(target);
        } catch (ResponseStatusException e) {
            auditService.record(actor, null, "CHANGE_USER_ROLE", ref, Map.of("role", newRole), false, e.getReason());
            throw e;
        }
    }
}
