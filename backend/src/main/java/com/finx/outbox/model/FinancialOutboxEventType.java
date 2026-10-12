package com.finx.outbox.model;

/**
 * Event types supported by the FinX Financial Outbox.
 * For Phase E3, strictly limited to RELEASE_DISPATCH_REQUESTED.
 */
public enum FinancialOutboxEventType {
    /**
     * Durable request to dispatch an authorized ReleaseInstruction to the banking provider.
     */
    RELEASE_DISPATCH_REQUESTED
}
