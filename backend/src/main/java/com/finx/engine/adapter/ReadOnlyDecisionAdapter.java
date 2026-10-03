package com.finx.engine.adapter;

import com.finx.engine.evaluator.DecisionEvaluator;
import com.finx.engine.model.EngineType;
import com.finx.engine.model.EvaluationContext;
import com.finx.engine.model.RecordEngineDecisionCommand;

/**
 * Common read-only adapter contract for domain decision evaluators.
 * <p>
 * Guarantees:
 * - Read-only inspection (zero writes, zero mutations to underlying domain entities)
 * - Tenant-scoped evaluation
 * - Strictly zero banking, ledger, escrow, or payment side effects
 * - Strictly zero recalculation side effects
 */
public interface ReadOnlyDecisionAdapter extends DecisionEvaluator {

    /**
     * The engine type evaluated by this adapter.
     */
    @Override
    EngineType type();

    /**
     * Evaluates domain evidence for the given context and returns an immutable decision command.
     * Must never perform side effects or mutate domain entities.
     *
     * @param context evaluation identity and scope
     * @return immutable command ready to be recorded
     */
    @Override
    RecordEngineDecisionCommand evaluate(EvaluationContext context);
}
