package com.finx.engine.release;

import com.finx.engine.entity.EngineDecision;
import com.finx.engine.model.RecordEngineDecisionCommand;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable envelope containing the evaluated RELEASE engine recommendation
 * alongside the eight underlying prerequisite component decisions.
 * <p>
 * STRICT SAFETY BOUNDARY:
 * - Pure recommendation envelope (EngineDecision with EngineType.RELEASE).
 * - NOT payment authorization or money movement dispatch.
 * - Zero execution capabilities, zero side-effects on ledger/escrow/banking.
 */
public record ReleaseEvaluationResult(
        UUID evaluationId,
        UUID correlationId,
        EngineDecision releaseDecision,
        List<EngineDecision> componentDecisions,
        RecordEngineDecisionCommand releaseCommand,
        List<RecordEngineDecisionCommand> componentCommands
) {
    public ReleaseEvaluationResult {
        Objects.requireNonNull(evaluationId, "evaluationId must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        componentDecisions = componentDecisions != null ? List.copyOf(componentDecisions) : Collections.emptyList();
        componentCommands = componentCommands != null ? List.copyOf(componentCommands) : Collections.emptyList();
    }
}
