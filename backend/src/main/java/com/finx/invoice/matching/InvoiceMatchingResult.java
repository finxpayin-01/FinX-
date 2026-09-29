package com.finx.invoice.matching;

import com.finx.invoice.enums.DuplicateConfidence;
import com.finx.invoice.enums.MatchingStatus;

import java.util.List;

public record InvoiceMatchingResult(
        int matchScore,
        MatchingStatus matchingStatus,
        DuplicateConfidence duplicateConfidence,
        String primaryReasonCode,
        List<InvoiceCheckDetail> checks,
        String summaryMessage,
        String policyVersion,
        java.util.Map<String,Object> evidence
) {
    public InvoiceMatchingResult(int matchScore, MatchingStatus matchingStatus, DuplicateConfidence duplicateConfidence,
            String primaryReasonCode, List<InvoiceCheckDetail> checks, String summaryMessage, String policyVersion) {
        this(matchScore,matchingStatus,duplicateConfidence,primaryReasonCode,checks,summaryMessage,policyVersion,java.util.Map.of());
    }
}
