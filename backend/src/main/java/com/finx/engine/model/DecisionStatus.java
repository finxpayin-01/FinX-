package com.finx.engine.model;

/**
 * Fundamental evaluation outcome of an engine decision.
 * Evaluation results ONLY — not financial or bank lifecycle states.
 */
public enum DecisionStatus {
    ALLOW,
    REVIEW,
    BLOCK
}
