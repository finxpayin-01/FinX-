package com.finx.engine.adapter;

import com.finx.engine.model.*;
import com.finx.invoice.enums.InvoiceStatus;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.org.repository.OrganizationBankAccountRepository;
import com.finx.paymentorchestration.service.ApprovalApplicabilityService;
import com.finx.risk.dto.RiskDtos.ReviewStatus;
import com.finx.risk.entity.RiskReview;
import com.finx.risk.entity.RiskScore;
import com.finx.risk.repository.BusinessRiskRepository;
import com.finx.security.service.UserPrincipal;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Existing product applicability only; no enterprise workflow or durable milestone approval binding. */
@Component
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ApprovalDecisionAdapter implements ReadOnlyDecisionAdapter {
    private final MilestoneDecisionEvidenceReader reader;
    private final ApprovalApplicabilityService applicability;
    private final OrganizationBankAccountRepository accounts;
    private final BusinessRiskRepository risk;

    public ApprovalDecisionAdapter(MilestoneDecisionEvidenceReader reader, ApprovalApplicabilityService applicability,
            OrganizationBankAccountRepository accounts, BusinessRiskRepository risk) {
        this.reader = reader; this.applicability = applicability; this.accounts = accounts; this.risk = risk;
    }

    @Override public EngineType type() { return EngineType.APPROVAL; }

    /** Internal tenant-scoped entry point. User-facing callers must use evaluateForCaller. */
    @Override public RecordEngineDecisionCommand evaluate(EvaluationContext context) {
        return evaluateSources(reader.read(context, null, null), context);
    }

    public RecordEngineDecisionCommand evaluateForDeal(UUID dealId, EvaluationContext context) {
        return evaluateSources(reader.read(context, Objects.requireNonNull(dealId), null), context);
    }

    public RecordEngineDecisionCommand evaluateForCaller(UUID dealId, EvaluationContext context, UserPrincipal caller) {
        return evaluateSources(reader.read(context, Objects.requireNonNull(dealId), Objects.requireNonNull(caller)), context);
    }

    private RecordEngineDecisionCommand evaluateSources(MilestoneDecisionEvidenceReader.Sources s, EvaluationContext context) {
        var result = applicability.evaluateApprovalApplicability(s.deal().getId(), s.milestone().getId());
        MilestoneDecisionEvidenceReader.requireScope(s.deal().getId().equals(result.dealId())
                && s.milestone().getId().equals(result.milestoneId()));
        var evidence = s.evidence();
        List<String> blocks = new ArrayList<>();
        Set<String> reviews = new LinkedHashSet<>();
        if (s.milestone().getStatus() == MilestoneStatus.REJECTED) blocks.add("APPROVAL_MILESTONE_REJECTED");
        if (!s.disputes().isEmpty() && !result.disputeApplicable()) blocks.add("APPROVAL_DISPUTE_ACTIVE");
        if (s.invoice() != null && s.invoice().getStatus() == InvoiceStatus.REJECTED && !result.invoiceApplicable()
                && result.reasonCodes().stream().anyMatch(r -> r.startsWith("INVOICE_NOT_APPROVED:"))) {
            blocks.add("APPROVAL_INVOICE_REJECTED");
        }

        // Native B10 risk applicability currently scans platform subjects. Resolve ownership before citing any ID.
        // Unscoped native restrictions are unresolved evidence, not proof of a prohibition on this deal.
        var restrictions = applicability.evaluateRiskApplicability(s.deal(), s.milestone());
        boolean scopedRestriction = false;
        for (UUID id : restrictions.activeRestrictionReviewIds().stream().distinct().sorted().toList()) {
            RiskReview review = risk.get(RiskReview.class, id);
            RiskScore subject = risk.get(RiskScore.class, review.subjectId);
            if (subject.organizationId == null || !(subject.organizationId.equals(s.deal().getBuyerOrganizationId())
                    || subject.organizationId.equals(s.deal().getSellerOrganizationId()))) continue;
            if (review.status != ReviewStatus.RESTRICTED || review.restrictionUntil == null
                    || !review.restrictionUntil.isAfter(result.observedAt())) continue;
            scopedRestriction = true;
            evidence.reference("RISK_REVIEW", review.id, review.reviewedAt, null, "PERSISTED_RISK_RESTRICTION", null);
            evidence.input("RISK_REVIEW", review.id, review.subjectId, review.status, review.restrictionUntil,
                    review.reviewedAt, review.reviewerId, review.version);
        }
        if (scopedRestriction) blocks.add("APPROVAL_RISK_RESTRICTION_ACTIVE");
        else if (!restrictions.applicable() || !result.riskApplicable()) reviews.add("APPROVAL_RISK_SCOPE_UNRESOLVED");

        // No current schema source can prove a durable milestone approval binding. No ALLOW branch exists.
        if (result.milestoneUnbound() || result.milestoneApproved()) reviews.add("APPROVAL_UNBOUND_MILESTONE");
        if (!result.milestoneApproved()) reviews.add("APPROVAL_PENDING");
        if (!result.invoiceApplicable() || !s.invoiceCurrent()) reviews.add("APPROVAL_INVOICE_PENDING");
        if (!result.agreementApplicable()) reviews.add("APPROVAL_AGREEMENT_NOT_SIGNED");
        if (!result.beneficiaryApplicable()) reviews.add("APPROVAL_BENEFICIARY_INELIGIBLE");
        if (!result.pmAssignmentApplicable()) reviews.add("APPROVAL_PM_ASSIGNMENT_CHANGED");
        for (String reason : result.reasonCodes()) {
            if (reason.startsWith("MILESTONE_MODIFIED_AFTER_APPROVAL")) reviews.add("APPROVAL_MODIFIED_AFTER_DECISION");
            if (reason.startsWith("SNAPSHOT_COHERENCE_VIOLATION")) reviews.add("APPROVAL_SNAPSHOT_INCOHERENT");
        }
        if (result.beneficiaryAccountId() != null) {
            var account = accounts.findById(result.beneficiaryAccountId()).orElseThrow(
                    () -> new IllegalStateException("Beneficiary evidence is unavailable"));
            MilestoneDecisionEvidenceReader.requireScope(account.getOrganizationId().equals(s.deal().getSellerOrganizationId()));
            evidence.reference("ORGANIZATION_BANK_ACCOUNT", account.getId(), account.getVerifiedAt(), null,
                    result.isSandboxBeneficiary() ? "SANDBOX_VERIFICATION" : "BENEFICIARY_VERIFICATION", null);
            evidence.input("BENEFICIARY", account.getId(), account.getOrganizationId(), account.getUpdatedAt(),
                    account.getVerificationStatus(), account.getVerifiedAt(), account.getNameMatchStatus(), result.isSandboxBeneficiary());
        }
        // PM assignment is persisted on DEAL; never fabricate a separate assignment or approval UUID.
        evidence.input("APPLICABILITY", result.applicable(), result.milestoneApproved(), result.milestoneUnbound(),
                result.invoiceApplicable(), result.agreementApplicable(), result.beneficiaryApplicable(),
                result.pmAssignmentApplicable(), result.disputeApplicable(), scopedRestriction, result.assignedProjectManagerId());
        reviews.add("APPROVAL_UNBOUND_MILESTONE");
        List<String> reasons = new ArrayList<>(blocks);
        reasons.addAll(reviews);
        evidence.input("MAPPING", String.join("|", reasons));
        return evidence.command(context, type(), blocks.isEmpty() ? DecisionStatus.REVIEW : DecisionStatus.BLOCK, reasons,
                "Historical milestone approval is not bound to a durable revision/fingerprint and cannot act as current financial authorization. "
                        + "This assesses existing product approval applicability only; enterprise approval is not implemented.");
    }
}
