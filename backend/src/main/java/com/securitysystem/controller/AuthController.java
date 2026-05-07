package com.securitysystem.controller;

import com.securitysystem.dto.LoginRequest;
import com.securitysystem.dto.LoginResponse;
import com.securitysystem.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        try {
            LoginResponse response = authService.login(request);
            return ResponseEntity.ok(response);
        } catch (LockedException e) {
            return ResponseEntity.status(423).body(Map.of("error", e.getMessage()));
        } catch (BadCredentialsException e) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid username or password"));
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(
            @AuthenticationPrincipal String username,
            @RequestBody Map<String, String> body) {

        String currentPassword = body.get("currentPassword");
        String newPassword     = body.get("newPassword");

        if (newPassword == null || newPassword.length() < 8) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "New password must be at least 8 characters"));
        }
        if (currentPassword == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Current password is required"));
        }
        try {
            authService.changePassword(username, currentPassword, newPassword);
            return ResponseEntity.ok(Map.of("message", "Password updated successfully"));
        } catch (BadCredentialsException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/users/{username}")
    public ResponseEntity<?> updateUser(@PathVariable String username, Authentication auth,
                                        @RequestBody Map<String, String> body) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).body(Map.of("error", "Admin only"));
        String newUsername = body.get("newUsername");
        String newPassword = body.get("newPassword");
        if ((newUsername == null || newUsername.isBlank()) && (newPassword == null || newPassword.isBlank())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Nothing to update"));
        }
        try {
            authService.updateUser(username, newUsername, newPassword);
            return ResponseEntity.ok(Map.of("message", "User updated"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/users")
    public ResponseEntity<?> getUsers(Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).body(Map.of("error", "Admin only"));
        return ResponseEntity.ok(authService.getUsers());
    }

    @PostMapping("/users")
    public ResponseEntity<?> createUser(Authentication auth, @RequestBody Map<String, String> body) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).body(Map.of("error", "Admin only"));
        String newUsername = body.get("username");
        String password    = body.get("password");
        if (newUsername == null || newUsername.isBlank() || password == null || password.length() < 8) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Username required and password must be at least 8 characters"));
        }
        try {
            authService.register(newUsername.trim(), password);
            return ResponseEntity.ok(Map.of("message", "User created"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/users/{username}")
    public ResponseEntity<?> deleteUser(@PathVariable String username, Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).body(Map.of("error", "Admin only"));
        try {
            authService.deleteUser(username);
            return ResponseEntity.ok(Map.of("message", "User deleted"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private boolean isAdmin(Authentication auth) {
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
}
