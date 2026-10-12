package com.finx.outbox.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Pure helper for generating stable, deterministic business operation identities.
 * Zero database calls, zero randomness, zero timestamps.
 */
public final class FinancialOperationKey {

    private FinancialOperationKey() {
        // Utility class
    }

    /**
     * Produces deterministic operation identity for a release dispatch command.
     * Guaranteed stable across retries and crashes.
     */
    public static String releaseDispatch(UUID releaseInstructionId) {
        Objects.requireNonNull(releaseInstructionId, "releaseInstructionId must not be null");
        return releaseInstructionId.toString();
    }
}
