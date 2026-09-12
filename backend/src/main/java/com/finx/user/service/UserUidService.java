package com.finx.user.service;

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
 * Service responsible for generating, managing, and resolving permanent, unique User UIDs (format: USR-XXXXXXXX).
 */
@Service
public class UserUidService {

    private static final Logger log = LoggerFactory.getLogger(UserUidService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final char[] HEX_CHARS = "0123456789ABCDEF".toCharArray();
    private static final int MAX_COLLISION_RETRIES = 20;

    private final UserRepository userRepository;

    public UserUidService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Generates a cryptographically secure, globally unique user tracker identifier in the format USR-XXXXXXXX.
     * Retries automatically if a collision is detected in the database.
     *
     * @return unique UID string (e.g. USR-8F3A21C7)
     */
    public String generateUniqueUid() {
        for (int attempt = 1; attempt <= MAX_COLLISION_RETRIES; attempt++) {
            String candidate = generateCandidateUid();
            if (!userRepository.existsByUid(candidate) && !userRepository.existsByUidIgnoreCase(candidate)) {
                return candidate;
            }
            log.warn("UID collision detected for candidate '{}' on attempt {}/{}. Retrying...", candidate, attempt, MAX_COLLISION_RETRIES);
        }
        throw new IllegalStateException("Failed to generate a unique user UID after " + MAX_COLLISION_RETRIES + " attempts.");
    }

    /**
     * Helper to generate a candidate UID string of format USR-XXXXXXXX using SecureRandom.
     */
    public String generateCandidateUid() {
        byte[] randomBytes = new byte[4];
        SECURE_RANDOM.nextBytes(randomBytes);
        char[] hexDigits = new char[8];
        for (int i = 0; i < 4; i++) {
            int byteVal = randomBytes[i] & 0xFF;
            hexDigits[i * 2] = HEX_CHARS[byteVal >>> 4];
            hexDigits[i * 2 + 1] = HEX_CHARS[byteVal & 0x0F];
        }
        return "USR-" + new String(hexDigits);
    }

    /**
     * Resolves a user entity by either an internal database UUID or a public-facing UID (with or without 'USR-' prefix).
     *
     * @param identifier input UUID string or UID string
     * @return Optional containing the resolved User, or empty
     */
    public Optional<User> resolveUser(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) {
            return Optional.empty();
        }
        String clean = identifier.trim();

        // 1. Try resolving as UUID
        try {
            UUID uuid = UUID.fromString(clean);
            Optional<User> byId = userRepository.findById(uuid);
            if (byId.isPresent()) {
                return byId;
            }
        } catch (IllegalArgumentException ignored) {
            // Not a UUID format, proceed to UID lookup
        }

        // 2. Try exact case-insensitive UID lookup
        String normalizedUid = clean.toUpperCase();
        Optional<User> byUid = userRepository.findByUidIgnoreCase(normalizedUid);
        if (byUid.isPresent()) {
            return byUid;
        }

        // 3. Try resolving with "USR-" prefix if omitted by user
        if (!normalizedUid.startsWith("USR-")) {
            return userRepository.findByUidIgnoreCase("USR-" + normalizedUid);
        }

        return Optional.empty();
    }

    /**
     * Safety startup backfill to ensure any existing users with null or blank UIDs are assigned permanent unique UIDs.
     */
    @Transactional
    public int backfillMissingUids() {
        List<User> usersWithoutUid = userRepository.findByUidIsNull();
        int backfilledCount = 0;
        for (User user : usersWithoutUid) {
            if (user.getUid() == null || user.getUid().trim().isEmpty()) {
                String newUid = generateUniqueUid();
                user.setUid(newUid);
                userRepository.save(user);
                backfilledCount++;
                log.info("Backfilled UID '{}' for existing user '{}' (id: {})", newUid, user.getEmail(), user.getId());
            }
        }
        return backfilledCount;
    }
}
