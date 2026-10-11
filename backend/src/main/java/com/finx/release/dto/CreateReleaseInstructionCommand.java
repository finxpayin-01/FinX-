package com.finx.release.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable command to initiate ReleaseInstruction creation from an authoritative EngineDecision(RELEASE, ALLOW).
 * Financial and routing fields (amount, currency, beneficiary, provider, providerAccountId, dealId, milestoneId)
 * are strictly derived from authoritative persisted records and cannot be supplied by the caller.
 */
public record CreateReleaseInstructionCommand(
        UUID releaseDecisionId,
        String idempotencyKey
) {
    public CreateReleaseInstructionCommand {
        Objects.requireNonNull(releaseDecisionId, "releaseDecisionId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        String trimmed = idempotencyKey.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        idempotencyKey = trimmed;
    }
}
