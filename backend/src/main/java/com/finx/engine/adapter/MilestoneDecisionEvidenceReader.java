package com.finx.engine.adapter;

import com.finx.agreement.entity.DealAgreement;
import com.finx.agreement.repository.DealAgreementRepository;
import com.finx.deal.entity.Deal;
import com.finx.deal.repository.DealRepository;
import com.finx.deal.service.DealAccessService;
import com.finx.dispute.entity.*;
import com.finx.dispute.repository.DisputeRepository;
import com.finx.engine.model.*;
import com.finx.exception.UnauthorizedException;
import com.finx.invoice.entity.*;
import com.finx.invoice.enums.*;
import com.finx.invoice.repository.*;
import com.finx.milestone.entity.*;
import com.finx.milestone.repository.*;
import com.finx.org.repository.OrganizationRepository;
import com.finx.paymentorchestration.service.ApprovalApplicabilityService;
import com.finx.security.service.UserPrincipal;
import org.springframework.stereotype.Component;
import java.util.*;

/** Shared source reads for the two milestone adapters. Caller/scope checks precede evidence reads. */
@Component
public class MilestoneDecisionEvidenceReader {
    private final DealRepository deals;
    private final MilestoneRepository milestones;
    private final OrganizationRepository organizations;
    private final DealAccessService access;
    private final DeliverableRepository deliverables;
    private final DealAgreementRepository agreements;
    private final DisputeRepository disputes;
    private final InvoiceRepository invoices;
    private final InvoiceAssessmentRepository assessments;
    private final InvoiceReviewRepository reviews;
    private final ApprovalApplicabilityService applicability;

    public MilestoneDecisionEvidenceReader(DealRepository deals, MilestoneRepository milestones,
            OrganizationRepository organizations, DealAccessService access, DeliverableRepository deliverables,
            DealAgreementRepository agreements, DisputeRepository disputes, InvoiceRepository invoices,
            InvoiceAssessmentRepository assessments, InvoiceReviewRepository reviews,
            ApprovalApplicabilityService applicability) {
        this.deals = deals; this.milestones = milestones; this.organizations = organizations;
        this.access = access; this.deliverables = deliverables; this.agreements = agreements;
        this.disputes = disputes; this.invoices = invoices; this.assessments = assessments;
        this.reviews = reviews; this.applicability = applicability;
    }

    record Sources(Deal deal, Milestone milestone, DealAgreement agreement, List<Deliverable> deliverables,
                   List<Dispute> disputes, Invoice invoice, InvoiceAssessment assessment,
                   List<InvoiceReview> reviews, boolean invoiceCurrent, DecisionEvidence evidence) {}

