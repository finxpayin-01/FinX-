package com.finx.user.service;

import com.finx.common.enums.Role;
import com.finx.user.entity.User;
import com.finx.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service responsible for generating, assigning, managing, and resolving permanent, unique
 * Project Manager identifiers (format: FINX-PM-XXXXXX).
 */
@Service
public class PmIdService {

    private static final Logger log = LoggerFactory.getLogger(PmIdService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final char[] ALPHANUMERIC_CHARS = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray(); // unambiguous uppercase
    private static final int PM_ID_RANDOM_LENGTH = 6;
    private static final int MAX_COLLISION_RETRIES = 30;
    public static final String PM_ID_PREFIX = "FINX-PM-";

    private final UserRepository userRepository;

    public PmIdService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Generates a cryptographically secure, globally unique Project Manager identifier in the format FINX-PM-XXXXXX.
     * Retries automatically if a collision is detected in the database.
     *
     * @return unique PM ID string (e.g. FINX-PM-K7X4Q9)
     */
    public String generateUniquePmId() {
        for (int attempt = 1; attempt <= MAX_COLLISION_RETRIES; attempt++) {
            String candidate = generateCandidatePmId();
            if (!userRepository.existsByPmId(candidate) && !userRepository.existsByPmIdIgnoreCase(candidate)) {
                return candidate;
            }
            log.warn("PM ID collision detected for candidate '{}' on attempt {}/{}. Retrying...", candidate, attempt, MAX_COLLISION_RETRIES);
        }
        throw new IllegalStateException("Failed to generate a unique PM ID after " + MAX_COLLISION_RETRIES + " attempts.");
    }

    /**
     * Helper to generate a candidate PM ID string of format FINX-PM-XXXXXX using SecureRandom.
     */
    public String generateCandidatePmId() {
        char[] buffer = new char[PM_ID_RANDOM_LENGTH];
        for (int i = 0; i < PM_ID_RANDOM_LENGTH; i++) {
            buffer[i] = ALPHANUMERIC_CHARS[SECURE_RANDOM.nextInt(ALPHANUMERIC_CHARS.length)];
        }
        return PM_ID_PREFIX + new String(buffer);
    }

    /**
     * Assigns a PM ID to a user if their role is PROJECT_MANAGER and they do not have one yet.
     */
    @Transactional
    public void assignPmIdIfEligible(User user) {
        if (user == null || user.getRole() != Role.PROJECT_MANAGER) {
            return;
        }
        if (user.getPmId() == null || user.getPmId().trim().isEmpty()) {
            String newPmId = generateUniquePmId();
            user.setPmId(newPmId);
            log.info("Assigned PM ID '{}' to project manager '{}' (id: {})", newPmId, user.getEmail(), user.getId());
        }
    }

    /**
     * Resolves a Project Manager entity by either internal database UUID or public PM ID (e.g. FINX-PM-K7X4Q9).
     */
    public Optional<User> resolveProjectManager(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) {
            return Optional.empty();
        }
        String clean = identifier.trim();

        // 1. Try resolving as UUID
        try {
            UUID uuid = UUID.fromString(clean);
            Optional<User> byId = userRepository.findById(uuid);
            if (byId.isPresent() && byId.get().getRole() == Role.PROJECT_MANAGER) {
                return byId;
            }
        } catch (IllegalArgumentException ignored) {
            // Not a UUID format, proceed to PM ID lookup
        }

        // 2. Try exact case-insensitive PM ID lookup
        String normalized = clean.toUpperCase();
        Optional<User> byPmId = userRepository.findByPmIdIgnoreCase(normalized);
        if (byPmId.isPresent() && byPmId.get().getRole() == Role.PROJECT_MANAGER) {
            return byPmId;
        }

        // 3. Try prefixing with FINX-PM- if user entered only the suffix
        if (!normalized.startsWith(PM_ID_PREFIX)) {
            Optional<User> withPrefix = userRepository.findByPmIdIgnoreCase(PM_ID_PREFIX + normalized);
            if (withPrefix.isPresent() && withPrefix.get().getRole() == Role.PROJECT_MANAGER) {
                return withPrefix;
            }
        }

        return Optional.empty();
    }

    /**
     * Safety startup backfill to ensure any existing users with role = PROJECT_MANAGER missing a PM ID receive one.
     */
    @Transactional
    public int backfillMissingPmIds() {
        List<User> pmsWithoutId = userRepository.findProjectManagersMissingPmId();
        int backfilledCount = 0;
        for (User user : pmsWithoutId) {
            if (user.getRole() == Role.PROJECT_MANAGER && (user.getPmId() == null || user.getPmId().trim().isEmpty())) {
                String newPmId = generateUniquePmId();
                user.setPmId(newPmId);
                userRepository.save(user);
                backfilledCount++;
                log.info("Backfilled PM ID '{}' for existing project manager '{}' (id: {})", newPmId, user.getEmail(), user.getId());
            }
        }
        return backfilledCount;
    }
}
