package com.finx.engine.adapter;

import com.finx.engine.model.*;
import com.finx.exception.ResourceNotFoundException;
import com.finx.invoice.entity.Invoice;
import com.finx.invoice.entity.InvoiceAssessment;
import com.finx.invoice.entity.InvoiceReview;
import com.finx.invoice.enums.*;
import com.finx.invoice.repository.InvoiceAssessmentRepository;
import com.finx.invoice.repository.InvoiceRepository;
import com.finx.invoice.repository.InvoiceReviewRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

/**
 * Read-only decision adapter for the DOCUMENT engine.
 * <p>
 * Guarantees:
 * - Pure read-only inspection consuming persisted InvoiceAssessment and Invoice records
 * - Never mutates invoices, assessments, or review records
 * - Never runs OCR, external AI providers, or document intelligence pipelines
 * - Evaluation owner bound to buyer organization scope
 * - Subject is INVOICE with subjectId = invoice.id
 * - Durable evidence references cite persisted InvoiceAssessment.id and InvoiceReview.id
 * - Manual extraction provenance is strictly preserved (never mislabeled AI/OCR)
 * - Missing assessments, superseded revisions, and un-cleared reviews map to REVIEW
 * - Stale approvals (under B10 freshness rules) map to REVIEW, never ALLOW
 * - Known hard prohibitions (REJECTED, MAJOR_MISMATCH, EXACT_DUPLICATE) map to BLOCK
 * - Verified, current approved assessments map to ALLOW
 * - Zero invoice writes, zero risk events, zero banking/ledger/escrow side effects
 */
@Component
@Transactional(readOnly = true)
public class DocumentDecisionAdapter implements ReadOnlyDecisionAdapter {

    public static final String REASON_DOCUMENT_APPROVED = "DOCUMENT_APPROVED";
    public static final String REASON_DOCUMENT_ASSESSMENT_MISSING = "DOCUMENT_ASSESSMENT_MISSING";
    public static final String REASON_DOCUMENT_SUPERSEDED_REVISION = "DOCUMENT_SUPERSEDED_REVISION";
    public static final String REASON_DOCUMENT_ASSESSMENT_STALE = "DOCUMENT_ASSESSMENT_STALE";
    public static final String REASON_DOCUMENT_REVIEW_REQUIRED = "DOCUMENT_REVIEW_REQUIRED";
    public static final String REASON_DOCUMENT_REVIEW_PENDING = "DOCUMENT_REVIEW_PENDING";
    public static final String REASON_DOCUMENT_REVIEW_NOT_APPROVED = "DOCUMENT_REVIEW_NOT_APPROVED";
    public static final String REASON_DOCUMENT_REVIEW_SUPERSEDED = "DOCUMENT_REVIEW_SUPERSEDED";
    public static final String REASON_DOCUMENT_MODIFIED_AFTER_REVIEW = "DOCUMENT_MODIFIED_AFTER_REVIEW";
    public static final String REASON_DOCUMENT_REJECTED = "DOCUMENT_REJECTED";
    public static final String REASON_DOCUMENT_MAJOR_MISMATCH = "DOCUMENT_MAJOR_MISMATCH";
    public static final String REASON_DOCUMENT_EXACT_DUPLICATE = "DOCUMENT_EXACT_DUPLICATE";

    private final InvoiceRepository invoiceRepository;
    private final InvoiceAssessmentRepository invoiceAssessmentRepository;
    private final InvoiceReviewRepository invoiceReviewRepository;

    public DocumentDecisionAdapter(
            InvoiceRepository invoiceRepository,
            InvoiceAssessmentRepository invoiceAssessmentRepository,
            InvoiceReviewRepository invoiceReviewRepository
    ) {
        this.invoiceRepository = Objects.requireNonNull(invoiceRepository, "invoiceRepository must not be null");
        this.invoiceAssessmentRepository = Objects.requireNonNull(invoiceAssessmentRepository, "invoiceAssessmentRepository must not be null");
        this.invoiceReviewRepository = Objects.requireNonNull(invoiceReviewRepository, "invoiceReviewRepository must not be null");
    }

