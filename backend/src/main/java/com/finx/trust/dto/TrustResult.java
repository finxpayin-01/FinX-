package com.finx.trust.dto;

import com.finx.trust.model.*;
import java.time.Instant;
import java.util.*;

public record TrustResult(UUID organizationId, TrustRole role, double overallScore,
        double confidenceScore, TrustLevel trustLevel, Map<TrustFactor, Component> factors,
        List<String> positiveSignals, List<String> negativeSignals, Map<String, Long> statistics,
        String explanation, String warning, String algorithmVersion, long configVersion,
        long dataPoints, String inputDigest, Instant lastCalculatedAt) {
    public record Component(double score, double weight, long observations, double effectiveEvidence) {}
}
