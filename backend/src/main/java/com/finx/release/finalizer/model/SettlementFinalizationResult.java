package com.finx.release.finalizer.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable safe result of trusted settlement finalization.
 * Exposes internal ledger and escrow references.
 * Strictly identifier-only: NO bank accounts, NO IFSC, NO credentials.
 */
public record SettlementFinalizationResult(
        UUID releaseInstructionId,
        UUID settlementObservationId,
        UUID journalEntryId,
        UUID escrowLedgerId,
        UUID reservationId,
        UUID milestoneId,
        UUID dealId,
        Instant finalizedAt,
        boolean idempotentReplay
) {}
