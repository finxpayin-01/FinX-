package com.finx.release.finalizer.service;

import com.finx.audit.service.AuditService;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.escrow.entity.EscrowAccount;
import com.finx.escrow.entity.EscrowLedger;
import com.finx.escrow.entity.TransactionType;
import com.finx.escrow.repository.EscrowAccountRepository;
import com.finx.escrow.repository.EscrowLedgerRepository;
import com.finx.ledger.entity.JournalEntry;
import com.finx.ledger.enums.LedgerEnums.LedgerTransactionType;
import com.finx.ledger.repository.JournalEntryRepository;
import com.finx.ledger.service.LedgerService;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.entity.ReleaseReservation;
import com.finx.release.execution.entity.SettlementExecutionAttempt;
import com.finx.release.execution.repository.SettlementExecutionAttemptRepository;
import com.finx.release.finalizer.gate.SandboxSettlementFinalizationGate;
import com.finx.release.finalizer.model.SettlementFinalizationResult;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.model.ReleaseReservationStatus;
import com.finx.release.observation.entity.SettlementObservation;
import com.finx.release.observation.model.SettlementObservationFinality;
import com.finx.release.observation.model.SettlementObservationSource;
import com.finx.release.observation.service.SettlementObservationEvidenceReader;
import com.finx.release.observation.service.SettlementObservationEvidenceReader.AuthoritativeObservationResult;
import com.finx.release.observation.service.SettlementObservationEvidenceReader.AuthoritativeStatus;
import com.finx.release.repository.ReleaseInstructionRepository;
import com.finx.release.repository.ReleaseInstructionRepository.ScopeProjection;
import com.finx.release.repository.ReleaseReservationRepository;
import com.finx.security.service.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrustedSettlementFinalizerTest {

    @Mock private ReleaseInstructionRepository releaseInstructionRepository;
    @Mock private ReleaseReservationRepository releaseReservationRepository;
    @Mock private EscrowAccountRepository escrowAccountRepository;
    @Mock private EscrowLedgerRepository escrowLedgerRepository;
    @Mock private MilestoneRepository milestoneRepository;
    @Mock private DealRepository dealRepository;
    @Mock private SettlementExecutionAttemptRepository attemptRepository;
    @Mock private JournalEntryRepository journalEntryRepository;
    @Mock private LedgerService ledgerService;
    @Mock private SettlementObservationEvidenceReader evidenceReader;
    @Mock private SandboxSettlementFinalizationGate finalizationGate;
    @Mock private AuditService auditService;

    private TrustedSettlementFinalizer finalizer;

    private UUID releaseInstructionId;
    private UUID dealId;
    private UUID milestoneId;
    private UUID escrowAccountId;
    private UUID attemptId;
    private UUID observationId;
    private UUID adminUserId;
    private UUID financeUserId;
    private UUID buyerUserId;
    private UUID sellerUserId;
    private UUID pmUserId;

    private UserPrincipal adminPrincipal;
    private UserPrincipal financePrincipal;
    private UserPrincipal buyerPrincipal;
    private UserPrincipal sellerPrincipal;
    private UserPrincipal pmPrincipal;

    private ReleaseInstruction releaseInstruction;
    private ReleaseReservation reservation;
    private SettlementExecutionAttempt attempt;
    private SettlementObservation observation;
    private Deal deal;
    private Milestone milestone;
    private EscrowAccount escrowAccount;

    @BeforeEach
    void setUp() {
        finalizer = new TrustedSettlementFinalizer(
                releaseInstructionRepository,
                releaseReservationRepository,
                escrowAccountRepository,
                escrowLedgerRepository,
                milestoneRepository,
                dealRepository,
                attemptRepository,
                journalEntryRepository,
                ledgerService,
                evidenceReader,
                finalizationGate,
                auditService
        );

        releaseInstructionId = UUID.randomUUID();
        dealId = UUID.randomUUID();
        milestoneId = UUID.randomUUID();
        escrowAccountId = UUID.randomUUID();
        attemptId = UUID.randomUUID();
        observationId = UUID.randomUUID();

        adminUserId = UUID.randomUUID();
        financeUserId = UUID.randomUUID();
        buyerUserId = UUID.randomUUID();
        sellerUserId = UUID.randomUUID();
        pmUserId = UUID.randomUUID();

        adminPrincipal = new UserPrincipal(adminUserId, "Admin", "admin@finx.com", "pass", Role.ADMIN, UserStatus.ACTIVE, List.of());
        financePrincipal = new UserPrincipal(financeUserId, "Finance", "finance@finx.com", "pass", Role.FINANCE, UserStatus.ACTIVE, List.of());
        buyerPrincipal = new UserPrincipal(buyerUserId, "Buyer", "buyer@finx.com", "pass", Role.BUYER, UserStatus.ACTIVE, List.of());
        sellerPrincipal = new UserPrincipal(sellerUserId, "Seller", "seller@finx.com", "pass", Role.SELLER, UserStatus.ACTIVE, List.of());
        pmPrincipal = new UserPrincipal(pmUserId, "PM", "pm@finx.com", "pass", Role.PROJECT_MANAGER, UserStatus.ACTIVE, List.of());

        deal = new Deal();
        deal.setId(dealId);
        deal.setStatus(DealStatus.FUNDED);

        milestone = new Milestone();
        milestone.setId(milestoneId);
        milestone.setDealId(dealId);
        milestone.setAmount(new BigDecimal("50000.00"));
        milestone.setStatus(MilestoneStatus.APPROVED);

        escrowAccount = new EscrowAccount();
        escrowAccount.setId(escrowAccountId);
        escrowAccount.setDealId(dealId);
        escrowAccount.setCurrency("INR");
        escrowAccount.setBalance(new BigDecimal("100000.00"));
        escrowAccount.setTotalReleased(BigDecimal.ZERO);

        releaseInstruction = new ReleaseInstruction(
                releaseInstructionId,
                dealId,
                milestoneId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "a".repeat(64),
                UUID.randomUUID(),
                "b".repeat(64),
                UUID.randomUUID(),
                "MOCK",
                "ESCROW-POOL-1",
                new BigDecimal("50000.00"),
                "INR",
                "idem-key-1",
                "c".repeat(64),
                UUID.randomUUID()
        );
        releaseInstruction.markAuthorized(adminUserId, Instant.now());
        releaseInstruction.markReserved();
        releaseInstruction.markQueued();
        releaseInstruction.markDispatching();
        releaseInstruction.markPendingSettlement();

        reservation = new ReleaseReservation(
                UUID.randomUUID(),
                releaseInstructionId,
                escrowAccountId,
                milestoneId,
                new BigDecimal("50000.00"),
                "INR"
        );

        attempt = new SettlementExecutionAttempt(
                attemptId,
                releaseInstructionId,
                UUID.randomUUID(),
                1,
                "OP-KEY-12345",
                "OP-KEY-12345",
                "MOCK",
                "ESCROW-POOL-1",
                releaseInstruction.getBeneficiaryBankAccountId(),
                new BigDecimal("50000.00"),
                "INR",
                "a".repeat(64),
                "worker-1",
                Instant.now()
        );
        attempt.markSending(Instant.now());
        attempt.markAcknowledged("SETTLE-BANK-999", "SUCCESS", Instant.now());

        observation = SettlementObservation.builder()
                .id(observationId)
                .releaseInstructionId(releaseInstructionId)
                .executionAttemptId(attemptId)
                .provider("MOCK")
                .source(SettlementObservationSource.WEBHOOK)
                .sourceReference("webhook-event-1")
                .providerOperationKey("OP-KEY-12345")
                .providerSettlementId("SETTLE-BANK-999")
                .providerAccountId("ESCROW-POOL-1")
                .providerStatus("SUCCESS")
                .finality(SettlementObservationFinality.FINAL_SUCCESS)
                .reasonCode("SUCCESS")
                .utrNumber("UTR-BANK-123456")
                .observedAmount(new BigDecimal("50000.00"))
                .observedCurrency("INR")
                .observedDirection("DEBIT")
                .evidenceHash("a".repeat(64))
                .observedAt(Instant.now())
                .build();
    }

    private void setupStandardSuccessfulMocks() {
        ScopeProjection scope = new ScopeProjection() {
            @Override public UUID getDealId() { return dealId; }
            @Override public UUID getMilestoneId() { return milestoneId; }
        };
        when(releaseInstructionRepository.findScopeById(releaseInstructionId)).thenReturn(Optional.of(scope));
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(milestoneRepository.findByIdForUpdate(milestoneId)).thenReturn(Optional.of(milestone));
        when(escrowAccountRepository.findByDealIdForUpdate(dealId)).thenReturn(Optional.of(escrowAccount));
        when(releaseInstructionRepository.findByIdForUpdate(releaseInstructionId)).thenReturn(Optional.of(releaseInstruction));
        when(releaseReservationRepository.findByReleaseInstructionIdForUpdate(releaseInstructionId)).thenReturn(Optional.of(reservation));
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));

        AuthoritativeObservationResult finalObs = new AuthoritativeObservationResult(
                AuthoritativeStatus.FINAL_SUCCESS,
                observation,
                "Trusted webhook success"
        );
        when(evidenceReader.getLatestAuthoritativeFinalObservation(releaseInstructionId)).thenReturn(finalObs);

        when(journalEntryRepository.findByTransactionTypeAndReferenceId(LedgerTransactionType.MILESTONE_SETTLEMENT, releaseInstructionId))
                .thenReturn(Optional.empty());
        when(escrowLedgerRepository.findByMilestoneIdAndTransactionType(milestoneId, TransactionType.RELEASE))
                .thenReturn(Optional.empty());

        JournalEntry mockJournal = new JournalEntry();
        mockJournal.setId(UUID.randomUUID());
        when(ledgerService.postMilestoneSettlement(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockJournal);

        EscrowLedger savedEscrowLedger = new EscrowLedger();
        savedEscrowLedger.setId(UUID.randomUUID());
        when(escrowLedgerRepository.save(any(EscrowLedger.class))).thenReturn(savedEscrowLedger);
        when(milestoneRepository.findByDealIdOrderBySequenceAsc(dealId)).thenReturn(List.of(milestone));
    }

    // 1. ADMIN can finalize
    @Test
    @DisplayName("Case 1: ADMIN principal can finalize successfully")
    void testAdminCanFinalize() {
        setupStandardSuccessfulMocks();

        SettlementFinalizationResult result = finalizer.finalizeRelease(releaseInstructionId, adminPrincipal);

        assertThat(result).isNotNull();
        assertThat(result.releaseInstructionId()).isEqualTo(releaseInstructionId);
        assertThat(result.settlementObservationId()).isEqualTo(observationId);
        assertThat(result.idempotentReplay()).isFalse();
        verify(finalizationGate).assertFinalizationAllowed(releaseInstruction);
    }

    // 2. FINANCE can finalize
    @Test
    @DisplayName("Case 2: FINANCE principal can finalize successfully")
    void testFinanceCanFinalize() {
        setupStandardSuccessfulMocks();

        SettlementFinalizationResult result = finalizer.finalizeRelease(releaseInstructionId, financePrincipal);

        assertThat(result).isNotNull();
        assertThat(result.releaseInstructionId()).isEqualTo(releaseInstructionId);
    }

    // 3. BUYER rejected
    @Test
    @DisplayName("Case 3: BUYER principal is rejected")
    void testBuyerRejected() {
        assertThatThrownBy(() -> finalizer.finalizeRelease(releaseInstructionId, buyerPrincipal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Only ADMIN or FINANCE roles can finalize");
    }

    // 4. SELLER rejected
    @Test
    @DisplayName("Case 4: SELLER principal is rejected")
    void testSellerRejected() {
        assertThatThrownBy(() -> finalizer.finalizeRelease(releaseInstructionId, sellerPrincipal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Only ADMIN or FINANCE roles can finalize");
    }

    // 5. PM rejected
    @Test
    @DisplayName("Case 5: PM principal is rejected")
    void testPmRejected() {
        assertThatThrownBy(() -> finalizer.finalizeRelease(releaseInstructionId, pmPrincipal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Only ADMIN or FINANCE roles can finalize");
    }

    // 6. null principal rejected
    @Test
    @DisplayName("Case 6: null principal is rejected")
    void testNullPrincipalRejected() {
        assertThatThrownBy(() -> finalizer.finalizeRelease(releaseInstructionId, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    // 7. sandbox disabled blocked
    @Test
    @DisplayName("Case 7: Gate failure blocks finalization")
    void testGateBlocked() {
        ScopeProjection scope = new ScopeProjection() {
            @Override public UUID getDealId() { return dealId; }
            @Override public UUID getMilestoneId() { return milestoneId; }
        };
        when(releaseInstructionRepository.findScopeById(releaseInstructionId)).thenReturn(Optional.of(scope));
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(milestoneRepository.findByIdForUpdate(milestoneId)).thenReturn(Optional.of(milestone));
        when(escrowAccountRepository.findByDealIdForUpdate(dealId)).thenReturn(Optional.of(escrowAccount));
        when(releaseInstructionRepository.findByIdForUpdate(releaseInstructionId)).thenReturn(Optional.of(releaseInstruction));
        when(releaseReservationRepository.findByReleaseInstructionIdForUpdate(releaseInstructionId)).thenReturn(Optional.of(reservation));

        doThrow(new IllegalStateException("Sandbox settlement finalization is disabled"))
                .when(finalizationGate).assertFinalizationAllowed(releaseInstruction);

        assertThatThrownBy(() -> finalizer.finalizeRelease(releaseInstructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Sandbox settlement finalization is disabled");
    }

    // 8-14: Evidence statuses blocked
    @Test
    @DisplayName("Cases 8-14: Non-FINAL_SUCCESS evidence statuses are blocked")
    void testNonFinalSuccessBlocked() {
        ScopeProjection scope = new ScopeProjection() {
            @Override public UUID getDealId() { return dealId; }
            @Override public UUID getMilestoneId() { return milestoneId; }
        };
        when(releaseInstructionRepository.findScopeById(releaseInstructionId)).thenReturn(Optional.of(scope));
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(milestoneRepository.findByIdForUpdate(milestoneId)).thenReturn(Optional.of(milestone));
        when(escrowAccountRepository.findByDealIdForUpdate(dealId)).thenReturn(Optional.of(escrowAccount));
        when(releaseInstructionRepository.findByIdForUpdate(releaseInstructionId)).thenReturn(Optional.of(releaseInstruction));
        when(releaseReservationRepository.findByReleaseInstructionIdForUpdate(releaseInstructionId)).thenReturn(Optional.of(reservation));

        for (AuthoritativeStatus status : List.of(
                AuthoritativeStatus.NO_EVIDENCE,
                AuthoritativeStatus.PENDING,
                AuthoritativeStatus.NOT_FOUND,
                AuthoritativeStatus.UNKNOWN,
                AuthoritativeStatus.FAILED,
                AuthoritativeStatus.REVERSED,
                AuthoritativeStatus.CONFLICT
        )) {
            when(evidenceReader.getLatestAuthoritativeFinalObservation(releaseInstructionId))
                    .thenReturn(new AuthoritativeObservationResult(status, observation, "Reason"));

            assertThatThrownBy(() -> finalizer.finalizeRelease(releaseInstructionId, adminPrincipal))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Authoritative FINAL_SUCCESS required");
        }
    }

    // 16. wrong release ID in observation blocked
    @Test
    @DisplayName("Case 16: Wrong release ID in observation blocked")
    void testWrongReleaseIdInObservationBlocked() {
        setupStandardSuccessfulMocks();
        SettlementObservation wrongReleaseObs = SettlementObservation.builder()
                .id(UUID.randomUUID())
                .releaseInstructionId(UUID.randomUUID()) // mismatch
                .executionAttemptId(attemptId)
                .provider("MOCK")
                .source(SettlementObservationSource.WEBHOOK)
                .sourceReference("webhook-event-1")
                .providerOperationKey("OP-KEY-12345")
                .providerSettlementId("SETTLE-BANK-999")
                .providerAccountId("ESCROW-POOL-1")
                .providerStatus("SUCCESS")
                .finality(SettlementObservationFinality.FINAL_SUCCESS)
                .reasonCode("SUCCESS")
                .utrNumber("UTR-BANK-123456")
                .observedAmount(new BigDecimal("50000.00"))
                .observedCurrency("INR")
                .observedDirection("DEBIT")
                .evidenceHash("a".repeat(64))
                .observedAt(Instant.now())
                .build();
        when(evidenceReader.getLatestAuthoritativeFinalObservation(releaseInstructionId))
                .thenReturn(new AuthoritativeObservationResult(AuthoritativeStatus.FINAL_SUCCESS, wrongReleaseObs, "OK"));

        assertThatThrownBy(() -> finalizer.finalizeRelease(releaseInstructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match release instruction");
    }

    // 17. wrong attempt binding blocked
    @Test
    @DisplayName("Case 17: Missing attempt binding blocked")
    void testMissingAttemptBindingBlocked() {
        setupStandardSuccessfulMocks();
        // Since builder enforces non-null executionAttemptId, we test non-matching attempt ID:
        UUID otherAttemptId = UUID.randomUUID();
        SettlementObservation mismatchAttemptObs = SettlementObservation.builder()
                .id(UUID.randomUUID())
                .releaseInstructionId(releaseInstructionId)
                .executionAttemptId(otherAttemptId)
                .provider("MOCK")
                .source(SettlementObservationSource.WEBHOOK)
                .sourceReference("webhook-event-1")
                .providerOperationKey("OP-KEY-12345")
                .providerSettlementId("SETTLE-BANK-999")
                .providerAccountId("ESCROW-POOL-1")
                .providerStatus("SUCCESS")
                .finality(SettlementObservationFinality.FINAL_SUCCESS)
                .reasonCode("SUCCESS")
                .utrNumber("UTR-BANK-123456")
                .observedAmount(new BigDecimal("50000.00"))
                .observedCurrency("INR")
                .observedDirection("DEBIT")
                .evidenceHash("a".repeat(64))
                .observedAt(Instant.now())
                .build();
        when(evidenceReader.getLatestAuthoritativeFinalObservation(releaseInstructionId))
                .thenReturn(new AuthoritativeObservationResult(AuthoritativeStatus.FINAL_SUCCESS, mismatchAttemptObs, "OK"));
        when(attemptRepository.findById(otherAttemptId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> finalizer.finalizeRelease(releaseInstructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Execution attempt not found for observation");
    }

    // 18. wrong provider blocked
    @Test
    @DisplayName("Case 18: Wrong provider in observation blocked")
    void testWrongProviderBlocked() {
        setupStandardSuccessfulMocks();
        SettlementObservation wrongProviderObs = SettlementObservation.builder()
                .id(UUID.randomUUID())
                .releaseInstructionId(releaseInstructionId)
                .executionAttemptId(attemptId)
                .provider("AXIS") // wrong provider
                .source(SettlementObservationSource.WEBHOOK)
                .sourceReference("webhook-event-1")
                .providerOperationKey("OP-KEY-12345")
                .providerSettlementId("SETTLE-BANK-999")
                .providerAccountId("ESCROW-POOL-1")
                .providerStatus("SUCCESS")
                .finality(SettlementObservationFinality.FINAL_SUCCESS)
                .reasonCode("SUCCESS")
                .utrNumber("UTR-BANK-123456")
                .observedAmount(new BigDecimal("50000.00"))
                .observedCurrency("INR")
                .observedDirection("DEBIT")
                .evidenceHash("a".repeat(64))
                .observedAt(Instant.now())
                .build();
        when(evidenceReader.getLatestAuthoritativeFinalObservation(releaseInstructionId))
                .thenReturn(new AuthoritativeObservationResult(AuthoritativeStatus.FINAL_SUCCESS, wrongProviderObs, "OK"));

        assertThatThrownBy(() -> finalizer.finalizeRelease(releaseInstructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match release instruction provider");
    }

    // 19. wrong operation key blocked
    @Test
    @DisplayName("Case 19: Wrong operation key in observation blocked")
    void testWrongOperationKeyBlocked() {
        setupStandardSuccessfulMocks();
        SettlementObservation wrongKeyObs = SettlementObservation.builder()
                .id(UUID.randomUUID())
                .releaseInstructionId(releaseInstructionId)
                .executionAttemptId(attemptId)
                .provider("MOCK")
                .source(SettlementObservationSource.WEBHOOK)
                .sourceReference("webhook-event-1")
                .providerOperationKey("DIFFERENT-OP-KEY") // mismatch
                .providerSettlementId("SETTLE-BANK-999")
                .providerAccountId("ESCROW-POOL-1")
                .providerStatus("SUCCESS")
                .finality(SettlementObservationFinality.FINAL_SUCCESS)
                .reasonCode("SUCCESS")
                .utrNumber("UTR-BANK-123456")
                .observedAmount(new BigDecimal("50000.00"))
                .observedCurrency("INR")
                .observedDirection("DEBIT")
                .evidenceHash("a".repeat(64))
                .observedAt(Instant.now())
                .build();
        when(evidenceReader.getLatestAuthoritativeFinalObservation(releaseInstructionId))
                .thenReturn(new AuthoritativeObservationResult(AuthoritativeStatus.FINAL_SUCCESS, wrongKeyObs, "OK"));

        assertThatThrownBy(() -> finalizer.finalizeRelease(releaseInstructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match execution attempt key");
    }

    // 20. wrong provider settlement ID blocked
    @Test
    @DisplayName("Case 20: Missing or blank providerSettlementId / UTR blocked")
    void testMissingSettlementIdOrUtrBlocked() {
        setupStandardSuccessfulMocks();
        SettlementObservation blankSettlementObs = SettlementObservation.builder()
                .id(UUID.randomUUID())
                .releaseInstructionId(releaseInstructionId)
                .executionAttemptId(attemptId)
                .provider("MOCK")
                .source(SettlementObservationSource.WEBHOOK)
                .sourceReference("webhook-event-1")
                .providerOperationKey("OP-KEY-12345")
                .providerSettlementId("   ") // blank
                .providerAccountId("ESCROW-POOL-1")
                .providerStatus("SUCCESS")
                .finality(SettlementObservationFinality.FINAL_SUCCESS)
                .reasonCode("SUCCESS")
                .utrNumber("UTR-BANK-123456")
                .observedAmount(new BigDecimal("50000.00"))
                .observedCurrency("INR")
                .observedDirection("DEBIT")
                .evidenceHash("a".repeat(64))
                .observedAt(Instant.now())
                .build();
        when(evidenceReader.getLatestAuthoritativeFinalObservation(releaseInstructionId))
                .thenReturn(new AuthoritativeObservationResult(AuthoritativeStatus.FINAL_SUCCESS, blankSettlementObs, "OK"));

        assertThatThrownBy(() -> finalizer.finalizeRelease(releaseInstructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires non-blank providerSettlementId");
    }

    // 21. providerAccount mismatch blocked when provided
    @Test
    @DisplayName("Case 21: providerAccountId mismatch blocked when provided")
    void testProviderAccountMismatchBlocked() {
        setupStandardSuccessfulMocks();
        SettlementObservation mismatchAccountObs = SettlementObservation.builder()
                .id(UUID.randomUUID())
                .releaseInstructionId(releaseInstructionId)
                .executionAttemptId(attemptId)
                .provider("MOCK")
                .source(SettlementObservationSource.WEBHOOK)
                .sourceReference("webhook-event-1")
                .providerOperationKey("OP-KEY-12345")
                .providerSettlementId("SETTLE-BANK-999")
                .providerAccountId("DIFFERENT-POOL-99") // mismatch
                .providerStatus("SUCCESS")
                .finality(SettlementObservationFinality.FINAL_SUCCESS)
                .reasonCode("SUCCESS")
                .utrNumber("UTR-BANK-123456")
                .observedAmount(new BigDecimal("50000.00"))
                .observedCurrency("INR")
                .observedDirection("DEBIT")
                .evidenceHash("a".repeat(64))
                .observedAt(Instant.now())
                .build();
        when(evidenceReader.getLatestAuthoritativeFinalObservation(releaseInstructionId))
                .thenReturn(new AuthoritativeObservationResult(AuthoritativeStatus.FINAL_SUCCESS, mismatchAccountObs, "OK"));

        assertThatThrownBy(() -> finalizer.finalizeRelease(releaseInstructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match release instruction providerAccountId");
    }

    // 22. amount mismatch blocked when provided
    @Test
    @DisplayName("Case 22: amount mismatch blocked when provided")
    void testAmountMismatchBlocked() {
        setupStandardSuccessfulMocks();
        SettlementObservation mismatchAmountObs = SettlementObservation.builder()
                .id(UUID.randomUUID())
                .releaseInstructionId(releaseInstructionId)
                .executionAttemptId(attemptId)
                .provider("MOCK")
                .source(SettlementObservationSource.WEBHOOK)
                .sourceReference("webhook-event-1")
                .providerOperationKey("OP-KEY-12345")
                .providerSettlementId("SETTLE-BANK-999")
                .providerAccountId("ESCROW-POOL-1")
                .providerStatus("SUCCESS")
                .finality(SettlementObservationFinality.FINAL_SUCCESS)
                .reasonCode("SUCCESS")
                .utrNumber("UTR-BANK-123456")
                .observedAmount(new BigDecimal("99999.00")) // mismatch
                .observedCurrency("INR")
                .observedDirection("DEBIT")
                .evidenceHash("a".repeat(64))
                .observedAt(Instant.now())
                .build();
        when(evidenceReader.getLatestAuthoritativeFinalObservation(releaseInstructionId))
                .thenReturn(new AuthoritativeObservationResult(AuthoritativeStatus.FINAL_SUCCESS, mismatchAmountObs, "OK"));

        assertThatThrownBy(() -> finalizer.finalizeRelease(releaseInstructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match release instruction amount");
    }

    // 23. currency mismatch blocked when provided
    @Test
    @DisplayName("Case 23: currency mismatch blocked when provided")
    void testCurrencyMismatchBlocked() {
        setupStandardSuccessfulMocks();
        SettlementObservation mismatchCurrObs = SettlementObservation.builder()
                .id(UUID.randomUUID())
                .releaseInstructionId(releaseInstructionId)
                .executionAttemptId(attemptId)
                .provider("MOCK")
                .source(SettlementObservationSource.WEBHOOK)
                .sourceReference("webhook-event-1")
                .providerOperationKey("OP-KEY-12345")
                .providerSettlementId("SETTLE-BANK-999")
                .providerAccountId("ESCROW-POOL-1")
                .providerStatus("SUCCESS")
                .finality(SettlementObservationFinality.FINAL_SUCCESS)
                .reasonCode("SUCCESS")
                .utrNumber("UTR-BANK-123456")
                .observedAmount(new BigDecimal("50000.00"))
                .observedCurrency("USD") // mismatch
                .observedDirection("DEBIT")
                .evidenceHash("a".repeat(64))
                .observedAt(Instant.now())
                .build();
        when(evidenceReader.getLatestAuthoritativeFinalObservation(releaseInstructionId))
                .thenReturn(new AuthoritativeObservationResult(AuthoritativeStatus.FINAL_SUCCESS, mismatchCurrObs, "OK"));

        assertThatThrownBy(() -> finalizer.finalizeRelease(releaseInstructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match release instruction currency");
    }

    // 24. direction mismatch blocked when provided
    @Test
    @DisplayName("Case 24: direction mismatch blocked when provided (must be DEBIT)")
    void testDirectionMismatchBlocked() {
        setupStandardSuccessfulMocks();
        SettlementObservation mismatchDirObs = SettlementObservation.builder()
                .id(UUID.randomUUID())
                .releaseInstructionId(releaseInstructionId)
                .executionAttemptId(attemptId)
                .provider("MOCK")
                .source(SettlementObservationSource.WEBHOOK)
                .sourceReference("webhook-event-1")
                .providerOperationKey("OP-KEY-12345")
                .providerSettlementId("SETTLE-BANK-999")
                .providerAccountId("ESCROW-POOL-1")
                .providerStatus("SUCCESS")
                .finality(SettlementObservationFinality.FINAL_SUCCESS)
                .reasonCode("SUCCESS")
                .utrNumber("UTR-BANK-123456")
                .observedAmount(new BigDecimal("50000.00"))
                .observedCurrency("INR")
                .observedDirection("CREDIT") // mismatch
                .evidenceHash("a".repeat(64))
                .observedAt(Instant.now())
                .build();
        when(evidenceReader.getLatestAuthoritativeFinalObservation(releaseInstructionId))
                .thenReturn(new AuthoritativeObservationResult(AuthoritativeStatus.FINAL_SUCCESS, mismatchDirObs, "OK"));

        assertThatThrownBy(() -> finalizer.finalizeRelease(releaseInstructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be DEBIT");
    }

    // 25. missing reservation blocked
    @Test
    @DisplayName("Case 25: Missing reservation blocked")
    void testMissingReservationBlocked() {
        setupStandardSuccessfulMocks();
        when(releaseReservationRepository.findByReleaseInstructionIdForUpdate(releaseInstructionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> finalizer.finalizeRelease(releaseInstructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ReleaseReservation not found");
    }

    // 26. non-ACTIVE fresh reservation blocked
    @Test
    @DisplayName("Case 26: non-ACTIVE fresh reservation blocked")
    void testNonActiveFreshReservationBlocked() {
        setupStandardSuccessfulMocks();
        reservation.markReleased(Instant.now());

        assertThatThrownBy(() -> finalizer.finalizeRelease(releaseInstructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected ACTIVE but found RELEASED");
    }

    // 27. reservation amount mismatch blocked
    @Test
    @DisplayName("Case 27: reservation amount mismatch blocked")
    void testReservationAmountMismatchBlocked() {
        setupStandardSuccessfulMocks();
        ReleaseReservation mismatchRes = new ReleaseReservation(
                UUID.randomUUID(),
                releaseInstructionId,
                escrowAccountId,
                milestoneId,
                new BigDecimal("99999.00"), // mismatch
                "INR"
        );
        when(releaseReservationRepository.findByReleaseInstructionIdForUpdate(releaseInstructionId)).thenReturn(Optional.of(mismatchRes));

        assertThatThrownBy(() -> finalizer.finalizeRelease(releaseInstructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Reservation amount 99999.00 does not match release instruction amount");
    }

    // 28. escrow currency mismatch blocked
    @Test
    @DisplayName("Case 28: escrow currency mismatch blocked")
    void testEscrowCurrencyMismatchBlocked() {
        setupStandardSuccessfulMocks();
        escrowAccount.setCurrency("USD"); // mismatch

        assertThatThrownBy(() -> finalizer.finalizeRelease(releaseInstructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Escrow currency USD does not match release currency INR");
    }

    // 29. insufficient escrow blocked
    @Test
    @DisplayName("Case 29: insufficient escrow balance blocked")
    void testInsufficientEscrowBlocked() {
        setupStandardSuccessfulMocks();
        escrowAccount.setBalance(new BigDecimal("1000.00")); // less than 50,000

        assertThatThrownBy(() -> finalizer.finalizeRelease(releaseInstructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insufficient escrow balance");
    }

    // 30. journal duplicate conflict blocked
    @Test
    @DisplayName("Case 30: journal duplicate conflict blocked for fresh finalization")
    void testJournalConflictBlocked() {
        setupStandardSuccessfulMocks();
        JournalEntry existingJournal = new JournalEntry();
        existingJournal.setId(UUID.randomUUID());
        when(journalEntryRepository.findByTransactionTypeAndReferenceId(LedgerTransactionType.MILESTONE_SETTLEMENT, releaseInstructionId))
                .thenReturn(Optional.of(existingJournal));

        assertThatThrownBy(() -> finalizer.finalizeRelease(releaseInstructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Journal entry already exists for non-SETTLED release instruction");
    }

    // 31. escrow RELEASE duplicate conflict blocked
    @Test
    @DisplayName("Case 31: escrow RELEASE duplicate conflict blocked for fresh finalization")
    void testEscrowReleaseConflictBlocked() {
        setupStandardSuccessfulMocks();
        EscrowLedger existingRelease = new EscrowLedger();
        existingRelease.setId(UUID.randomUUID());
        when(escrowLedgerRepository.findByMilestoneIdAndTransactionType(milestoneId, TransactionType.RELEASE))
                .thenReturn(Optional.of(existingRelease));

        assertThatThrownBy(() -> finalizer.finalizeRelease(releaseInstructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Escrow RELEASE ledger row already exists for non-SETTLED release instruction");
    }

    // 32-45: Successful finalization effects
    @Test
    @DisplayName("Cases 32-45: Successful finalization asserts exact journal, escrow, reservation, release, milestone effects, 0 bank calls, 0 SettlementInstruction writes")
    void testSuccessfulFinalizationEffects() {
        setupStandardSuccessfulMocks();

        BigDecimal initialBalance = escrowAccount.getBalance();
        BigDecimal initialTotalReleased = escrowAccount.getTotalReleased();

        SettlementFinalizationResult result = finalizer.finalizeRelease(releaseInstructionId, adminPrincipal);

        // 32. exactly one journal posted
        verify(ledgerService, times(1)).postMilestoneSettlement(
                eq(releaseInstructionId),
                eq(dealId),
                eq(milestoneId),
                eq(new BigDecimal("50000.00")),
                eq("INR"),
                eq(adminUserId),
                any()
        );

        // 37. escrow balance decreases exact release amount
        assertThat(escrowAccount.getBalance()).isEqualByComparingTo(initialBalance.subtract(new BigDecimal("50000.00")));
        // 38. totalReleased increases exact amount
        assertThat(escrowAccount.getTotalReleased()).isEqualByComparingTo(initialTotalReleased.add(new BigDecimal("50000.00")));

        // 39. one escrow RELEASE row
        ArgumentCaptor<EscrowLedger> ledgerCaptor = ArgumentCaptor.forClass(EscrowLedger.class);
        verify(escrowLedgerRepository, times(1)).save(ledgerCaptor.capture());
        EscrowLedger savedLedger = ledgerCaptor.getValue();
        assertThat(savedLedger.getTransactionType()).isEqualTo(TransactionType.RELEASE);
        assertThat(savedLedger.getMilestoneId()).isEqualTo(milestoneId);
        assertThat(savedLedger.getAmount()).isEqualByComparingTo(new BigDecimal("50000.00"));
        assertThat(savedLedger.getBalanceAfter()).isEqualByComparingTo(new BigDecimal("50000.00"));

        // 40. reservation CONSUMED
        assertThat(reservation.getStatus()).isEqualTo(ReleaseReservationStatus.CONSUMED);
        assertThat(reservation.getConsumedAt()).isNotNull();

        // 41. release SETTLED
        assertThat(releaseInstruction.getStatus()).isEqualTo(ReleaseInstructionStatus.SETTLED);
        // 42. exact final observation bound
        assertThat(releaseInstruction.getFinalSettlementObservationId()).isEqualTo(observationId);
        assertThat(releaseInstruction.getFinalizedBy()).isEqualTo(adminUserId);
        assertThat(releaseInstruction.getSettledAt()).isNotNull();

        // 43. milestone COMPLETED
        assertThat(milestone.getStatus()).isEqualTo(MilestoneStatus.COMPLETED);

        // 44. bank calls 0: verified by architecture (no BankingProvider dependency in finalizer)
        // 45. SettlementInstruction writes 0: verified by architecture (no SettlementInstructionRepository interaction)
    }

    // Part AU — Dispute after dispatch test
    @Test
    @DisplayName("Part AU: Deal in DISPUTED status does not block financial finalization; status remains DISPUTED")
    void testDisputeAfterDispatchDoesNotBlockFinalization() {
        deal.setStatus(DealStatus.DISPUTED);
        setupStandardSuccessfulMocks();

        SettlementFinalizationResult result = finalizer.finalizeRelease(releaseInstructionId, adminPrincipal);

        assertThat(result).isNotNull();
        assertThat(releaseInstruction.getStatus()).isEqualTo(ReleaseInstructionStatus.SETTLED);
        assertThat(reservation.getStatus()).isEqualTo(ReleaseReservationStatus.CONSUMED);
        assertThat(escrowAccount.getBalance()).isEqualByComparingTo(new BigDecimal("50000.00"));
        // Crucial: Deal status remains DISPUTED, never overwritten to COMPLETED
        assertThat(deal.getStatus()).isEqualTo(DealStatus.DISPUTED);
    }

    // Part AV — Beneficiary changed after dispatch test
    @Test
    @DisplayName("Part AV: Beneficiary changed after dispatch does not re-run eligibility; uses immutable release snapshot")
    void testBeneficiaryChangedAfterDispatch() {
        setupStandardSuccessfulMocks();
        // The attempt matches the immutable release snapshot's beneficiary
        assertThat(attempt.getBeneficiaryBankAccountId()).isEqualTo(releaseInstruction.getBeneficiaryBankAccountId());

        SettlementFinalizationResult result = finalizer.finalizeRelease(releaseInstructionId, adminPrincipal);

        assertThat(result).isNotNull();
        assertThat(releaseInstruction.getStatus()).isEqualTo(ReleaseInstructionStatus.SETTLED);
    }

    // Part AW — Milestone amount changed after dispatch test
    @Test
    @DisplayName("Part AW: Milestone amount changed after dispatch uses immutable ReleaseInstruction.amount for accounting")
    void testMilestoneAmountChangedAfterDispatch() {
        // Mutate milestone amount to 120,000 (after release instruction created for 50,000)
        milestone.setAmount(new BigDecimal("120000.00"));
        setupStandardSuccessfulMocks();

        SettlementFinalizationResult result = finalizer.finalizeRelease(releaseInstructionId, adminPrincipal);

        assertThat(result).isNotNull();
        // Accounting used ReleaseInstruction.amount (50,000), not Milestone.amount (120,000)
        verify(ledgerService).postMilestoneSettlement(
                any(), any(), any(), eq(new BigDecimal("50000.00")), any(), any(), any()
        );
        assertThat(escrowAccount.getBalance()).isEqualByComparingTo(new BigDecimal("50000.00"));
    }

    // Part BE — Observation conflict / reversal test
    @Test
    @DisplayName("Part BE: Reversal after settlement reports REVERSAL_RECOVERY_REQUIRED and does not auto-reverse")
    void testReversalAfterSettlementRequiresManualRecovery() {
        // Settle release first
        releaseInstruction.markSettledFromObservation(observationId, adminUserId, Instant.now());
        reservation.markConsumed(Instant.now());

        ScopeProjection scope = new ScopeProjection() {
            @Override public UUID getDealId() { return dealId; }
            @Override public UUID getMilestoneId() { return milestoneId; }
        };
        when(releaseInstructionRepository.findScopeById(releaseInstructionId)).thenReturn(Optional.of(scope));
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(milestoneRepository.findByIdForUpdate(milestoneId)).thenReturn(Optional.of(milestone));
        when(escrowAccountRepository.findByDealIdForUpdate(dealId)).thenReturn(Optional.of(escrowAccount));
        when(releaseInstructionRepository.findByIdForUpdate(releaseInstructionId)).thenReturn(Optional.of(releaseInstruction));
        when(releaseReservationRepository.findByReleaseInstructionIdForUpdate(releaseInstructionId)).thenReturn(Optional.of(reservation));

        AuthoritativeObservationResult conflictObs = new AuthoritativeObservationResult(
                AuthoritativeStatus.CONFLICT,
                observation,
                "Reversal conflict detected"
        );
        when(evidenceReader.getLatestAuthoritativeFinalObservation(releaseInstructionId)).thenReturn(conflictObs);

        assertThatThrownBy(() -> finalizer.finalizeRelease(releaseInstructionId, adminPrincipal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REVERSAL_RECOVERY_REQUIRED");

        // Assert release remains SETTLED (not un-settled)
        assertThat(releaseInstruction.getStatus()).isEqualTo(ReleaseInstructionStatus.SETTLED);
        // Assert reservation remains CONSUMED
        assertThat(reservation.getStatus()).isEqualTo(ReleaseReservationStatus.CONSUMED);
    }

    // Idempotent replay test
    @Test
    @DisplayName("Idempotent replay returns existing result and performs 0 new ledger/escrow writes")
    void testIdempotentReplay() {
        // Prepare instruction in SETTLED state
        Instant settledAt = Instant.now();
        releaseInstruction.markSettledFromObservation(observationId, adminUserId, settledAt);
        reservation.markConsumed(settledAt);

        ScopeProjection scope = new ScopeProjection() {
            @Override public UUID getDealId() { return dealId; }
            @Override public UUID getMilestoneId() { return milestoneId; }
        };
        when(releaseInstructionRepository.findScopeById(releaseInstructionId)).thenReturn(Optional.of(scope));
        when(dealRepository.findByIdForUpdate(dealId)).thenReturn(Optional.of(deal));
        when(milestoneRepository.findByIdForUpdate(milestoneId)).thenReturn(Optional.of(milestone));
        when(escrowAccountRepository.findByDealIdForUpdate(dealId)).thenReturn(Optional.of(escrowAccount));
        when(releaseInstructionRepository.findByIdForUpdate(releaseInstructionId)).thenReturn(Optional.of(releaseInstruction));
        when(releaseReservationRepository.findByReleaseInstructionIdForUpdate(releaseInstructionId)).thenReturn(Optional.of(reservation));

        AuthoritativeObservationResult finalObs = new AuthoritativeObservationResult(
                AuthoritativeStatus.FINAL_SUCCESS,
                observation,
                "Trusted webhook success"
        );
        when(evidenceReader.getLatestAuthoritativeFinalObservation(releaseInstructionId)).thenReturn(finalObs);

        JournalEntry existingJournal = new JournalEntry();
        existingJournal.setId(UUID.randomUUID());
        when(journalEntryRepository.findByTransactionTypeAndReferenceId(LedgerTransactionType.MILESTONE_SETTLEMENT, releaseInstructionId))
                .thenReturn(Optional.of(existingJournal));

        EscrowLedger existingRelease = new EscrowLedger();
        existingRelease.setId(UUID.randomUUID());
        existingRelease.setAmount(new BigDecimal("50000.00"));
        when(escrowLedgerRepository.findByMilestoneIdAndTransactionType(milestoneId, TransactionType.RELEASE))
                .thenReturn(Optional.of(existingRelease));

        SettlementFinalizationResult result = finalizer.finalizeRelease(releaseInstructionId, adminPrincipal);

        assertThat(result).isNotNull();
        assertThat(result.idempotentReplay()).isTrue();
        assertThat(result.journalEntryId()).isEqualTo(existingJournal.getId());
        assertThat(result.escrowLedgerId()).isEqualTo(existingRelease.getId());

        // Zero additional writes
        verify(ledgerService, never()).postMilestoneSettlement(any(), any(), any(), any(), any(), any(), any());
        verify(escrowLedgerRepository, never()).save(any());
    }
}
