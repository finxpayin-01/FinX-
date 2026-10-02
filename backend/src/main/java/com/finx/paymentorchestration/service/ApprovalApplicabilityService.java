package com.finx.paymentorchestration.service;

import com.finx.agreement.entity.DealAgreement;
import com.finx.agreement.enums.AgreementStatus;
import com.finx.agreement.repository.DealAgreementRepository;
import com.finx.deal.entity.Deal;
import com.finx.deal.repository.DealRepository;
import com.finx.dispute.entity.Dispute;
import com.finx.dispute.entity.DisputeStatus;
import com.finx.dispute.repository.DisputeRepository;
import com.finx.invoice.entity.Invoice;
import com.finx.invoice.entity.InvoiceAssessment;
import com.finx.invoice.entity.InvoiceReview;
import com.finx.invoice.enums.InvoiceReviewStatus;
import com.finx.invoice.enums.InvoiceStatus;
import com.finx.invoice.repository.InvoiceAssessmentRepository;
import com.finx.invoice.repository.InvoiceRepository;
import com.finx.invoice.repository.InvoiceReviewRepository;
import com.finx.milestone.entity.Deliverable;
import com.finx.milestone.entity.DeliverableStatus;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.DeliverableRepository;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.risk.dto.RiskDtos.ReviewStatus;
import com.finx.risk.entity.RiskReview;
import com.finx.risk.repository.BusinessRiskRepository;
import com.finx.risk.service.RiskActionService;
import com.finx.verification.service.VerificationEligibilityService;
import com.finx.verification.service.VerificationEligibilityService.BeneficiaryEligibilityResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Read-only contract for evaluating whether historical approvals and reviews remain
 * currently applicable for financial decisioning, or whether subsequent material changes
 * (milestone edits, invoice revisions, agreement updates, beneficiary changes, PM reassignments,
 * disputes, or risk restrictions) have rendered them stale.
 *
 * Does NOT mutate milestones, move money, authorize releases, change beneficiaries, or call bank providers.
 */
