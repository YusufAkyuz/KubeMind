package com.kubemind.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminUsername;
    private final String adminPassword;

    public DataInitializer(UserRepository userRepository,
                           PasswordEncoder passwordEncoder,
                           @Value("${kubemind.admin.username:admin}") String adminUsername,
                           @Value("${kubemind.admin.password:}") String adminPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(String... args) {
        // Phase 0: seed a local admin ONLY if a password is provided via env (backend/.env).
        // No password -> no user created, so a non-local deploy never ships a default credential.
        // TODO: replace this whole mechanism with OIDC/SSO before any non-local deployment (see CLAUDE.md).
        if (adminPassword == null || adminPassword.isBlank()) {
            log.warn("KUBEMIND_ADMIN_PASSWORD is not set - skipping admin seeding. "
                + "Set it in backend/.env to create the '{}' user for local login.", adminUsername);
            return;
        }
        if (userRepository.findByUsername(adminUsername).isEmpty()) {
            userRepository.save(new User(adminUsername, passwordEncoder.encode(adminPassword), "ADMIN"));
            log.info("Seeded admin user '{}'.", adminUsername);
        }
    }
}
