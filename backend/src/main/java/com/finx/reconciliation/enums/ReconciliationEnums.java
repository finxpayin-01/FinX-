package com.finx.reconciliation.enums;

public class ReconciliationEnums {

    public enum ReconciliationRunStatus {
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }

    public enum ReconciliationItemStatus {
        MATCHED,
        MISSING_INTERNAL,
        MISSING_EXTERNAL,
        AMOUNT_MISMATCH,
        STATUS_MISMATCH,
        DUPLICATE,
        REQUIRES_REVIEW
    }

    public enum ResolutionStatus {
        UNRESOLVED,
        RESOLVED,
        IGNORED
    }
}
