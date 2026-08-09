package com.kubemind.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    // Null for an OIDC-provisioned account — it has no local password to log
    // in with (see DbUserDetailsService, which must reject rather than NPE).
    @Column(name = "password_hash")
    private String passwordHash;

    @Column(nullable = false)
    private String role = "USER";

    @Column(name = "identity_provider", nullable = false)
    private String identityProvider = "local";

    protected User() {
        // for JPA
    }

    public User(String username, String passwordHash, String role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.identityProvider = "local";
    }

    /** JIT-provisioned from an OIDC login — see KubemindOidcUserService. */
    public static User oidcProvisioned(String username, String role) {
        User user = new User();
        user.username = username;
        user.passwordHash = null;
        user.role = role;
        user.identityProvider = "oidc";
        return user;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getRole() {
        return role;
    }

    public String getIdentityProvider() {
        return identityProvider;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
