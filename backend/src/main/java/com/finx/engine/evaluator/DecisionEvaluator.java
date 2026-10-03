package com.finx.engine.evaluator;

import com.finx.engine.model.EngineType;
import com.finx.engine.model.EvaluationContext;
import com.finx.engine.model.RecordEngineDecisionCommand;

/**
 * Small SPI contract for future read-only engine evaluators.
 * Implementations evaluate a specific domain engine in isolation
 * and return an immutable decision command envelope.
 *
 * NOTE: Individual business engine implementations are deferred;
 * this interface provides only the common SPI contract.
 */
public interface DecisionEvaluator {

    /**
     * Returns the engine type supported by this evaluator.
     */
    EngineType type();

    /**
     * Evaluates the subject within the given context.
     * Evaluators must never execute banking or state mutations.
     *
     * @param context evaluation identity and scope
     * @return immutable command ready to be recorded
     */
    RecordEngineDecisionCommand evaluate(EvaluationContext context);
}
