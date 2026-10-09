package com.finx.reconciliation.dto;

import com.finx.reconciliation.entity.ReconciliationItem;
import com.finx.reconciliation.entity.ReconciliationRun;

import java.util.Collections;
import java.util.List;

/**
 * Result of a deterministic reconciliation matcher execution.
 *
 * Note: clean is true ONLY if status == COMPLETED, mismatchCount == 0,
 * and matcherVersion == FINX_RECONCILIATION_MATCHER_V1.
 */
public record DeterministicReconciliationResult(
        ReconciliationRun run,
        boolean clean,
        List<ReconciliationItem> items
) {
    public DeterministicReconciliationResult {
        items = items != null ? Collections.unmodifiableList(items) : Collections.emptyList();
    }
}