@Service
public class ApprovalApplicabilityService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalApplicabilityService.class);

    private final DealRepository dealRepository;
    private final MilestoneRepository milestoneRepository;
    private final DeliverableRepository deliverableRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceReviewRepository invoiceReviewRepository;
    private final InvoiceAssessmentRepository invoiceAssessmentRepository;
    private final DealAgreementRepository agreementRepository;
    private final VerificationEligibilityService eligibilityService;
    private final DisputeRepository disputeRepository;
    private final RiskActionService riskActionService;
    private final BusinessRiskRepository businessRiskRepository;

    @Autowired
    public ApprovalApplicabilityService(
            DealRepository dealRepository,
            MilestoneRepository milestoneRepository,
            DeliverableRepository deliverableRepository,
            @Autowired(required = false) InvoiceRepository invoiceRepository,
            @Autowired(required = false) InvoiceReviewRepository invoiceReviewRepository,
            @Autowired(required = false) InvoiceAssessmentRepository invoiceAssessmentRepository,
            @Autowired(required = false) DealAgreementRepository agreementRepository,
            @Autowired(required = false) VerificationEligibilityService eligibilityService,
            @Autowired(required = false) DisputeRepository disputeRepository,
            @Autowired(required = false) RiskActionService riskActionService,
            @Autowired(required = false) BusinessRiskRepository businessRiskRepository) {
        this.dealRepository = dealRepository;
        this.milestoneRepository = milestoneRepository;
        this.deliverableRepository = deliverableRepository;
        this.invoiceRepository = invoiceRepository;
        this.invoiceReviewRepository = invoiceReviewRepository;
        this.invoiceAssessmentRepository = invoiceAssessmentRepository;
        this.agreementRepository = agreementRepository;
        this.eligibilityService = eligibilityService;
        this.disputeRepository = disputeRepository;
        this.riskActionService = riskActionService;
        this.businessRiskRepository = businessRiskRepository;
    }

    public record ApprovalApplicabilityResult(
            boolean applicable,
            List<String> reasonCodes,
            Instant observedAt,
            UUID dealId,
            UUID milestoneId,
            boolean milestoneApproved,
            boolean milestoneUnbound,
            boolean invoiceApplicable,
            boolean agreementApplicable,
            boolean beneficiaryApplicable,
            boolean pmAssignmentApplicable,
            boolean disputeApplicable,
            boolean riskApplicable,
            UUID invoiceId,
            String invoiceFingerprint,
            UUID invoiceAssessmentId,
            UUID agreementId,
            Integer agreementVersion,
            String agreementSha256,
            UUID beneficiaryAccountId,
            boolean isSandboxBeneficiary,
            UUID assignedProjectManagerId
    ) {}

    public record InvoiceApplicabilityResult(
            boolean applicable,
            String reasonCode,
            UUID invoiceId,
            String invoiceFingerprint,
            UUID assessmentId,
            UUID reviewId
    ) {}

    public record AgreementApplicabilityResult(
            boolean applicable,
            String reasonCode,
            UUID agreementId,
            Integer version,
            String documentSha256,
            AgreementStatus status
    ) {}

    public record BeneficiaryApplicabilityResult(
            boolean applicable,
            String reasonCode,
            UUID bankAccountId,
            boolean isSandbox
    ) {}

    public record DisputeApplicabilityResult(
            boolean applicable,
            String reasonCode,
            List<UUID> openDisputeIds
    ) {}

    public record RiskApplicabilityResult(
            boolean applicable,
            String reasonCode,
            List<UUID> activeRestrictionReviewIds
    ) {}

    public record PmAssignmentApplicabilityResult(
            boolean applicable,
            String reasonCode,
            UUID currentProjectManagerId,
            UUID approvingProjectManagerId
    ) {}

    /**
     * Evaluates comprehensive approval applicability for a deal milestone.
     */
    @Transactional(readOnly = true)
    public ApprovalApplicabilityResult evaluateApprovalApplicability(UUID dealId, UUID milestoneId) {
        Instant observedAt = Instant.now();
        List<String> reasons = new ArrayList<>();

        if (dealId == null || milestoneId == null) {
            reasons.add("INVALID_INPUT: dealId and milestoneId must not be null");
            return new ApprovalApplicabilityResult(false, reasons, observedAt, dealId, milestoneId,
                    false, true, false, false, false, false, false, false,
                    null, null, null, null, null, null, null, false, null);
        }

        Deal deal = dealRepository.findById(dealId).orElse(null);
        if (deal == null) {
            reasons.add("DEAL_NOT_FOUND: No deal found with id " + dealId);
            return new ApprovalApplicabilityResult(false, reasons, observedAt, dealId, milestoneId,
                    false, true, false, false, false, false, false, false,
                    null, null, null, null, null, null, null, false, null);
        }

        Milestone milestone = milestoneRepository.findById(milestoneId).orElse(null);
        if (milestone == null) {
            reasons.add("MILESTONE_NOT_FOUND: No milestone found with id " + milestoneId);
            return new ApprovalApplicabilityResult(false, reasons, observedAt, dealId, milestoneId,
                    false, true, false, false, false, false, false, false,
                    null, null, null, null, null, null, null, false, deal.getProjectManagerId());
        }

        if (!dealId.equals(milestone.getDealId())) {
            reasons.add("MILESTONE_DEAL_MISMATCH: Milestone " + milestoneId + " does not belong to deal " + dealId);
            return new ApprovalApplicabilityResult(false, reasons, observedAt, dealId, milestoneId,
                    false, true, false, false, false, false, false, false,
                    null, null, null, null, null, null, null, false, deal.getProjectManagerId());
        }

        // 1. Milestone Approval Status & Unbound Evaluation
        boolean milestoneApproved = milestone.getStatus() == MilestoneStatus.APPROVED || milestone.getStatus() == MilestoneStatus.COMPLETED;
        boolean milestoneUnbound = true; // In current schema, milestones lack persistent approval revision/fingerprint
        if (!milestoneApproved) {
            reasons.add("MILESTONE_NOT_APPROVED: Milestone status is " + milestone.getStatus());
        } else {
            reasons.add("UNBOUND_MILESTONE_APPROVAL: Milestone approval is historical status only without durable revision or fingerprint binding; cannot serve as current financial authorization");
        }

        // Check if milestone was modified after approval
        List<Deliverable> deliverables = deliverableRepository.findByMilestoneIdOrderBySubmittedAtDesc(milestoneId);
        Instant latestDeliverableReview = deliverables.stream()
                .map(Deliverable::getReviewedAt)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);

        if (latestDeliverableReview != null && milestone.getUpdatedAt() != null && milestone.getUpdatedAt().isAfter(latestDeliverableReview)) {
            reasons.add("MILESTONE_MODIFIED_AFTER_APPROVAL: Milestone attributes updated at " + milestone.getUpdatedAt() + " after deliverable review at " + latestDeliverableReview);
        }

        // 2. Invoice Freshness Evaluation
        InvoiceApplicabilityResult invoiceResult = evaluateInvoiceApplicability(milestone);
        if (!invoiceResult.applicable()) {
            reasons.add(invoiceResult.reasonCode());
        }

        // 3. Agreement Freshness Evaluation
        AgreementApplicabilityResult agreementResult = evaluateAgreementApplicability(dealId);
        if (!agreementResult.applicable()) {
            reasons.add(agreementResult.reasonCode());
        }

        // 4. Beneficiary Freshness Evaluation
        BeneficiaryApplicabilityResult beneficiaryResult = evaluateBeneficiaryApplicability(deal.getSellerOrganizationId());
        if (!beneficiaryResult.applicable()) {
            reasons.add(beneficiaryResult.reasonCode());
        }

        // 5. PM Assignment Freshness Evaluation
        PmAssignmentApplicabilityResult pmResult = evaluatePmAssignmentApplicability(deal, deliverables);
        if (!pmResult.applicable()) {
            reasons.add(pmResult.reasonCode());
        }

        // 6. Dispute Freshness Evaluation
        DisputeApplicabilityResult disputeResult = evaluateDisputeApplicability(dealId, milestoneId);
        if (!disputeResult.applicable()) {
            reasons.add(disputeResult.reasonCode());
        }

        // 7. Risk / Policy Freshness Evaluation
        RiskApplicabilityResult riskResult = evaluateRiskApplicability(deal, milestone);
        if (!riskResult.applicable()) {
            reasons.add(riskResult.reasonCode());
        }

        // For financial authorization: must be bound and all components fresh.
        // Since milestoneUnbound is true in current schema (and payout is disabled under B04), overall applicable is false.
        boolean overallApplicable = milestoneApproved && !milestoneUnbound
                && invoiceResult.applicable()
                && agreementResult.applicable()
                && beneficiaryResult.applicable()
                && pmResult.applicable()
                && disputeResult.applicable()
                && riskResult.applicable();

        return new ApprovalApplicabilityResult(
                overallApplicable,
                reasons,
                observedAt,
                dealId,
                milestoneId,
                milestoneApproved,
                milestoneUnbound,
                invoiceResult.applicable(),
                agreementResult.applicable(),
                beneficiaryResult.applicable(),
                pmResult.applicable(),
                disputeResult.applicable(),
                riskResult.applicable(),
                invoiceResult.invoiceId(),
                invoiceResult.invoiceFingerprint(),
                invoiceResult.assessmentId(),
                agreementResult.agreementId(),
                agreementResult.version(),
                agreementResult.documentSha256(),
                beneficiaryResult.bankAccountId(),
                beneficiaryResult.isSandbox(),
                deal.getProjectManagerId()
        );
    }

    /**
     * Evaluates invoice review freshness against current matching evidence and revisions.
     */
    @Transactional(readOnly = true)
    public InvoiceApplicabilityResult evaluateInvoiceApplicability(Milestone milestone) {
        if (!milestone.isInvoiceRequired()) {
            return new InvoiceApplicabilityResult(true, null, null, null, null, null);
        }

        if (invoiceRepository == null) {
            return new InvoiceApplicabilityResult(false, "INVOICE_REPOSITORY_UNAVAILABLE", null, null, null, null);
        }

        Optional<Invoice> latestInvoiceOpt = invoiceRepository.findFirstByMilestoneIdOrderByCreatedAtDesc(milestone.getId());
        if (latestInvoiceOpt.isEmpty()) {
            return new InvoiceApplicabilityResult(false, "INVOICE_MISSING: Milestone requires invoice but none exists", null, null, null, null);
        }

        Invoice invoice = latestInvoiceOpt.get();
        UUID invoiceId = invoice.getId();

        if (invoice.getStatus() != InvoiceStatus.APPROVED) {
            return new InvoiceApplicabilityResult(false, "INVOICE_NOT_APPROVED: Latest invoice status is " + invoice.getStatus(),
                    invoiceId, null, null, null);
        }

        if (invoiceReviewRepository == null || invoiceAssessmentRepository == null) {
            return new InvoiceApplicabilityResult(false, "INVOICE_REVIEW_DATA_UNAVAILABLE", invoiceId, null, null, null);
        }

        // Must have an approved review
        Optional<InvoiceReview> reviewOpt = invoiceReviewRepository.findFirstByInvoiceIdAndStatus(invoiceId, InvoiceReviewStatus.APPROVED);
        if (reviewOpt.isEmpty()) {
            return new InvoiceApplicabilityResult(false, "INVOICE_REVIEW_NOT_APPROVED: No APPROVED review recorded for invoice " + invoiceId,
                    invoiceId, null, null, null);
        }
        InvoiceReview review = reviewOpt.get();

        // Must have matching latest assessment
        Optional<InvoiceAssessment> assessmentOpt = invoiceAssessmentRepository.findFirstByInvoiceIdOrderByCreatedAtDesc(invoiceId);
        if (assessmentOpt.isEmpty()) {
            return new InvoiceApplicabilityResult(false, "INVOICE_ASSESSMENT_MISSING: No assessment recorded for invoice " + invoiceId,
                    invoiceId, null, null, review.getId());
        }
        InvoiceAssessment latestAssessment = assessmentOpt.get();

        // Review must be bound to the latest assessment (not superseded evidence)
        if (review.getAssessmentId() == null || !review.getAssessmentId().equals(latestAssessment.id)) {
            return new InvoiceApplicabilityResult(false, "INVOICE_REVIEW_SUPERSEDED: Review was recorded for superseded assessment " + review.getAssessmentId() + ", latest is " + latestAssessment.id,
                    invoiceId, latestAssessment.fingerprint, latestAssessment.id, review.getId());
        }

        // Invoice must not have been modified after review was recorded
        if (review.getReviewedAt() != null && invoice.getUpdatedAt() != null && invoice.getUpdatedAt().isAfter(review.getReviewedAt())) {
            return new InvoiceApplicabilityResult(false, "INVOICE_MODIFIED_AFTER_REVIEW: Invoice updatedAt " + invoice.getUpdatedAt() + " is after review at " + review.getReviewedAt(),
                    invoiceId, latestAssessment.fingerprint, latestAssessment.id, review.getId());
        }

        return new InvoiceApplicabilityResult(true, null, invoiceId, latestAssessment.fingerprint, latestAssessment.id, review.getId());
    }

    /**
     * Evaluates agreement signing freshness for the deal.
     */
    @Transactional(readOnly = true)
    public AgreementApplicabilityResult evaluateAgreementApplicability(UUID dealId) {
        if (agreementRepository == null) {
            return new AgreementApplicabilityResult(false, "AGREEMENT_REPOSITORY_UNAVAILABLE", null, null, null, null);
        }

        Optional<DealAgreement> latestAgreementOpt = agreementRepository.findTopByDealIdOrderByVersionDesc(dealId);
        if (latestAgreementOpt.isEmpty()) {
            return new AgreementApplicabilityResult(false, "AGREEMENT_MISSING: No agreement exists for deal " + dealId, null, null, null, null);
        }

        DealAgreement agreement = latestAgreementOpt.get();
        if (agreement.getStatus() != AgreementStatus.SIGNED) {
            return new AgreementApplicabilityResult(false,
                    "AGREEMENT_NOT_SIGNED: Latest agreement v" + agreement.getVersion() + " status is " + agreement.getStatus() + " (not SIGNED)",
                    agreement.getId(), agreement.getVersion(), agreement.getDocumentSha256(), agreement.getStatus());
        }

        return new AgreementApplicabilityResult(true, null, agreement.getId(), agreement.getVersion(), agreement.getDocumentSha256(), agreement.getStatus());
    }

    /**
     * Evaluates beneficiary eligibility freshness reusing B09 boundary.
     */
    @Transactional(readOnly = true)
    public BeneficiaryApplicabilityResult evaluateBeneficiaryApplicability(UUID sellerOrganizationId) {
        if (sellerOrganizationId == null) {
            return new BeneficiaryApplicabilityResult(false, "SELLER_ORGANIZATION_MISSING: Deal has no seller organization bound", null, false);
        }

        if (eligibilityService == null) {
            return new BeneficiaryApplicabilityResult(false, "ELIGIBILITY_SERVICE_UNAVAILABLE", null, false);
        }

        BeneficiaryEligibilityResult result = eligibilityService.evaluateBeneficiaryEligibility(sellerOrganizationId);
        if (!result.eligible()) {
            return new BeneficiaryApplicabilityResult(false, "BENEFICIARY_INELIGIBLE: " + result.failureReason(), null, result.isSandbox());
        }

        return new BeneficiaryApplicabilityResult(true, null, result.bankAccount().getId(), result.isSandbox());
    }

    /**
     * Evaluates whether PM assignment has changed since approvals occurred.
     */
    @Transactional(readOnly = true)
    public PmAssignmentApplicabilityResult evaluatePmAssignmentApplicability(Deal deal, List<Deliverable> deliverables) {
        UUID currentPmId = deal.getProjectManagerId();

        for (Deliverable d : deliverables) {
            if (d.getStatus() == DeliverableStatus.APPROVED && d.getReviewedBy() != null) {
                UUID reviewerId = d.getReviewedBy();
                // If reviewed by the buyer directly, that authority is direct party authority
                if (reviewerId.equals(deal.getBuyerId())) {
                    continue;
                }
                // If reviewed by a PM, that reviewer must still be the actively assigned PM on the deal
                if (currentPmId == null) {
                    return new PmAssignmentApplicabilityResult(false,
                            "PM_ASSIGNMENT_CHANGED: Deliverable " + d.getId() + " was approved by PM " + reviewerId + " but deal currently has no assigned PM",
                            null, reviewerId);
                }
                if (!currentPmId.equals(reviewerId)) {
                    return new PmAssignmentApplicabilityResult(false,
                            "PM_ASSIGNMENT_CHANGED: Deliverable " + d.getId() + " was approved by former PM " + reviewerId + " but current assigned PM is " + currentPmId,
                            currentPmId, reviewerId);
                }
            }
        }

        return new PmAssignmentApplicabilityResult(true, null, currentPmId, null);
    }

    /**
     * Evaluates dispute state on deal and milestone.
     */
    @Transactional(readOnly = true)
    public DisputeApplicabilityResult evaluateDisputeApplicability(UUID dealId, UUID milestoneId) {
        if (disputeRepository == null) {
            return new DisputeApplicabilityResult(true, null, Collections.emptyList());
        }

        List<Dispute> openDisputes = disputeRepository.findByDealIdOrderByCreatedAtDesc(dealId).stream()
                .filter(d -> d.getStatus() == DisputeStatus.OPEN)
                .toList();

        if (!openDisputes.isEmpty()) {
            List<UUID> openIds = openDisputes.stream().map(Dispute::getId).toList();
            boolean onMilestone = openDisputes.stream().anyMatch(d -> milestoneId.equals(d.getMilestoneId()));
            String code = onMilestone
                    ? "DISPUTE_OPEN_ON_MILESTONE: Milestone has active open dispute(s)"
                    : "DISPUTE_OPEN_ON_DEAL: Deal has active open dispute(s)";
            return new DisputeApplicabilityResult(false, code, openIds);
        }

        return new DisputeApplicabilityResult(true, null, Collections.emptyList());
    }

    /**
     * Evaluates whether subsequent risk restrictions or policy holds invalidate prior approval.
     */
    @Transactional(readOnly = true)
    public RiskApplicabilityResult evaluateRiskApplicability(Deal deal, Milestone milestone) {
        if (businessRiskRepository == null) {
            return new RiskApplicabilityResult(true, null, Collections.emptyList());
        }

        try {
            // Check active RESTRICTED reviews on deal parties
            List<RiskReview> activeRestrictions = businessRiskRepository.query(
                    RiskReview.class,
                    "from RiskReview where status = :status and restrictionUntil is not null and restrictionUntil > :now",
                    Map.of("status", ReviewStatus.RESTRICTED, "now", Instant.now()),
                    100
            );

            if (!activeRestrictions.isEmpty()) {
                List<UUID> restrictionIds = activeRestrictions.stream().map(r -> r.id).toList();
                return new RiskApplicabilityResult(false,
                        "RISK_RESTRICTION_ACTIVE: Active risk restriction(s) present on platform subject(s)",
                        restrictionIds);
            }
        } catch (Exception e) {
            log.warn("Could not query risk restrictions: {}", e.getMessage());
        }

        return new RiskApplicabilityResult(true, null, Collections.emptyList());
    }
}
