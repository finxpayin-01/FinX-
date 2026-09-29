package com.finx.invoice.matching;

import java.math.BigDecimal;

public record InvoiceMatchingPolicy(
        String version,
        BigDecimal allowedTolerance,
        String comparisonBasis,
        BigDecimal minExtractionConfidence,
        int businessIdentityWeight,
        int dealMilestoneWeight,
        int amountCurrencyWeight,
        int identifierDateWeight,
        int duplicateWeight,
        int supportingEvidenceWeight
) {
    public InvoiceMatchingPolicy {
        if (version == null || version.isBlank() || allowedTolerance == null || allowedTolerance.signum() < 0
                || !java.util.Set.of("TOTAL", "SUBTOTAL").contains(comparisonBasis)
                || minExtractionConfidence == null || minExtractionConfidence.signum() < 0 || minExtractionConfidence.compareTo(BigDecimal.ONE) > 0
                || java.util.stream.IntStream.of(businessIdentityWeight, dealMilestoneWeight, amountCurrencyWeight,
                    identifierDateWeight, duplicateWeight, supportingEvidenceWeight).anyMatch(w -> w < 0)
                || businessIdentityWeight + dealMilestoneWeight + amountCurrencyWeight + identifierDateWeight + duplicateWeight + supportingEvidenceWeight != 100)
            throw new IllegalArgumentException("Invoice policy must have valid thresholds, comparison basis and weights totaling 100");
    }
    public static InvoiceMatchingPolicy defaultPolicy() {
        return new InvoiceMatchingPolicy(
                "v1.0.0",
                BigDecimal.ZERO, // Strict match by default
                "TOTAL",
                new BigDecimal("0.7500"),
                20,
                20,
                25,
                10,
                15,
                10
        );
    }
}
