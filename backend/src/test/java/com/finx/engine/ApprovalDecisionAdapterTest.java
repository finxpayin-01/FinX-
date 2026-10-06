package com.finx.engine;

import com.finx.agreement.enums.AgreementStatus;
import com.finx.engine.model.*;
import com.finx.invoice.enums.*;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.risk.dto.RiskDtos.ReviewStatus;
import com.finx.risk.entity.*;
import com.finx.verification.service.VerificationEligibilityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ApprovalDecisionAdapterTest extends MilestoneAdapterFixture {
    @Test void historicalApprovedIsUnboundAndCannotAllow() {
        invoice(); var result = approval.evaluate(context);
        assertDecision(result, DecisionStatus.REVIEW, "APPROVAL_UNBOUND_MILESTONE");
        assertThat(result.engineType()).isEqualTo(EngineType.APPROVAL);
        assertThat(result.explanation()).contains("not bound to a durable revision/fingerprint", "cannot act as current financial authorization");
        assertThat(result.inputRevision()).isNull(); assertThat(result.sourceAssessmentId()).isNull();
        assertThat(result.evidenceReferences()).noneMatch(r -> r.sourceType().contains("APPLICABILITY") || r.sourceType().equals("APPROVAL"));
    }
    @ParameterizedTest @CsvSource({
        "pending,REVIEW,APPROVAL_PENDING", "rejected,BLOCK,APPROVAL_MILESTONE_REJECTED",
        "dispute,BLOCK,APPROVAL_DISPUTE_ACTIVE", "risk,BLOCK,APPROVAL_RISK_RESTRICTION_ACTIVE",
        "invoiceRejected,BLOCK,APPROVAL_INVOICE_REJECTED", "invoiceStale,REVIEW,APPROVAL_INVOICE_PENDING",
        "invoiceSuperseded,REVIEW,APPROVAL_INVOICE_PENDING", "invoiceRequestInfo,REVIEW,APPROVAL_INVOICE_PENDING",
        "unsigned,REVIEW,APPROVAL_AGREEMENT_NOT_SIGNED", "beneficiary,REVIEW,APPROVAL_BENEFICIARY_INELIGIBLE",
        "ambiguous,REVIEW,APPROVAL_BENEFICIARY_INELIGIBLE", "pm,REVIEW,APPROVAL_PM_ASSIGNMENT_CHANGED",
        "modified,REVIEW,APPROVAL_MODIFIED_AFTER_DECISION", "foreignRisk,REVIEW,APPROVAL_RISK_SCOPE_UNRESOLVED"
    }) void existingApplicabilityMapping(String scenario, DecisionStatus status, String reason) {
        switch (scenario) {
            case "pending" -> milestone.setStatus(MilestoneStatus.PENDING);
            case "rejected" -> milestone.setStatus(MilestoneStatus.REJECTED);
            case "dispute" -> dispute();
            case "risk" -> restriction(deal.getBuyerOrganizationId());
            case "foreignRisk" -> restriction(UUID.randomUUID());
            case "invoiceRejected" -> { invoice(); invoice.setStatus(InvoiceStatus.REJECTED); }
            case "invoiceStale" -> { invoice(); invoice.setUpdatedAt(now); }
            case "invoiceSuperseded" -> { invoice(); review.setAssessmentId(UUID.randomUUID()); }
            case "invoiceRequestInfo" -> { invoice(); review.setDecision(ReviewDecision.REQUEST_INFORMATION); }
            case "unsigned" -> agreement.setStatus(AgreementStatus.DRAFT);
            case "beneficiary", "ambiguous" -> when(eligibility.evaluateBeneficiaryEligibility(deal.getSellerOrganizationId())).thenReturn(
                    new VerificationEligibilityService.BeneficiaryEligibilityResult(false, null, scenario, false));
            case "pm" -> { deliverable.setReviewedBy(UUID.randomUUID()); deal.setProjectManagerId(UUID.randomUUID()); }
            case "modified" -> milestone.setUpdatedAt(now);
            default -> throw new AssertionError(scenario);
        }
        var result = approval.evaluate(context); assertDecision(result, status, reason); assertReadOnly();
        if (scenario.equals("foreignRisk")) assertThat(result.evidenceReferences()).noneMatch(r -> r.sourceType().startsWith("RISK"));
    }
    private void restriction(UUID org) {
        RiskScore score = new RiskScore(); score.organizationId = org;
        RiskReview r = new RiskReview(); r.subjectId = score.id; r.status = ReviewStatus.RESTRICTED;
        r.restrictionUntil = Instant.now().plusSeconds(3600); r.reviewedAt = now;
        when(risk.query(eq(RiskReview.class), anyString(), anyMap(), anyInt())).thenReturn(List.of(r));
        when(risk.get(RiskReview.class, r.id)).thenReturn(r); when(risk.get(RiskScore.class, score.id)).thenReturn(score);
    }
    @Test void everyCurrentMilestoneStatusIsNonAllow() {
        for (MilestoneStatus status : MilestoneStatus.values()) {
            milestone.setStatus(status); assertThat(approval.evaluate(context).decision()).isNotEqualTo(DecisionStatus.ALLOW);
        }
    }
    @Test void deterministicFingerprintChangesWithPmAndBeneficiaryState() {
        String first = approval.evaluate(context).inputFingerprint();
        assertThat(approval.evaluate(context).inputFingerprint()).isEqualTo(first);
        deal.setProjectManagerId(UUID.randomUUID()); String reassigned = approval.evaluate(context).inputFingerprint();
        assertThat(reassigned).isNotEqualTo(first); bank.setVerifiedAt(now);
        assertThat(approval.evaluate(context).inputFingerprint()).isNotEqualTo(reassigned); assertReadOnly();
    }
    @Test void optionalRejectedInvoiceDoesNotManufactureProhibition() {
        invoice(); invoice.setStatus(InvoiceStatus.REJECTED); milestone.setInvoiceRequired(false);
        assertThat(approval.evaluate(context).reasonCodes()).doesNotContain("APPROVAL_INVOICE_REJECTED");
    }
    @Test void invalidOrganizationAndDealProduceNoApplicabilityLeak() {
        var foreign = new EvaluationContext(context.evaluationId(), UUID.randomUUID(), context.correlationId(), "MILESTONE", milestone.getId(), now);
        assertThatThrownBy(() -> approval.evaluate(foreign)).isInstanceOf(com.finx.exception.UnauthorizedException.class);
        assertThatThrownBy(() -> approval.evaluateForDeal(UUID.randomUUID(), context)).isInstanceOf(com.finx.exception.UnauthorizedException.class);
        verifyNoInteractions(eligibility, risk, banks, invoices, deliverables);
    }
    @Test void repeatedReadsAreStableAndNeverMutateApproval() {
        invoice(); var first = approval.evaluate(context); var again = approval.evaluate(context);
        assertThat(again).isEqualTo(first); assertThat(milestone.getStatus()).isEqualTo(MilestoneStatus.APPROVED);
        assertThat(review.getDecision()).isEqualTo(ReviewDecision.APPROVE); assertReadOnly();
    }
}
