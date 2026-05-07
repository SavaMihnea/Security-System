package com.securitysystem.service;

import com.securitysystem.dto.LoginRequest;
import com.securitysystem.dto.LoginResponse;
import com.securitysystem.dto.UserDto;
import com.securitysystem.model.User;
import com.securitysystem.repository.UserRepository;
import com.securitysystem.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int    MAX_ATTEMPTS       = 5;
    private static final long   LOCKOUT_SECONDS    = 15 * 60L; // 15 minutes

    // username -> failed attempt count
    private final ConcurrentHashMap<String, Integer>  failedAttempts = new ConcurrentHashMap<>();
    // username -> lockout expiry (epoch seconds)
    private final ConcurrentHashMap<String, Long>     lockedUntil    = new ConcurrentHashMap<>();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public LoginResponse login(LoginRequest request) {
        String username = request.getUsername();

        // Periodically purge expired lockouts to prevent memory leak
        long now = Instant.now().getEpochSecond();
        lockedUntil.entrySet().removeIf(e -> e.getValue() < now);

        // Check if account is currently locked out
        Long lockExpiry = lockedUntil.get(username);
        if (lockExpiry != null) {
            if (Instant.now().getEpochSecond() < lockExpiry) {
                long remaining = lockExpiry - Instant.now().getEpochSecond();
                throw new LockedException(
                    "Account locked due to too many failed attempts. Try again in " + remaining + " seconds.");
            } else {
                // Lockout expired — reset
                lockedUntil.remove(username);
                failedAttempts.remove(username);
            }
        }

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> {
                    recordFailure(username);
                    return new BadCredentialsException("Invalid credentials");
                });

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            recordFailure(username);
            throw new BadCredentialsException("Invalid credentials");
        }

        // Successful login — clear failure counters
        failedAttempts.remove(username);
        lockedUntil.remove(username);

        String token = jwtUtil.generateToken(user.getUsername(), user.getRole());
        return new LoginResponse(token, user.getUsername(), user.getRole());
    }

    private void recordFailure(String username) {
        int attempts = failedAttempts.merge(username, 1, (a, b) -> a + b);
        if (attempts >= MAX_ATTEMPTS) {
            lockedUntil.put(username, Instant.now().getEpochSecond() + LOCKOUT_SECONDS);
            failedAttempts.remove(username);
        }
    }

    public List<UserDto> getUsers() {
        return userRepository.findAll().stream()
                .sorted((a, b) -> a.getUsername().compareToIgnoreCase(b.getUsername()))
                .map(UserDto::from)
                .toList();
    }

    public void deleteUser(String usernameToDelete) {
        if ("admin".equalsIgnoreCase(usernameToDelete)) {
            throw new IllegalArgumentException("The admin account cannot be deleted");
        }
        User user = userRepository.findByUsername(usernameToDelete)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + usernameToDelete));
        userRepository.deleteById(user.getId());
    }

    public void updateUser(String oldUsername, String newUsername, String newPassword) {
        if ("admin".equalsIgnoreCase(oldUsername)) {
            throw new IllegalArgumentException("The admin account cannot be modified via this endpoint");
        }
        User user = userRepository.findByUsername(oldUsername)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + oldUsername));
        if (newUsername != null && !newUsername.isBlank() && !newUsername.equals(oldUsername)) {
            String trimmed = newUsername.trim();
            if (userRepository.existsByUsername(trimmed)) {
                throw new IllegalArgumentException("Username already taken");
            }
            user.setUsername(trimmed);
        }
        if (newPassword != null && !newPassword.isBlank()) {
            if (newPassword.length() < 8) {
                throw new IllegalArgumentException("Password must be at least 8 characters");
            }
            user.setPasswordHash(passwordEncoder.encode(newPassword));
        }
        userRepository.save(user);
    }

    public void changePassword(String username, String currentPassword, String newPassword) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BadCredentialsException("User not found"));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BadCredentialsException("Current password is incorrect");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }

    public void register(String username, String rawPassword) {
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("Username already exists");
        }
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        userRepository.save(user);
    }
}
