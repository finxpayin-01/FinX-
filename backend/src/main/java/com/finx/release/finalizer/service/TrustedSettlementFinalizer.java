package com.finx.release.finalizer.service;

import com.finx.audit.service.AuditService;
import com.finx.common.enums.Role;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.escrow.entity.EscrowAccount;
import com.finx.escrow.entity.EscrowLedger;
import com.finx.escrow.entity.TransactionType;
import com.finx.escrow.repository.EscrowAccountRepository;
import com.finx.escrow.repository.EscrowLedgerRepository;
import com.finx.exception.ResourceNotFoundException;
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
import com.finx.release.observation.service.SettlementObservationEvidenceReader;
import com.finx.release.observation.service.SettlementObservationEvidenceReader.AuthoritativeObservationResult;
import com.finx.release.observation.service.SettlementObservationEvidenceReader.AuthoritativeStatus;
import com.finx.release.repository.ReleaseInstructionRepository;
import com.finx.release.repository.ReleaseInstructionRepository.ScopeProjection;
import com.finx.release.repository.ReleaseReservationRepository;
import com.finx.security.service.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Atomic coordinator for converting authoritative external banking truth (FINAL_SUCCESS SettlementObservation)
 * into internal FinX financial and domain truth.
 *
 * Enforces canonical lock ordering:
 *   Deal -> Milestone -> EscrowAccount -> ReleaseInstruction -> ReleaseReservation -> sorted LedgerAccounts
 *
 * Ensures:
 *   - ZERO bank calls
 *   - Balanced double-entry GL journal (Dr 2010 Escrow Liability / Cr 1010 Escrow Pool)
 *   - Escrow balance deduction and EscrowLedger RELEASE record
 *   - Reservation consumption (ACTIVE -> CONSUMED)
 *   - ReleaseInstruction marked SETTLED with observation binding
 *   - Milestone marked COMPLETED
 *   - Deal marked COMPLETED if all milestones complete and not disputed/cancelled
 *   - 100% idempotent replay
 *   - Strict atomic rollback
 */
@Service
public class TrustedSettlementFinalizer {

    private static final Logger log = LoggerFactory.getLogger(TrustedSettlementFinalizer.class);

    private final ReleaseInstructionRepository releaseInstructionRepo;
    private final ReleaseReservationRepository reservationRepo;
    private final EscrowAccountRepository escrowRepo;
    private final EscrowLedgerRepository escrowLedgerRepo;
    private final MilestoneRepository milestoneRepo;
    private final DealRepository dealRepo;
    private final SettlementExecutionAttemptRepository attemptRepo;
    private final JournalEntryRepository journalEntryRepo;
    private final LedgerService ledgerService;
    private final SettlementObservationEvidenceReader evidenceReader;
    private final SandboxSettlementFinalizationGate finalizationGate;
    private final AuditService auditService;

    public TrustedSettlementFinalizer(
            ReleaseInstructionRepository releaseInstructionRepo,
            ReleaseReservationRepository reservationRepo,
            EscrowAccountRepository escrowRepo,
            EscrowLedgerRepository escrowLedgerRepo,
            MilestoneRepository milestoneRepo,
            DealRepository dealRepo,
            SettlementExecutionAttemptRepository attemptRepo,
            JournalEntryRepository journalEntryRepo,
            LedgerService ledgerService,
            SettlementObservationEvidenceReader evidenceReader,
            SandboxSettlementFinalizationGate finalizationGate,
            AuditService auditService
    ) {
        this.releaseInstructionRepo = releaseInstructionRepo;
        this.reservationRepo = reservationRepo;
        this.escrowRepo = escrowRepo;
        this.escrowLedgerRepo = escrowLedgerRepo;
        this.milestoneRepo = milestoneRepo;
        this.dealRepo = dealRepo;
        this.attemptRepo = attemptRepo;
        this.journalEntryRepo = journalEntryRepo;
        this.ledgerService = ledgerService;
        this.evidenceReader = evidenceReader;
        this.finalizationGate = finalizationGate;
        this.auditService = auditService;
    }