    Sources read(EvaluationContext context, UUID expectedDealId, UserPrincipal caller) {
        Objects.requireNonNull(context);
        if (!DecisionSubjectType.MILESTONE.name().equals(context.subjectType())) {
            throw new IllegalArgumentException("Expected MILESTONE subject");
        }
        Milestone milestone = milestones.findById(context.subjectId()).orElseThrow(MilestoneDecisionEvidenceReader::scopeDenied);
        if (milestone.getDealId() == null || !context.subjectId().equals(milestone.getId())
                || (expectedDealId != null && !expectedDealId.equals(milestone.getDealId()))) throw scopeDenied();
        Deal deal = deals.findById(milestone.getDealId()).orElseThrow(MilestoneDecisionEvidenceReader::scopeDenied);
        if (!milestone.getDealId().equals(deal.getId()) || deal.getBuyerOrganizationId() == null
                || !context.organizationId().equals(deal.getBuyerOrganizationId())
                || !organizations.existsById(deal.getBuyerOrganizationId())) throw scopeDenied();
        if (caller != null) access.requireDealAccess(deal, caller);

        DecisionEvidence evidence = new DecisionEvidence();
        evidence.reference("DEAL", deal.getId(), deal.getUpdatedAt(), null, "DEAL_REPOSITORY", null);
        evidence.input("DEAL", deal.getId(), deal.getBuyerOrganizationId(), deal.getSellerOrganizationId(),
                deal.getBuyerId(), deal.getSellerId(), deal.getProjectManagerId(), deal.getUpdatedAt(),
                deal.getTotalAmount(), deal.getCurrency(), deal.getStatus());
        milestoneEvidence(evidence, milestone);
        DealAgreement agreement = agreements.findTopByDealIdOrderByVersionDesc(deal.getId()).orElse(null);
        if (agreement != null) {
            requireScope(deal.getId().equals(agreement.getDealId()));
            evidence.reference("AGREEMENT", agreement.getId(), agreement.getUpdatedAt(),
                    agreement.getDocumentSha256(), "AGREEMENT_REPOSITORY", Integer.toString(agreement.getVersion()));
            evidence.input("AGREEMENT", agreement.getId(), agreement.getVersion(), agreement.getDocumentSha256(),
                    agreement.getStatus(), agreement.getUpdatedAt(), agreement.getBuyerAcceptedAt(), agreement.getSellerAcceptedAt());
        } else evidence.input("AGREEMENT_MISSING");
        List<Deliverable> submitted = deliverables.findByMilestoneIdOrderBySubmittedAtDesc(milestone.getId()).stream()
                .sorted(Comparator.comparing(Deliverable::getId)).toList();
        for (Deliverable d : submitted) {
            requireScope(milestone.getId().equals(d.getMilestoneId()));
            evidence.reference("DELIVERABLE", d.getId(), d.getSubmittedAt(), null, "DELIVERABLE_REPOSITORY", null);
            evidence.input("DELIVERABLE", d.getId(), d.getStatus(), d.getReviewedAt(), d.getReviewedBy(), d.getSubmittedAt());
        }
        List<Dispute> open = disputes.findByDealIdOrderByCreatedAtDesc(deal.getId()).stream()
                .filter(d -> d.getStatus() == DisputeStatus.OPEN).sorted(Comparator.comparing(Dispute::getId)).toList();
        for (Dispute d : open) {
            requireScope(deal.getId().equals(d.getDealId()));
            evidence.reference("DISPUTE", d.getId(), d.getCreatedAt(), null, "DISPUTE_REPOSITORY", null);
            evidence.input("DISPUTE", d.getId(), d.getMilestoneId(), d.getStatus());
        }
        Invoice invoice = milestone.isInvoiceRequired()
                ? invoices.findFirstByMilestoneIdOrderByCreatedAtDesc(milestone.getId()).orElse(null) : null;
        InvoiceAssessment assessment = null;
        List<InvoiceReview> invoiceReviews = List.of();
        boolean current = !milestone.isInvoiceRequired();
        if (invoice != null) {
            requireScope(deal.getId().equals(invoice.getDealId()) && milestone.getId().equals(invoice.getMilestoneId())
                    && context.organizationId().equals(invoice.getBuyerOrganizationId())
                    && Objects.equals(deal.getSellerOrganizationId(), invoice.getVendorOrganizationId()));
            String provenance = invoice.getExtractionStatus() == null ? "UNKNOWN" : invoice.getExtractionStatus().name();
            evidence.reference("INVOICE", invoice.getId(), invoice.getUpdatedAt(), invoice.getDocumentHash(), provenance,
                    invoice.getRevisionOf() == null ? null : invoice.getRevisionOf().toString());
            evidence.input("INVOICE", invoice.getId(), invoice.getRevisionOf(), invoice.getUpdatedAt(), invoice.getStatus(),
                    invoice.getDocumentHash(), invoice.getExtractionStatus());
            assessment = assessments.findFirstByInvoiceIdOrderByCreatedAtDesc(invoice.getId()).orElse(null);
            if (assessment != null) {
                requireScope(invoice.getId().equals(assessment.invoiceId));
                evidence.reference("INVOICE_ASSESSMENT", assessment.id, assessment.createdAt, assessment.fingerprint, provenance, null);
                evidence.input("INVOICE_ASSESSMENT", assessment.id, assessment.fingerprint, assessment.createdAt);
            }
            invoiceReviews = reviews.findByInvoiceIdOrderByCreatedAtDesc(invoice.getId()).stream()
                    .sorted(Comparator.comparing(InvoiceReview::getId)).toList();
            for (InvoiceReview review : invoiceReviews) {
                requireScope(review.getInvoice() != null && invoice.getId().equals(review.getInvoice().getId()));
                evidence.reference("INVOICE_REVIEW", review.getId(), review.getReviewedAt(), null, "HUMAN_REVIEW", null);
                evidence.input("INVOICE_REVIEW", review.getId(), review.getAssessmentId(), review.getStatus(),
                        review.getDecision(), review.getReviewerId(), review.getReviewedAt(), review.getUpdatedAt());
            }
            var nativeInvoice = applicability.evaluateInvoiceApplicability(milestone);
            InvoiceAssessment latest = assessment;
            current = nativeInvoice.applicable() && invoice.getId().equals(nativeInvoice.invoiceId())
                    && latest != null && latest.id.equals(nativeInvoice.assessmentId())
                    && invoiceReviews.stream().anyMatch(r -> r.getId().equals(nativeInvoice.reviewId())
                        && r.getStatus() == InvoiceReviewStatus.APPROVED && r.getDecision() == ReviewDecision.APPROVE
                        && r.getReviewerId() != null && r.getReviewedAt() != null
                        && latest.id.equals(r.getAssessmentId()) && !r.getReviewedAt().isBefore(latest.createdAt)
                        && invoice.getUpdatedAt() != null && !invoice.getUpdatedAt().isAfter(r.getReviewedAt()));
        } else evidence.input(milestone.isInvoiceRequired() ? "INVOICE_MISSING" : "INVOICE_NOT_REQUIRED");
        evidence.input("INVOICE_CURRENT", current);
        return new Sources(deal, milestone, agreement, submitted, open, invoice, assessment, invoiceReviews, current, evidence);
    }

    static void milestoneEvidence(DecisionEvidence evidence, Milestone m) {
        evidence.reference("MILESTONE", m.getId(), m.getUpdatedAt(), null, "MILESTONE_REPOSITORY", null);
        evidence.input("MILESTONE", m.getId(), m.getDealId(), m.getUpdatedAt(), m.getAmount(), m.getCurrency(),
                m.getSequence(), m.getStatus(), m.isInvoiceRequired(), m.getSubmissionDeadline(), m.getDueDate());
    }

    static void requireScope(boolean matches) { if (!matches) throw scopeDenied(); }
    private static UnauthorizedException scopeDenied() {
        return new UnauthorizedException("Milestone evaluation scope is unavailable or unauthorized");
    }
}
