package com.finx.release.execution.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable safe work item produced by E4 worker preparation.
 * Contains only canonical entity and operation identifiers.
 * Strictly identifier-only: NO beneficiary account numbers, NO IFSC, NO raw provider credentials.
 */
public record PreparedSettlementWorkItem(
        UUID outboxMessageId,
        UUID releaseInstructionId,
        UUID executionAttemptId,
        String operationKey,
        String providerOperationKey,
        String workerId
) {
    public PreparedSettlementWorkItem {
        Objects.requireNonNull(outboxMessageId, "outboxMessageId must not be null");
        Objects.requireNonNull(releaseInstructionId, "releaseInstructionId must not be null");
        Objects.requireNonNull(executionAttemptId, "executionAttemptId must not be null");
        Objects.requireNonNull(operationKey, "operationKey must not be null");
        Objects.requireNonNull(providerOperationKey, "providerOperationKey must not be null");
        Objects.requireNonNull(workerId, "workerId must not be null");
    }
}
