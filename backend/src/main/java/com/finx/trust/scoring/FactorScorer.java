package com.finx.trust.scoring;

/** Stable extension point: future models can replace a factor without changing the API. */
public interface FactorScorer {
    double score(double weightedTotal, double evidenceWeight, double priorWeight);
}
