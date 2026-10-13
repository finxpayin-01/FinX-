package com.finx.release.execution.service;

import com.finx.approval.dto.ApprovalBindingEvaluation;
import com.finx.approval.service.MilestoneApprovalBindingService;
import com.finx.common.time.FinxClock;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.milestone.repository.DeliverableRepository;
import com.finx.dispute.entity.DisputeStatus;
import com.finx.dispute.repository.DisputeRepository;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.org.entity.OrganizationBankAccount;
import com.finx.org.repository.OrganizationBankAccountRepository;
import com.finx.outbox.entity.FinancialOutboxMessage;
import com.finx.outbox.model.FinancialOutboxEventType;
import com.finx.outbox.model.FinancialOutboxStatus;
import com.finx.outbox.repository.FinancialOutboxRepository;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.entity.ReleaseReservation;
import com.finx.release.execution.entity.SettlementExecutionAttempt;
import com.finx.release.execution.model.DispatchPreparedContext;
import com.finx.release.execution.model.ProviderSettlementOperationKey;
import com.finx.release.execution.model.SettlementExecutionAttemptStatus;
import com.finx.release.execution.repository.SettlementExecutionAttemptRepository;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.model.ReleaseReservationStatus;
import com.finx.release.repository.ReleaseInstructionRepository;
import com.finx.release.repository.ReleaseReservationRepository;
import com.finx.release.service.ReleaseDecisionExecutionValidator;
import com.finx.release.service.ReleaseFundingScopeResolver;
import com.finx.verification.service.VerificationEligibilityService;
import com.finx.verification.service.VerificationEligibilityService.BeneficiaryEligibilityResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettlementDispatchStateServiceTest {

    private FinancialOutboxRepository outboxRepository;
    private SettlementExecutionAttemptRepository attemptRepository;
    private ReleaseInstructionRepository instructionRepository;
    private ReleaseReservationRepository reservationRepository;
    private DealRepository dealRepository;
    private MilestoneRepository milestoneRepository;
    private DisputeRepository disputeRepository;
    private OrganizationBankAccountRepository bankAccountRepository;
    private VerificationEligibilityService verificationEligibilityService;
    private MilestoneApprovalBindingService approvalBindingService;
    private DeliverableRepository deliverableRepository;
    private ReleaseDecisionExecutionValidator executionValidator;
    private ReleaseFundingScopeResolver fundingScopeResolver;
    private FinxClock clock;

    private SettlementDispatchStateService stateService;

    @BeforeEach
    void setUp() {
        outboxRepository = Mockito.mock(FinancialOutboxRepository.class);
        attemptRepository = Mockito.mock(SettlementExecutionAttemptRepository.class);
        instructionRepository = Mockito.mock(ReleaseInstructionRepository.class);
        reservationRepository = Mockito.mock(ReleaseReservationRepository.class);
        dealRepository = Mockito.mock(DealRepository.class);
        milestoneRepository = Mockito.mock(MilestoneRepository.class);
        disputeRepository = Mockito.mock(DisputeRepository.class);
        bankAccountRepository = Mockito.mock(OrganizationBankAccountRepository.class);
        verificationEligibilityService = Mockito.mock(VerificationEligibilityService.class);
        approvalBindingService = Mockito.mock(MilestoneApprovalBindingService.class);
        deliverableRepository = Mockito.mock(DeliverableRepository.class);
        executionValidator = Mockito.mock(ReleaseDecisionExecutionValidator.class);
        fundingScopeResolver = Mockito.mock(ReleaseFundingScopeResolver.class);
        clock = Mockito.mock(FinxClock.class);

        when(clock.now()).thenReturn(Instant.parse("2026-10-13T12:00:00Z"));

        stateService = new SettlementDispatchStateService(
                outboxRepository,
                attemptRepository,
                instructionRepository,
                reservationRepository,
                dealRepository,
                milestoneRepository,
                disputeRepository,
                bankAccountRepository,
                verificationEligibilityService,
                approvalBindingService,
                deliverableRepository,
                executionValidator,
                fundingScopeResolver,
                clock
        );
    }

    @Test
    @DisplayName("beginDispatch commits PREPARED -> SENDING, QUEUED -> DISPATCHING, leaves outbox LEASED, reservation ACTIVE")
    void testBeginDispatchTransitions() {
        UUID instructionId = UUID.randomUUID();
        UUID outboxId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID dealId = UUID.randomUUID();
        UUID milestoneId = UUID.randomUUID();
        UUID bankAccountId = UUID.randomUUID();
        UUID sellerOrgId = UUID.randomUUID();
        String workerId = "worker-1";
        BigDecimal amount = new BigDecimal("50000.00");

        ReleaseInstruction instruction = createQueuedInstruction(instructionId, dealId, milestoneId, bankAccountId, amount);
        FinancialOutboxMessage outbox = createLeasedOutbox(outboxId, instructionId, workerId);
        SettlementExecutionAttempt attempt = createPreparedAttempt(attemptId, instructionId, outboxId, workerId, bankAccountId, amount);
        ReleaseReservation reservation = new ReleaseReservation(UUID.randomUUID(), instructionId, milestoneId, UUID.randomUUID(), amount, "INR");

        Deal deal = Mockito.mock(Deal.class);
        when(deal.getId()).thenReturn(dealId);
        when(deal.getStatus()).thenReturn(DealStatus.FUNDED);
        when(deal.getSellerOrganizationId()).thenReturn(sellerOrgId);

        Milestone milestone = Mockito.mock(Milestone.class);
        when(milestone.getId()).thenReturn(milestoneId);
        when(milestone.getStatus()).thenReturn(MilestoneStatus.APPROVED);

        OrganizationBankAccount bankAccount = Mockito.mock(OrganizationBankAccount.class);
        when(bankAccount.getId()).thenReturn(bankAccountId);
        when(bankAccount.getOrganizationId()).thenReturn(sellerOrgId);
        when(bankAccount.getProvider()).thenReturn("MOCK");
        when(bankAccount.getVerificationReference()).thenReturn("MOCK-REF-1");
        when(bankAccount.getEncryptedAccountNumber()).thenReturn("ENC_123456");
        when(bankAccount.getIfscCode()).thenReturn("MOCK0001");
        when(bankAccount.getAccountHolderName()).thenReturn("Acme Corp");

        when(outboxRepository.findByIdForUpdate(outboxId)).thenReturn(Optional.of(outbox));
        when(attemptRepository.findByIdForUpdate(attemptId)).thenReturn(Optional.of(attempt));
        when(instructionRepository.findByIdForUpdate(instructionId)).thenReturn(Optional.of(instruction));
        when(reservationRepository.findByReleaseInstructionId(instructionId)).thenReturn(Optional.of(reservation));
        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));
        when(milestoneRepository.findById(milestoneId)).thenReturn(Optional.of(milestone));

        when(approvalBindingService.evaluateCurrentBinding(any(), any(), any()))
                .thenReturn(new ApprovalBindingEvaluation(true, true, "APPROVAL_BINDING_CURRENT", null));
        when(deliverableRepository.findByMilestoneIdOrderBySubmittedAtDesc(milestoneId))
                .thenReturn(Collections.emptyList());
        when(disputeRepository.existsByDealIdAndStatus(dealId, DisputeStatus.OPEN)).thenReturn(false);
        when(disputeRepository.existsByMilestoneIdAndStatus(milestoneId, DisputeStatus.OPEN)).thenReturn(false);

        when(verificationEligibilityService.evaluateBeneficiaryEligibility(sellerOrgId))
                .thenReturn(new BeneficiaryEligibilityResult(true, bankAccount, null, true));
        when(fundingScopeResolver.resolveFundingScope(deal, milestone))
                .thenReturn(new ReleaseFundingScopeResolver.FundingScope("MOCK", "MOCK-ESCROW", "INR"));
        when(bankAccountRepository.findById(bankAccountId)).thenReturn(Optional.of(bankAccount));

        DispatchPreparedContext ctx = stateService.beginDispatch(outboxId, attemptId, workerId);

        // Verify Attempt transitioned to SENDING
        assertThat(attempt.getStatus()).isEqualTo(SettlementExecutionAttemptStatus.SENDING);
        assertThat(attempt.getRequestStartedAt()).isEqualTo(Instant.parse("2026-10-13T12:00:00Z"));
        verify(attemptRepository).save(attempt);

        // Verify Instruction transitioned to DISPATCHING
        assertThat(instruction.getStatus()).isEqualTo(ReleaseInstructionStatus.DISPATCHING);
        verify(instructionRepository).save(instruction);

        // Outbox remains LEASED
        assertThat(outbox.getStatus()).isEqualTo(FinancialOutboxStatus.LEASED);
        assertThat(outbox.getLockedBy()).isEqualTo(workerId);

        // Reservation remains ACTIVE
        assertThat(reservation.getStatus()).isEqualTo(ReleaseReservationStatus.ACTIVE);

        // Context contains expected fields
        assertThat(ctx.providerOperationKey()).isEqualTo("FINX-REL-" + instructionId);
        assertThat(ctx.encryptedAccountNumber()).isEqualTo("ENC_123456");
        assertThat(ctx.beneficiaryName()).isEqualTo("Acme Corp");
        assertThat(ctx.beneficiaryIfsc()).isEqualTo("MOCK0001");
    }

    @Test
    @DisplayName("recordAcknowledged atomically transitions SENDING -> ACKNOWLEDGED, DISPATCHING -> PENDING_SETTLEMENT, LEASED -> PROCESSED")
    void testRecordAcknowledgedAtomicity() {
        UUID instructionId = UUID.randomUUID();
        UUID outboxId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        String workerId = "worker-1";

        ReleaseInstruction instruction = createQueuedInstruction(instructionId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("50000.00"));
        instruction.markDispatching();

        FinancialOutboxMessage outbox = createLeasedOutbox(outboxId, instructionId, workerId);

        SettlementExecutionAttempt attempt = createPreparedAttempt(attemptId, instructionId, outboxId, workerId, UUID.randomUUID(), new BigDecimal("50000.00"));
        attempt.markSending(Instant.now());

        when(outboxRepository.findByIdForUpdate(outboxId)).thenReturn(Optional.of(outbox));
        when(attemptRepository.findByIdForUpdate(attemptId)).thenReturn(Optional.of(attempt));
        when(instructionRepository.findByIdForUpdate(instructionId)).thenReturn(Optional.of(instruction));

        Instant responseTime = Instant.now();
        stateService.recordAcknowledged(outboxId, attemptId, workerId, "MOCK-SETTL-001", "SETTLED", responseTime);

        // Attempt -> ACKNOWLEDGED
        assertThat(attempt.getStatus()).isEqualTo(SettlementExecutionAttemptStatus.ACKNOWLEDGED);
        assertThat(attempt.getProviderSettlementId()).isEqualTo("MOCK-SETTL-001");
        assertThat(attempt.getProviderStatus()).isEqualTo("SETTLED");
        assertThat(attempt.getResponseReceivedAt()).isEqualTo(responseTime);
        assertThat(attempt.getRetryable()).isFalse();

        // Instruction -> PENDING_SETTLEMENT (NOT SETTLED!)
        assertThat(instruction.getStatus()).isEqualTo(ReleaseInstructionStatus.PENDING_SETTLEMENT);

        // Outbox -> PROCESSED
        assertThat(outbox.getStatus()).isEqualTo(FinancialOutboxStatus.PROCESSED);
        assertThat(outbox.getProcessedAt()).isEqualTo(responseTime);
        assertThat(outbox.getLockedBy()).isNull();
        assertThat(outbox.getLastErrorCode()).isNull();

        verify(attemptRepository).save(attempt);
        verify(instructionRepository).save(instruction);
        verify(outboxRepository).save(outbox);
    }

    @Test
    @DisplayName("recordUnknown atomically transitions SENDING -> UNKNOWN, DISPATCHING -> UNKNOWN_RECON_REQUIRED, LEASED -> PROCESSED")
    void testRecordUnknownAtomicity() {
        UUID instructionId = UUID.randomUUID();
        UUID outboxId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        String workerId = "worker-1";

        ReleaseInstruction instruction = createQueuedInstruction(instructionId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("50000.00"));
        instruction.markDispatching();

        FinancialOutboxMessage outbox = createLeasedOutbox(outboxId, instructionId, workerId);

        SettlementExecutionAttempt attempt = createPreparedAttempt(attemptId, instructionId, outboxId, workerId, UUID.randomUUID(), new BigDecimal("50000.00"));
        attempt.markSending(Instant.now());

        when(outboxRepository.findByIdForUpdate(outboxId)).thenReturn(Optional.of(outbox));
        when(attemptRepository.findByIdForUpdate(attemptId)).thenReturn(Optional.of(attempt));
        when(instructionRepository.findByIdForUpdate(instructionId)).thenReturn(Optional.of(instruction));

        Instant observedTime = Instant.now();
        stateService.recordUnknown(outboxId, attemptId, workerId, "PROVIDER_DISPATCH_TIMEOUT", observedTime);

        // Attempt -> UNKNOWN
        assertThat(attempt.getStatus()).isEqualTo(SettlementExecutionAttemptStatus.UNKNOWN);
        assertThat(attempt.getErrorCode()).isEqualTo("PROVIDER_DISPATCH_TIMEOUT");
        assertThat(attempt.getResponseReceivedAt()).isEqualTo(observedTime);
        assertThat(attempt.getRetryable()).isFalse();

        // Instruction -> UNKNOWN_RECON_REQUIRED
        assertThat(instruction.getStatus()).isEqualTo(ReleaseInstructionStatus.UNKNOWN_RECON_REQUIRED);

        // Outbox -> PROCESSED (enters recovery, not redelivery!)
        assertThat(outbox.getStatus()).isEqualTo(FinancialOutboxStatus.PROCESSED);
        assertThat(outbox.getLastErrorCode()).isEqualTo("PROVIDER_DISPATCH_TIMEOUT");
        assertThat(outbox.getProcessedAt()).isEqualTo(observedTime);
        assertThat(outbox.getLockedBy()).isNull();

        verify(attemptRepository).save(attempt);
        verify(instructionRepository).save(instruction);
        verify(outboxRepository).save(outbox);
    }

    @Test
    @DisplayName("recordPreDispatchTerminalFailure transitions PREPARED -> TERMINAL_FAILED, QUEUED -> MANUAL_REVIEW, LEASED -> DEAD_LETTER")
    void testRecordPreDispatchTerminalFailure() {
        UUID instructionId = UUID.randomUUID();
        UUID outboxId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        String workerId = "worker-1";

        ReleaseInstruction instruction = createQueuedInstruction(instructionId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("50000.00"));
        FinancialOutboxMessage outbox = createLeasedOutbox(outboxId, instructionId, workerId);
        SettlementExecutionAttempt attempt = createPreparedAttempt(attemptId, instructionId, outboxId, workerId, UUID.randomUUID(), new BigDecimal("50000.00"));

        when(outboxRepository.findByIdForUpdate(outboxId)).thenReturn(Optional.of(outbox));
        when(attemptRepository.findByIdForUpdate(attemptId)).thenReturn(Optional.of(attempt));
        when(instructionRepository.findByIdForUpdate(instructionId)).thenReturn(Optional.of(instruction));

        Instant failTime = Instant.now();
        stateService.recordPreDispatchTerminalFailure(outboxId, attemptId, workerId, "PRE_DISPATCH_MISMATCH", failTime);

        assertThat(attempt.getStatus()).isEqualTo(SettlementExecutionAttemptStatus.TERMINAL_FAILED);
        assertThat(attempt.getErrorCode()).isEqualTo("PRE_DISPATCH_MISMATCH");

        assertThat(instruction.getStatus()).isEqualTo(ReleaseInstructionStatus.MANUAL_REVIEW);

        assertThat(outbox.getStatus()).isEqualTo(FinancialOutboxStatus.DEAD_LETTER);
        assertThat(outbox.getLastErrorCode()).isEqualTo("PRE_DISPATCH_MISMATCH");
    }

    private ReleaseInstruction createQueuedInstruction(UUID id, UUID dealId, UUID milestoneId, UUID bankAccountId, BigDecimal amount) {
        ReleaseInstruction instruction = new ReleaseInstruction(
                id,
                UUID.randomUUID(),
                dealId,
                milestoneId,
                UUID.randomUUID(),
                "a".repeat(64),
                UUID.randomUUID(),
                "b".repeat(64),
                bankAccountId,
                "MOCK",
                "MOCK-ESCROW",
                amount,
                "INR",
                "IDEM-" + id,
                "c".repeat(64),
                UUID.randomUUID()
        );
        instruction.markAuthorized(UUID.randomUUID(), Instant.now());
        instruction.markReserved();
        instruction.markQueued();
        return instruction;
    }

    private FinancialOutboxMessage createLeasedOutbox(UUID id, UUID instructionId, String workerId) {
        FinancialOutboxMessage msg = new FinancialOutboxMessage(
                id,
                instructionId,
                FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED,
                instructionId.toString(),
                1,
                FinancialOutboxStatus.PENDING,
                0,
                Instant.now(),
                Instant.now(),
                Instant.now()
        );
        msg.markLeased(workerId, Instant.now());
        return msg;
    }

    private SettlementExecutionAttempt createPreparedAttempt(UUID id, UUID instructionId, UUID outboxId, String workerId, UUID bankAccountId, BigDecimal amount) {
        return new SettlementExecutionAttempt(
                id,
                instructionId,
                outboxId,
                1,
                instructionId.toString(),
                ProviderSettlementOperationKey.release(instructionId),
                "MOCK",
                "MOCK-ESCROW",
                bankAccountId,
                amount,
                "INR",
                "c".repeat(64),
                workerId,
                Instant.now()
        );
    }
}
