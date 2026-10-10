package com.finx.engine;

import com.finx.agreement.enums.AgreementStatus;
import com.finx.approval.entity.MilestoneApprovalAuthority;
import com.finx.approval.entity.MilestoneApprovalBinding;
import com.finx.engine.model.*;
import com.finx.invoice.enums.*;
import com.finx.milestone.entity.Deliverable;
import com.finx.milestone.entity.DeliverableStatus;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.risk.dto.RiskDtos.ReviewStatus;
import com.finx.risk.entity.*;
import com.finx.verification.service.VerificationEligibilityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ApprovalDecisionAdapterTest extends MilestoneAdapterFixture {

    // 1. valid current durable approval + all applicability checks valid -> ALLOW
    @Test
    void validCurrentDurableApprovalAllows() {
        invoice();
        MilestoneApprovalBinding binding = createValidBinding();

        var result = approval.evaluate(context);
        assertDecision(result, DecisionStatus.ALLOW, "APPROVAL_CURRENT_BINDING_CONFIRMED");
        assertThat(result.engineType()).isEqualTo(EngineType.APPROVAL);
        assertThat(result.score()).isNull();
        assertReadOnly();
    }

    // 2. missing binding -> REVIEW
    @Test
    void missingBindingReturnsReview() {
        invoice();
        when(bindings.findByMilestoneId(milestone.getId())).thenReturn(Optional.empty());

        var result = approval.evaluate(context);
        assertDecision(result, DecisionStatus.REVIEW, "APPROVAL_BINDING_MISSING");
        assertThat(result.engineType()).isEqualTo(EngineType.APPROVAL);
        assertReadOnly();
    }

    // 3. stale milestone fingerprint -> REVIEW
    @Test
    void staleMilestoneFingerprintReturnsReview() {
        invoice();
        createValidBinding();

        milestone.setAmount(new BigDecimal("999.00")); // modified after binding

        var result = approval.evaluate(context);
        assertDecision(result, DecisionStatus.REVIEW, "APPROVAL_BINDING_MILESTONE_CHANGED");
        assertReadOnly();
    }

    // 4. stale deliverables fingerprint -> REVIEW
    @Test
    void staleDeliverablesFingerprintReturnsReview() {
        invoice();
        createValidBinding();

        deliverable.setFileUrl("https://finx.local/modified_file.pdf"); // modified after binding

        var result = approval.evaluate(context);
        assertDecision(result, DecisionStatus.REVIEW, "APPROVAL_BINDING_DELIVERABLES_CHANGED");
        assertReadOnly();
    }

    // 5. PM reassigned -> REVIEW
    @Test
    void pmReassignedReturnsReview() {
        invoice();
        UUID initialPm = UUID.randomUUID();
        deal.setProjectManagerId(initialPm);

        String mHash = fingerprints.computeMilestoneFingerprint(milestone);
        String dHash = fingerprints.computeDeliverablesFingerprint(submissions);
        String aHash = fingerprints.computeApprovalFingerprint(
                deal.getBuyerOrganizationId(), deal.getId(), milestone.getId(),
                initialPm, MilestoneApprovalAuthority.PROJECT_MANAGER, initialPm,
                mHash, dHash, now.minusSeconds(50)
        );
        MilestoneApprovalBinding binding = new MilestoneApprovalBinding(
                UUID.randomUUID(), deal.getBuyerOrganizationId(), deal.getId(), milestone.getId(),
                initialPm, MilestoneApprovalAuthority.PROJECT_MANAGER,
                mHash, dHash, aHash, initialPm,
                now.minusSeconds(50), now.minusSeconds(50)
        );
        when(bindings.findByMilestoneId(milestone.getId())).thenReturn(Optional.of(binding));

        deal.setProjectManagerId(UUID.randomUUID()); // PM reassigned

        var result = approval.evaluate(context);
        assertDecision(result, DecisionStatus.REVIEW, "APPROVAL_BINDING_AUTHORITY_CHANGED");
        assertReadOnly();
    }

    // 6. legacy unbound approval -> REVIEW
    @Test
    void legacyUnboundApprovalReturnsReview() {
        invoice();
        when(bindings.findByMilestoneId(milestone.getId())).thenReturn(Optional.empty());

        var result = approval.evaluate(context);
        assertDecision(result, DecisionStatus.REVIEW, "APPROVAL_BINDING_MISSING");
        assertReadOnly();
    }

    // 7. invoice pending -> REVIEW
    @Test
    void invoicePendingReturnsReview() {
        invoice();
        createValidBinding();

        review.setStatus(InvoiceReviewStatus.IN_REVIEW); // invoice pending

        var result = approval.evaluate(context);
        assertDecision(result, DecisionStatus.REVIEW, "APPROVAL_INVOICE_PENDING");
        assertReadOnly();
    }

    // 8. agreement stale/not signed -> REVIEW
    @Test
    void agreementNotSignedReturnsReview() {
        invoice();
        createValidBinding();

        agreement.setStatus(AgreementStatus.DRAFT);

        var result = approval.evaluate(context);
        assertDecision(result, DecisionStatus.REVIEW, "APPROVAL_AGREEMENT_NOT_SIGNED");
        assertReadOnly();
    }

    // 9. beneficiary ineligible -> REVIEW
    @Test
    void beneficiaryIneligibleReturnsReview() {
        invoice();
        createValidBinding();

        when(eligibility.evaluateBeneficiaryEligibility(deal.getSellerOrganizationId())).thenReturn(
                new VerificationEligibilityService.BeneficiaryEligibilityResult(false, null, "INELIGIBLE", false));

        var result = approval.evaluate(context);
        assertDecision(result, DecisionStatus.REVIEW, "APPROVAL_BENEFICIARY_INELIGIBLE");
        assertReadOnly();
    }

    // 10. dispute -> BLOCK where existing policy says BLOCK
    @Test
    void disputeReturnsBlock() {
        invoice();
        createValidBinding();

        dispute();

        var result = approval.evaluate(context);
        assertDecision(result, DecisionStatus.BLOCK, "APPROVAL_DISPUTE_ACTIVE");
        assertReadOnly();
    }

    // 11. rejected milestone -> BLOCK
    @Test
    void rejectedMilestoneReturnsBlock() {
        invoice();
        createValidBinding();

        milestone.setStatus(MilestoneStatus.REJECTED);

        var result = approval.evaluate(context);
        assertDecision(result, DecisionStatus.BLOCK, "APPROVAL_MILESTONE_REJECTED");
        assertReadOnly();
    }

    // 12. current binding evidence reference included
    @Test
    void currentBindingEvidenceReferenceIncluded() {
        invoice();
        MilestoneApprovalBinding binding = createValidBinding();

        var result = approval.evaluate(context);
        assertThat(result.evidenceReferences()).anyMatch(r ->
                "MILESTONE_APPROVAL_BINDING".equals(r.sourceType()) &&
                binding.getId().equals(r.sourceId())
        );
    }

    // 13. score null
    @Test
    void scoreIsNull() {
        invoice();
        createValidBinding();

        var result = approval.evaluate(context);
        assertThat(result.score()).isNull();
    }

    // 14. no financial side effects
    @Test
    void noFinancialSideEffects() {
        invoice();
        createValidBinding();

        var result = approval.evaluate(context);
        assertThat(result.decision()).isEqualTo(DecisionStatus.ALLOW);
        assertReadOnly();
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

    @Test void everyCurrentMilestoneStatusWithoutBindingIsNonAllow() {
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
        invoice(); createValidBinding();
        var first = approval.evaluate(context); var again = approval.evaluate(context);
        assertThat(again).isEqualTo(first); assertThat(milestone.getStatus()).isEqualTo(MilestoneStatus.APPROVED);
        assertThat(review.getDecision()).isEqualTo(ReviewDecision.APPROVE); assertReadOnly();
    }
}
