package com.kubemind.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    public record CreateUserRequest(
        @NotBlank @Size(max = 64)
        @Pattern(regexp = "[a-zA-Z0-9._-]+", message = "Username may only contain letters, digits, '.', '_' and '-'")
        String username,
        @NotBlank @Size(min = 8, max = 128, message = "Password must be 8-128 characters")
        String password,
        @NotBlank String role
    ) {}

    public record ResetPasswordRequest(
        @NotBlank @Size(min = 8, max = 128, message = "Password must be 8-128 characters")
        String password
    ) {}

    public record ChangeRoleRequest(@NotBlank String role) {}

    @GetMapping
    public List<UserDto> list() {
        return userService.list();
    }

    @PostMapping
    public ResponseEntity<UserDto> create(@Valid @RequestBody CreateUserRequest request, Authentication auth) {
        var dto = userService.create(auth.getName(), request.username().trim(), request.password(), request.role());
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, Authentication auth) {
        userService.delete(auth.getName(), id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/password")
    public ResponseEntity<Void> resetPassword(@PathVariable long id,
                                              @Valid @RequestBody ResetPasswordRequest request,
                                              Authentication auth) {
        userService.resetPassword(auth.getName(), id, request.password());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/role")
    public UserDto changeRole(@PathVariable long id, @Valid @RequestBody ChangeRoleRequest request,
                              Authentication auth) {
        return userService.changeRole(auth.getName(), id, request.role());
    }
}
