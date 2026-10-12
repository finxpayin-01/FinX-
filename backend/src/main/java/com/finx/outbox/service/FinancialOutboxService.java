package com.finx.outbox.service;

import com.finx.common.time.FinxClock;
import com.finx.outbox.entity.FinancialOutboxMessage;
import com.finx.outbox.model.FinancialOperationKey;
import com.finx.outbox.model.FinancialOutboxEventType;
import com.finx.outbox.repository.FinancialOutboxRepository;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.model.ReleaseInstructionStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/**
 * Internal-only service for enqueuing financial outbox commands.
 * Strictly enforces Propagation.MANDATORY — outbox messages cannot be created
 * outside an active financial authorization transaction (TX2).
 */
@Service
public class FinancialOutboxService {

    private final FinancialOutboxRepository repository;
    private final FinxClock clock;

    public FinancialOutboxService(FinancialOutboxRepository repository, FinxClock clock) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public FinancialOutboxMessage enqueueReleaseDispatch(ReleaseInstruction instruction) {
        Objects.requireNonNull(instruction, "instruction must not be null");
        if (instruction.getId() == null) {
            throw new IllegalArgumentException("instruction id must not be null");
        }
        if (instruction.getStatus() != ReleaseInstructionStatus.RESERVED) {
            throw new IllegalStateException("Release dispatch outbox command requires RESERVED instruction status. Current status: " + instruction.getStatus());
        }

        String operationKey = FinancialOperationKey.releaseDispatch(instruction.getId());

        // 1. Check existing outbox message for this instruction and event type
        Optional<FinancialOutboxMessage> existingForInstruction = repository.findByReleaseInstructionIdAndEventType(
                instruction.getId(),
                FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED
        );

        if (existingForInstruction.isPresent()) {
            FinancialOutboxMessage existing = existingForInstruction.get();
            if (existing.getReleaseInstructionId().equals(instruction.getId())
                    && existing.getEventType() == FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED
                    && existing.getOperationKey().equals(operationKey)
                    && existing.getPayloadVersion() == 1) {
                return existing;
            }
            throw new IllegalStateException("OUTBOX_IDENTITY_CONFLICT: Existing outbox event for instruction "
                    + instruction.getId() + " does not match expected identity");
        }

        // 2. Check for conflicting row with same operation key
        Optional<FinancialOutboxMessage> existingForKey = repository.findByEventTypeAndOperationKey(
                FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED,
                operationKey
        );

        if (existingForKey.isPresent()) {
            FinancialOutboxMessage existing = existingForKey.get();
            if (!existing.getReleaseInstructionId().equals(instruction.getId())) {
                throw new IllegalStateException("OUTBOX_IDENTITY_CONFLICT: Operation key " + operationKey
                        + " is bound to a different release instruction: " + existing.getReleaseInstructionId());
            }
            // If it has same instruction ID, it would have been found in step 1, but fail closed on any discrepancy
            throw new IllegalStateException("OUTBOX_IDENTITY_CONFLICT: Duplicate operation key " + operationKey + " detected");
        }

        FinancialOutboxMessage message = FinancialOutboxMessage.createReleaseDispatch(instruction, clock.now());
        return repository.save(message);
    }
}
