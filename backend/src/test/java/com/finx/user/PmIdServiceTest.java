package com.finx.user;

import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.user.entity.User;
import com.finx.user.repository.UserRepository;
import com.finx.user.service.PmIdService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class PmIdServiceTest {

    private UserRepository userRepository;
    private PmIdService pmIdService;

    @BeforeEach
    public void setup() {
        userRepository = Mockito.mock(UserRepository.class);
        pmIdService = new PmIdService(userRepository);
    }

    @Test
    public void testGenerateUniquePmId_FormatAndPrefix() {
        when(userRepository.existsByPmId(anyString())).thenReturn(false);
        when(userRepository.existsByPmIdIgnoreCase(anyString())).thenReturn(false);

        String pmId = pmIdService.generateUniquePmId();

        assertNotNull(pmId);
        assertTrue(pmId.startsWith("FINX-PM-"), "PM ID must start with 'FINX-PM-' prefix");
        assertEquals(14, pmId.length(), "PM ID length should be 14 characters (FINX-PM- + 6 chars)");
        String suffix = pmId.substring("FINX-PM-".length());
        assertTrue(suffix.matches("^[0-9A-Z]{6}$"), "PM ID suffix must be 6 uppercase alphanumeric characters");
    }

    @Test
    public void testGenerateUniquePmId_CollisionRetry() {
        // First attempt collides, second attempt succeeds
        when(userRepository.existsByPmId(anyString()))
                .thenReturn(true)
                .thenReturn(false);
        when(userRepository.existsByPmIdIgnoreCase(anyString()))
                .thenReturn(false);

        String pmId = pmIdService.generateUniquePmId();
        assertNotNull(pmId);
        verify(userRepository, atLeast(2)).existsByPmId(anyString());
    }

    @Test
    public void testAssignPmIdIfEligible_OnlyProjectManager() {
        when(userRepository.existsByPmId(anyString())).thenReturn(false);
        when(userRepository.existsByPmIdIgnoreCase(anyString())).thenReturn(false);

        User buyer = new User("Buyer", "buyer@test.com", "pass", Role.BUYER, UserStatus.ACTIVE);
        pmIdService.assignPmIdIfEligible(buyer);
        assertNull(buyer.getPmId(), "Buyer must NOT receive a PM ID");

        User pm = new User("PM", "pm@test.com", "pass", Role.PROJECT_MANAGER, UserStatus.ACTIVE);
        pmIdService.assignPmIdIfEligible(pm);
        assertNotNull(pm.getPmId(), "Project Manager MUST receive a PM ID");
        assertTrue(pm.getPmId().startsWith("FINX-PM-"));
    }

    @Test
    public void testResolveProjectManager() {
        User pm = new User("PM User", "pm@test.com", "pass", Role.PROJECT_MANAGER, UserStatus.ACTIVE);
        pm.setId(UUID.randomUUID());
        pm.setPmId("FINX-PM-K7X4Q9");

        when(userRepository.findByPmIdIgnoreCase("FINX-PM-K7X4Q9")).thenReturn(Optional.of(pm));
        when(userRepository.findById(pm.getId())).thenReturn(Optional.of(pm));

        // Resolve by exact PM ID
        Optional<User> byPmId = pmIdService.resolveProjectManager("FINX-PM-K7X4Q9");
        assertTrue(byPmId.isPresent());
        assertEquals(pm.getId(), byPmId.get().getId());

        // Resolve by lowercase PM ID
        Optional<User> byLower = pmIdService.resolveProjectManager("finx-pm-k7x4q9");
        assertTrue(byLower.isPresent());

        // Resolve by suffix only
        Optional<User> bySuffix = pmIdService.resolveProjectManager("K7X4Q9");
        assertTrue(bySuffix.isPresent());

        // Resolve by UUID
        Optional<User> byUuid = pmIdService.resolveProjectManager(pm.getId().toString());
        assertTrue(byUuid.isPresent());
    }

    @Test
    public void testBackfillMissingPmIds() {
        User pm1 = new User("PM 1", "pm1@test.com", "pass", Role.PROJECT_MANAGER, UserStatus.ACTIVE);
        pm1.setId(UUID.randomUUID());
        User pm2 = new User("PM 2", "pm2@test.com", "pass", Role.PROJECT_MANAGER, UserStatus.ACTIVE);
        pm2.setId(UUID.randomUUID());

        when(userRepository.findProjectManagersMissingPmId()).thenReturn(List.of(pm1, pm2));
        when(userRepository.existsByPmId(anyString())).thenReturn(false);
        when(userRepository.existsByPmIdIgnoreCase(anyString())).thenReturn(false);

        int count = pmIdService.backfillMissingPmIds();
        assertEquals(2, count);
        assertNotNull(pm1.getPmId());
        assertNotNull(pm2.getPmId());
        verify(userRepository, times(2)).save(any(User.class));
    }
}
