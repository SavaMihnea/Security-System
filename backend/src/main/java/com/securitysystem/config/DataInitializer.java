package com.securitysystem.config;

import com.securitysystem.repository.UserRepository;
import com.securitysystem.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runs once on startup. Creates a default admin user if the database is empty.
 * Change the password immediately after first login.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final AuthService authService;

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.count() == 0) {
            authService.register("admin", "Voxwall@2026");
            log.warn("=================================================");
            log.warn("  Default admin user created: admin / Voxwall@2026");
            log.warn("=================================================");
        }

        // Idempotent: ensure the admin account always carries ADMIN role.
        // Fixes existing installations where register() defaulted to USER role.
        userRepository.findByUsername("admin").ifPresent(admin -> {
            if (!"ADMIN".equals(admin.getRole())) {
                admin.setRole("ADMIN");
                userRepository.save(admin);
                log.info("[INIT] Admin role corrected to ADMIN.");
            }
        });
    }
}
