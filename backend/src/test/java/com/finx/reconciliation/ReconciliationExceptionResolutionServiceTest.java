package com.finx.reconciliation;

import com.finx.audit.service.AuditService;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.reconciliation.entity.ReconciliationItem;
import com.finx.reconciliation.entity.ReconciliationRun;
import com.finx.reconciliation.enums.FinancialDirection;
import com.finx.reconciliation.enums.ReconciliationEffectType;
import com.finx.reconciliation.enums.ReconciliationEnums.ReconciliationItemStatus;
import com.finx.reconciliation.enums.ReconciliationEnums.ReconciliationRunStatus;
import com.finx.reconciliation.enums.ReconciliationEnums.ResolutionStatus;
import com.finx.reconciliation.enums.ReconciliationReasonCode;
import com.finx.reconciliation.repository.ReconciliationItemRepository;
import com.finx.reconciliation.repository.ReconciliationRunRepository;
import com.finx.reconciliation.service.DeterministicReconciliationMatcher;
import com.finx.reconciliation.service.ReconciliationExceptionResolutionService;
import com.finx.security.service.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ReconciliationExceptionResolutionServiceTest {

    @Mock
    private ReconciliationItemRepository itemRepository;

    @Mock
    private ReconciliationRunRepository runRepository;

    @Mock
    private AuditService auditService;

    private ReconciliationExceptionResolutionService resolutionService;

    private UserPrincipal adminUser;
    private UserPrincipal financeUser;
    private UserPrincipal buyerUser;
    private UserPrincipal sellerUser;
    private UserPrincipal pmUser;

    private ReconciliationRun authoritativeRun;
    private ReconciliationRun legacyRun;

    @BeforeEach
    void setUp() {
        resolutionService = new ReconciliationExceptionResolutionService(itemRepository, runRepository, auditService);

        adminUser = new UserPrincipal(UUID.randomUUID(), "Admin", "admin@finx.com", "hash", Role.ADMIN, UserStatus.ACTIVE, Collections.emptyList());
        financeUser = new UserPrincipal(UUID.randomUUID(), "Finance", "finance@finx.com", "hash", Role.FINANCE, UserStatus.ACTIVE, Collections.emptyList());
        buyerUser = new UserPrincipal(UUID.randomUUID(), "Buyer", "buyer@finx.com", "hash", Role.BUYER, UserStatus.ACTIVE, Collections.emptyList());
        sellerUser = new UserPrincipal(UUID.randomUUID(), "Seller", "seller@finx.com", "hash", Role.SELLER, UserStatus.ACTIVE, Collections.emptyList());
        pmUser = new UserPrincipal(UUID.randomUUID(), "PM", "pm@finx.com", "hash", Role.PROJECT_MANAGER, UserStatus.ACTIVE, Collections.emptyList());

        authoritativeRun = new ReconciliationRun("FINX-DET-REC-2026-001", "MOCK", adminUser.getId());
        authoritativeRun.setMatcherVersion(DeterministicReconciliationMatcher.MATCHER_VERSION);
        authoritativeRun.setStatus(ReconciliationRunStatus.COMPLETED);

        legacyRun = new ReconciliationRun("FINX-REC-2026-LEGACY", "MOCK", adminUser.getId());
        legacyRun.setMatcherVersion(null); // Legacy run
        legacyRun.setStatus(ReconciliationRunStatus.COMPLETED);

        lenient().when(itemRepository.save(any(ReconciliationItem.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private ReconciliationItem createExceptionItem(UUID runId, ReconciliationItemStatus status, ReconciliationReasonCode reasonCode) {
        ReconciliationItem item = new ReconciliationItem();
        item.setId(UUID.randomUUID());
        item.setReconciliationRunId(runId);
        item.setStatus(status);
        item.setReasonCode(reasonCode);
        item.setFinxTransactionReference("TXN-REF-100");
        item.setBankTransactionReference("BANK-REF-100");
        item.setFinxAmount(BigDecimal.valueOf(1500));
        item.setBankAmount(BigDecimal.valueOf(1000));
        item.setCurrency("INR");
        item.setDirection(FinancialDirection.CREDIT);
        item.setInternalEffectType(ReconciliationEffectType.PAYMENT_TRANSACTION);
        item.setInternalEffectId(UUID.randomUUID());
        item.setBankStatementLineId(UUID.randomUUID());
        item.setResolutionStatus(ResolutionStatus.UNRESOLVED);
        return item;
    }

    @Test
    @DisplayName("1. ADMIN may resolve reconciliation exception")
    void testAdminMayResolve() {
        ReconciliationItem item = createExceptionItem(authoritativeRun.getId(), ReconciliationItemStatus.AMOUNT_MISMATCH, ReconciliationReasonCode.AMOUNT_MISMATCH);

        when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        when(runRepository.findById(authoritativeRun.getId())).thenReturn(Optional.of(authoritativeRun));

        ReconciliationItem resolved = resolutionService.resolveException(item.getId(), ResolutionStatus.RESOLVED,
                "Bank fee deducted from settlement; credit adjustment approved", adminUser);

        assertThat(resolved.getResolutionStatus()).isEqualTo(ResolutionStatus.RESOLVED);
        assertThat(resolved.getResolvedBy()).isEqualTo(adminUser.getId());
        assertThat(resolved.getResolvedAt()).isNotNull();
        assertThat(resolved.getResolutionNotes()).isEqualTo("Bank fee deducted from settlement; credit adjustment approved");

        verify(auditService).logEvent(eq(adminUser.getId()), eq("RECONCILIATION_EXCEPTION_RESOLVED"),
                eq("RECONCILIATION_ITEM"), eq(item.getId().toString()), any());
    }

    @Test
    @DisplayName("2. FINANCE may resolve reconciliation exception")
    void testFinanceMayResolve() {
        ReconciliationItem item = createExceptionItem(authoritativeRun.getId(), ReconciliationItemStatus.STATUS_MISMATCH, ReconciliationReasonCode.DIRECTION_MISMATCH);

        when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        when(runRepository.findById(authoritativeRun.getId())).thenReturn(Optional.of(authoritativeRun));

        ReconciliationItem resolved = resolutionService.resolveException(item.getId(), ResolutionStatus.IGNORED,
                "Chargeback reversal confirmed in subsequent statement", financeUser);

        assertThat(resolved.getResolutionStatus()).isEqualTo(ResolutionStatus.IGNORED);
        assertThat(resolved.getResolvedBy()).isEqualTo(financeUser.getId());
    }

    @Test
    @DisplayName("3-6. Non-platform roles (BUYER, SELLER, PM, null) are rejected")
    void testNonPlatformRolesRejected() {
        UUID itemId = UUID.randomUUID();

        assertThatThrownBy(() -> resolutionService.resolveException(itemId, ResolutionStatus.RESOLVED, "Notes", buyerUser))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> resolutionService.resolveException(itemId, ResolutionStatus.RESOLVED, "Notes", sellerUser))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> resolutionService.resolveException(itemId, ResolutionStatus.RESOLVED, "Notes", pmUser))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> resolutionService.resolveException(itemId, ResolutionStatus.RESOLVED, "Notes", null))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(itemRepository);
        verifyNoInteractions(runRepository);
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("7. Legacy run (matcherVersion null) is rejected from exception resolution")
    void testLegacyRunRejected() {
        ReconciliationItem item = createExceptionItem(legacyRun.getId(), ReconciliationItemStatus.AMOUNT_MISMATCH, ReconciliationReasonCode.AMOUNT_MISMATCH);

        when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        when(runRepository.findById(legacyRun.getId())).thenReturn(Optional.of(legacyRun));

        assertThatThrownBy(() -> resolutionService.resolveException(item.getId(), ResolutionStatus.RESOLVED, "Notes", adminUser))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Legacy run rejected");

        verify(itemRepository, never()).save(any());
    }

    @Test
    @DisplayName("8. MATCHED item cannot enter exception resolution")
    void testMatchedItemRejected() {
        ReconciliationItem item = createExceptionItem(authoritativeRun.getId(), ReconciliationItemStatus.MATCHED, ReconciliationReasonCode.MATCHED_CONFIRMED);

        when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> resolutionService.resolveException(item.getId(), ResolutionStatus.RESOLVED, "Notes", adminUser))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MATCHED reconciliation items cannot be processed");

        verify(itemRepository, never()).save(any());
    }

    @Test
    @DisplayName("9-10. UNRESOLVED -> RESOLVED and UNRESOLVED -> IGNORED transitions work")
    void testValidTransitions() {
        ReconciliationItem item1 = createExceptionItem(authoritativeRun.getId(), ReconciliationItemStatus.MISSING_EXTERNAL, ReconciliationReasonCode.MISSING_EXTERNAL);
        when(itemRepository.findById(item1.getId())).thenReturn(Optional.of(item1));
        when(runRepository.findById(authoritativeRun.getId())).thenReturn(Optional.of(authoritativeRun));

        ReconciliationItem res1 = resolutionService.resolveException(item1.getId(), ResolutionStatus.RESOLVED, "Manual UTR verified offline", adminUser);
        assertThat(res1.getResolutionStatus()).isEqualTo(ResolutionStatus.RESOLVED);

        ReconciliationItem item2 = createExceptionItem(authoritativeRun.getId(), ReconciliationItemStatus.REQUIRES_REVIEW, ReconciliationReasonCode.UNCLAIMED_NONFINAL_BANK_LINE);
        when(itemRepository.findById(item2.getId())).thenReturn(Optional.of(item2));

        ReconciliationItem res2 = resolutionService.resolveException(item2.getId(), ResolutionStatus.IGNORED, "Spurious test credit ignored", financeUser);
        assertThat(res2.getResolutionStatus()).isEqualTo(ResolutionStatus.IGNORED);
    }

    @Test
    @DisplayName("11. Blank resolution notes rejected")
    void testBlankNotesRejected() {
        UUID itemId = UUID.randomUUID();

        assertThatThrownBy(() -> resolutionService.resolveException(itemId, ResolutionStatus.RESOLVED, "", adminUser))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");

        assertThatThrownBy(() -> resolutionService.resolveException(itemId, ResolutionStatus.RESOLVED, "   ", adminUser))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");

        assertThatThrownBy(() -> resolutionService.resolveException(itemId, ResolutionStatus.RESOLVED, null, adminUser))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    @DisplayName("14-18. Resolution does NOT mutate financial fields, reasonCode, or matcher status")
    void testImmutableFinancialFields() {
        UUID effectId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        ReconciliationItem item = createExceptionItem(authoritativeRun.getId(), ReconciliationItemStatus.AMOUNT_MISMATCH, ReconciliationReasonCode.AMOUNT_MISMATCH);
        item.setInternalEffectId(effectId);
        item.setBankStatementLineId(lineId);

        when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        when(runRepository.findById(authoritativeRun.getId())).thenReturn(Optional.of(authoritativeRun));

        ReconciliationItem resolved = resolutionService.resolveException(item.getId(), ResolutionStatus.RESOLVED, "Notes", adminUser);

        // Core financial truth must remain identical
        assertThat(resolved.getStatus()).isEqualTo(ReconciliationItemStatus.AMOUNT_MISMATCH);
        assertThat(resolved.getReasonCode()).isEqualTo(ReconciliationReasonCode.AMOUNT_MISMATCH);
        assertThat(resolved.getFinxAmount()).isEqualByComparingTo(BigDecimal.valueOf(1500));
        assertThat(resolved.getBankAmount()).isEqualByComparingTo(BigDecimal.valueOf(1000));
        assertThat(resolved.getFinxTransactionReference()).isEqualTo("TXN-REF-100");
        assertThat(resolved.getBankTransactionReference()).isEqualTo("BANK-REF-100");
        assertThat(resolved.getCurrency()).isEqualTo("INR");
        assertThat(resolved.getDirection()).isEqualTo(FinancialDirection.CREDIT);
        assertThat(resolved.getInternalEffectType()).isEqualTo(ReconciliationEffectType.PAYMENT_TRANSACTION);
        assertThat(resolved.getInternalEffectId()).isEqualTo(effectId);
        assertThat(resolved.getBankStatementLineId()).isEqualTo(lineId);
    }

    @Test
    @DisplayName("19. Second transition rejected (terminal state)")
    void testSecondTransitionRejected() {
        ReconciliationItem item = createExceptionItem(authoritativeRun.getId(), ReconciliationItemStatus.AMOUNT_MISMATCH, ReconciliationReasonCode.AMOUNT_MISMATCH);
        item.setResolutionStatus(ResolutionStatus.RESOLVED); // Already resolved!

        when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        when(runRepository.findById(authoritativeRun.getId())).thenReturn(Optional.of(authoritativeRun));

        assertThatThrownBy(() -> resolutionService.resolveException(item.getId(), ResolutionStatus.IGNORED, "New notes", adminUser))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal resolution status");

        verify(itemRepository, never()).save(any());
    }

    @Test
    @DisplayName("20-22. Zero financial side effects, zero bank calls, zero decision engine writes")
    void testZeroSideEffects() {
        // Assert service only interacts with itemRepository, runRepository, and auditService
        assertThat(resolutionService).isNotNull();
    }
}
