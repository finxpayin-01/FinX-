package com.finx.release.execution.service;

import com.finx.common.time.FinxClock;
import com.finx.outbox.entity.FinancialOutboxMessage;
import com.finx.outbox.model.FinancialOutboxEventType;
import com.finx.outbox.model.FinancialOutboxStatus;
import com.finx.outbox.repository.FinancialOutboxRepository;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.entity.ReleaseReservation;
import com.finx.release.execution.entity.SettlementExecutionAttempt;
import com.finx.release.execution.model.ProviderSettlementOperationKey;
import com.finx.release.execution.model.SettlementExecutionAttemptStatus;
import com.finx.release.execution.repository.SettlementExecutionAttemptRepository;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.model.ReleaseReservationStatus;
import com.finx.release.repository.ReleaseInstructionRepository;
import com.finx.release.repository.ReleaseReservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service managing durable settlement execution attempt lifecycle.
 * Prepares immutable PREPARED execution attempt snapshots from canonical ReleaseInstruction state.
 * Strictly identifier-only: ZERO bank calls, zero ledger postings, zero escrow mutations.
 */
@Service
public class SettlementExecutionAttemptService {

    private final FinancialOutboxRepository outboxRepository;
    private final SettlementExecutionAttemptRepository attemptRepository;
    private final ReleaseInstructionRepository instructionRepository;
    private final ReleaseReservationRepository reservationRepository;
    private final FinxClock clock;

