package com.finx.auth.controller;

import com.finx.audit.service.AuditService;
import com.finx.auth.repository.RefreshTokenRepository;
import com.finx.common.response.ApiResponse;
import com.finx.user.entity.User;
import com.finx.user.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/auth/dev")
@Profile({"dev", "local", "test"})
@Tag(name = "Development Account Recovery", description = "Development-only credential recovery endpoints (disabled in production)")
public class DevAccountRecoveryController {

    private static final Logger log = LoggerFactory.getLogger(DevAccountRecoveryController.class);

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public DevAccountRecoveryController(UserRepository userRepository,
                                        RefreshTokenRepository refreshTokenRepository,
                                        PasswordEncoder passwordEncoder,
                                        AuditService auditService) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    public record DevRecoveryRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 100) String newPassword
    ) {}

    @PostMapping("/recover-password")
    @Transactional
    @Operation(summary = "Reset local development account password (dev profile only)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> recoverPassword(@Valid @RequestBody DevRecoveryRequest request) {
        User user = userRepository.findByEmail(request.email().trim().toLowerCase())
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + request.email()));

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        // Revoke all existing sessions/refresh tokens for security
        refreshTokenRepository.revokeAllActiveUserTokens(user, Instant.now());

        log.info("[DEV RECOVERY] Password reset for dev user: {} (Role: {})", user.getEmail(), user.getRole());
        auditService.logEvent(user.getId(), "DEV_PASSWORD_RECOVERY", "USER", user.getId().toString(), "Development password reset");

        return ResponseEntity.ok(ApiResponse.success("Password reset successfully for " + user.getEmail(), Map.of(
                "email", user.getEmail(),
                "role", user.getRole().name(),
                "name", user.getName()
        )));
    }
}
