package com.finx.release.execution.service;

import com.finx.approval.dto.ApprovalBindingEvaluation;
import com.finx.approval.service.MilestoneApprovalBindingService;
import com.finx.common.time.FinxClock;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.dispute.entity.DisputeStatus;
import com.finx.dispute.repository.DisputeRepository;
import com.finx.exception.ResourceNotFoundException;
import com.finx.milestone.entity.Deliverable;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.DeliverableRepository;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Transactional state manager for sandbox settlement dispatch lifecycle transitions.
 * Owns TX-D1 (validation and marking network start) and TX-D2 (recording acknowledgement or unknown outcome).
 * Strictly zero BankingProvider dependency and zero PiiEncryptionService dependency.
 */
@Service
public class SettlementDispatchStateService {

    private static final Logger log = LoggerFactory.getLogger(SettlementDispatchStateService.class);

    private final FinancialOutboxRepository outboxRepository;
    private final SettlementExecutionAttemptRepository attemptRepository;
    private final ReleaseInstructionRepository instructionRepository;
    private final ReleaseReservationRepository reservationRepository;
    private final DealRepository dealRepository;
    private final MilestoneRepository milestoneRepository;
    private final DisputeRepository disputeRepository;
    private final OrganizationBankAccountRepository bankAccountRepository;
    private final VerificationEligibilityService verificationEligibilityService;
    private final MilestoneApprovalBindingService approvalBindingService;
    private final DeliverableRepository deliverableRepository;
    private final ReleaseDecisionExecutionValidator executionValidator;
    private final ReleaseFundingScopeResolver fundingScopeResolver;
    private final FinxClock clock;

