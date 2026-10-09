package com.finx.release.model;

/**
 * Execution lifecycle states for a ReleaseInstruction.
 * Strictly decoupled from advisory EngineDecision states (ALLOW/REVIEW/BLOCK).
 */
public enum ReleaseInstructionStatus {
    /**
     * Intent to release created and bound to an approved EngineDecision.
     */
    CREATED,

    /**
     * Authorized by authorized platform role (e.g. FINANCE_ADMIN).
     */
    AUTHORIZED,

    /**
     * Escrow funds successfully reserved via durable ReleaseReservation.
     */
    RESERVED,

    /**
     * Enqueued into transactional financial outbox for bank dispatch.
     */
    QUEUED,

    /**
     * Leased by execution worker and currently dispatching to banking provider.
     */
    DISPATCHING,

    /**
     * Successfully transmitted to banking provider; transport acknowledgement received.
     */
    SUBMITTED,

    /**
     * Awaiting trusted settlement observation (webhook, status poll, statement recon).
     */
    PENDING_SETTLEMENT,

    /**
     * Transport timed out or status ambiguous; requires deterministic reconciliation before retry.
     */
    UNKNOWN_RECON_REQUIRED,

    /**
     * Execution placed on hold for manual platform operator review.
     */
    MANUAL_REVIEW,

    /**
     * Trusted bank finality observed and double-entry ledger/escrow finalization completed.
     */
    SETTLED,

    /**
     * Terminal failure (provider rejection, permanent error, or aborted).
     */
    FAILED,

    /**
     * Instruction cancelled prior to bank dispatch; reservation released.
     */
    CANCELLED;

    public boolean isActive() {
        return this != SETTLED && this != FAILED && this != CANCELLED;
    }

    public boolean isTerminal() {
        return this == SETTLED || this == FAILED || this == CANCELLED;
    }
}
