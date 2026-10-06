package com.finx.engine;

import com.finx.agreement.enums.AgreementStatus;
import com.finx.engine.model.*;
import com.finx.escrow.entity.*;
import com.finx.invoice.enums.*;
import com.finx.milestone.entity.*;
import com.finx.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MilestoneRulesDecisionAdapterTest extends MilestoneAdapterFixture {
    @Test void validContractAllowsWithoutClaimingPaymentAuthorization() {
        var result = rules.evaluate(context);
        assertDecision(result, DecisionStatus.ALLOW, "MILESTONE_CONTRACT_COMPLIANT");
        assertThat(result.engineType()).isEqualTo(EngineType.MILESTONE_RULES);
        assertThat(result.explanation()).contains("not payment authorization");
        assertThat(result.inputRevision()).isNull();
        assertReadOnly();
    }

    @ParameterizedTest @CsvSource({
        "currency,BLOCK,MILESTONE_CURRENCY_MISMATCH", "nullAmount,BLOCK,MILESTONE_AMOUNT_INVALID",
        "zero,BLOCK,MILESTONE_AMOUNT_INVALID", "negative,BLOCK,MILESTONE_AMOUNT_INVALID",
        "exceeds,BLOCK,MILESTONE_AMOUNT_EXCEEDS_DEAL", "allocation,BLOCK,MILESTONE_ALLOCATION_MISMATCH",
        "agreementMissing,REVIEW,AGREEMENT_MISSING", "unsigned,REVIEW,AGREEMENT_NOT_SIGNED",
        "deliverablesMissing,REVIEW,DELIVERABLES_MISSING", "deliverablePending,REVIEW,DELIVERABLES_PENDING_APPROVAL",
        "invoiceMissing,REVIEW,INVOICE_EVIDENCE_MISSING", "invoicePending,REVIEW,INVOICE_EVIDENCE_STALE",
        "superseded,REVIEW,INVOICE_EVIDENCE_STALE", "edited,REVIEW,INVOICE_EVIDENCE_STALE",
        "notHumanApprove,REVIEW,INVOICE_EVIDENCE_STALE", "requestInfo,REVIEW,INVOICE_EVIDENCE_STALE",
        "missingReviewer,REVIEW,INVOICE_EVIDENCE_STALE", "missingReviewTime,REVIEW,INVOICE_EVIDENCE_STALE",
        "missingAssessment,REVIEW,INVOICE_EVIDENCE_STALE", "dispute,BLOCK,MILESTONE_ACTIVE_DISPUTE",
        "noEscrow,REVIEW,ESCROW_ACCOUNT_MISSING", "funds,REVIEW,INSUFFICIENT_CONFIRMED_FUNDS",
        "released,BLOCK,MILESTONE_ALREADY_RELEASED", "rejected,BLOCK,MILESTONE_STATUS_REJECTED",
        "cancelled,BLOCK,MILESTONE_STATUS_CANCELLED", "sequence,REVIEW,SEQUENCE_PREREQUISITE_UNMET",
        "deadline,REVIEW,DEADLINE_EXCEEDED_REVIEW", "unsupported,REVIEW,MILESTONE_CURRENCY_UNSUPPORTED"
    }) void contractualMappings(String scenario, DecisionStatus status, String reason) {
        switch (scenario) {
            case "currency" -> milestone.setCurrency("USD");
            case "nullAmount" -> milestone.setAmount(null);
            case "zero" -> milestone.setAmount(BigDecimal.ZERO);
            case "negative" -> milestone.setAmount(new BigDecimal("-1"));
            case "exceeds" -> milestone.setAmount(new BigDecimal("101"));
            case "allocation" -> deal.setTotalAmount(new BigDecimal("101"));
            case "agreementMissing" -> when(agreements.findTopByDealIdOrderByVersionDesc(deal.getId())).thenReturn(Optional.empty());
            case "unsigned" -> agreement.setStatus(AgreementStatus.DRAFT);
            case "deliverablesMissing" -> submissions.clear();
            case "deliverablePending" -> deliverable.setStatus(DeliverableStatus.PENDING);
            case "invoiceMissing" -> milestone.setInvoiceRequired(true);
            case "invoicePending" -> { invoice(); invoice.setStatus(InvoiceStatus.PROCESSING); }
            case "superseded" -> { invoice(); review.setAssessmentId(UUID.randomUUID()); }
            case "edited" -> { invoice(); invoice.setUpdatedAt(now); }
            case "notHumanApprove" -> { invoice(); review.setDecision(ReviewDecision.REJECT); }
            case "requestInfo" -> { invoice(); review.setStatus(InvoiceReviewStatus.OPEN); review.setDecision(ReviewDecision.REQUEST_INFORMATION); }
            case "missingReviewer" -> { invoice(); review.setReviewerId(null); }
            case "missingReviewTime" -> { invoice(); review.setReviewedAt(null); }
            case "missingAssessment" -> { invoice(); when(assessments.findFirstByInvoiceIdOrderByCreatedAtDesc(invoice.getId())).thenReturn(Optional.empty()); }
            case "dispute" -> dispute();
            case "noEscrow" -> when(accounts.findByDealId(deal.getId())).thenReturn(Optional.empty());
            case "funds" -> account.setBalance(new BigDecimal("99.99"));
            case "released" -> {
                EscrowLedger release = new EscrowLedger(account.getId(), null, milestone.getId(), TransactionType.RELEASE, milestone.getAmount(), BigDecimal.ZERO, "");
                release.setId(UUID.randomUUID()); when(ledger.findByMilestoneIdAndTransactionType(milestone.getId(), TransactionType.RELEASE)).thenReturn(Optional.of(release));
            }
            case "rejected" -> milestone.setStatus(MilestoneStatus.REJECTED);
            case "cancelled" -> milestone.setStatus(MilestoneStatus.CANCELLED);
            case "sequence" -> prior(MilestoneStatus.PENDING);
            case "deadline" -> { milestone.setSubmissionDeadline(now.minusSeconds(1)); deliverable.setStatus(DeliverableStatus.PENDING); }
            case "unsupported" -> { deal.setCurrency("USD"); milestone.setCurrency("USD"); account.setCurrency("USD"); }
            default -> throw new AssertionError(scenario);
        }
        assertDecision(rules.evaluate(context), status, reason); assertReadOnly();
    }

    @Test void approvedInvoicePreservesManualProvenance() {
        invoice(); var result = rules.evaluate(context);
        assertDecision(result, DecisionStatus.ALLOW, "MILESTONE_CONTRACT_COMPLIANT");
        assertThat(result.evidenceReferences()).anyMatch(r -> r.sourceId().equals(assessment.id) && r.provenance().equals("MANUAL_ENTRY"));
    }
    @Test void completedPriorPermitsProgression() { prior(MilestoneStatus.COMPLETED); assertThat(rules.evaluate(context).decision()).isEqualTo(DecisionStatus.ALLOW); }
    @Test void cancelledPriorExcludedFromAllocationAndSequence() {
        prior(MilestoneStatus.CANCELLED); milestone.setAmount(new BigDecimal("100"));
        assertThat(rules.evaluate(context).decision()).isEqualTo(DecisionStatus.ALLOW);
    }
    @Test void approvedCompletionDoesNotFailExpiredDeadline() {
        milestone.setSubmissionDeadline(now.minusSeconds(1)); assertThat(rules.evaluate(context).decision()).isEqualTo(DecisionStatus.ALLOW);
    }
    @Test void blockDominatesMissingEvidence() {
        dispute(); submissions.clear(); var result = rules.evaluate(context);
        assertDecision(result, DecisionStatus.BLOCK, "MILESTONE_ACTIVE_DISPUTE"); assertThat(result.reasonCodes()).contains("DELIVERABLES_MISSING");
    }
    @Test void deterministicCollectionsAndMaterialChanges() {
        prior(MilestoneStatus.COMPLETED); invoice(); var first = rules.evaluate(context);
        Collections.reverse(allocation); assertThat(rules.evaluate(context).inputFingerprint()).isEqualTo(first.inputFingerprint());
        allocation.getFirst().setStatus(MilestoneStatus.PENDING);
        assertThat(rules.evaluate(context).inputFingerprint()).isNotEqualTo(first.inputFingerprint());
    }
    @Test void invoiceReviewChangesFingerprintEvenWithSameAssessment() {
        invoice(); String before = rules.evaluate(context).inputFingerprint(); review.setDecision(ReviewDecision.REQUEST_INFORMATION);
        assertThat(rules.evaluate(context).inputFingerprint()).isNotEqualTo(before);
    }
    @Test void repeatedEvaluationDoesNotMutateEntitiesOrRepositories() {
        invoice(); var first = rules.evaluate(context); var second = rules.evaluate(context);
        assertThat(second).isEqualTo(first); assertThat(account.getBalance()).isEqualByComparingTo("100");
        assertThat(milestone.getStatus()).isEqualTo(MilestoneStatus.APPROVED); assertReadOnly();
    }
    @ParameterizedTest @CsvSource({"missingMilestone", "missingDeal", "missingOrg", "unmappedOrg", "wrongOrg", "wrongDeal", "wrongSubject"})
    void invalidScopeReturnsNoEvidence(String kind) {
        EvaluationContext requested = context;
        switch (kind) {
            case "missingMilestone" -> when(milestones.findById(milestone.getId())).thenReturn(Optional.empty());
            case "missingDeal" -> when(deals.findById(deal.getId())).thenReturn(Optional.empty());
            case "missingOrg" -> deal.setBuyerOrganizationId(null);
            case "unmappedOrg" -> when(organizations.existsById(context.organizationId())).thenReturn(false);
            case "wrongOrg" -> requested = new EvaluationContext(context.evaluationId(), UUID.randomUUID(), context.correlationId(), "MILESTONE", milestone.getId(), now);
            case "wrongSubject" -> requested = new EvaluationContext(context.evaluationId(), context.organizationId(), context.correlationId(), "INVOICE", milestone.getId(), now);
        }
        EvaluationContext finalRequested = requested;
        assertThatThrownBy(() -> rules.evaluateForDeal(kind.equals("wrongDeal") ? UUID.randomUUID() : deal.getId(), finalRequested))
                .isInstanceOfAny(UnauthorizedException.class, IllegalArgumentException.class);
        verifyNoInteractions(deliverables, invoices, accounts, ledger, disputes, agreements);
    }
}
