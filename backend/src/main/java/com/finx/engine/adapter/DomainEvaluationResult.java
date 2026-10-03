package com.finx.engine.adapter;

import com.finx.engine.model.RecordEngineDecisionCommand;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable envelope containing individual domain decision commands from a coordinated evaluation cycle.
 * <p>
 * STRICT SAFETY BOUNDARY:
 * - This record holds individual commands ONLY.
 * - It DOES NOT aggregate results into a combined score or RELEASE decision.
 * - It DOES NOT make financial authorization or money movement decisions.
 */
public record DomainEvaluationResult(
        UUID evaluationId,
        UUID correlationId,
        RecordEngineDecisionCommand buyerRiskCommand,
        RecordEngineDecisionCommand vendorRiskCommand,
        RecordEngineDecisionCommand documentCommand
) {
    public DomainEvaluationResult {
        Objects.requireNonNull(evaluationId, "evaluationId must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
    }
}
