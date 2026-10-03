package com.finx.engine.adapter;

import com.finx.deal.entity.Deal;
import com.finx.engine.model.*;
import com.finx.risk.dto.RiskDtos.Level;
import com.finx.risk.dto.RiskDtos.ReviewStatus;
import com.finx.risk.dto.RiskDtos.UserType;
import com.finx.risk.dto.RiskEvidenceDtos.EvidenceStatus;
import com.finx.risk.dto.RiskEvidenceDtos.SubjectRiskEvidence;
import com.finx.risk.service.RiskEvidenceReadService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

/**
 * Read-only decision adapter for the RISK engine.
 * <p>
 * Guarantees:
 * - Pure read-only inspection consuming persisted B13 evidence via RiskEvidenceReadService
 * - Never calls RiskEngineService.getAssessmentForDeal (avoids transient aggregates and recalculation)
 * - Counterparties (Buyer and Vendor) are evaluated into independent EngineDecision records
 * - Durable evidence references cite only persisted RiskScore, RiskAssessment, and RiskReview IDs
 * - Transient deal aggregate IDs are never cited as durable evidence
 * - Native score, reviewRequired, and effective configured restrictions are strictly preserved
 * - High score + cleared review does NOT blindly become BLOCK
 * - Lower score + active restriction preserves BLOCK
 * - Missing or stale evidence maps to REVIEW
 * - Zero risk history rows, zero reviews created, zero risk events, zero bank/ledger/escrow side effects
 */
@Component
@Transactional(readOnly = true)
public class RiskDecisionAdapter implements ReadOnlyDecisionAdapter {

    public static final String REASON_RISK_CLEAR = "RISK_POLICY_CLEAR";
    public static final String REASON_RISK_RESTRICTION_ACTIVE = "RISK_RESTRICTION_ACTIVE";
    public static final String REASON_RISK_REVIEW_REQUIRED = "RISK_REVIEW_REQUIRED";
    public static final String REASON_RISK_EVIDENCE_MISSING = "RISK_EVIDENCE_MISSING";
    public static final String REASON_RISK_EVIDENCE_STALE = "RISK_EVIDENCE_STALE";

    private final RiskEvidenceReadService riskEvidenceReadService;

    public RiskDecisionAdapter(RiskEvidenceReadService riskEvidenceReadService) {
        this.riskEvidenceReadService = Objects.requireNonNull(riskEvidenceReadService, "riskEvidenceReadService must not be null");
    }

    @Override
    public EngineType type() {
        return EngineType.RISK;
    }

    /**
     * Evaluates risk for an organization in the given context.
     * Defaults to BUYER role if not specified.
     */
    @Override
    public RecordEngineDecisionCommand evaluate(EvaluationContext context) {
        return evaluate(context, UserType.BUYER);
    }

    /**
     * Evaluates risk for an organization in the given context for a specific counterparty role (BUYER or VENDOR).
     */
    public RecordEngineDecisionCommand evaluate(EvaluationContext context, UserType userType) {
        Objects.requireNonNull(context, "context must not be null");
        UserType role = userType != null ? userType : UserType.BUYER;
        SubjectRiskEvidence evidence = riskEvidenceReadService.getSubjectRiskEvidence(null, context.organizationId(), role);
        return mapEvidenceToCommand(context, role, evidence);
    }

    /**
     * Evaluates counterparty risk for a specific Deal and role (BUYER or VENDOR).
     * Slices buyer and vendor into separate decisions with their respective organization IDs as subjectId.
     */
    public RecordEngineDecisionCommand evaluateForDeal(Deal deal, UserType userType, EvaluationContext context) {
        Objects.requireNonNull(deal, "deal must not be null");
        Objects.requireNonNull(userType, "userType must not be null");
        Objects.requireNonNull(context, "context must not be null");

        UUID userId = userType == UserType.BUYER ? deal.getBuyerId() : deal.getSellerId();
        UUID orgId = userType == UserType.BUYER ? deal.getBuyerOrganizationId() : deal.getSellerOrganizationId();

        SubjectRiskEvidence evidence = riskEvidenceReadService.getSubjectRiskEvidence(userId, orgId, userType);
        return mapEvidenceToCommand(context, userType, evidence);
    }

