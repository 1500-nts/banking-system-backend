package com.banking.authservice.config;

import com.banking.authservice.entity.Role;
import com.banking.authservice.entity.User;
import com.banking.authservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Since public registration only ever creates USER accounts, and creating
 * an ADMIN requires an existing ADMIN, this seeds exactly one bootstrap
 * admin on first startup - only if no admin exists yet.
 *
 * Configure via env vars:
 *   ADMIN_BOOTSTRAP_EMAIL
 *   ADMIN_BOOTSTRAP_PASSWORD
 *
 * Change this password immediately after first login in a real deployment.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminBootstrapRunner implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${admin.bootstrap.email:admin@bank.com}")
    private String bootstrapEmail;

    @Value("${admin.bootstrap.password:ChangeMe123!}")
    private String bootstrapPassword;

    @Override
    public void run(String... args) {
        boolean anyAdminExists = !userRepository.findByRole(Role.ADMIN).isEmpty();
        if (anyAdminExists) {
            return;
        }

        User admin = new User();
        admin.setName("System Admin");
        admin.setEmail(bootstrapEmail);
        admin.setPassword(passwordEncoder.encode(bootstrapPassword));
        admin.setRole(Role.ADMIN);
        admin.setEnabled(true);

        userRepository.save(admin);
        log.warn("==============================================================");
        log.warn("No admin existed - bootstrap admin created: {}", bootstrapEmail);
        log.warn("Log in with this account and create further admins via");
        log.warn("POST /api/v1/auth/admin/users, then rotate this password.");
        log.warn("==============================================================");
    }
}