    /**
     * Atomically finalizes a release instruction based on authoritative trusted observation evidence.
     * Caller cannot provide financial or observation parameters; the finalizer evaluates state authoritatively.
     */
    @Transactional
    public SettlementFinalizationResult finalizeRelease(UUID releaseInstructionId, UserPrincipal currentUser) {
        Objects.requireNonNull(releaseInstructionId, "releaseInstructionId must not be null");

        // Step 1: Validate actor authority (ADMIN or FINANCE only)
        if (currentUser == null || currentUser.getRole() == null
                || (currentUser.getRole() != Role.ADMIN && currentUser.getRole() != Role.FINANCE)) {
            throw new AccessDeniedException("Only ADMIN or FINANCE roles can finalize trusted settlements");
        }

        // Step 2: Read scope (dealId, milestoneId) without locking
        ScopeProjection scope = releaseInstructionRepo.findScopeById(releaseInstructionId)
                .orElseThrow(() -> new ResourceNotFoundException("ReleaseInstruction", "id", releaseInstructionId));

        UUID dealId = scope.getDealId();
        UUID milestoneId = scope.getMilestoneId();

        // Step 3-7: Canonical locking in strict order:
        // Deal -> Milestone -> EscrowAccount -> ReleaseInstruction -> ReleaseReservation
        Deal deal = dealRepo.findByIdForUpdate(dealId)
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", dealId));

        Milestone milestone = milestoneRepo.findByIdForUpdate(milestoneId)
                .orElseThrow(() -> new ResourceNotFoundException("Milestone", "id", milestoneId));

        EscrowAccount escrowAccount = escrowRepo.findByDealIdForUpdate(dealId)
                .orElseThrow(() -> new ResourceNotFoundException("EscrowAccount", "dealId", dealId));

        ReleaseInstruction instruction = releaseInstructionRepo.findByIdForUpdate(releaseInstructionId)
                .orElseThrow(() -> new ResourceNotFoundException("ReleaseInstruction", "id", releaseInstructionId));

        ReleaseReservation reservation = reservationRepo.findByReleaseInstructionIdForUpdate(releaseInstructionId)
                .orElseThrow(() -> new IllegalStateException("ReleaseReservation not found for instruction: " + releaseInstructionId));

        // Step 4: Sandbox finalization gate check
        finalizationGate.assertFinalizationAllowed(instruction);

        // Step 8: Read authoritative observation evidence
        AuthoritativeObservationResult obsResult = evidenceReader.getLatestAuthoritativeFinalObservation(releaseInstructionId);

        // Check for already SETTLED (idempotent replay vs reversal conflict)
        if (instruction.getStatus() == ReleaseInstructionStatus.SETTLED) {
            return handleIdempotentSettledReplay(instruction, reservation, deal, milestone, escrowAccount, obsResult);
        }

        // For fresh finalization, validate instruction status
        if (instruction.getStatus() != ReleaseInstructionStatus.PENDING_SETTLEMENT
                && instruction.getStatus() != ReleaseInstructionStatus.UNKNOWN_RECON_REQUIRED
                && instruction.getStatus() != ReleaseInstructionStatus.DISPATCHING) {
            throw new IllegalStateException("Cannot finalize ReleaseInstruction in status " + instruction.getStatus());
        }

        // Authoritative observation status must be FINAL_SUCCESS
        if (obsResult.status() != AuthoritativeStatus.FINAL_SUCCESS) {
            throw new IllegalStateException("Authoritative FINAL_SUCCESS required, but found " + obsResult.status() + ": " + obsResult.details());
        }

        SettlementObservation obs = obsResult.latestObservation();
        if (obs == null) {
            throw new IllegalStateException("No authoritative observation available for release " + releaseInstructionId);
        }

        // Validate observation identity binding
        validateObservationBinding(obs, instruction);

        // Validate execution attempt identity binding
        SettlementExecutionAttempt attempt = attemptRepo.findById(obs.getExecutionAttemptId())
                .orElseThrow(() -> new IllegalStateException("Execution attempt not found for observation: " + obs.getExecutionAttemptId()));
        validateAttemptBinding(attempt, instruction, obs);

        // Validate reservation invariants
        validateReservation(reservation, instruction, milestone, escrowAccount);

        // Validate escrow account invariants
        validateEscrowAccount(escrowAccount, instruction, deal);

        // Check-before-write effect idempotency
        if (journalEntryRepo.findByTransactionTypeAndReferenceId(LedgerTransactionType.MILESTONE_SETTLEMENT, releaseInstructionId).isPresent()) {
            throw new IllegalStateException("Journal entry already exists for non-SETTLED release instruction: " + releaseInstructionId);
        }
        if (escrowLedgerRepo.findByMilestoneIdAndTransactionType(milestoneId, TransactionType.RELEASE).isPresent()) {
            throw new IllegalStateException("Escrow RELEASE ledger row already exists for non-SETTLED release instruction: milestone " + milestoneId);
        }

        // Execute Financial Postings:
        // 1. Post balanced double-entry journal (Dr 2010 Escrow Liability / Cr 1010 Escrow Pool)
        JournalEntry journalEntry = ledgerService.postMilestoneSettlement(
                releaseInstructionId,
                dealId,
                milestoneId,
                instruction.getAmount(),
                instruction.getCurrency(),
                currentUser.getId(),
                "Trusted settlement for ReleaseInstruction " + releaseInstructionId + " UTR " + obs.getUtrNumber()
        );

        // 2. Decrement escrow balance and increment totalReleased
        BigDecimal newBalance = escrowAccount.getBalance().subtract(instruction.getAmount());
        escrowAccount.setBalance(newBalance);
        escrowAccount.setTotalReleased(escrowAccount.getTotalReleased().add(instruction.getAmount()));
        escrowRepo.save(escrowAccount);

        // 3. Create EscrowLedger RELEASE projection
        EscrowLedger escrowLedger = new EscrowLedger(
                escrowAccount.getId(),
                null,
                null,
                milestoneId,
                TransactionType.RELEASE,
                instruction.getAmount(),
                newBalance,
                "Trusted settlement release for ReleaseInstruction " + releaseInstructionId
        );
        escrowLedger = escrowLedgerRepo.save(escrowLedger);

        // 4. Consume reservation (ACTIVE -> CONSUMED)
        Instant finalizedAt = Instant.now();
        reservation.markConsumed(finalizedAt);
        reservationRepo.save(reservation);

        // 5. Mark instruction SETTLED with durable observation binding
        instruction.markSettledFromObservation(obs.getId(), currentUser.getId(), finalizedAt);
        releaseInstructionRepo.save(instruction);

        // 6. Update Milestone
        if (milestone.getAmount().compareTo(instruction.getAmount()) != 0) {
            log.warn("MILESTONE_CHANGED_AFTER_DISPATCH: milestone {} amount {} differs from settled instruction amount {}",
                    milestoneId, milestone.getAmount(), instruction.getAmount());
            auditService.logEvent(currentUser.getId(), "MILESTONE_CHANGED_AFTER_DISPATCH", "MILESTONE", milestoneId.toString(),
                    "Milestone amount " + milestone.getAmount() + " differs from settled instruction amount " + instruction.getAmount());
        }

        if (milestone.getStatus() == MilestoneStatus.APPROVED) {
            milestone.setStatus(MilestoneStatus.COMPLETED);
            milestoneRepo.save(milestone);
        } else {
            log.warn("MILESTONE_STATE_ANOMALY_AFTER_SETTLEMENT: milestone {} in status {}, expected APPROVED", milestoneId, milestone.getStatus());
            auditService.logEvent(currentUser.getId(), "MILESTONE_STATE_ANOMALY_AFTER_SETTLEMENT", "MILESTONE", milestoneId.toString(),
                    "Milestone in status " + milestone.getStatus() + " settled by trusted observation");
        }

        // 7. Update Deal if all milestones completed and not disputed/cancelled/rejected
        List<Milestone> dealMilestones = milestoneRepo.findByDealIdOrderBySequenceAsc(dealId);
        boolean allCompleted = dealMilestones.stream().allMatch(m -> m.getStatus() == MilestoneStatus.COMPLETED);
        if (allCompleted) {
            if (deal.getStatus() != DealStatus.DISPUTED
                    && deal.getStatus() != DealStatus.CANCELLED
                    && deal.getStatus() != DealStatus.REJECTED) {
                deal.setStatus(DealStatus.COMPLETED);
                dealRepo.save(deal);
            }
        }

        // Safe audit logging (IDs only, NO PII, NO account numbers)
        auditService.logEvent(currentUser.getId(), "TRUSTED_SETTLEMENT_FINALIZED", "RELEASE_INSTRUCTION",
                instruction.getId().toString(), "Finalized trusted settlement: observation=" + obs.getId() + " milestone=" + milestoneId);
        auditService.logEvent(currentUser.getId(), "RELEASE_RESERVATION_CONSUMED", "RELEASE_RESERVATION",
                reservation.getId().toString(), "Consumed release reservation for instruction " + instruction.getId());
        auditService.logEvent(currentUser.getId(), "MILESTONE_SETTLED", "MILESTONE",
                milestoneId.toString(), "Milestone marked settled from observation " + obs.getId());

        log.info("Successfully finalized trusted settlement for release {} with observation {} UTR {}",
                releaseInstructionId, obs.getId(), obs.getUtrNumber());

        return new SettlementFinalizationResult(
                releaseInstructionId,
                obs.getId(),
                journalEntry.getId(),
                escrowLedger.getId(),
                reservation.getId(),
                milestoneId,
                dealId,
                finalizedAt,
                false
        );
    }

