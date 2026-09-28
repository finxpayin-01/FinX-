package com.finx.trust.model;

public enum TrustLevel {
    HIGHLY_TRUSTED, TRUSTED, MODERATE, ELEVATED_RISK, HIGH_RISK, INSUFFICIENT_DATA;

    public static TrustLevel of(double score, boolean sufficient) {
        if (!sufficient) return INSUFFICIENT_DATA;
        if (score >= 90) return HIGHLY_TRUSTED;
        if (score >= 75) return TRUSTED;
        if (score >= 60) return MODERATE;
        if (score >= 40) return ELEVATED_RISK;
        return HIGH_RISK;
    }
}
