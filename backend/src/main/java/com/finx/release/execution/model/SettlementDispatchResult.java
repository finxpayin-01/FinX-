package com.finx.release.execution.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Result of dispatching a prepared settlement work item.
 */
public record SettlementDispatchResult(
        UUID executionAttemptId,
        SettlementExecutionAttemptStatus status,
        String providerSettlementId,
        String providerStatus,
        String errorCode,
        Instant timestamp
) {
    public SettlementDispatchResult {
        Objects.requireNonNull(executionAttemptId, "executionAttemptId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
    }

    public static SettlementDispatchResult acknowledged(UUID attemptId, String providerSettlementId, String providerStatus, Instant timestamp) {
        return new SettlementDispatchResult(attemptId, SettlementExecutionAttemptStatus.ACKNOWLEDGED, providerSettlementId, providerStatus, null, timestamp);
    }

    public static SettlementDispatchResult unknown(UUID attemptId, String errorCode, Instant timestamp) {
        return new SettlementDispatchResult(attemptId, SettlementExecutionAttemptStatus.UNKNOWN, null, null, errorCode, timestamp);
    }
}