    public SettlementExecutionAttemptService(
            FinancialOutboxRepository outboxRepository,
            SettlementExecutionAttemptRepository attemptRepository,
            ReleaseInstructionRepository instructionRepository,
            ReleaseReservationRepository reservationRepository,
            FinxClock clock
    ) {
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository must not be null");
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository must not be null");
        this.instructionRepository = Objects.requireNonNull(instructionRepository, "instructionRepository must not be null");
        this.reservationRepository = Objects.requireNonNull(reservationRepository, "reservationRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Prepares a durable PREPARED execution attempt for a leased outbox message.
     * Reuses an existing PREPARED attempt if valid (crash recovery).
     * Fails closed if the attempt state requires recovery or if any snapshot mismatch exists.
     */
    @Transactional
    public SettlementExecutionAttempt prepareAttempt(UUID outboxMessageId, String workerId) {
        Objects.requireNonNull(outboxMessageId, "outboxMessageId must not be null");
        Objects.requireNonNull(workerId, "workerId must not be null");
        if (workerId.trim().isEmpty() || workerId.length() > 128) {
            throw new IllegalArgumentException("workerId must be non-blank and <= 128 characters: " + workerId);
        }

        // 1. Load outbox message with pessimistic lock
        FinancialOutboxMessage outbox = outboxRepository.findByIdForUpdate(outboxMessageId)
                .orElseThrow(() -> new IllegalArgumentException("Outbox message not found: " + outboxMessageId));

        if (outbox.getStatus() != FinancialOutboxStatus.LEASED) {
            throw new IllegalStateException("Outbox message must be in LEASED status to prepare attempt, found: "
                    + outbox.getStatus());
        }

        if (!workerId.equals(outbox.getLockedBy())) {
            throw new IllegalStateException("Outbox message is leased by " + outbox.getLockedBy()
                    + ", but preparation requested by " + workerId);
        }

        if (outbox.getEventType() != FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED) {
            throw new IllegalStateException("Unsupported outbox event type for settlement attempt: "
                    + outbox.getEventType());
        }

        if (!outbox.getOperationKey().equals(outbox.getReleaseInstructionId().toString())) {
            throw new IllegalStateException("Outbox operation key mismatch: key=" + outbox.getOperationKey()
                    + ", instructionId=" + outbox.getReleaseInstructionId());
        }

        // 2. Load canonical ReleaseInstruction (must be QUEUED)
        ReleaseInstruction instruction = instructionRepository.findById(outbox.getReleaseInstructionId())
                .orElseThrow(() -> new IllegalStateException("Referenced ReleaseInstruction not found: "
                        + outbox.getReleaseInstructionId()));

        if (instruction.getStatus() != ReleaseInstructionStatus.QUEUED) {
            throw new IllegalStateException("ReleaseInstruction must be in QUEUED status to prepare attempt, found: "
                    + instruction.getStatus());
        }

        // 3. Verify exactly one consistent ACTIVE reservation
        ReleaseReservation reservation = reservationRepository.findByReleaseInstructionId(instruction.getId())
                .orElseThrow(() -> new IllegalStateException("ACTIVE reservation required for release instruction: "
                        + instruction.getId()));

        if (reservation.getStatus() != ReleaseReservationStatus.ACTIVE) {
            throw new IllegalStateException("Release reservation must be ACTIVE, found: " + reservation.getStatus());
        }

        if (reservation.getAmount().compareTo(instruction.getAmount()) != 0) {
            throw new IllegalStateException("Reservation amount mismatch: res=" + reservation.getAmount()
                    + ", ins=" + instruction.getAmount());
        }

        if (!reservation.getCurrency().equalsIgnoreCase(instruction.getCurrency())) {
            throw new IllegalStateException("Reservation currency mismatch: res=" + reservation.getCurrency()
                    + ", ins=" + instruction.getCurrency());
        }

        String expectedProviderOpKey = ProviderSettlementOperationKey.release(instruction.getId());

        // 4. Inspect latest existing attempt for idempotency and crash recovery
        Optional<SettlementExecutionAttempt> latestOpt =
                attemptRepository.findFirstByOutboxMessageIdOrderByAttemptNumberDesc(outboxMessageId);

        if (latestOpt.isPresent()) {
            SettlementExecutionAttempt latest = latestOpt.get();
            if (latest.getStatus() == SettlementExecutionAttemptStatus.PREPARED) {
                // Validate snapshot consistency
                validateSnapshotMatches(latest, instruction, outbox, expectedProviderOpKey);
                return latest; // Idempotent reuse of pre-crash PREPARED attempt
            }

            if (latest.getStatus() == SettlementExecutionAttemptStatus.SENDING
                    || latest.getStatus() == SettlementExecutionAttemptStatus.ACKNOWLEDGED
                    || latest.getStatus() == SettlementExecutionAttemptStatus.UNKNOWN) {
                throw new IllegalStateException("EXECUTION_OUTCOME_RECOVERY_REQUIRED: Latest attempt status is "
                        + latest.getStatus());
            }

            if (latest.getStatus() == SettlementExecutionAttemptStatus.TRANSPORT_FAILED
                    || latest.getStatus() == SettlementExecutionAttemptStatus.TERMINAL_FAILED) {
                throw new IllegalStateException("UNSUPPORTED_ATTEMPT_STATE_FOR_PREPARATION: Latest attempt is "
                        + latest.getStatus());
            }
        }

        // 5. Create new PREPARED attempt
        int nextAttemptNumber = attemptRepository.maxAttemptNumberForOutbox(outboxMessageId) + 1;
        Instant now = clock.now();

        SettlementExecutionAttempt newAttempt = new SettlementExecutionAttempt(
                UUID.randomUUID(),
                instruction.getId(),
                outbox.getId(),
                nextAttemptNumber,
                outbox.getOperationKey(),
                expectedProviderOpKey,
                instruction.getProvider(),
                instruction.getProviderAccountId(),
                instruction.getBeneficiaryBankAccountId(),
                instruction.getAmount(),
                instruction.getCurrency(),
                instruction.getRequestHash(),
                workerId,
                now
        );

        return attemptRepository.save(newAttempt);
    }

    private void validateSnapshotMatches(
            SettlementExecutionAttempt attempt,
            ReleaseInstruction instruction,
            FinancialOutboxMessage outbox,
            String expectedProviderOpKey
    ) {
        boolean match = attempt.getReleaseInstructionId().equals(instruction.getId())
                && attempt.getOutboxMessageId().equals(outbox.getId())
                && attempt.getOperationKey().equals(outbox.getOperationKey())
                && attempt.getProviderOperationKey().equals(expectedProviderOpKey)
                && attempt.getProvider().equals(instruction.getProvider())
                && attempt.getProviderAccountId().equals(instruction.getProviderAccountId())
                && attempt.getBeneficiaryBankAccountId().equals(instruction.getBeneficiaryBankAccountId())
                && attempt.getAmount().compareTo(instruction.getAmount()) == 0
                && attempt.getCurrency().equals(instruction.getCurrency())
                && attempt.getRequestHash().equals(instruction.getRequestHash());

        if (!match) {
            throw new IllegalStateException("EXECUTION_ATTEMPT_SNAPSHOT_CONFLICT: Existing PREPARED attempt "
                    + attempt.getId() + " does not match current instruction snapshot");
        }
    }
}
