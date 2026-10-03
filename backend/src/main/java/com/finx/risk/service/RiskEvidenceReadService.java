package com.finx.risk.service;

import com.finx.deal.entity.Deal;
import com.finx.deal.repository.DealRepository;
import com.finx.deal.service.DealAccessService;
import com.finx.exception.ResourceNotFoundException;
import com.finx.invoice.entity.Invoice;
import com.finx.invoice.entity.InvoiceAssessment;
import com.finx.invoice.enums.ExtractionStatus;
import com.finx.invoice.enums.InvoiceStatus;
import com.finx.invoice.repository.InvoiceAssessmentRepository;
import com.finx.invoice.repository.InvoiceRepository;
import com.finx.risk.dto.RiskDtos.*;
import com.finx.risk.dto.RiskEvidenceDtos.*;
import com.finx.risk.entity.RiskAssessment;
import com.finx.risk.entity.RiskReview;
import com.finx.risk.entity.RiskScore;
import com.finx.risk.repository.BusinessRiskRepository;
import com.finx.security.service.UserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dedicated read-only service for deal risk evidence snapshots and persisted assessment references.
 *
 * Guarantees:
 * - Pure read-only inspection (zero writes, zero risk history appends, zero reviews created, zero events emitted)
 * - Durable source IDs exist in the database (no transient UUIDs cited as durable evidence)
 * - Counterparties (Buyer and Vendor) remain independent
 * - Invoice assessment identity and manual extraction provenance are preserved
 * - B07 access authorization is enforced for user reads
 */
@Service
@Transactional(readOnly = true)
public class RiskEvidenceReadService {

    private final DealRepository dealRepository;
    private final DealAccessService dealAccessService;
    private final BusinessRiskRepository db;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceAssessmentRepository invoiceAssessmentRepository;

    public RiskEvidenceReadService(DealRepository dealRepository,
                                  DealAccessService dealAccessService,
                                  BusinessRiskRepository db,
                                  InvoiceRepository invoiceRepository,
                                  InvoiceAssessmentRepository invoiceAssessmentRepository) {
        this.dealRepository = dealRepository;
        this.dealAccessService = dealAccessService;
        this.db = db;
        this.invoiceRepository = invoiceRepository;
        this.invoiceAssessmentRepository = invoiceAssessmentRepository;
    }

    /**
     * Read-only evidence retrieval authorized for current user under B07 deal access rules.
     */
    public DealRiskEvidenceSnapshot getDealRiskEvidence(UUID dealId, UserPrincipal currentUser) {
        Deal deal = dealRepository.findById(dealId)
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", dealId));
        if (currentUser != null) {
            dealAccessService.requireDealAccess(deal, currentUser);
        }
        return inspectDeal(deal);
    }

    /**
     * Internal read-only evidence retrieval (for internal framework or system inspections).
     */
    public DealRiskEvidenceSnapshot getDealRiskEvidenceInternal(UUID dealId) {
        Deal deal = dealRepository.findById(dealId)
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", dealId));
        return inspectDeal(deal);
    }

    private DealRiskEvidenceSnapshot inspectDeal(Deal deal) {
        SubjectRiskEvidence buyerEvidence = resolveSubjectEvidence(
                deal.getBuyerId(), deal.getBuyerOrganizationId(), UserType.BUYER);

        SubjectRiskEvidence vendorEvidence = deal.getSellerId() != null
                ? resolveSubjectEvidence(deal.getSellerId(), deal.getSellerOrganizationId(), UserType.VENDOR)
                : SubjectRiskEvidence.missing(null, null, UserType.VENDOR);

        InvoiceRiskEvidence invoiceEvidence = resolveInvoiceEvidence(deal.getId());

        boolean reviewRequired = (buyerEvidence.reviewRequired() != null && buyerEvidence.reviewRequired())
                || (vendorEvidence.reviewRequired() != null && vendorEvidence.reviewRequired())
                || (invoiceEvidence.invoiceStatus() == InvoiceStatus.REVIEW_REQUIRED
                    || invoiceEvidence.invoiceStatus() == InvoiceStatus.PARTIAL_MATCH);

        boolean effectiveRestrictionActive = buyerEvidence.effectiveRestrictionActive()
                || vendorEvidence.effectiveRestrictionActive();

        String summary = String.format("Buyer risk: %s, Vendor risk: %s, Invoice status: %s, Restriction active: %b",
                buyerEvidence.status(), vendorEvidence.status(), invoiceEvidence.status(), effectiveRestrictionActive);

        return new DealRiskEvidenceSnapshot(
                deal.getId(),
                buyerEvidence,
                vendorEvidence,
                invoiceEvidence,
                Instant.now(),
                false, // hasDurableAggregate: deal aggregate is transient; counterparty and invoice records are durable
                reviewRequired,
                effectiveRestrictionActive,
                summary
        );
    }

