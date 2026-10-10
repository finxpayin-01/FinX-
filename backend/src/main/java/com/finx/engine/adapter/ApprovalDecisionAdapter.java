package com.finx.engine.adapter;

import com.finx.approval.entity.MilestoneApprovalBinding;
import com.finx.approval.repository.MilestoneApprovalBindingRepository;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Durable milestone approval binding evaluation with real ALLOW branch under confirmed binding. */
@Component
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ApprovalDecisionAdapter implements ReadOnlyDecisionAdapter {
    private final MilestoneDecisionEvidenceReader reader;
    private final ApprovalApplicabilityService applicability;
    private final OrganizationBankAccountRepository accounts;
    private final BusinessRiskRepository risk;
    private final MilestoneApprovalBindingRepository bindingRepository;

    @Autowired
    public ApprovalDecisionAdapter(MilestoneDecisionEvidenceReader reader, ApprovalApplicabilityService applicability,
            OrganizationBankAccountRepository accounts, BusinessRiskRepository risk,
            @Autowired(required = false) MilestoneApprovalBindingRepository bindingRepository) {
        this.reader = reader; this.applicability = applicability; this.accounts = accounts; this.risk = risk;
        this.bindingRepository = bindingRepository;
    }

    public ApprovalDecisionAdapter(MilestoneDecisionEvidenceReader reader, ApprovalApplicabilityService applicability,
            OrganizationBankAccountRepository accounts, BusinessRiskRepository risk) {
        this(reader, applicability, accounts, risk, null);
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

        // Durable milestone approval binding evidence
        MilestoneApprovalBinding binding = null;
        if (bindingRepository != null) {
            binding = bindingRepository.findByMilestoneId(s.milestone().getId()).orElse(null);
        }
        if (binding != null) {
            MilestoneDecisionEvidenceReader.requireScope(binding.getDealId().equals(s.deal().getId())
                    && binding.getOrganizationId().equals(s.deal().getBuyerOrganizationId()));
            evidence.reference("MILESTONE_APPROVAL_BINDING", binding.getId(), binding.getApprovedAt(), null,
                    "MILESTONE_APPROVAL_BINDING", null);
            evidence.input("MILESTONE_APPROVAL_BINDING", binding.getId(), binding.getOrganizationId(), binding.getDealId(),
                    binding.getMilestoneId(), binding.getApprovedBy(), binding.getApprovalAuthority().name(),
                    binding.getProjectManagerId(), binding.getMilestoneFingerprint(), binding.getDeliverablesFingerprint(),
                    binding.getApprovalFingerprint(), binding.getApprovedAt());
        }

        for (String reason : result.reasonCodes()) {
            if (reason.startsWith("APPROVAL_BINDING_MISSING")) reviews.add("APPROVAL_BINDING_MISSING");
            else if (reason.startsWith("APPROVAL_BINDING_MILESTONE_CHANGED")) reviews.add("APPROVAL_BINDING_MILESTONE_CHANGED");
            else if (reason.startsWith("APPROVAL_BINDING_DELIVERABLES_CHANGED")) reviews.add("APPROVAL_BINDING_DELIVERABLES_CHANGED");
            else if (reason.startsWith("APPROVAL_BINDING_AUTHORITY_CHANGED")) reviews.add("APPROVAL_BINDING_AUTHORITY_CHANGED");
            else if (reason.startsWith("APPROVAL_BUYER_ORGANIZATION_UNBOUND")) reviews.add("APPROVAL_BUYER_ORGANIZATION_UNBOUND");
            else if (reason.startsWith("MILESTONE_MODIFIED_AFTER_APPROVAL")) reviews.add("APPROVAL_MODIFIED_AFTER_DECISION");
            else if (reason.startsWith("SNAPSHOT_COHERENCE_VIOLATION")) reviews.add("APPROVAL_SNAPSHOT_INCOHERENT");
        }
        if (result.milestoneUnbound() && reviews.stream().noneMatch(r -> r.startsWith("APPROVAL_BINDING_") || r.equals("APPROVAL_BUYER_ORGANIZATION_UNBOUND"))) {
            reviews.add("APPROVAL_UNBOUND_MILESTONE");
        }
        if (!result.milestoneApproved()) reviews.add("APPROVAL_PENDING");
        if (!result.invoiceApplicable() || !s.invoiceCurrent()) reviews.add("APPROVAL_INVOICE_PENDING");
        if (!result.agreementApplicable()) reviews.add("APPROVAL_AGREEMENT_NOT_SIGNED");
        if (!result.beneficiaryApplicable()) reviews.add("APPROVAL_BENEFICIARY_INELIGIBLE");
        if (!result.pmAssignmentApplicable()) reviews.add("APPROVAL_PM_ASSIGNMENT_CHANGED");
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

        if (!blocks.isEmpty()) {
            List<String> reasons = new ArrayList<>(blocks);
            reasons.addAll(reviews);
            evidence.input("MAPPING", String.join("|", reasons));
            return evidence.command(context, type(), DecisionStatus.BLOCK, reasons,
                    "Explicit approval prohibition active.");
        }

        if (!reviews.isEmpty() || !result.applicable() || binding == null) {
            List<String> reasons = new ArrayList<>(reviews);
            evidence.input("MAPPING", String.join("|", reasons));
            String explanation = reviews.contains("APPROVAL_UNBOUND_MILESTONE")
                    ? "Historical milestone approval is not bound to a durable revision/fingerprint and cannot act as current financial authorization. This assesses existing product approval applicability only; enterprise approval is not implemented."
                    : "Approval review requirements are not satisfied.";
            return evidence.command(context, type(), DecisionStatus.REVIEW, reasons, explanation);
        }

        List<String> allowReasons = List.of("APPROVAL_CURRENT_BINDING_CONFIRMED");
        evidence.input("MAPPING", String.join("|", allowReasons));
        return evidence.command(context, type(), DecisionStatus.ALLOW, allowReasons,
                "Durable milestone approval binding confirmed and current. All applicability requirements satisfied.");
    }
}
