package com.kubemind.auth;

/** Boundary shape for user management — never exposes the password hash. */
public record UserDto(Long id, String username, String role) {

    static UserDto from(User user) {
        return new UserDto(user.getId(), user.getUsername(), user.getRole());
    }
}