    private SubjectRiskEvidence resolveSubjectEvidence(UUID userId, UUID organizationId, UserType type) {
        if (userId == null && organizationId == null) {
            return SubjectRiskEvidence.missing(null, null, type);
        }

        String key = (organizationId == null ? "USER:" + userId : "ORG:" + organizationId) + ":" + type;
        var scores = db.query(RiskScore.class, "from RiskScore where subjectKey=:key", Map.of("key", key), 1);
        if (scores.isEmpty() && organizationId != null) {
            scores = db.query(RiskScore.class, "from RiskScore where organizationId=:orgId and userType=:type",
                    Map.of("orgId", organizationId, "type", type), 1);
        }
        if (scores.isEmpty() && userId != null) {
            scores = db.query(RiskScore.class, "from RiskScore where userId=:userId and userType=:type",
                    Map.of("userId", userId, "type", type), 1);
        }

        if (scores.isEmpty()) {
            return SubjectRiskEvidence.missing(userId, organizationId, type);
        }

        RiskScore subject = scores.getFirst();
        if (subject.assessmentId == null) {
            return new SubjectRiskEvidence(
                    subject.id,
                    userId,
                    organizationId,
                    type,
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
                    subject.nextRefreshAt,
                    List.of(),
                    List.of()
            );
        }

        boolean isStale = subject.nextRefreshAt != null && subject.nextRefreshAt.isBefore(Instant.now());
        EvidenceStatus status = isStale ? EvidenceStatus.STALE : EvidenceStatus.PRESENT;

        Assessment assessment = db.current(subject);
        var assessmentRows = db.query(RiskAssessment.class, "from RiskAssessment where id=:id", Map.of("id", subject.assessmentId), 1);
        RiskAssessment row = assessmentRows.isEmpty() ? null : assessmentRows.getFirst();

        var reviews = db.query(RiskReview.class, "from RiskReview where subjectId=:id order by createdAt desc", Map.of("id", subject.id), 10);
        RiskReview activeRestriction = reviews.stream()
                .filter(r -> r.status == ReviewStatus.RESTRICTED && r.restrictionUntil != null && r.restrictionUntil.isAfter(Instant.now()))
                .findFirst().orElse(null);

        boolean effectiveRestriction = activeRestriction != null;
        UUID activeRestrictionId = activeRestriction != null ? activeRestriction.id : null;
        Instant restrictionUntil = activeRestriction != null ? activeRestriction.restrictionUntil : null;
        String restrictionReason = activeRestriction != null ? activeRestriction.reason : null;

        ReviewStatus latestReviewStatus = reviews.isEmpty()
                ? (assessment != null && assessment.reviewRequired() ? ReviewStatus.OPEN : ReviewStatus.CLEARED)
                : reviews.getFirst().status;

        boolean reviewReq = (assessment != null && assessment.reviewRequired()) || (latestReviewStatus != ReviewStatus.CLEARED);

        Instant observedAt = subject.calculatedAt != null
                ? subject.calculatedAt
                : (row != null && row.getCreatedAt() != null ? row.getCreatedAt() : (assessment != null ? assessment.calculatedAt() : null));

        String ruleVersion = (row != null && row.ruleVersion != null && !row.ruleVersion.isBlank())
                ? row.ruleVersion
                : (assessment != null && assessment.ruleVersion() != null && !assessment.ruleVersion().isBlank() ? assessment.ruleVersion() : null);

        List<String> reasonCodes = assessment != null && assessment.reasonCodes() != null ? assessment.reasonCodes() : List.of();
        List<String> humanReasons = assessment != null && assessment.humanReadableReasons() != null ? assessment.humanReadableReasons() : List.of();

        return new SubjectRiskEvidence(
                subject.id,
                subject.userId,
                subject.organizationId,
                type,
                subject.assessmentId,
                status,
                subject.score,
                subject.riskLevel,
                reviewReq,
                latestReviewStatus,
                effectiveRestriction,
                activeRestrictionId,
                restrictionUntil,
                restrictionReason,
                ruleVersion,
                observedAt,
                subject.nextRefreshAt,
                reasonCodes,
                humanReasons
        );
    }

    private InvoiceRiskEvidence resolveInvoiceEvidence(UUID dealId) {
        List<Invoice> invoiceList = invoiceRepository.findByDealIdOrderByCreatedAtDesc(dealId);
        if (invoiceList.isEmpty()) {
            return InvoiceRiskEvidence.missing();
        }

        Invoice inv = invoiceList.getFirst();
        var optAss = invoiceAssessmentRepository.findFirstByInvoiceIdOrderByCreatedAtDesc(inv.getId());
        if (optAss.isEmpty()) {
            boolean isManual = inv.getExtractionStatus() == ExtractionStatus.MANUAL_ENTRY;
            String provenance = isManual ? "MANUAL_ENTRY" : (inv.getExtractionStatus() != null ? inv.getExtractionStatus().name() : "UNKNOWN");
            return new InvoiceRiskEvidence(
                    inv.getId(),
                    null,
                    null,
                    inv.getRevisionOf(),
                    inv.getStatus(),
                    inv.getExtractionStatus(),
                    provenance,
                    isManual,
                    inv.getMatchScore(),
                    inv.getMatchingStatus(),
                    inv.getDuplicateConfidence(),
                    inv.getPolicyVersion(),
                    inv.getCreatedAt(),
                    EvidenceStatus.UNASSESSED
            );
        }

        InvoiceAssessment invAss = optAss.get();
        boolean isManual = inv.getExtractionStatus() == ExtractionStatus.MANUAL_ENTRY;
        String provenance = isManual ? "MANUAL_ENTRY" : (inv.getExtractionStatus() != null ? inv.getExtractionStatus().name() : "EXTRACTED");

        return new InvoiceRiskEvidence(
                inv.getId(),
                invAss.id,
                invAss.fingerprint,
                inv.getRevisionOf(),
                inv.getStatus(),
                inv.getExtractionStatus(),
                provenance,
                isManual,
                inv.getMatchScore(),
                inv.getMatchingStatus(),
                inv.getDuplicateConfidence(),
                inv.getPolicyVersion(),
                invAss.createdAt,
                EvidenceStatus.PRESENT
        );
    }
}
