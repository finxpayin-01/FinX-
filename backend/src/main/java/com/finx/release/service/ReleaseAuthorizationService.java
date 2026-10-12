package com.finx.release.service;

import com.finx.approval.dto.ApprovalBindingEvaluation;
import com.finx.approval.service.MilestoneApprovalBindingService;
import com.finx.common.enums.Role;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.dispute.entity.DisputeStatus;
import com.finx.dispute.repository.DisputeRepository;
import com.finx.escrow.entity.EscrowAccount;
import com.finx.escrow.entity.EscrowAccountStatus;
import com.finx.escrow.repository.EscrowAccountRepository;
import com.finx.exception.ResourceNotFoundException;
import com.finx.exception.UnauthorizedException;
import com.finx.milestone.entity.Deliverable;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.DeliverableRepository;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.outbox.entity.FinancialOutboxMessage;
import com.finx.outbox.model.FinancialOutboxEventType;
import com.finx.outbox.repository.FinancialOutboxRepository;
import com.finx.outbox.service.FinancialOutboxService;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.entity.ReleaseReservation;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.model.ReleaseReservationStatus;
import com.finx.release.repository.ReleaseInstructionRepository;
import com.finx.release.repository.ReleaseReservationRepository;
import com.finx.security.service.UserPrincipal;
import com.finx.verification.service.VerificationEligibilityService;
import com.finx.verification.service.VerificationEligibilityService.BeneficiaryEligibilityResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service governing ReleaseInstruction platform authorization, atomic fund reservation,
 * and transactional outbox enqueueing (TX2).
 * <p>
 * Enforces canonical pessimistic write lock order:
 * Deal -> Milestone -> EscrowAccount -> ReleaseInstruction
 * <p>
 * Strictly atomic: Transitions CREATED -> AUTHORIZED -> RESERVED -> QUEUED in one local PostgreSQL transaction.
 * Serialized by EscrowAccount row-level locks to prevent concurrent oversubscription.
 * Zero ledger postings, zero escrow balance deductions, zero bank calls.
 */