    @Override
    public EngineType type() {
        return EngineType.DOCUMENT;
    }

    @Override
    public RecordEngineDecisionCommand evaluate(EvaluationContext context) {
        Objects.requireNonNull(context, "context must not be null");
        Invoice invoice = invoiceRepository.findById(context.subjectId())
                .orElseThrow(() -> new ResourceNotFoundException("Invoice", "id", context.subjectId()));
        return evaluateInvoice(invoice, context);
    }

    /**
     * Evaluates a specific invoice entity within the given evaluation context.
     */
    public RecordEngineDecisionCommand evaluateInvoice(Invoice invoice, EvaluationContext context) {
        Objects.requireNonNull(invoice, "invoice must not be null");
        Objects.requireNonNull(context, "context must not be null");

        UUID organizationId = context.organizationId() != null
                ? context.organizationId()
                : invoice.getBuyerOrganizationId();
        UUID invoiceId = invoice.getId();
        String revision = invoice.getRevisionOf() != null ? invoice.getRevisionOf().toString() : "ORIGINAL";

        // Determine extraction provenance fidelity
        boolean isManual = invoice.getExtractionStatus() == ExtractionStatus.MANUAL_ENTRY;
        String provenance = isManual
                ? "MANUAL_ENTRY"
                : (invoice.getExtractionStatus() != null ? invoice.getExtractionStatus().name() : "UNKNOWN");

        // 1. Check if invoice is superseded by a newer invoice for the milestone
        boolean isSuperseded = false;
        if (invoice.getMilestoneId() != null) {
            Optional<Invoice> latestForMilestone = invoiceRepository.findFirstByMilestoneIdOrderByCreatedAtDesc(invoice.getMilestoneId());
            if (latestForMilestone.isPresent() && !latestForMilestone.get().getId().equals(invoiceId)) {
                isSuperseded = true;
            }
        }

        // 2. Fetch latest persisted assessment for this invoice
        Optional<InvoiceAssessment> assessmentOpt = invoiceAssessmentRepository.findFirstByInvoiceIdOrderByCreatedAtDesc(invoiceId);

        // 3. Handle missing assessment
        if (assessmentOpt.isEmpty()) {
            String inputFingerprint = computeFingerprint("INVOICE|" + invoiceId + "|" + invoice.getDocumentHash() + "|NO_ASSESSMENT");
            List<EvidenceReference> refs = new ArrayList<>();
            refs.add(new EvidenceReference("INVOICE", invoiceId, invoice.getCreatedAt(), invoice.getDocumentHash(), provenance, revision));

            return RecordEngineDecisionCommand.builder()
                    .organizationId(organizationId)
                    .engineType(EngineType.DOCUMENT)
                    .subjectType(DecisionSubjectType.INVOICE.name())
                    .subjectId(invoiceId)
                    .decision(DecisionStatus.REVIEW)
                    .evaluationOutcome(EvaluationOutcome.SUCCESS)
                    .score(invoice.getMatchScore() != null ? BigDecimal.valueOf(invoice.getMatchScore()) : null)
                    .reasonCodes(List.of(REASON_DOCUMENT_ASSESSMENT_MISSING))
                    .explanation("No persisted assessment found for invoice " + invoiceId)
                    .evidenceReferences(refs)
                    .ruleVersion(invoice.getPolicyVersion())
                    .sourceAssessmentId(null)
                    .evaluationId(context.evaluationId())
                    .correlationId(context.correlationId())
                    .inputFingerprint(inputFingerprint)
                    .inputRevision(revision)
                    .engineImplementationVersion("1.0.0")
                    .evaluatedAt(context.evaluationTime() != null ? context.evaluationTime() : Instant.now())
                    .build();
        }

        InvoiceAssessment assessment = assessmentOpt.get();

        // 4. Handle superseded revision (never ALLOW a superseded revision)
        if (isSuperseded) {
            String inputFingerprint = assessment.fingerprint != null
                    ? assessment.fingerprint
                    : computeFingerprint("INVOICE|" + invoiceId + "|" + assessment.id + "|SUPERSEDED");

            List<EvidenceReference> refs = List.of(
                    new EvidenceReference("INVOICE_ASSESSMENT", assessment.id, assessment.createdAt, assessment.fingerprint, provenance, revision),
                    new EvidenceReference("INVOICE", invoiceId, invoice.getCreatedAt(), invoice.getDocumentHash(), provenance, revision)
            );

            return RecordEngineDecisionCommand.builder()
                    .organizationId(organizationId)
                    .engineType(EngineType.DOCUMENT)
                    .subjectType(DecisionSubjectType.INVOICE.name())
                    .subjectId(invoiceId)
                    .decision(DecisionStatus.REVIEW)
                    .evaluationOutcome(EvaluationOutcome.SUCCESS)
                    .score(invoice.getMatchScore() != null ? BigDecimal.valueOf(invoice.getMatchScore()) : null)
                    .reasonCodes(List.of(REASON_DOCUMENT_SUPERSEDED_REVISION))
                    .explanation("Invoice " + invoiceId + " is superseded by a newer invoice revision for milestone " + invoice.getMilestoneId())
                    .evidenceReferences(refs)
                    .ruleVersion(invoice.getPolicyVersion())
                    .sourceAssessmentId(assessment.id)
                    .evaluationId(context.evaluationId())
                    .correlationId(context.correlationId())
                    .inputFingerprint(inputFingerprint)
                    .inputRevision(revision)
                    .engineImplementationVersion("1.0.0")
                    .evaluatedAt(context.evaluationTime() != null ? context.evaluationTime() : Instant.now())
                    .build();
        }

        // 5. Gather evidence references
        List<EvidenceReference> evidenceRefs = new ArrayList<>();
        evidenceRefs.add(new EvidenceReference("INVOICE_ASSESSMENT", assessment.id, assessment.createdAt, assessment.fingerprint, provenance, revision));
        evidenceRefs.add(new EvidenceReference("INVOICE", invoiceId, invoice.getCreatedAt(), invoice.getDocumentHash(), provenance, revision));

        Optional<InvoiceReview> approvedReviewOpt = invoiceReviewRepository.findFirstByInvoiceIdAndStatus(invoiceId, InvoiceReviewStatus.APPROVED);
        if (approvedReviewOpt.isPresent()) {
            InvoiceReview rev = approvedReviewOpt.get();
            evidenceRefs.add(new EvidenceReference("INVOICE_REVIEW", rev.getId(), rev.getCreatedAt(), null, "HUMAN_REVIEW", null));
        }

        String inputFingerprint = assessment.fingerprint != null
                ? assessment.fingerprint
                : computeFingerprint(assessment.id + "|" + invoice.getDocumentHash());

        BigDecimal score = invoice.getMatchScore() != null ? BigDecimal.valueOf(invoice.getMatchScore()) : null;
        List<String> reasonCodes = new ArrayList<>();
        DecisionStatus decision;
        String explanation;

        // 6. Decision Mapping according to authoritative assessment & review semantics
        // Hard prohibitions -> BLOCK
        if (invoice.getStatus() == InvoiceStatus.REJECTED) {
            decision = DecisionStatus.BLOCK;
            reasonCodes.add(REASON_DOCUMENT_REJECTED);
            explanation = "Invoice rejected: " + (invoice.getRejectionReason() != null ? invoice.getRejectionReason() : "Policy rejection");
        } else if (invoice.getDuplicateConfidence() == DuplicateConfidence.EXACT_DUPLICATE) {
            decision = DecisionStatus.BLOCK;
            reasonCodes.add(REASON_DOCUMENT_EXACT_DUPLICATE);
            explanation = "Invoice is an exact duplicate of an existing invoice";
        } else if (invoice.getMatchingStatus() == MatchingStatus.MAJOR_MISMATCH) {
            decision = DecisionStatus.BLOCK;
            reasonCodes.add(REASON_DOCUMENT_MAJOR_MISMATCH);
            explanation = "Invoice has major mismatch against milestone requirements";
        }
        // Human review required / unapproved statuses -> REVIEW
        else if (invoice.getStatus() == InvoiceStatus.REVIEW_REQUIRED || invoice.getStatus() == InvoiceStatus.PARTIAL_MATCH) {
            decision = DecisionStatus.REVIEW;
            reasonCodes.add(REASON_DOCUMENT_REVIEW_REQUIRED);
            explanation = "Invoice assessment requires human review (status: " + invoice.getStatus() + ")";
        } else if (invoice.getMatchingStatus() == MatchingStatus.REVIEW_REQUIRED || invoice.getMatchingStatus() == MatchingStatus.MATCH_WITH_WARNINGS) {
            decision = DecisionStatus.REVIEW;
            reasonCodes.add(REASON_DOCUMENT_REVIEW_REQUIRED);
            explanation = "Invoice matching requires review (matchingStatus: " + invoice.getMatchingStatus() + ")";
        }
        // Approved invoice: Enforce B10 Freshness and Integrity verification
        else if (invoice.getStatus() == InvoiceStatus.APPROVED) {
            if (approvedReviewOpt.isEmpty()) {
                decision = DecisionStatus.REVIEW;
                reasonCodes.add(REASON_DOCUMENT_REVIEW_NOT_APPROVED);
                explanation = "Invoice has APPROVED status but lacks a recorded APPROVED human review";
            } else {
                InvoiceReview review = approvedReviewOpt.get();
                // Must be bound to the latest assessment (not superseded assessment)
                if (review.getAssessmentId() == null || !review.getAssessmentId().equals(assessment.id)) {
                    decision = DecisionStatus.REVIEW;
                    reasonCodes.add(REASON_DOCUMENT_REVIEW_SUPERSEDED);
                    explanation = "Invoice review was recorded for superseded assessment " + review.getAssessmentId() + ", latest is " + assessment.id;
                }
                // Invoice must not have been modified after review was recorded
                else if (review.getReviewedAt() != null && invoice.getUpdatedAt() != null && invoice.getUpdatedAt().isAfter(review.getReviewedAt())) {
                    decision = DecisionStatus.REVIEW;
                    reasonCodes.add(REASON_DOCUMENT_MODIFIED_AFTER_REVIEW);
                    explanation = "Invoice modified after review (updatedAt: " + invoice.getUpdatedAt() + " > reviewedAt: " + review.getReviewedAt() + ")";
                }
                // All B10 approval criteria satisfied
                else {
                    decision = DecisionStatus.ALLOW;
                    reasonCodes.add(REASON_DOCUMENT_APPROVED);
                    explanation = "Invoice assessment and human review verified and approved (score: " + invoice.getMatchScore() + ", provenance: " + provenance + ")";
                }
            }
        }
        // Other statuses (UPLOADED, PROCESSING, etc.) -> REVIEW
        else {
            decision = DecisionStatus.REVIEW;
            reasonCodes.add(REASON_DOCUMENT_REVIEW_PENDING);
            explanation = "Invoice is in non-final state: " + invoice.getStatus();
        }

        return RecordEngineDecisionCommand.builder()
                .organizationId(organizationId)
                .engineType(EngineType.DOCUMENT)
                .subjectType(DecisionSubjectType.INVOICE.name())
                .subjectId(invoiceId)
                .decision(decision)
                .evaluationOutcome(EvaluationOutcome.SUCCESS)
                .score(score)
                .reasonCodes(reasonCodes)
                .explanation(explanation)
                .evidenceReferences(evidenceRefs)
                .ruleVersion(invoice.getPolicyVersion())
                .sourceAssessmentId(assessment.id)
                .evaluationId(context.evaluationId())
                .correlationId(context.correlationId())
                .inputFingerprint(inputFingerprint)
                .inputRevision(revision)
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