    public SettlementDispatchStateService(
            FinancialOutboxRepository outboxRepository,
            SettlementExecutionAttemptRepository attemptRepository,
            ReleaseInstructionRepository instructionRepository,
            ReleaseReservationRepository reservationRepository,
            DealRepository dealRepository,
            MilestoneRepository milestoneRepository,
            DisputeRepository disputeRepository,
            OrganizationBankAccountRepository bankAccountRepository,
            VerificationEligibilityService verificationEligibilityService,
            MilestoneApprovalBindingService approvalBindingService,
            DeliverableRepository deliverableRepository,
            ReleaseDecisionExecutionValidator executionValidator,
            ReleaseFundingScopeResolver fundingScopeResolver,
            FinxClock clock
    ) {
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository must not be null");
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository must not be null");
        this.instructionRepository = Objects.requireNonNull(instructionRepository, "instructionRepository must not be null");
        this.reservationRepository = Objects.requireNonNull(reservationRepository, "reservationRepository must not be null");
        this.dealRepository = Objects.requireNonNull(dealRepository, "dealRepository must not be null");
        this.milestoneRepository = Objects.requireNonNull(milestoneRepository, "milestoneRepository must not be null");
        this.disputeRepository = Objects.requireNonNull(disputeRepository, "disputeRepository must not be null");
        this.bankAccountRepository = Objects.requireNonNull(bankAccountRepository, "bankAccountRepository must not be null");
        this.verificationEligibilityService = Objects.requireNonNull(verificationEligibilityService, "verificationEligibilityService must not be null");
        this.approvalBindingService = Objects.requireNonNull(approvalBindingService, "approvalBindingService must not be null");
        this.deliverableRepository = Objects.requireNonNull(deliverableRepository, "deliverableRepository must not be null");
        this.executionValidator = Objects.requireNonNull(executionValidator, "executionValidator must not be null");
        this.fundingScopeResolver = Objects.requireNonNull(fundingScopeResolver, "fundingScopeResolver must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * TX-D1: Validates all pre-conditions, re-evaluates execution guards, transitions
     * Attempt from PREPARED -> SENDING and ReleaseInstruction from QUEUED -> DISPATCHING,
     * while leaving outbox LEASED and reservation ACTIVE.
     * Commits before external network call.
     */
    @Transactional
    public DispatchPreparedContext beginDispatch(
            UUID outboxMessageId,
            UUID executionAttemptId,
            String workerId
    ) {
        Objects.requireNonNull(outboxMessageId, "outboxMessageId must not be null");
        Objects.requireNonNull(executionAttemptId, "executionAttemptId must not be null");
        Objects.requireNonNull(workerId, "workerId must not be null");

        // 1. Load and lock: Outbox, Attempt, Instruction
        FinancialOutboxMessage outbox = outboxRepository.findByIdForUpdate(outboxMessageId)
                .orElseThrow(() -> new ResourceNotFoundException("FinancialOutboxMessage", "id", outboxMessageId));

        SettlementExecutionAttempt attempt = attemptRepository.findByIdForUpdate(executionAttemptId)
                .orElseThrow(() -> new ResourceNotFoundException("SettlementExecutionAttempt", "id", executionAttemptId));

        ReleaseInstruction instruction = instructionRepository.findByIdForUpdate(attempt.getReleaseInstructionId())
                .orElseThrow(() -> new ResourceNotFoundException("ReleaseInstruction", "id", attempt.getReleaseInstructionId()));

        // 2. Outbox preconditions
        if (outbox.getStatus() != FinancialOutboxStatus.LEASED) {
            throw new IllegalStateException("Outbox message is not LEASED: " + outbox.getStatus());
        }
        if (!workerId.equals(outbox.getLockedBy())) {
            throw new IllegalStateException("Outbox message lockedBy mismatch: expected " + workerId + " but was " + outbox.getLockedBy());
        }
        if (outbox.getEventType() != FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED) {
            throw new IllegalStateException("Outbox message eventType mismatch: " + outbox.getEventType());
        }

        // 3. Attempt preconditions
        if (!attempt.getOutboxMessageId().equals(outbox.getId())) {
            throw new IllegalStateException("Attempt outboxMessageId mismatch");
        }
        if (!attempt.getReleaseInstructionId().equals(outbox.getReleaseInstructionId())) {
            throw new IllegalStateException("Attempt releaseInstructionId mismatch");
        }
        if (attempt.getStatus() != SettlementExecutionAttemptStatus.PREPARED) {
            throw new IllegalStateException("Attempt status is not PREPARED: " + attempt.getStatus());
        }
        if (!workerId.equals(attempt.getWorkerId())) {
            throw new IllegalStateException("Attempt workerId mismatch: expected " + workerId + " but was " + attempt.getWorkerId());
        }

        // 4. ReleaseInstruction preconditions
        if (instruction.getStatus() != ReleaseInstructionStatus.QUEUED) {
            throw new IllegalStateException("ReleaseInstruction status is not QUEUED: " + instruction.getStatus());
        }

        // 5. Reservation check
        ReleaseReservation reservation = reservationRepository.findByReleaseInstructionId(instruction.getId())
                .orElseThrow(() -> new IllegalStateException("Active ReleaseReservation missing for instruction: " + instruction.getId()));
        if (reservation.getStatus() != ReleaseReservationStatus.ACTIVE) {
            throw new IllegalStateException("ReleaseReservation is not ACTIVE: " + reservation.getStatus());
        }
        if (reservation.getAmount().compareTo(instruction.getAmount()) != 0) {
            throw new IllegalStateException("Reservation amount mismatch");
        }
        if (!reservation.getCurrency().equalsIgnoreCase(instruction.getCurrency())) {
            throw new IllegalStateException("Reservation currency mismatch");
        }

        // 6. Operation keys
        if (!attempt.getOperationKey().equals(outbox.getOperationKey())) {
            throw new IllegalStateException("OperationKey mismatch: attempt=" + attempt.getOperationKey() + " vs outbox=" + outbox.getOperationKey());
        }
        String expectedProviderOpKey = "FINX-REL-" + instruction.getId();
        if (!expectedProviderOpKey.equals(attempt.getProviderOperationKey())) {
            throw new IllegalStateException("ProviderOperationKey mismatch: expected " + expectedProviderOpKey + " but was " + attempt.getProviderOperationKey());
        }

        // 7. Part N: Snapshot validation
        if (!attempt.getProvider().equalsIgnoreCase(instruction.getProvider())) {
            throw new IllegalStateException("Provider mismatch between attempt and instruction");
        }
        if (!attempt.getProviderAccountId().equals(instruction.getProviderAccountId())) {
            throw new IllegalStateException("ProviderAccountId mismatch between attempt and instruction");
        }
        if (!attempt.getBeneficiaryBankAccountId().equals(instruction.getBeneficiaryBankAccountId())) {
            throw new IllegalStateException("BeneficiaryBankAccountId mismatch between attempt and instruction");
        }
        if (attempt.getAmount().compareTo(instruction.getAmount()) != 0) {
            throw new IllegalStateException("Amount mismatch between attempt and instruction");
        }
        if (!attempt.getCurrency().equalsIgnoreCase(instruction.getCurrency())) {
            throw new IllegalStateException("Currency mismatch between attempt and instruction");
        }
        if (!attempt.getRequestHash().equals(instruction.getRequestHash())) {
            throw new IllegalStateException("RequestHash mismatch between attempt and instruction");
        }

        // 8. Part O: Provider must be MOCK
        if (!"MOCK".equalsIgnoreCase(attempt.getProvider()) || !"MOCK".equalsIgnoreCase(instruction.getProvider())) {
            throw new IllegalStateException("Provider is not MOCK: attempt=" + attempt.getProvider() + ", instruction=" + instruction.getProvider());
        }

        // 9. Load Deal & Milestone for execution-critical revalidation (Part G)
        Deal deal = dealRepository.findById(instruction.getDealId())
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", instruction.getDealId()));
        Milestone milestone = milestoneRepository.findById(instruction.getMilestoneId())
                .orElseThrow(() -> new ResourceNotFoundException("Milestone", "id", instruction.getMilestoneId()));

        if (milestone.getStatus() != MilestoneStatus.APPROVED) {
            throw new IllegalStateException("Milestone is not APPROVED: " + milestone.getStatus());
        }
        DealStatus dealStatus = deal.getStatus();
        if (dealStatus == DealStatus.REJECTED || dealStatus == DealStatus.CANCELLED
                || dealStatus == DealStatus.COMPLETED || dealStatus == DealStatus.DISPUTED) {
            throw new IllegalStateException("Deal status prohibits release dispatch: " + dealStatus);
        }

        // 10. Re-run execution-critical guards
        // A. Release decision integrity & currentness
        executionValidator.validateForExecution(
                instruction.getReleaseDecisionId(),
                deal,
                milestone,
                instruction.getBeneficiaryBankAccountId()
        );

        // B. Milestone approval binding currentness
        List<Deliverable> deliverables = deliverableRepository.findByMilestoneIdOrderBySubmittedAtDesc(milestone.getId());
        ApprovalBindingEvaluation approvalEval = approvalBindingService.evaluateCurrentBinding(deal, milestone, deliverables);
        if (!approvalEval.present() || !approvalEval.current()) {
            throw new IllegalStateException("Milestone approval binding is missing or not current: " + approvalEval.reasonCode());
        }

        // C. No open deal or milestone dispute
        if (disputeRepository.existsByDealIdAndStatus(deal.getId(), DisputeStatus.OPEN)) {
            throw new IllegalStateException("Deal has an OPEN dispute: " + deal.getId());
        }
        if (disputeRepository.existsByMilestoneIdAndStatus(milestone.getId(), DisputeStatus.OPEN)) {
            throw new IllegalStateException("Milestone has an OPEN dispute: " + milestone.getId());
        }

        // D. Canonical beneficiary currentness
        BeneficiaryEligibilityResult bResult = verificationEligibilityService.evaluateBeneficiaryEligibility(deal.getSellerOrganizationId());
        if (!bResult.eligible() || bResult.bankAccount() == null) {
            throw new IllegalStateException("Canonical beneficiary resolution failed: " + bResult.failureReason());
        }
        if (!bResult.bankAccount().getId().equals(instruction.getBeneficiaryBankAccountId())) {
            throw new IllegalStateException("Canonical beneficiary has changed: original="
                    + instruction.getBeneficiaryBankAccountId() + " vs current=" + bResult.bankAccount().getId());
        }

        // E. Funding scope recheck
        ReleaseFundingScopeResolver.FundingScope scope = fundingScopeResolver.resolveFundingScope(deal, milestone);
        if (!scope.provider().equalsIgnoreCase(instruction.getProvider())) {
            throw new IllegalStateException("Funding provider changed: " + scope.provider() + " vs " + instruction.getProvider());
        }
        if (!scope.providerAccountId().equals(instruction.getProviderAccountId())) {
            throw new IllegalStateException("Funding providerAccountId changed: " + scope.providerAccountId() + " vs " + instruction.getProviderAccountId());
        }

        // 11. Part J & K: Beneficiary request boundary & sandbox safety
        OrganizationBankAccount bankAccount = bankAccountRepository.findById(instruction.getBeneficiaryBankAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("OrganizationBankAccount", "id", instruction.getBeneficiaryBankAccountId()));

        if (!deal.getSellerOrganizationId().equals(bankAccount.getOrganizationId())) {
            throw new IllegalStateException("Beneficiary bank account organization mismatch");
        }
        if (!bResult.isSandbox()) {
            throw new IllegalStateException("Beneficiary account is not sandbox-eligible");
        }
        boolean hasMockEvidence = "MOCK".equalsIgnoreCase(bankAccount.getProvider())
                || (bankAccount.getVerificationReference() != null && bankAccount.getVerificationReference().startsWith("MOCK"));
        if (!hasMockEvidence) {
            throw new IllegalStateException("Beneficiary account lacks explicit MOCK verification evidence");
        }
        if (bankAccount.getEncryptedAccountNumber() == null || bankAccount.getEncryptedAccountNumber().isBlank()) {
            throw new IllegalStateException("Beneficiary encryptedAccountNumber is missing or blank");
        }
        if (bankAccount.getIfscCode() == null || bankAccount.getIfscCode().isBlank()) {
            throw new IllegalStateException("Beneficiary IFSC is missing or blank");
        }
        if (bankAccount.getAccountHolderName() == null || bankAccount.getAccountHolderName().isBlank()) {
            throw new IllegalStateException("Beneficiary accountHolderName is missing or blank");
        }

        // 12. Transition TX-D1:
        // Attempt: PREPARED -> SENDING
        // ReleaseInstruction: QUEUED -> DISPATCHING
        // Outbox: remains LEASED
        // Reservation: remains ACTIVE
        Instant now = clock.now();
        attempt.markSending(now);
        instruction.markDispatching();

        attemptRepository.save(attempt);
        instructionRepository.save(instruction);

        return new DispatchPreparedContext(
                outbox.getId(),
                attempt.getId(),
                instruction.getId(),
                deal.getId(),
                milestone.getId(),
                instruction.getAmount(),
                instruction.getCurrency(),
                instruction.getProvider(),
                instruction.getProviderAccountId(),
                attempt.getProviderOperationKey(),
                instruction.getBeneficiaryBankAccountId(),
                bankAccount.getAccountHolderName(),
                bankAccount.getIfscCode(),
                bankAccount.getEncryptedAccountNumber()
        );
    }

    /**
     * TX-D2: Records successful provider acknowledgement.
     * Transitions Attempt -> ACKNOWLEDGED, ReleaseInstruction -> PENDING_SETTLEMENT,
     * and Outbox -> PROCESSED.
     * ReleaseReservation remains untouched and ACTIVE.
     */
    @Transactional
    public void recordAcknowledged(
            UUID outboxMessageId,
            UUID executionAttemptId,
            String workerId,
            String providerSettlementId,
            String providerStatus,
            Instant responseReceivedAt
    ) {
        Objects.requireNonNull(outboxMessageId, "outboxMessageId must not be null");
        Objects.requireNonNull(executionAttemptId, "executionAttemptId must not be null");
        Objects.requireNonNull(workerId, "workerId must not be null");
        Objects.requireNonNull(providerSettlementId, "providerSettlementId must not be null");
        Objects.requireNonNull(providerStatus, "providerStatus must not be null");
        Objects.requireNonNull(responseReceivedAt, "responseReceivedAt must not be null");

        FinancialOutboxMessage outbox = outboxRepository.findByIdForUpdate(outboxMessageId)
                .orElseThrow(() -> new ResourceNotFoundException("FinancialOutboxMessage", "id", outboxMessageId));

        SettlementExecutionAttempt attempt = attemptRepository.findByIdForUpdate(executionAttemptId)
                .orElseThrow(() -> new ResourceNotFoundException("SettlementExecutionAttempt", "id", executionAttemptId));

        ReleaseInstruction instruction = instructionRepository.findByIdForUpdate(attempt.getReleaseInstructionId())
                .orElseThrow(() -> new ResourceNotFoundException("ReleaseInstruction", "id", attempt.getReleaseInstructionId()));

        if (attempt.getStatus() != SettlementExecutionAttemptStatus.SENDING) {
            throw new IllegalStateException("Cannot record acknowledged: attempt status is " + attempt.getStatus());
        }
        if (!workerId.equals(attempt.getWorkerId())) {
            throw new IllegalStateException("WorkerId mismatch on attempt");
        }
        if (instruction.getStatus() != ReleaseInstructionStatus.DISPATCHING) {
            throw new IllegalStateException("Cannot record acknowledged: instruction status is " + instruction.getStatus());
        }
        if (outbox.getStatus() != FinancialOutboxStatus.LEASED) {
            throw new IllegalStateException("Cannot record acknowledged: outbox status is " + outbox.getStatus());
        }
        if (!workerId.equals(outbox.getLockedBy())) {
            throw new IllegalStateException("WorkerId mismatch on outbox lease");
        }

        attempt.markAcknowledged(providerSettlementId, providerStatus, responseReceivedAt);
        instruction.markPendingSettlement();
        outbox.markProcessed(responseReceivedAt, null);

        attemptRepository.save(attempt);
        instructionRepository.save(instruction);
        outboxRepository.save(outbox);
    }

    /**
     * TX-D2: Records unknown / ambiguous provider outcome.
     * Transitions Attempt -> UNKNOWN, ReleaseInstruction -> UNKNOWN_RECON_REQUIRED,
     * and Outbox -> PROCESSED (with safe error code).
     * ReleaseReservation remains untouched and ACTIVE.
     */
    @Transactional
    public void recordUnknown(
            UUID outboxMessageId,
            UUID executionAttemptId,
            String workerId,
            String safeErrorCode,
            Instant observedAt
    ) {
        Objects.requireNonNull(outboxMessageId, "outboxMessageId must not be null");
        Objects.requireNonNull(executionAttemptId, "executionAttemptId must not be null");
        Objects.requireNonNull(workerId, "workerId must not be null");
        Objects.requireNonNull(observedAt, "observedAt must not be null");

        FinancialOutboxMessage outbox = outboxRepository.findByIdForUpdate(outboxMessageId)
                .orElseThrow(() -> new ResourceNotFoundException("FinancialOutboxMessage", "id", outboxMessageId));

        SettlementExecutionAttempt attempt = attemptRepository.findByIdForUpdate(executionAttemptId)
                .orElseThrow(() -> new ResourceNotFoundException("SettlementExecutionAttempt", "id", executionAttemptId));

        ReleaseInstruction instruction = instructionRepository.findByIdForUpdate(attempt.getReleaseInstructionId())
                .orElseThrow(() -> new ResourceNotFoundException("ReleaseInstruction", "id", attempt.getReleaseInstructionId()));

        if (attempt.getStatus() != SettlementExecutionAttemptStatus.SENDING) {
            throw new IllegalStateException("Cannot record unknown: attempt status is " + attempt.getStatus());
        }
        if (!workerId.equals(attempt.getWorkerId())) {
            throw new IllegalStateException("WorkerId mismatch on attempt");
        }
        if (instruction.getStatus() != ReleaseInstructionStatus.DISPATCHING) {
            throw new IllegalStateException("Cannot record unknown: instruction status is " + instruction.getStatus());
        }
        if (outbox.getStatus() != FinancialOutboxStatus.LEASED) {
            throw new IllegalStateException("Cannot record unknown: outbox status is " + outbox.getStatus());
        }
        if (!workerId.equals(outbox.getLockedBy())) {
            throw new IllegalStateException("WorkerId mismatch on outbox lease");
        }

        String errCode = (safeErrorCode != null && !safeErrorCode.isBlank()) ? safeErrorCode : "DISPATCH_OUTCOME_UNKNOWN";

        attempt.markUnknown(errCode, observedAt);
        instruction.markUnknownReconciliationRequired();
        outbox.markProcessed(observedAt, errCode);

        attemptRepository.save(attempt);
        instructionRepository.save(instruction);
        outboxRepository.save(outbox);
    }

    /**
     * Transitions preflight failure prior to network dispatch:
     * Attempt -> TERMINAL_FAILED, ReleaseInstruction -> MANUAL_REVIEW, Outbox -> DEAD_LETTER.
     */
    @Transactional
    public void recordPreDispatchTerminalFailure(
            UUID outboxMessageId,
            UUID executionAttemptId,
            String workerId,
            String safeErrorCode,
            Instant now
    ) {
        Objects.requireNonNull(outboxMessageId, "outboxMessageId must not be null");
        Objects.requireNonNull(executionAttemptId, "executionAttemptId must not be null");
        Objects.requireNonNull(workerId, "workerId must not be null");
        Objects.requireNonNull(now, "now must not be null");

        FinancialOutboxMessage outbox = outboxRepository.findByIdForUpdate(outboxMessageId)
                .orElseThrow(() -> new ResourceNotFoundException("FinancialOutboxMessage", "id", outboxMessageId));

        SettlementExecutionAttempt attempt = attemptRepository.findByIdForUpdate(executionAttemptId)
                .orElseThrow(() -> new ResourceNotFoundException("SettlementExecutionAttempt", "id", executionAttemptId));

        ReleaseInstruction instruction = instructionRepository.findByIdForUpdate(attempt.getReleaseInstructionId())
                .orElseThrow(() -> new ResourceNotFoundException("ReleaseInstruction", "id", attempt.getReleaseInstructionId()));

        if (attempt.getStatus() != SettlementExecutionAttemptStatus.PREPARED) {
            throw new IllegalStateException("Cannot record terminal failure: attempt status is " + attempt.getStatus());
        }
        if (outbox.getStatus() != FinancialOutboxStatus.LEASED) {
            throw new IllegalStateException("Cannot record terminal failure: outbox status is " + outbox.getStatus());
        }

        String errCode = (safeErrorCode != null && !safeErrorCode.isBlank()) ? safeErrorCode : "PRE_DISPATCH_TERMINAL_FAILURE";

        attempt.markTerminalFailedBeforeDispatch(errCode, now);
        instruction.markManualReview();
        outbox.markDeadLetter(errCode, now);

        attemptRepository.save(attempt);
        instructionRepository.save(instruction);
        outboxRepository.save(outbox);
    }
}
