package com.finx.release.controller.dto;

import java.time.Instant;

/**
 * High-level aggregate operational status summary for FinX release execution.
 * Contains zero PII, zero credentials, and zero account numbers.
 */
public record ReleaseOperationalSummaryDto(
        long outboxPending,
        long outboxRetry,
        long outboxLeased,
        long outboxHeld,
        long outboxDeadLetter,
        double oldestPendingAgeSeconds,
        long attemptsPrepared,
        long attemptsSending,
        long attemptsUnknown,
        long attemptsAcknowledged,
        long pendingSettlementCount,
        double oldestPendingSettlementAgeSeconds,
        long settledCount,
        long observationsConflict,
        long observationsReversed,
        long reconciliationReview,
        long reconciliationBlocked,
        long reconciliationReversalRequired,
        boolean providerReadySandbox,
        boolean providerReadyProduction,
        boolean productionGateOpen,
        Instant generatedAt
) {}
