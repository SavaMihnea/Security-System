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
            authService.register("admin", "mihneasava345");
            log.warn("=================================================");
            log.warn("  Default admin user created: admin / mihneasava345");
            log.warn("  CHANGE THIS PASSWORD BEFORE GOING LIVE!");
            log.warn("=================================================");
        }
    }
}