    private SettlementFinalizationResult handleIdempotentSettledReplay(
            ReleaseInstruction instruction,
            ReleaseReservation reservation,
            Deal deal,
            Milestone milestone,
            EscrowAccount escrowAccount,
            AuthoritativeObservationResult obsResult
    ) {
        // If a subsequent observation reports REVERSED or CONFLICT, report reversal recovery
        if (obsResult.status() == AuthoritativeStatus.REVERSED || obsResult.status() == AuthoritativeStatus.CONFLICT) {
            throw new IllegalStateException("REVERSAL_RECOVERY_REQUIRED: ReleaseInstruction is already SETTLED but subsequent observation indicates reversal or conflict");
        }

        if (instruction.getFinalSettlementObservationId() == null) {
            throw new IllegalStateException("FINALIZATION_INTEGRITY_ERROR: ReleaseInstruction is SETTLED but finalSettlementObservationId is null");
        }

        if (obsResult.latestObservation() != null
                && !instruction.getFinalSettlementObservationId().equals(obsResult.latestObservation().getId())) {
            if (obsResult.status() != SettlementObservationEvidenceReader.AuthoritativeStatus.FINAL_SUCCESS
                    || obsResult.latestObservation().getFinality() != SettlementObservationFinality.FINAL_SUCCESS) {
                throw new IllegalStateException("FINALIZATION_INTEGRITY_ERROR: ReleaseInstruction is already settled with a different observation: expected "
                        + instruction.getFinalSettlementObservationId() + ", latest " + obsResult.latestObservation().getId());
            }
        }

        if (reservation.getStatus() != ReleaseReservationStatus.CONSUMED) {
            throw new IllegalStateException("FINALIZATION_INTEGRITY_ERROR: Release is SETTLED but reservation is not CONSUMED (status=" + reservation.getStatus() + ")");
        }

        JournalEntry existingJournal = journalEntryRepo.findByTransactionTypeAndReferenceId(
                LedgerTransactionType.MILESTONE_SETTLEMENT,
                instruction.getId()
        ).orElseThrow(() -> new IllegalStateException("FINALIZATION_INTEGRITY_ERROR: Release is SETTLED but MILESTONE_SETTLEMENT journal entry is missing"));

        EscrowLedger existingEscrowLedger = escrowLedgerRepo.findByMilestoneIdAndTransactionType(
                milestone.getId(),
                TransactionType.RELEASE
        ).orElseThrow(() -> new IllegalStateException("FINALIZATION_INTEGRITY_ERROR: Release is SETTLED but EscrowLedger RELEASE row is missing"));

        log.info("Idempotent settlement finalization replay for release {}", instruction.getId());

        return new SettlementFinalizationResult(
                instruction.getId(),
                instruction.getFinalSettlementObservationId(),
                existingJournal.getId(),
                existingEscrowLedger.getId(),
                reservation.getId(),
                milestone.getId(),
                deal.getId(),
                instruction.getSettledAt(),
                true
        );
    }

