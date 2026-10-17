package com.finx.reconciliation.model;

/**
 * Authoritative statuses for closed-loop modern release payout reconciliation against bank statement evidence.
 */
public enum ReleaseReconciliationStatus {
    CONFIRMED,
    REVIEW,
    BLOCKED,
    REVERSAL_RECOVERY_REQUIRED,
    NO_EVIDENCE
}
