package com.finx.user;

import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.user.entity.User;
import com.finx.user.repository.UserRepository;
import com.finx.user.service.UserUidService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserUidServiceTest {

    private static final Pattern UID_PATTERN = Pattern.compile("^USR-[0-9A-F]{8}$");

    @Mock
    private UserRepository userRepository;

    private UserUidService userUidService;

    @BeforeEach
    void setUp() {
        userUidService = new UserUidService(userRepository);
    }

    @Test
    @DisplayName("Generate 150+ UIDs and assert zero duplicate collisions and valid format USR-XXXXXXXX")
    void testBulkGenerateUniqueUids_ZeroCollisions() {
        int count = 150;
        Set<String> generatedUids = new HashSet<>();

        when(userRepository.existsByUid(anyString())).thenReturn(false);
        when(userRepository.existsByUidIgnoreCase(anyString())).thenReturn(false);

        for (int i = 0; i < count; i++) {
            String uid = userUidService.generateUniqueUid();

            assertThat(uid).isNotNull();
            assertThat(uid).matches(UID_PATTERN);
            assertThat(uid.length()).isEqualTo(12); // "USR-" (4) + 8 hex digits = 12

            boolean added = generatedUids.add(uid);
            assertThat(added).as("UID '%s' must be unique and not repeated", uid).isTrue();
        }

        assertThat(generatedUids).hasSize(count);
    }

    @Test
    @DisplayName("Successfully retries when initial candidate has database collision")
    void testCollisionRetry() {
        // First candidate collides, second candidate succeeds
        when(userRepository.existsByUid(anyString()))
                .thenReturn(true)
                .thenReturn(false);
        when(userRepository.existsByUidIgnoreCase(anyString())).thenReturn(false);

        String uid = userUidService.generateUniqueUid();
        assertThat(uid).isNotNull();
        assertThat(uid).matches(UID_PATTERN);

        verify(userRepository, atLeast(2)).existsByUid(anyString());
    }

    @Test
    @DisplayName("Throws IllegalStateException if collision exceeds max retries")
    void testMaxCollisionRetriesExceeded() {
        when(userRepository.existsByUid(anyString())).thenReturn(true);

        assertThatThrownBy(() -> userUidService.generateUniqueUid())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to generate a unique user UID after 20 attempts");
    }

    @Test
    @DisplayName("Resolve user via UUID, exact UID, case-insensitive UID, or omitted prefix")
    void testResolveUser() {
        UUID userId = UUID.randomUUID();
        User testUser = new User("Test Vendor", "vendor@finx.com", "hash", Role.SELLER, UserStatus.ACTIVE);
        testUser.setId(userId);
        testUser.setUid("USR-8F3A21C7");

        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(userRepository.findByUidIgnoreCase("USR-8F3A21C7")).thenReturn(Optional.of(testUser));

        // 1. Resolve by UUID string
        Optional<User> byUuid = userUidService.resolveUser(userId.toString());
        assertThat(byUuid).isPresent().contains(testUser);

        // 2. Resolve by exact UID
        Optional<User> byExactUid = userUidService.resolveUser("USR-8F3A21C7");
        assertThat(byExactUid).isPresent().contains(testUser);

        // 3. Resolve by lowercase UID
        Optional<User> byLowerUid = userUidService.resolveUser("usr-8f3a21c7");
        assertThat(byLowerUid).isPresent().contains(testUser);

        // 4. Resolve by UID with omitted "USR-" prefix
        Optional<User> byOmittedPrefix = userUidService.resolveUser("8F3A21C7");
        assertThat(byOmittedPrefix).isPresent().contains(testUser);

        // 5. Empty or null input returns empty
        assertThat(userUidService.resolveUser(null)).isEmpty();
        assertThat(userUidService.resolveUser("   ")).isEmpty();
    }

    @Test
    @DisplayName("Backfill missing UIDs assigns unique UIDs to users with null or blank UIDs")
    void testBackfillMissingUids() {
        User user1 = new User("User 1", "u1@finx.com", "hash", Role.BUYER, UserStatus.ACTIVE);
        user1.setId(UUID.randomUUID());
        user1.setUid(null);

        User user2 = new User("User 2", "u2@finx.com", "hash", Role.SELLER, UserStatus.ACTIVE);
        user2.setId(UUID.randomUUID());
        user2.setUid("");

        when(userRepository.findByUidIsNull()).thenReturn(List.of(user1, user2));
        when(userRepository.existsByUid(anyString())).thenReturn(false);
        when(userRepository.existsByUidIgnoreCase(anyString())).thenReturn(false);

        int backfilledCount = userUidService.backfillMissingUids();

        assertThat(backfilledCount).isEqualTo(2);
        assertThat(user1.getUid()).isNotNull().matches(UID_PATTERN);
        assertThat(user2.getUid()).isNotNull().matches(UID_PATTERN);
        assertThat(user1.getUid()).isNotEqualTo(user2.getUid());

        verify(userRepository, times(2)).save(any(User.class));
    }
}
