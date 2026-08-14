package com.kubemind.auth;

/**
 * Boundary shape for user management — never exposes the password hash.
 *
 * @param identityProvider "local" or "oidc". Surfaced so the Users page can say
 *                         which accounts are SSO-managed and stop offering a
 *                         password reset that {@link UserService#resetPassword}
 *                         would refuse anyway.
 */
public record UserDto(Long id, String username, String role, String identityProvider) {

    static UserDto from(User user) {
        return new UserDto(user.getId(), user.getUsername(), user.getRole(), user.getIdentityProvider());
    }
}
