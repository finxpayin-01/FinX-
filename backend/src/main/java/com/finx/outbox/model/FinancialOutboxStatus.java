package com.finx.outbox.model;

/**
 * Lifecycle states of an outbox message.
 * Phase E3 creates and verifies PENDING.
 * Other states represent schema and model preparation for the Phase E4 worker.
 */
public enum FinancialOutboxStatus {
    /**
     * Freshly enqueued, available for worker leasing.
     */
    PENDING,

    /**
     * Leased by an active worker node via SELECT FOR UPDATE SKIP LOCKED.
     */
    LEASED,

    /**
     * Transient failure observed; scheduled for future retry attempt.
     */
    RETRY,

    /**
     * Administratively placed on hold by ADMIN/FINANCE prior to network dispatch.
     */
    HELD,

    /**
     * Dispatch completed and terminal result observed.
     */
    PROCESSED,

    /**
     * Exhausted retry threshold or encountered permanent rejection.
     */
    DEAD_LETTER
}