@Service
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class ReleaseAuthorizationService {

    private final ReleaseInstructionRepository releaseInstructionRepository;
    private final ReleaseReservationRepository releaseReservationRepository;
    private final DealRepository dealRepository;
    private final MilestoneRepository milestoneRepository;
    private final EscrowAccountRepository escrowAccountRepository;
    private final DisputeRepository disputeRepository;
    private final VerificationEligibilityService verificationEligibilityService;
    private final MilestoneApprovalBindingService approvalBindingService;
    private final DeliverableRepository deliverableRepository;
    private final ReleaseDecisionExecutionValidator executionValidator;
    private final ReleaseFundingScopeResolver fundingScopeResolver;
    private final EscrowReservationService escrowReservationService;
    private final FinancialOutboxService financialOutboxService;
    private final FinancialOutboxRepository financialOutboxRepository;
    private final PlatformTransactionManager transactionManager;

    @Deprecated
    public ReleaseAuthorizationService(
            ReleaseInstructionRepository releaseInstructionRepository,
            ReleaseReservationRepository releaseReservationRepository,
            DealRepository dealRepository,
            MilestoneRepository milestoneRepository,
            EscrowAccountRepository escrowAccountRepository,
            DisputeRepository disputeRepository,
            VerificationEligibilityService verificationEligibilityService,
            MilestoneApprovalBindingService approvalBindingService,
            DeliverableRepository deliverableRepository,
            ReleaseDecisionExecutionValidator executionValidator,
            ReleaseFundingScopeResolver fundingScopeResolver,
            EscrowReservationService escrowReservationService
    ) {
        this(releaseInstructionRepository, releaseReservationRepository, dealRepository, milestoneRepository,
                escrowAccountRepository, disputeRepository, verificationEligibilityService, approvalBindingService,
                deliverableRepository, executionValidator, fundingScopeResolver, escrowReservationService,
                null, null, null);
    }

    public ReleaseAuthorizationService(
            ReleaseInstructionRepository releaseInstructionRepository,
            ReleaseReservationRepository releaseReservationRepository,
            DealRepository dealRepository,
            MilestoneRepository milestoneRepository,
            EscrowAccountRepository escrowAccountRepository,
            DisputeRepository disputeRepository,
            VerificationEligibilityService verificationEligibilityService,
            MilestoneApprovalBindingService approvalBindingService,
            DeliverableRepository deliverableRepository,
            ReleaseDecisionExecutionValidator executionValidator,
            ReleaseFundingScopeResolver fundingScopeResolver,
            EscrowReservationService escrowReservationService,
            FinancialOutboxService financialOutboxService,
            FinancialOutboxRepository financialOutboxRepository
    ) {
        this(releaseInstructionRepository, releaseReservationRepository, dealRepository, milestoneRepository,
                escrowAccountRepository, disputeRepository, verificationEligibilityService, approvalBindingService,
                deliverableRepository, executionValidator, fundingScopeResolver, escrowReservationService,
                financialOutboxService, financialOutboxRepository, null);
    }

    @Autowired
    public ReleaseAuthorizationService(
            ReleaseInstructionRepository releaseInstructionRepository,
            ReleaseReservationRepository releaseReservationRepository,
            DealRepository dealRepository,
            MilestoneRepository milestoneRepository,
            EscrowAccountRepository escrowAccountRepository,
            DisputeRepository disputeRepository,
            VerificationEligibilityService verificationEligibilityService,
            MilestoneApprovalBindingService approvalBindingService,
            DeliverableRepository deliverableRepository,
            ReleaseDecisionExecutionValidator executionValidator,
            ReleaseFundingScopeResolver fundingScopeResolver,
            EscrowReservationService escrowReservationService,
            FinancialOutboxService financialOutboxService,
            FinancialOutboxRepository financialOutboxRepository,
            PlatformTransactionManager transactionManager
    ) {
        this.releaseInstructionRepository = Objects.requireNonNull(releaseInstructionRepository, "releaseInstructionRepository must not be null");
        this.releaseReservationRepository = Objects.requireNonNull(releaseReservationRepository, "releaseReservationRepository must not be null");
        this.dealRepository = Objects.requireNonNull(dealRepository, "dealRepository must not be null");
        this.milestoneRepository = Objects.requireNonNull(milestoneRepository, "milestoneRepository must not be null");
        this.escrowAccountRepository = Objects.requireNonNull(escrowAccountRepository, "escrowAccountRepository must not be null");
        this.disputeRepository = Objects.requireNonNull(disputeRepository, "disputeRepository must not be null");
        this.verificationEligibilityService = Objects.requireNonNull(verificationEligibilityService, "verificationEligibilityService must not be null");
        this.approvalBindingService = Objects.requireNonNull(approvalBindingService, "approvalBindingService must not be null");
        this.deliverableRepository = Objects.requireNonNull(deliverableRepository, "deliverableRepository must not be null");
        this.executionValidator = Objects.requireNonNull(executionValidator, "executionValidator must not be null");
        this.fundingScopeResolver = Objects.requireNonNull(fundingScopeResolver, "fundingScopeResolver must not be null");
        this.escrowReservationService = Objects.requireNonNull(escrowReservationService, "escrowReservationService must not be null");
        this.financialOutboxService = financialOutboxService;
        this.financialOutboxRepository = financialOutboxRepository;
        this.transactionManager = transactionManager;
    }

    public ReleaseReservation authorizeAndReserve(
            UUID releaseInstructionId,
            UserPrincipal currentUser
    ) {
        Objects.requireNonNull(releaseInstructionId, "releaseInstructionId must not be null");

        if (transactionManager == null) {
            return executeAuthorizeAndReserve(releaseInstructionId, currentUser);
        }

        org.springframework.transaction.support.TransactionTemplate tx =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        int maxAttempts = 5;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return tx.execute(status -> executeAuthorizeAndReserve(releaseInstructionId, currentUser));
            } catch (Exception e) {
                if (isRetryableConcurrencyException(e) && attempt < maxAttempts) {
                    try {
                        Thread.sleep(25L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                    continue;
                }
                throw e;
            }
        }
        throw new IllegalStateException("Exhausted retries for release authorization: " + releaseInstructionId);
    }

    private boolean isRetryableConcurrencyException(Throwable t) {
        if (t instanceof org.springframework.dao.ConcurrencyFailureException
                || t instanceof jakarta.persistence.PessimisticLockException
                || t instanceof jakarta.persistence.LockTimeoutException
                || t instanceof jakarta.persistence.OptimisticLockException) {
            return true;
        }
        Throwable root = org.springframework.core.NestedExceptionUtils.getMostSpecificCause(t);
        if (root instanceof java.sql.SQLException sqlEx) {
            String sqlState = sqlEx.getSQLState();
            if ("40001".equals(sqlState) || "55P03".equals(sqlState) || "40P01".equals(sqlState)) {
                return true;
            }
        }
        String msg = root != null && root.getMessage() != null ? root.getMessage().toLowerCase() : "";
        return msg.contains("could not serialize access")
                || msg.contains("lock conflict")
                || msg.contains("deadlock")
                || msg.contains("row was updated or deleted by another transaction");
    }

    private ReleaseReservation executeAuthorizeAndReserve(
            UUID releaseInstructionId,
            UserPrincipal currentUser
    ) {

        // 1. Authorization Authority Check (Part S)
        if (currentUser == null) {
            throw new UnauthorizedException("Release authorization requires an authenticated platform principal");
        }
        if (!currentUser.isEnabled()) {
            throw new UnauthorizedException("User account is disabled or suspended: " + currentUser.getId());
        }
        Role role = currentUser.getRole();
        if (role != Role.ADMIN && role != Role.FINANCE) {
            throw new UnauthorizedException("Role " + role + " is not authorized to authorize release instructions. Only ADMIN and FINANCE permitted.");
        }

        // 2. Discover Deal & Milestone IDs without write lock
        ReleaseInstructionRepository.ScopeProjection initialScope = releaseInstructionRepository.findScopeById(releaseInstructionId)
                .orElseThrow(() -> new ResourceNotFoundException("ReleaseInstruction", "id", releaseInstructionId));

        UUID dealId = initialScope.getDealId();
        UUID milestoneId = initialScope.getMilestoneId();

        // 3. Acquire Pessimistic Locks in Canonical Order: Deal -> Milestone -> EscrowAccount -> ReleaseInstruction
        Deal lockedDeal = dealRepository.findByIdForUpdate(dealId)
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", dealId));

        Milestone lockedMilestone = milestoneRepository.findByIdForUpdate(milestoneId)
                .orElseThrow(() -> new ResourceNotFoundException("Milestone", "id", milestoneId));

        EscrowAccount lockedEscrow = escrowAccountRepository.findByDealIdForUpdate(dealId)
                .orElseThrow(() -> new ResourceNotFoundException("EscrowAccount", "dealId", dealId));

        ReleaseInstruction lockedInstruction = releaseInstructionRepository.findByIdForUpdate(releaseInstructionId)
                .orElseThrow(() -> new ResourceNotFoundException("ReleaseInstruction", "id", releaseInstructionId));

        // 4. Re-verify All Entity Bindings After Locks Acquired
        if (!lockedInstruction.getDealId().equals(lockedDeal.getId())) {
            throw new IllegalStateException("Locked instruction dealId does not match locked Deal");
        }
        if (!lockedInstruction.getMilestoneId().equals(lockedMilestone.getId())) {
            throw new IllegalStateException("Locked instruction milestoneId does not match locked Milestone");
        }
        if (!lockedMilestone.getDealId().equals(lockedDeal.getId())) {
            throw new IllegalStateException("Locked milestone dealId does not match locked Deal");
        }
        if (!lockedEscrow.getDealId().equals(lockedDeal.getId())) {
            throw new IllegalStateException("Locked escrow dealId does not match locked Deal");
        }
        if (lockedDeal.getBuyerOrganizationId() == null || !lockedDeal.getBuyerOrganizationId().equals(lockedInstruction.getOrganizationId())) {
            throw new IllegalStateException("Locked deal buyerOrganizationId does not match locked instruction organizationId");
        }
        if (lockedInstruction.getAmount().compareTo(lockedMilestone.getAmount()) != 0) {
            throw new IllegalStateException("Instruction amount " + lockedInstruction.getAmount() + " does not match milestone amount " + lockedMilestone.getAmount());
        }
        if (!lockedInstruction.getCurrency().equalsIgnoreCase(lockedMilestone.getCurrency())) {
            throw new IllegalStateException("Instruction currency " + lockedInstruction.getCurrency() + " does not match milestone currency " + lockedMilestone.getCurrency());
        }
        if (!lockedEscrow.getCurrency().equalsIgnoreCase(lockedInstruction.getCurrency())) {
            throw new IllegalStateException("Escrow account currency " + lockedEscrow.getCurrency() + " does not match instruction currency " + lockedInstruction.getCurrency());
        }
        if (lockedEscrow.getStatus() != EscrowAccountStatus.ACTIVE) {
            throw new IllegalStateException("Escrow account is not ACTIVE. Current status: " + lockedEscrow.getStatus());
        }
        if (lockedMilestone.getStatus() != MilestoneStatus.APPROVED) {
            throw new IllegalStateException("Milestone is not APPROVED. Current status: " + lockedMilestone.getStatus());
        }

        // Deal status guards
        DealStatus dealStatus = lockedDeal.getStatus();
        if (dealStatus == DealStatus.REJECTED || dealStatus == DealStatus.CANCELLED
                || dealStatus == DealStatus.COMPLETED || dealStatus == DealStatus.DISPUTED) {
            throw new IllegalStateException("Deal status " + dealStatus + " prohibits milestone release authorization");
        }

        // 5. Instruction Lifecycle & Idempotent Replay Handling (Parts U, V, W, X, Y, Z)
        if (lockedInstruction.getStatus() == ReleaseInstructionStatus.QUEUED) {
            Optional<ReleaseReservation> resOpt = releaseReservationRepository.findByReleaseInstructionId(lockedInstruction.getId());
            if (resOpt.isEmpty()) {
                throw new IllegalStateException("DATA_INTEGRITY_ERROR: ReleaseInstruction is QUEUED but ReleaseReservation is missing");
            }
            ReleaseReservation existingRes = resOpt.get();
            if (existingRes.getStatus() != ReleaseReservationStatus.ACTIVE
                    || !existingRes.getMilestoneId().equals(lockedMilestone.getId())
                    || !existingRes.getEscrowAccountId().equals(lockedEscrow.getId())
                    || existingRes.getAmount().compareTo(lockedInstruction.getAmount()) != 0
                    || !existingRes.getCurrency().equalsIgnoreCase(lockedInstruction.getCurrency())) {
                throw new IllegalStateException("DATA_INTEGRITY_ERROR: ReleaseInstruction is QUEUED but ReleaseReservation is inconsistent");
            }

            if (financialOutboxRepository != null) {
                Optional<FinancialOutboxMessage> outboxOpt = financialOutboxRepository.findByReleaseInstructionIdAndEventType(
                        lockedInstruction.getId(), FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED);
                if (outboxOpt.isEmpty()) {
                    throw new IllegalStateException("DATA_INTEGRITY_ERROR: ReleaseInstruction is QUEUED but outbox event is missing");
                }
                FinancialOutboxMessage outbox = outboxOpt.get();
                if (!outbox.getReleaseInstructionId().equals(lockedInstruction.getId())
                        || outbox.getEventType() != FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED
                        || !outbox.getOperationKey().equals(lockedInstruction.getId().toString())) {
                    throw new IllegalStateException("DATA_INTEGRITY_ERROR: ReleaseInstruction is QUEUED but outbox event identity is inconsistent");
                }

                Optional<FinancialOutboxMessage> opKeyOpt = financialOutboxRepository.findByEventTypeAndOperationKey(
                        FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED, lockedInstruction.getId().toString());
                if (opKeyOpt.isPresent() && !opKeyOpt.get().getId().equals(outbox.getId())) {
                    throw new IllegalStateException("DATA_INTEGRITY_ERROR: Conflicting outbox event for operation key " + lockedInstruction.getId());
                }
            }

            return existingRes;
        }

        if (lockedInstruction.getStatus() == ReleaseInstructionStatus.RESERVED) {
            Optional<ReleaseReservation> resOpt = releaseReservationRepository.findByReleaseInstructionId(lockedInstruction.getId());
            if (resOpt.isEmpty()) {
                throw new IllegalStateException("DATA_INTEGRITY_ERROR: ReleaseInstruction is RESERVED but ReleaseReservation is missing");
            }
            ReleaseReservation existingRes = resOpt.get();
            if (existingRes.getStatus() != ReleaseReservationStatus.ACTIVE
                    || !existingRes.getMilestoneId().equals(lockedMilestone.getId())
                    || !existingRes.getEscrowAccountId().equals(lockedEscrow.getId())
                    || existingRes.getAmount().compareTo(lockedInstruction.getAmount()) != 0
                    || !existingRes.getCurrency().equalsIgnoreCase(lockedInstruction.getCurrency())) {
                throw new IllegalStateException("DATA_INTEGRITY_ERROR: ReleaseInstruction is RESERVED but ReleaseReservation is inconsistent");
            }

            // Part V: If instruction == RESERVED and outbox event already exists: FAIL CLOSED
            if (financialOutboxRepository != null) {
                if (financialOutboxRepository.findByReleaseInstructionIdAndEventType(
                        lockedInstruction.getId(), FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED).isPresent()) {
                    throw new IllegalStateException("DATA_INTEGRITY_ERROR: ReleaseInstruction is in illegal state RESERVED with existing outbox event");
                }
            }

            // Part U: Re-run all execution-critical guards without calling markAuthorized again
            runExecutionGuards(lockedDeal, lockedMilestone, lockedInstruction);

            // Enqueue dispatch event, mark QUEUED and commit
            if (financialOutboxService != null) {
                financialOutboxService.enqueueReleaseDispatch(lockedInstruction);
            }
            lockedInstruction.markQueued();
            releaseInstructionRepository.save(lockedInstruction);

            return existingRes;
        }

        if (lockedInstruction.getStatus() == ReleaseInstructionStatus.AUTHORIZED) {
            throw new IllegalStateException("DATA_INTEGRITY_ERROR: ReleaseInstruction is in illegal state AUTHORIZED without reservation. Manual repair required.");
        }

        if (lockedInstruction.getStatus() != ReleaseInstructionStatus.CREATED) {
            throw new IllegalStateException("ReleaseInstruction is in invalid state for authorization: " + lockedInstruction.getStatus());
        }

        // Invariant: No ACTIVE reservation may pre-exist for this instruction or milestone while instruction is CREATED
        if (releaseReservationRepository.findByReleaseInstructionId(lockedInstruction.getId()).isPresent()) {
            throw new IllegalStateException("DATA_INTEGRITY_ERROR: ReleaseReservation already exists for CREATED instruction " + lockedInstruction.getId());
        }
        if (releaseReservationRepository.findFirstByMilestoneIdAndStatus(lockedMilestone.getId(), ReleaseReservationStatus.ACTIVE).isPresent()) {
            throw new IllegalStateException("An active ReleaseReservation already exists for milestone " + lockedMilestone.getId());
        }

        // Part Y: Invariant: No outbox event may pre-exist for CREATED instruction
        if (financialOutboxRepository != null) {
            if (financialOutboxRepository.findByReleaseInstructionIdAndEventType(
                    lockedInstruction.getId(), FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED).isPresent()) {
                throw new IllegalStateException("DATA_INTEGRITY_ERROR: Outbox event already exists for CREATED instruction " + lockedInstruction.getId());
            }
        }

        // 6. Pre-Reservation Execution Guards
        runExecutionGuards(lockedDeal, lockedMilestone, lockedInstruction);

        // 7. Escrow Balance Availability Check (Part AB)
        BigDecimal activeReserved = releaseReservationRepository.sumActiveReservationsForEscrow(lockedEscrow.getId());
        BigDecimal availableToReserve = lockedEscrow.getBalance().subtract(activeReserved);
        if (availableToReserve.compareTo(lockedInstruction.getAmount()) < 0) {
            throw new IllegalStateException("Insufficient available escrow funds to reserve: available="
                    + availableToReserve + ", required=" + lockedInstruction.getAmount());
        }

        // 8. Atomic State Transitions, Reservation Creation & Outbox Enqueue (Parts S, T)
        lockedInstruction.markAuthorized(currentUser.getId(), Instant.now());
        ReleaseReservation reservation = escrowReservationService.createActiveReservation(lockedInstruction, lockedEscrow, lockedMilestone);
        lockedInstruction.markReserved();

        if (financialOutboxService != null) {
            financialOutboxService.enqueueReleaseDispatch(lockedInstruction);
        }
        lockedInstruction.markQueued();
        releaseInstructionRepository.save(lockedInstruction);

        // Advance EscrowAccount version to serialize concurrent reservations & reflect durable reservation
        lockedEscrow.setUpdatedAt(Instant.now());
        escrowAccountRepository.save(lockedEscrow);

        return reservation;
    }

    private void runExecutionGuards(Deal lockedDeal, Milestone lockedMilestone, ReleaseInstruction lockedInstruction) {
        // A. Release decision integrity & currentness
        executionValidator.validateForExecution(
                lockedInstruction.getReleaseDecisionId(),
                lockedDeal,
                lockedMilestone,
                lockedInstruction.getBeneficiaryBankAccountId()
        );

        // B. Durable milestone approval binding recheck
        List<Deliverable> deliverables = deliverableRepository.findByMilestoneIdOrderBySubmittedAtDesc(lockedMilestone.getId());
        ApprovalBindingEvaluation approvalEval = approvalBindingService.evaluateCurrentBinding(lockedDeal, lockedMilestone, deliverables);
        if (!approvalEval.present() || !approvalEval.current()) {
            throw new IllegalStateException("Milestone approval binding is missing or not current: " + approvalEval.reasonCode());
        }

        // C. Dispute recheck
        if (disputeRepository.existsByDealIdAndStatus(lockedDeal.getId(), DisputeStatus.OPEN)) {
            throw new IllegalStateException("Deal " + lockedDeal.getId() + " has an OPEN dispute");
        }
        if (disputeRepository.existsByMilestoneIdAndStatus(lockedMilestone.getId(), DisputeStatus.OPEN)) {
            throw new IllegalStateException("Milestone " + lockedMilestone.getId() + " has an OPEN dispute");
        }

        // D. Canonical beneficiary recheck
        BeneficiaryEligibilityResult bResult = verificationEligibilityService.evaluateBeneficiaryEligibility(lockedDeal.getSellerOrganizationId());
        if (!bResult.eligible() || bResult.bankAccount() == null) {
            throw new IllegalStateException("Canonical beneficiary resolution failed: " + bResult.failureReason());
        }
        if (!bResult.bankAccount().getId().equals(lockedInstruction.getBeneficiaryBankAccountId())) {
            throw new IllegalStateException("Canonical beneficiary has changed since instruction creation: original="
                    + lockedInstruction.getBeneficiaryBankAccountId() + " vs current=" + bResult.bankAccount().getId());
        }

        // E. Funding scope recheck
        ReleaseFundingScopeResolver.FundingScope scope = fundingScopeResolver.resolveFundingScope(lockedDeal, lockedMilestone);
        if (!scope.provider().equalsIgnoreCase(lockedInstruction.getProvider())) {
            throw new IllegalStateException("Funding provider changed from " + lockedInstruction.getProvider() + " to " + scope.provider());
        }
        if (!scope.providerAccountId().equals(lockedInstruction.getProviderAccountId())) {
            throw new IllegalStateException("Funding providerAccountId changed from " + lockedInstruction.getProviderAccountId() + " to " + scope.providerAccountId());
        }
    }
}
