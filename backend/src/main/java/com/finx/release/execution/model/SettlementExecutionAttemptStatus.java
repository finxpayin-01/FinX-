package com.finx.release.execution.model;

/**
 * Lifecycle status for durable settlement execution attempts.
 * E4 creates only PREPARED. Subsequent states support future phases (E5/E6).
 */
public enum SettlementExecutionAttemptStatus {
    PREPARED,
    SENDING,
    ACKNOWLEDGED,
    TRANSPORT_FAILED,
    UNKNOWN,
    TERMINAL_FAILED
}