    private RecordEngineDecisionCommand mapEvidenceToCommand(
            EvaluationContext context,
            UserType userType,
            SubjectRiskEvidence evidence
    ) {
        UUID orgId = context.organizationId();
        UUID subjectId = context.subjectId();

        // 1. Missing evidence handling
        if (evidence == null || evidence.status() == EvidenceStatus.MISSING || evidence.subjectId() == null) {
            String inputFingerprint = computeFingerprint("MISSING|" + orgId + "|" + userType);
            return RecordEngineDecisionCommand.builder()
                    .organizationId(orgId)
                    .engineType(EngineType.RISK)
                    .subjectType(DecisionSubjectType.ORGANIZATION.name())
                    .subjectId(subjectId)
                    .decision(DecisionStatus.REVIEW)
                    .evaluationOutcome(EvaluationOutcome.SUCCESS)
                    .score(null)
                    .reasonCodes(List.of(REASON_RISK_EVIDENCE_MISSING))
                    .explanation("No persisted risk evidence found for organization " + orgId + " (" + userType + ")")
                    .evidenceReferences(List.of()) // No fake source IDs
                    .ruleVersion(null)
                    .sourceAssessmentId(null)
                    .evaluationId(context.evaluationId())
                    .correlationId(context.correlationId())
                    .inputFingerprint(inputFingerprint)
                    .engineImplementationVersion("1.0.0")
                    .evaluatedAt(context.evaluationTime() != null ? context.evaluationTime() : Instant.now())
                    .build();
        }

        // 2. Build durable evidence references (only real persisted IDs, never transient aggregate IDs)
        List<EvidenceReference> evidenceRefs = new ArrayList<>();
        if (evidence.subjectId() != null) {
            evidenceRefs.add(new EvidenceReference("RISK_SCORE", evidence.subjectId(), evidence.observedAt(), null, "RISK_ENGINE", null));
        }
        if (evidence.persistedAssessmentId() != null) {
            evidenceRefs.add(new EvidenceReference("RISK_ASSESSMENT", evidence.persistedAssessmentId(), evidence.observedAt(), null, "RISK_ENGINE", evidence.ruleVersion()));
        }
        if (evidence.activeRestrictionId() != null) {
            evidenceRefs.add(new EvidenceReference("RISK_RESTRICTION", evidence.activeRestrictionId(), evidence.observedAt(), null, "RISK_REVIEW", null));
        }

        // 3. Compute deterministic input fingerprint
        String inputMaterial = String.join("|",
                String.valueOf(evidence.subjectId()),
                String.valueOf(evidence.persistedAssessmentId()),
                String.valueOf(evidence.score()),
                String.valueOf(evidence.riskLevel()),
                String.valueOf(evidence.effectiveRestrictionActive()),
                String.valueOf(evidence.reviewRequired()),
                String.valueOf(evidence.observedAt()),
                String.valueOf(evidence.ruleVersion())
        );
        String inputFingerprint = computeFingerprint(inputMaterial);

        BigDecimal score = evidence.score() != null ? BigDecimal.valueOf(evidence.score()) : null;
        List<String> reasonCodes = new ArrayList<>();
        DecisionStatus decision;
        String explanation;

        // 4. Decision Mapping according to native policy semantics
        // Hard active restriction -> BLOCK
        if (evidence.effectiveRestrictionActive()) {
            decision = DecisionStatus.BLOCK;
            reasonCodes.add(REASON_RISK_RESTRICTION_ACTIVE);
            if (evidence.reasonCodes() != null && !evidence.reasonCodes().isEmpty()) {
                reasonCodes.addAll(evidence.reasonCodes());
            }
            explanation = "Active risk restriction in effect until " + evidence.restrictionUntil()
                    + (evidence.restrictionReason() != null ? ": " + evidence.restrictionReason() : "");
        }
        // Stale evidence -> REVIEW
        else if (evidence.status() == EvidenceStatus.STALE) {
            decision = DecisionStatus.REVIEW;
            reasonCodes.add(REASON_RISK_EVIDENCE_STALE);
            explanation = "Persisted risk evidence is stale (refresh expired at " + evidence.nextRefreshAt() + ")";
        }
        // Review required -> REVIEW
        else if (Boolean.TRUE.equals(evidence.reviewRequired())
                || (evidence.reviewStatus() != null && evidence.reviewStatus() != ReviewStatus.CLEARED)) {
            decision = DecisionStatus.REVIEW;
            reasonCodes.add(REASON_RISK_REVIEW_REQUIRED);
            if (evidence.reasonCodes() != null && !evidence.reasonCodes().isEmpty()) {
                reasonCodes.addAll(evidence.reasonCodes());
            }
            explanation = "Manual risk review is required (reviewStatus: " + evidence.reviewStatus() + ")";
        }
        // Explicitly clear / eligible -> ALLOW (including high score with cleared review)
        else {
            decision = DecisionStatus.ALLOW;
            reasonCodes.add(REASON_RISK_CLEAR);
            if (evidence.reasonCodes() != null && !evidence.reasonCodes().isEmpty()) {
                reasonCodes.addAll(evidence.reasonCodes());
            }
            explanation = "Risk policy clear (score: " + evidence.score() + ", level: " + evidence.riskLevel() + ")";
        }

        return RecordEngineDecisionCommand.builder()
                .organizationId(orgId)
                .engineType(EngineType.RISK)
                .subjectType(DecisionSubjectType.ORGANIZATION.name())
                .subjectId(subjectId)
                .decision(decision)
                .evaluationOutcome(EvaluationOutcome.SUCCESS)
                .score(score)
                .reasonCodes(reasonCodes)
                .explanation(explanation)
                .evidenceReferences(evidenceRefs)
                .ruleVersion(evidence.ruleVersion())
                .sourceAssessmentId(evidence.persistedAssessmentId())
                .evaluationId(context.evaluationId())
                .correlationId(context.correlationId())
                .inputFingerprint(inputFingerprint)
                .engineImplementationVersion("1.0.0")
                .evaluatedAt(context.evaluationTime() != null ? context.evaluationTime() : Instant.now())
                .build();
    }

    private static String computeFingerprint(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
