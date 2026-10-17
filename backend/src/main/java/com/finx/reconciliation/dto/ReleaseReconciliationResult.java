package com.finx.reconciliation.dto;

import com.finx.reconciliation.model.ReleaseReconciliationStatus;

import java.util.UUID;

/**
 * Immutable result model returned by ReleaseReconciliationEvidenceReader.
 * Contains safe identifiers and machine-readable reconciliation status.
 * Contains zero sensitive bank account details or raw payloads.
 */
public record ReleaseReconciliationResult(
        UUID releaseInstructionId,
        ReleaseReconciliationStatus status,
        UUID reconciliationRunId,
        UUID reconciliationItemId,
        UUID bankStatementLineId,
        UUID bankStatementImportId,
        String reason
) {
    public ReleaseReconciliationStatus getStatus() { return status; }
    public UUID getReleaseInstructionId() { return releaseInstructionId; }
    public UUID getReconciliationRunId() { return reconciliationRunId; }
    public UUID getReconciliationItemId() { return reconciliationItemId; }
    public UUID getBankStatementLineId() { return bankStatementLineId; }
    public UUID getBankStatementImportId() { return bankStatementImportId; }
    public String getReason() { return reason; }
}

