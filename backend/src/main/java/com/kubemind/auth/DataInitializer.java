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
        User existing = userRepository.findByUsername(adminUsername).orElse(null);
        if (existing == null) {
            userRepository.save(new User(adminUsername, passwordEncoder.encode(adminPassword), "ADMIN"));
            log.info("Seeded admin user '{}'.", adminUsername);
            return;
        }
        // The configured password is the source of truth, so changing the Helm
        // secret (or backend/.env) actually takes effect. Seeding only when the
        // row was absent meant a database that outlived its configuration —
        // restored from a backup, or carried to another machine — left nobody
        // able to log in, with no way back short of editing the table by hand.
        //
        // The trade-off, deliberately taken: a password changed through the
        // Users page reverts to the configured one on the next restart. That is
        // recoverable; being locked out of your own installation is not.
        if (!"local".equals(existing.getIdentityProvider())) {
            // An SSO-provisioned account of the same name must never be handed a
            // local password here — see UserService.resetPassword for why.
            log.warn("Admin username '{}' belongs to an SSO-provisioned account; "
                + "leaving it alone. KUBEMIND_ADMIN_PASSWORD has no effect.", adminUsername);
            return;
        }
        if (!passwordEncoder.matches(adminPassword, existing.getPasswordHash())) {
            existing.setPasswordHash(passwordEncoder.encode(adminPassword));
            userRepository.save(existing);
            log.warn("Admin '{}' did not match KUBEMIND_ADMIN_PASSWORD — reset to the configured value. "
                + "If you changed it in the UI, change the configuration instead; it wins on every start.",
                adminUsername);
        }
    }
}