    private void validateObservationBinding(SettlementObservation obs, ReleaseInstruction instruction) {
        if (!obs.getReleaseInstructionId().equals(instruction.getId())) {
            throw new IllegalStateException("Observation releaseInstructionId '" + obs.getReleaseInstructionId()
                    + "' does not match release instruction '" + instruction.getId() + "'");
        }
        if (obs.getExecutionAttemptId() == null) {
            throw new IllegalStateException("Observation executionAttemptId is null");
        }
        if (!obs.getProvider().equalsIgnoreCase(instruction.getProvider())) {
            throw new IllegalStateException("Observation provider '" + obs.getProvider()
                    + "' does not match release instruction provider '" + instruction.getProvider() + "'");
        }
        if (obs.getProviderSettlementId() == null || obs.getProviderSettlementId().isBlank()) {
            throw new IllegalStateException("Observation requires non-blank providerSettlementId");
        }
        if (obs.getUtrNumber() == null || obs.getUtrNumber().isBlank()) {
            throw new IllegalStateException("Observation requires non-blank UTR");
        }
        if (obs.getProviderAccountId() != null && !obs.getProviderAccountId().equals(instruction.getProviderAccountId())) {
            throw new IllegalStateException("Observation providerAccountId '" + obs.getProviderAccountId()
                    + "' does not match release instruction providerAccountId '" + instruction.getProviderAccountId() + "'");
        }
        if (obs.getObservedAmount() != null && obs.getObservedAmount().compareTo(instruction.getAmount()) != 0) {
            throw new IllegalStateException("Observation observedAmount '" + obs.getObservedAmount()
                    + "' does not match release instruction amount '" + instruction.getAmount() + "'");
        }
        if (obs.getObservedCurrency() != null && !obs.getObservedCurrency().equalsIgnoreCase(instruction.getCurrency())) {
            throw new IllegalStateException("Observation observedCurrency '" + obs.getObservedCurrency()
                    + "' does not match release instruction currency '" + instruction.getCurrency() + "'");
        }
        if (obs.getObservedDirection() != null && !"DEBIT".equalsIgnoreCase(obs.getObservedDirection())) {
            throw new IllegalStateException("Observation observedDirection must be DEBIT, got '" + obs.getObservedDirection() + "'");
        }
    }

