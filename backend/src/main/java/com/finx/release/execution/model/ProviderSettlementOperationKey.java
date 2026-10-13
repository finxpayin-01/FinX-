package com.finx.release.execution.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Pure deterministic provider operation key generator.
 * Provides stable financial idempotency identity across retries, worker crashes, and transport attempts.
 * Zero timestamps, zero random UUIDs, zero database access.
 */
public final class ProviderSettlementOperationKey {

    public static final String PREFIX = "FINX-REL-";

    private ProviderSettlementOperationKey() {
        // Pure utility class
    }

    public static String release(UUID releaseInstructionId) {
        Objects.requireNonNull(releaseInstructionId, "releaseInstructionId must not be null");
        return PREFIX + releaseInstructionId;
    }
}
