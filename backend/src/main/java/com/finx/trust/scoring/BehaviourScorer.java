package com.finx.trust.scoring;

public class BehaviourScorer implements FactorScorer {
    @Override public double score(double total, double weight, double prior) {
        return Math.max(0, Math.min(100, (50 * prior + total) / (prior + weight)));
    }
}
