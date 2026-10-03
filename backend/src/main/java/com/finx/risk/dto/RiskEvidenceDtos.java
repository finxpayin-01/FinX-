package com.finx.risk.dto;

import com.finx.invoice.enums.DuplicateConfidence;
import com.finx.invoice.enums.ExtractionStatus;
import com.finx.invoice.enums.InvoiceStatus;
import com.finx.invoice.enums.MatchingStatus;
import com.finx.risk.dto.RiskDtos.Level;
import com.finx.risk.dto.RiskDtos.ReviewStatus;
import com.finx.risk.dto.RiskDtos.UserType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class RiskEvidenceDtos {

    private RiskEvidenceDtos() {}

    public enum EvidenceStatus {
        PRESENT,
        MISSING,
        STALE,
        UNASSESSED
    }

    public record SubjectRiskEvidence(
            UUID subjectId,
            UUID userId,
            UUID organizationId,
            UserType userType,
            UUID persistedAssessmentId,
            EvidenceStatus status,
            Integer score,
            Level riskLevel,
            Boolean reviewRequired,
            ReviewStatus reviewStatus,
            boolean effectiveRestrictionActive,
            UUID activeRestrictionId,
            Instant restrictionUntil,
            String restrictionReason,
            String ruleVersion,
            Instant observedAt,
            Instant nextRefreshAt,
            List<String> reasonCodes,
            List<String> humanReadableReasons
    ) {
        public static SubjectRiskEvidence missing(UUID userId, UUID organizationId, UserType userType) {
            return new SubjectRiskEvidence(
                    null,
                    userId,
                    organizationId,
                    userType,
                    null,
                    EvidenceStatus.MISSING,
                    null,
                    null,
                    null,
                    null,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    List.of()
            );
        }
    }

    public record InvoiceRiskEvidence(
            UUID invoiceId,
            UUID persistedAssessmentId,
            String fingerprint,
            UUID revisionOf,
            InvoiceStatus invoiceStatus,
            ExtractionStatus extractionStatus,
            String provenance,
            boolean manualProvenance,
            Integer matchScore,
            MatchingStatus matchingStatus,
            DuplicateConfidence duplicateConfidence,
            String policyVersion,
            Instant observedAt,
            EvidenceStatus status
    ) {
        public static InvoiceRiskEvidence missing() {
            return new InvoiceRiskEvidence(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    EvidenceStatus.MISSING
            );
        }
    }

    public record DealRiskEvidenceSnapshot(
            UUID dealId,
            SubjectRiskEvidence buyerEvidence,
            SubjectRiskEvidence vendorEvidence,
            InvoiceRiskEvidence invoiceEvidence,
            Instant snapshotAt,
            boolean hasDurableAggregate,
            boolean reviewRequired,
            boolean effectiveRestrictionActive,
            String summary
    ) {}
}
