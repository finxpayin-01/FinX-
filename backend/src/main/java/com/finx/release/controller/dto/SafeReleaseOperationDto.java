package com.finx.release.controller.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Sanitized operational view of a release instruction and its associated lifecycle states.
 * Strictly identifier-only: NO raw or encrypted bank accounts, NO IFSC, NO credentials.
 */
public record SafeReleaseOperationDto(
        UUID releaseInstructionId,
        UUID dealId,
        UUID milestoneId,
        String status,
        BigDecimal amount,
        String currency,
        String provider,
        Instant createdAt,
        Instant updatedAt,
        Instant authorizedAt,
        Instant settledAt,
        String outboxStatus,
        String attemptStatus,
        String observationFinality,
        String reconciliationStatus,
        String reasonCode
) {}