    private void validateAttemptBinding(SettlementExecutionAttempt attempt, ReleaseInstruction instruction, SettlementObservation obs) {
        if (!attempt.getReleaseInstructionId().equals(instruction.getId())) {
            throw new IllegalStateException("Attempt releaseInstructionId mismatch");
        }
        if (!attempt.getProvider().equalsIgnoreCase(instruction.getProvider())) {
            throw new IllegalStateException("Attempt provider mismatch");
        }
        if (!attempt.getProviderOperationKey().equals(obs.getProviderOperationKey())) {
            throw new IllegalStateException("Attempt providerOperationKey '" + attempt.getProviderOperationKey()
                    + "' does not match execution attempt key / observation key '" + obs.getProviderOperationKey() + "'");
        }
        if (attempt.getAmount().compareTo(instruction.getAmount()) != 0) {
            throw new IllegalStateException("Attempt amount mismatch with instruction");
        }
        if (!attempt.getCurrency().equalsIgnoreCase(instruction.getCurrency())) {
            throw new IllegalStateException("Attempt currency mismatch with instruction");
        }
        if (!attempt.getBeneficiaryBankAccountId().equals(instruction.getBeneficiaryBankAccountId())) {
            throw new IllegalStateException("Attempt beneficiaryBankAccountId mismatch with instruction");
        }
        if (attempt.getProviderSettlementId() != null
                && !attempt.getProviderSettlementId().equals(obs.getProviderSettlementId())) {
            throw new IllegalStateException("Attempt providerSettlementId mismatch with observation");
        }
    }

    private void validateReservation(ReleaseReservation reservation, ReleaseInstruction instruction, Milestone milestone, EscrowAccount escrowAccount) {
        if (reservation.getStatus() != ReleaseReservationStatus.ACTIVE) {
            throw new IllegalStateException("Fresh finalization requires ACTIVE reservation: expected ACTIVE but found " + reservation.getStatus());
        }
        if (!reservation.getMilestoneId().equals(milestone.getId())) {
            throw new IllegalStateException("Reservation milestoneId mismatch: expected " + milestone.getId() + ", got " + reservation.getMilestoneId());
        }
        if (!reservation.getEscrowAccountId().equals(escrowAccount.getId())) {
            throw new IllegalStateException("Reservation escrowAccountId mismatch: expected " + escrowAccount.getId() + ", got " + reservation.getEscrowAccountId());
        }
        if (reservation.getAmount().compareTo(instruction.getAmount()) != 0) {
            throw new IllegalStateException("Reservation amount " + reservation.getAmount() + " does not match release instruction amount " + instruction.getAmount());
        }
        if (!reservation.getCurrency().equalsIgnoreCase(instruction.getCurrency())) {
            throw new IllegalStateException("Reservation currency mismatch: reservation=" + reservation.getCurrency() + ", instruction=" + instruction.getCurrency());
        }
    }

    private void validateEscrowAccount(EscrowAccount escrowAccount, ReleaseInstruction instruction, Deal deal) {
        if (!escrowAccount.getDealId().equals(deal.getId())) {
            throw new IllegalStateException("Escrow account dealId mismatch");
        }
        if (!escrowAccount.getCurrency().equalsIgnoreCase(instruction.getCurrency())) {
            throw new IllegalStateException("Escrow currency " + escrowAccount.getCurrency() + " does not match release currency " + instruction.getCurrency());
        }
        if (escrowAccount.getBalance().compareTo(instruction.getAmount()) < 0) {
            throw new IllegalStateException("Insufficient escrow balance: available " + escrowAccount.getBalance() + ", required " + instruction.getAmount());
        }
    }
}
