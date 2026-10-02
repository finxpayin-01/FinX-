package com.finx.invoice.service;

import com.finx.audit.service.AuditService;
import com.finx.common.enums.Role;
import com.finx.deal.entity.Deal;
import com.finx.deal.repository.DealRepository;
import com.finx.exception.BadRequestException;
import com.finx.exception.ResourceNotFoundException;
import com.finx.exception.UnauthorizedException;
import com.finx.invoice.dto.InvoiceDashboardResponse;
import com.finx.invoice.dto.InvoiceResponse;
import com.finx.invoice.dto.InvoiceReviewDecisionRequest;
import com.finx.invoice.dto.InvoiceUploadRequest;
import com.finx.invoice.entity.Invoice;
import com.finx.invoice.entity.InvoiceCheck;
import com.finx.invoice.entity.InvoiceExtractedField;
import com.finx.invoice.entity.InvoiceReview;
import com.finx.invoice.enums.*;
import com.finx.invoice.extraction.DefaultInvoiceExtractionProvider;
import com.finx.invoice.extraction.ExtractedInvoiceData;
import com.finx.invoice.extraction.InvoiceDocumentInput;
import com.finx.invoice.extraction.InvoiceExtractionProvider;
import com.finx.invoice.matching.*;
import com.finx.invoice.normalization.InvoiceNormalizationService;
import com.finx.invoice.repository.InvoiceCheckRepository;
import com.finx.invoice.repository.InvoiceExtractedFieldRepository;
import com.finx.invoice.repository.InvoiceRepository;
import com.finx.invoice.repository.InvoiceReviewRepository;
import com.finx.milestone.entity.Deliverable;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.DeliverableRepository;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.notification.service.NotificationService;
import com.finx.org.entity.Organization;
import com.finx.org.entity.OrganizationBankAccount;
import com.finx.org.enums.OrgEnums.BankVerificationStatus;
import com.finx.org.repository.OrganizationBankAccountRepository;
import com.finx.org.repository.OrganizationRepository;
import com.finx.security.service.UserPrincipal;
import com.finx.verification.entity.KybProfile;
import com.finx.verification.repository.KybProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);

    private final InvoiceRepository invoiceRepository;
    private final InvoiceCheckRepository checkRepository;
    private final InvoiceReviewRepository reviewRepository;
    private final InvoiceExtractedFieldRepository extractedFieldRepository;
    private final DealRepository dealRepository;
    private final MilestoneRepository milestoneRepository;
    private final OrganizationRepository organizationRepository;
    private final KybProfileRepository kybProfileRepository;
    private final OrganizationBankAccountRepository bankAccountRepository;
    private final DeliverableRepository deliverableRepository;
    private final InvoiceNormalizationService normalization;
    private final InvoiceMatchingEngine matchingEngine;
    private final InvoiceExtractionProvider extractionProvider;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final com.finx.org.repository.OrganizationMemberRepository members;
    private final InvoiceDocumentService documents;
    private final com.finx.invoice.repository.InvoiceDocumentRepository documentRepository;
    private final com.finx.invoice.repository.InvoiceAssessmentRepository assessments;
    private final InvoiceMatchingPolicy policy;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    private final com.finx.risk.service.RiskEventService riskEvents;

    public InvoiceService(InvoiceRepository invoiceRepository,
                          InvoiceCheckRepository checkRepository,
                          InvoiceReviewRepository reviewRepository,
                          InvoiceExtractedFieldRepository extractedFieldRepository,
                          DealRepository dealRepository,
                          MilestoneRepository milestoneRepository,
                          OrganizationRepository organizationRepository,
                          KybProfileRepository kybProfileRepository,
                          OrganizationBankAccountRepository bankAccountRepository,
                          DeliverableRepository deliverableRepository,
                          InvoiceNormalizationService normalization,
                          InvoiceMatchingEngine matchingEngine,
                          InvoiceExtractionProvider extractionProvider,
                          AuditService auditService,
                          NotificationService notificationService,
                          com.finx.org.repository.OrganizationMemberRepository members, InvoiceDocumentService documents,
                          com.finx.invoice.repository.InvoiceDocumentRepository documentRepository,
                          com.finx.invoice.repository.InvoiceAssessmentRepository assessments,
                          InvoiceMatchingPolicy policy, com.fasterxml.jackson.databind.ObjectMapper json,
                          com.finx.risk.service.RiskEventService riskEvents) {
        this.members = members; this.documents = documents; this.documentRepository = documentRepository;
        this.assessments = assessments; this.policy = policy; this.json = json; this.riskEvents = riskEvents;
        this.invoiceRepository = invoiceRepository;
        this.checkRepository = checkRepository;
        this.reviewRepository = reviewRepository;
        this.extractedFieldRepository = extractedFieldRepository;
        this.dealRepository = dealRepository;
        this.milestoneRepository = milestoneRepository;
        this.organizationRepository = organizationRepository;
        this.kybProfileRepository = kybProfileRepository;
        this.bankAccountRepository = bankAccountRepository;
        this.deliverableRepository = deliverableRepository;
        this.normalization = normalization;
        this.matchingEngine = matchingEngine;
        this.extractionProvider = extractionProvider;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    @Transactional
    public InvoiceResponse uploadAndProcessInvoice(UUID milestoneId, InvoiceUploadRequest request, UserPrincipal currentUser) {
        Milestone milestone = milestoneRepository.findByIdForUpdate(milestoneId)
                .orElseThrow(() -> new ResourceNotFoundException("Milestone", "id", milestoneId));

        Deal deal = dealRepository.findById(milestone.getDealId())
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", milestone.getDealId()));

        // Security / Authorization: Vendor or Admin only
        boolean isSeller = currentUser.getRole() == Role.SELLER && Objects.equals(deal.getSellerId(),currentUser.getId());
        boolean isAdmin = currentUser.getRole() == Role.ADMIN;
        if (!isSeller) {
            throw new UnauthorizedException("Only the authorized vendor for this deal can upload invoices");
        }

        // Validate milestone state
        if (milestone.getStatus() == MilestoneStatus.CANCELLED || milestone.getStatus() == MilestoneStatus.COMPLETED || deal.getStatus() == com.finx.deal.entity.DealStatus.CANCELLED || deal.getStatus() == com.finx.deal.entity.DealStatus.COMPLETED) {
            throw new BadRequestException("Cannot submit invoices for a cancelled milestone");
        }

        if (deal.getSellerOrganizationId() == null || deal.getBuyerOrganizationId() == null)
            throw new BadRequestException("Buyer and vendor organizations must be linked before invoice submission");
        Organization submittingOrg=organizationRepository.findById(deal.getSellerOrganizationId()).orElseThrow(() -> new BadRequestException("Vendor organization not found"));
        boolean activeMember=members.findByOrganizationIdAndUserId(submittingOrg.getId(),currentUser.getId())
                .map(m -> m.getStatus()==com.finx.org.enums.OrgEnums.MemberStatus.ACTIVE).orElse(Objects.equals(submittingOrg.getCreatedBy(),currentUser.getId()));
        if (!activeMember) throw new UnauthorizedException("Uploader is not an active member of this vendor organization");
        InvoiceDocumentService.Document document = documents.validate(request);
        String documentHash = document.hash();
        if (request.getInvoiceNumber() == null || request.getInvoiceNumber().isBlank() || request.getInvoiceNumber().length() > 100
                || request.getTotalAmount() == null || request.getTotalAmount().signum() <= 0 || request.getTotalAmount().precision() > 15
                || request.getInvoiceDate() == null || request.getCurrency() == null || !request.getCurrency().matches("(?i)[A-Z]{3}"))
            throw new BadRequestException("Invoice number, date, positive amount and three-letter currency are required");
        if (request.getTotalAmount().scale() > 2 || request.getSubtotal()!=null && (request.getSubtotal().signum()<0 || request.getSubtotal().scale()>2)
                || request.getTaxAmount()!=null && (request.getTaxAmount().signum()<0 || request.getTaxAmount().scale()>2))
            throw new BadRequestException("FinX invoices require nonnegative amounts with at most two decimal places");
        if (request.getBankAccountLast4()!=null && !request.getBankAccountLast4().matches("[0-9]{4}"))
            throw new BadRequestException("Only the last four bank account digits may be submitted");
        Invoice previous = invoiceRepository.findFirstByMilestoneIdOrderByCreatedAtDesc(milestoneId).orElse(null);
        if (request.getRevisionOf()!=null && (previous==null || !previous.getId().equals(request.getRevisionOf())
                || previous.getStatus()==InvoiceStatus.APPROVED))
            throw new BadRequestException("A revision must reference the latest unapproved invoice on this milestone");

        // Prepare extraction input
        ExtractedInvoiceData manualData = new ExtractedInvoiceData();
        manualData.setInvoiceNumber(request.getInvoiceNumber());
        manualData.setInvoiceDate(request.getInvoiceDate());
        manualData.setDueDate(request.getDueDate());
        manualData.setSubtotal(request.getSubtotal());
        manualData.setTaxAmount(request.getTaxAmount());
        manualData.setTotalAmount(request.getTotalAmount());
        manualData.setCurrency(request.getCurrency());
        manualData.setVendorLegalName(request.getVendorLegalName());
        manualData.setVendorPan(request.getVendorPan());
        manualData.setVendorGstin(request.getVendorGstin());
        manualData.setBuyerLegalName(request.getBuyerLegalName());
        manualData.setBuyerGstin(request.getBuyerGstin());
        manualData.setBankAccountLast4(request.getBankAccountLast4());
        manualData.setBankIfsc(request.getBankIfsc());

        byte[] rawBytes = document.bytes();
        InvoiceDocumentInput docInput = new InvoiceDocumentInput(
                request.getFileName(),
                null,
                request.getMimeType(),
                rawBytes,
                manualData
        );

        ExtractedInvoiceData extracted;
        boolean extractionFailed=false;
        try {
            if (!extractionProvider.supports(document.mimeType())) throw new IllegalArgumentException("Unsupported extraction type");
            extracted = extractionProvider.extract(docInput);
            if (extracted==null) throw new IllegalStateException("No extraction result");
        } catch (RuntimeException failure) {
            extracted=manualData; extractionFailed=true;
            extracted.setConfidenceByField(Map.of());
        }

        // Authoritative Vendor and Buyer Org IDs from Deal (no fallback to User ID)
        UUID vendorOrgId = deal.getSellerOrganizationId();
        UUID buyerOrgId = deal.getBuyerOrganizationId();

        // Build Invoice entity
        Invoice invoice = new Invoice();
        invoice.setRevisionOf(request.getRevisionOf());
        invoice.setDealId(deal.getId());
        invoice.setMilestoneId(milestone.getId());
        invoice.setVendorOrganizationId(vendorOrgId);
        invoice.setBuyerOrganizationId(buyerOrgId);

        String rawInvNum = extracted.getInvoiceNumber() != null ? extracted.getInvoiceNumber() : request.getInvoiceNumber();
        if (rawInvNum == null || rawInvNum.isBlank()) {
            throw new BadRequestException("Invoice number could not be extracted or supplied");
        }
        invoice.setInvoiceNumber(rawInvNum.trim());
        invoice.setNormalizedInvoiceNumber(normalization.normalizeInvoiceNumber(rawInvNum));

        invoice.setInvoiceDate(extracted.getInvoiceDate() != null ? extracted.getInvoiceDate() : (request.getInvoiceDate() != null ? request.getInvoiceDate() : LocalDate.now()));
        invoice.setDueDate(extracted.getDueDate() != null ? extracted.getDueDate() : (request.getDueDate() != null ? request.getDueDate() : LocalDate.now().plusDays(30)));

        BigDecimal totalAmt = extracted.getTotalAmount() != null ? extracted.getTotalAmount() : (request.getTotalAmount() != null ? request.getTotalAmount() : milestone.getAmount());
        invoice.setTotalAmount(normalization.normalizeAmount(totalAmt));
        invoice.setSubtotal(normalization.normalizeAmount(extracted.getSubtotal() != null ? extracted.getSubtotal() : request.getSubtotal()));
        invoice.setTaxAmount(normalization.normalizeAmount(extracted.getTaxAmount() != null ? extracted.getTaxAmount() : request.getTaxAmount()));

        String curr = extracted.getCurrency() != null ? extracted.getCurrency() : (request.getCurrency() != null ? request.getCurrency() : milestone.getCurrency());
        invoice.setCurrency(normalization.normalizeCurrency(curr));
        invoice.setComparisonBasis(policy.comparisonBasis());

        invoice.setVendorLegalName(extracted.getVendorLegalName() != null ? extracted.getVendorLegalName() : request.getVendorLegalName());
        invoice.setVendorPan(normalization.normalizePan(extracted.getVendorPan() != null ? extracted.getVendorPan() : request.getVendorPan()));
        invoice.setVendorGstin(normalization.normalizeGstin(extracted.getVendorGstin() != null ? extracted.getVendorGstin() : request.getVendorGstin()));

        invoice.setBuyerLegalName(extracted.getBuyerLegalName() != null ? extracted.getBuyerLegalName() : request.getBuyerLegalName());
        invoice.setBuyerGstin(normalization.normalizeGstin(extracted.getBuyerGstin() != null ? extracted.getBuyerGstin() : request.getBuyerGstin()));

        invoice.setBankAccountLast4(extracted.getBankAccountLast4() != null ? extracted.getBankAccountLast4() : request.getBankAccountLast4());
        invoice.setBankIfsc(extracted.getBankIfsc() != null ? extracted.getBankIfsc().trim().toUpperCase() : (request.getBankIfsc() != null ? request.getBankIfsc().trim().toUpperCase() : null));

        invoice.setFileName(request.getFileName());
        invoice.setFileUrl("PRIVATE_DOCUMENT");
        invoice.setFileSizeBytes((long) rawBytes.length);
        invoice.setMimeType(request.getMimeType());
        invoice.setDocumentHash(documentHash);

        invoice.setStatus(InvoiceStatus.PROCESSING);
        invoice.setExtractionStatus(extractionFailed ? ExtractionStatus.FAILED : extractionProvider.getProviderName().equals("MANUAL_ENTRY_PROVIDER") ? ExtractionStatus.MANUAL_ENTRY : ExtractionStatus.EXTRACTED);
        invoice.setCreatedBy(currentUser.getId());

        Invoice saved = invoiceRepository.saveAndFlush(invoice);
        saved.setFileUrl("/api/v1/invoices/" + saved.getId() + "/document");
        documentRepository.save(new com.finx.invoice.entity.InvoiceDocument(saved.getId(), rawBytes));

        // Save Extracted Fields
        saveExtractedFields(saved, extracted);

        // Run Matching Engine
        InvoiceMatchingResult matchResult = runMatching(saved, deal, milestone);
        applyMatchResult(saved, matchResult);

        // Save Checks
        saveChecks(saved, matchResult.checks());
        recordAssessment(saved, matchResult);
        recordRisk(saved, deal, matchResult);

        // Audit Logging
        auditService.logEvent(currentUser.getId(), "INVOICE_UPLOADED", "INVOICE", saved.getId().toString(),
                "Uploaded invoice " + saved.getInvoiceNumber() + " with SHA-256 " + documentHash);
        auditService.logEvent(currentUser.getId(), "INVOICE_EXTRACTION_COMPLETED", "INVOICE", saved.getId().toString(),
                "Extracted invoice fields with provider " + extractionProvider.getProviderName());
        auditService.logEvent(currentUser.getId(), "INVOICE_MATCH_COMPLETED", "INVOICE", saved.getId().toString(),
                "Invoice match completed with score " + saved.getMatchScore() + "/100 status " + saved.getStatus());

        if (saved.getDuplicateConfidence() != DuplicateConfidence.UNIQUE) {
            auditService.logEvent(currentUser.getId(), "INVOICE_DUPLICATE_DETECTED", "INVOICE", saved.getId().toString(),
                    "Duplicate invoice indicator: " + saved.getDuplicateConfidence() + ", code: " + matchResult.primaryReasonCode());
        }

        // Handle Review Creation and Notifications
        handleReviewAndNotifications(saved, deal, milestone, matchResult);

        log.info("Invoice processed: id={} number={} score={} status={}", saved.getId(), saved.getInvoiceNumber(), saved.getMatchScore(), saved.getStatus());
        return response(saved, currentUser);
    }

    @Transactional(readOnly = true)
    public InvoiceResponse getInvoiceById(UUID invoiceId, UserPrincipal currentUser) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice", "id", invoiceId));

        Deal deal = dealRepository.findById(invoice.getDealId())
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", invoice.getDealId()));

        verifyAccess(deal, currentUser);
        return response(invoice, currentUser);
    }

    @Transactional(readOnly = true)
    public List<InvoiceResponse> getInvoicesForMilestone(UUID milestoneId, UserPrincipal currentUser) {
        Milestone milestone = milestoneRepository.findById(milestoneId)
                .orElseThrow(() -> new ResourceNotFoundException("Milestone", "id", milestoneId));

        Deal deal = dealRepository.findById(milestone.getDealId())
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", milestone.getDealId()));

        verifyAccess(deal, currentUser);
        return invoiceRepository.findByMilestoneIdOrderByCreatedAtDesc(milestoneId).stream()
                .map(i -> response(i, currentUser))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<InvoiceResponse> getInvoicesForDeal(UUID dealId, UserPrincipal currentUser) {
        Deal deal = dealRepository.findById(dealId)
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", dealId));

        verifyAccess(deal, currentUser);
        return invoiceRepository.findByDealIdOrderByCreatedAtDesc(dealId).stream()
                .map(i -> response(i, currentUser))
                .collect(Collectors.toList());
    }

    @Transactional
    public InvoiceResponse recalculateInvoiceMatching(UUID invoiceId, UserPrincipal currentUser) {
        Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId).orElseThrow(() -> new ResourceNotFoundException("Invoice", "id", invoiceId));
        Deal deal = dealRepository.findById(invoice.getDealId()).orElseThrow();
        verifyReviewer(deal, currentUser);
        Milestone milestone = milestoneRepository.findById(invoice.getMilestoneId()).orElseThrow();
        InvoiceMatchingResult result = runMatching(invoice, deal, milestone);
        String fingerprint = fingerprint(result);
        if (assessments.findFirstByInvoiceIdOrderByCreatedAtDesc(invoiceId).map(a -> a.fingerprint.equals(fingerprint)).orElse(false))
            return response(invoice, currentUser);
        applyMatchResult(invoice, result);
        saveChecks(invoice, result.checks());
        recordAssessment(invoice, result);
        handleReviewAndNotifications(invoice, deal, milestone, result);
        auditService.logEvent(currentUser.getId(), "INVOICE_MATCH_COMPLETED", "INVOICE", invoiceId.toString(), "Recalculated;deal="+deal.getId()+";milestone="+milestone.getId());
        return response(invoiceRepository.saveAndFlush(invoice), currentUser);
    }

    @Transactional
    public InvoiceResponse submitReviewDecision(UUID invoiceId, UUID reviewId, InvoiceReviewDecisionRequest request, UserPrincipal currentUser) {
        Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice", "id", invoiceId));

        Deal deal = dealRepository.findById(invoice.getDealId())
                .orElseThrow(() -> new ResourceNotFoundException("Deal", "id", invoice.getDealId()));

        verifyReviewer(deal, currentUser);
        InvoiceReview review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("InvoiceReview", "id", reviewId));

        if (!review.getInvoice().getId().equals(invoiceId)) throw new UnauthorizedException("Review does not belong to this invoice");
        if (review.getReviewedAt()!=null) throw new BadRequestException("A recorded review decision is immutable; open a new review");
        var latest = assessments.findFirstByInvoiceIdOrderByCreatedAtDesc(invoiceId).orElseThrow();
        InvoiceMatchingResult currentEvidence=runMatching(invoice,deal,milestoneRepository.findById(invoice.getMilestoneId()).orElseThrow());
        if (!latest.fingerprint.equals(fingerprint(currentEvidence))) throw new BadRequestException("Evidence changed after assessment; recalculate invoice matching before review");
        if (!Objects.equals(review.getAssessmentId(), latest.id)) throw new BadRequestException("Review is for superseded evidence");
        if (!invoiceRepository.findFirstByMilestoneIdOrderByCreatedAtDesc(invoice.getMilestoneId()).orElseThrow().getId().equals(invoiceId))
            throw new BadRequestException("Only the latest invoice revision can be reviewed");
        if (request.getDecision()==null || request.getNotes()==null || request.getNotes().isBlank()) throw new BadRequestException("A decision and review notes are required");
        review.setReviewerId(currentUser.getId());
        review.setDecision(request.getDecision());
        review.setDecisionReason(request.getDecisionReason());
        review.setNotes(request.getNotes());
        review.setEvidenceReferences(request.getEvidenceReferences());
        review.setReviewedAt(Instant.now());

        switch (request.getDecision()) {
            case APPROVE -> {
                review.setStatus(InvoiceReviewStatus.APPROVED);
                invoice.setStatus(InvoiceStatus.APPROVED);
                auditService.logEvent(currentUser.getId(), "INVOICE_APPROVED", "INVOICE", invoice.getId().toString(),
                        "Invoice manually approved by " + currentUser.getRole());
                notificationService.sendNotification(deal.getSellerId(), "Invoice Approved",
                        "Your invoice " + invoice.getInvoiceNumber() + " has been approved. Settlement remains subject to FinX authorization controls.",
                        "/vendor/projects/" + deal.getId() + "/milestones/" + invoice.getMilestoneId());
            }
            case REJECT -> {
                review.setStatus(InvoiceReviewStatus.REJECTED);
                invoice.setStatus(InvoiceStatus.REJECTED);
                invoice.setRejectionReason(request.getDecisionReason());
                auditService.logEvent(currentUser.getId(), "INVOICE_REJECTED", "INVOICE", invoice.getId().toString(),
                        "Invoice rejected: " + request.getDecisionReason());
                notificationService.sendNotification(deal.getSellerId(), "Invoice Rejected",
                        "Invoice " + invoice.getInvoiceNumber() + " requires revision: " + request.getDecisionReason(),
                        "/vendor/projects/" + deal.getId() + "/milestones/" + invoice.getMilestoneId());
            }
            case REQUEST_INFORMATION -> {
                review.setStatus(InvoiceReviewStatus.NEEDS_INFORMATION);
                invoice.setStatus(InvoiceStatus.REVIEW_REQUIRED);
                auditService.logEvent(currentUser.getId(), "INVOICE_REVIEW_DECISION", "INVOICE", invoice.getId().toString(),
                        "Additional information requested for invoice: " + request.getNotes());
                notificationService.sendNotification(deal.getSellerId(), "Invoice Information Requested",
                        "PM requested additional information on invoice " + invoice.getInvoiceNumber() + ": " + request.getNotes(),
                        "/vendor/projects/" + deal.getId() + "/milestones/" + invoice.getMilestoneId());
            }
        }

        auditService.logEvent(currentUser.getId(), "INVOICE_REVIEW_DECISION", "INVOICE", invoiceId.toString(),
                "decision="+request.getDecision()+";assessment="+review.getAssessmentId()+";deal="+deal.getId()+";milestone="+invoice.getMilestoneId());
        reviewRepository.save(review);
        Invoice updated = invoiceRepository.saveAndFlush(invoice);
        return response(updated, currentUser);
    }

    @Transactional(readOnly = true)
    public InvoiceDashboardResponse getAdminDashboardMetrics() {
        long total = invoiceRepository.count();
        long strongMatches = invoiceRepository.countByMatchingStatus(MatchingStatus.STRONG_MATCH);
        long reviewRequired = invoiceRepository.countByStatus(InvoiceStatus.REVIEW_REQUIRED);
        long majorMismatches = invoiceRepository.countByMatchingStatus(MatchingStatus.MAJOR_MISMATCH);
        long duplicates = invoiceRepository.countDuplicatesDetected();
        long pendingReviews = invoiceRepository.countPendingReviews();

        List<InvoiceResponse> recent = invoiceRepository.findTop20ByOrderByCreatedAtDesc().stream()
                .limit(20)
                .map(InvoiceResponse::fromEntity)
                .collect(Collectors.toList());

        return new InvoiceDashboardResponse(
                total,
                strongMatches,
                reviewRequired,
                majorMismatches,
                duplicates,
                pendingReviews,
                recent
        );
    }

    private InvoiceMatchingResult runMatching(Invoice invoice, Deal deal, Milestone milestone) {
        Organization vendorOrg = null;
        if (invoice.getVendorOrganizationId() != null) {
            vendorOrg = organizationRepository.findById(invoice.getVendorOrganizationId()).orElse(null);
        }

        Organization buyerOrg = null;
        if (invoice.getBuyerOrganizationId() != null) {
            buyerOrg = organizationRepository.findById(invoice.getBuyerOrganizationId()).orElse(null);
        }

        KybProfile vendorKyb = null;
        if (vendorOrg != null) {
            vendorKyb = kybProfileRepository.findByOrganizationId(vendorOrg.getId()).orElse(null);
        }

        OrganizationBankAccount vendorBank = null;
        if (vendorOrg != null) {
            List<OrganizationBankAccount> verifiedBanks = bankAccountRepository.findByOrganizationId(vendorOrg.getId()).stream()
                    .filter(a -> a.getVerificationStatus() == BankVerificationStatus.VERIFIED)
                    .filter(a -> a.getEncryptedAccountNumber() != null && !a.getEncryptedAccountNumber().isBlank())
                    .collect(Collectors.toList());
            if (verifiedBanks.size() == 1) {
                vendorBank = verifiedBanks.get(0);
            }
        }

        List<Deliverable> deliverables = deliverableRepository.findByMilestoneIdOrderBySubmittedAtDesc(milestone.getId());
        List<Invoice> existingInvoices = invoiceRepository.findDuplicateCandidates(invoice.getDocumentHash(), invoice.getNormalizedInvoiceNumber());

        InvoiceMatchingContext ctx = new InvoiceMatchingContext(
                invoice,
                deal,
                milestone,
                vendorOrg,
                buyerOrg,
                vendorKyb,
                vendorBank,
                deliverables,
                existingInvoices,
                policy
        );

        InvoiceMatchingResult result=matchingEngine.evaluate(ctx);
        Map<String,Object> evidence=new TreeMap<>();
        evidence.put("dealId",deal.getId()); evidence.put("milestoneId",milestone.getId()); evidence.put("milestoneAmount",milestone.getAmount());
        evidence.put("milestoneCurrency",milestone.getCurrency()); evidence.put("dealCurrency",deal.getCurrency()); evidence.put("submissionDeadline",milestone.getSubmissionDeadline());
        evidence.put("vendorOrganization",invoice.getVendorOrganizationId()); evidence.put("buyerOrganization",invoice.getBuyerOrganizationId());
        evidence.put("vendorVerification",vendorOrg==null?null:vendorOrg.getVerificationStatus()); evidence.put("buyerVerification",buyerOrg==null?null:buyerOrg.getVerificationStatus());
        evidence.put("kybProfile",vendorKyb==null?null:vendorKyb.getId()); evidence.put("kybStatus",vendorKyb==null?null:vendorKyb.getStatus());
        evidence.put("bankAccount",vendorBank==null?null:vendorBank.getId());
        evidence.put("deliverables",deliverables.stream().sorted(Comparator.comparing(d->d.getId().toString())).map(d->Map.of("id",d.getId(),"status",d.getStatus(),"fileName",d.getFileName())).toList());
        evidence.put("extraction",invoice.getExtractedFields().stream().sorted(Comparator.comparing(InvoiceExtractedField::getFieldName)).map(f->Map.of("field",f.getFieldName(),"confidence",f.getConfidence())).toList());
        return new InvoiceMatchingResult(result.matchScore(),result.matchingStatus(),result.duplicateConfidence(),result.primaryReasonCode(),result.checks(),result.summaryMessage(),result.policyVersion(),evidence);
    }

    private void applyMatchResult(Invoice invoice, InvoiceMatchingResult matchResult) {
        invoice.setMatchScore(matchResult.matchScore());
        invoice.setMatchingStatus(matchResult.matchingStatus());
        invoice.setDuplicateConfidence(matchResult.duplicateConfidence());
        invoice.setPolicyVersion(matchResult.policyVersion());

        switch (matchResult.matchingStatus()) {
            case STRONG_MATCH -> invoice.setStatus(InvoiceStatus.MATCHED);
            case MATCH_WITH_WARNINGS -> invoice.setStatus(InvoiceStatus.PARTIAL_MATCH);
            case REVIEW_REQUIRED -> invoice.setStatus(InvoiceStatus.REVIEW_REQUIRED);
            case MAJOR_MISMATCH -> invoice.setStatus(InvoiceStatus.REVIEW_REQUIRED);
            default -> invoice.setStatus(InvoiceStatus.REVIEW_REQUIRED);
        }
    }

    private void saveExtractedFields(Invoice invoice, ExtractedInvoiceData data) {
        invoice.getExtractedFields().clear();

        addField(invoice, "invoiceNumber", data.getInvoiceNumber(), normalization.normalizeInvoiceNumber(data.getInvoiceNumber()), data.getFieldConfidence("invoiceNumber"));
        addField(invoice, "invoiceDate", data.getInvoiceDate()==null?null:data.getInvoiceDate().toString(), data.getInvoiceDate()==null?null:data.getInvoiceDate().toString(), data.getFieldConfidence("invoiceDate"));
        addField(invoice, "totalAmount", data.getTotalAmount()==null?null:data.getTotalAmount().toPlainString(), String.valueOf(normalization.normalizeAmount(data.getTotalAmount())), data.getFieldConfidence("totalAmount"));
        addField(invoice, "currency", data.getCurrency(), normalization.normalizeCurrency(data.getCurrency()), data.getFieldConfidence("currency"));
        addField(invoice, "vendorGstin", data.getVendorGstin(), normalization.normalizeGstin(data.getVendorGstin()), data.getFieldConfidence("vendorGstin"));
        addField(invoice, "vendorPan", data.getVendorPan(), normalization.normalizePan(data.getVendorPan()), data.getFieldConfidence("vendorPan"));
        addField(invoice, "vendorLegalName", data.getVendorLegalName(), normalization.normalizeLegalName(data.getVendorLegalName()), data.getFieldConfidence("vendorLegalName"));
        addField(invoice, "buyerLegalName", data.getBuyerLegalName(), normalization.normalizeLegalName(data.getBuyerLegalName()), data.getFieldConfidence("buyerLegalName"));
        addField(invoice, "bankAccountLast4", data.getBankAccountLast4(), data.getBankAccountLast4(), data.getFieldConfidence("bankAccountLast4"));
        addField(invoice, "bankIfsc", data.getBankIfsc(), data.getBankIfsc(), data.getFieldConfidence("bankIfsc"));
        addField(invoice, "buyerGstin", data.getBuyerGstin(), normalization.normalizeGstin(data.getBuyerGstin()), data.getFieldConfidence("buyerGstin"));
    }

    private void addField(Invoice invoice, String name, String raw, String norm, BigDecimal conf) {
        if (raw != null) {
            InvoiceExtractedField field = new InvoiceExtractedField(invoice, name, raw, norm, conf);
            invoice.getExtractedFields().add(field);
            extractedFieldRepository.save(field);
        }
    }

    private void saveChecks(Invoice invoice, List<InvoiceCheckDetail> checkDetails) {
        List<InvoiceCheck> entities = new ArrayList<>();
        for (InvoiceCheckDetail cd : checkDetails) {
            InvoiceCheck check = new InvoiceCheck(
                    invoice,
                    cd.checkType(),
                    cd.status(),
                    cd.reasonCode(),
                    cd.message(),
                    cd.weight(),
                    cd.scoreEarned(),
                    cd.details()
            );
            entities.add(checkRepository.save(check));
        }
        invoice.getChecks().clear();
        invoice.getChecks().addAll(entities);
    }

    private void handleReviewAndNotifications(Invoice invoice, Deal deal, Milestone milestone, InvoiceMatchingResult matchResult) {
        if (invoice.getStatus() != InvoiceStatus.APPROVED) {
            UUID assessmentId = assessments.findFirstByInvoiceIdOrderByCreatedAtDesc(invoice.getId()).orElseThrow().id;
            if (invoice.getReviews().stream().anyMatch(r -> Objects.equals(r.getAssessmentId(), assessmentId) && r.getReviewedAt()==null)) return;
            InvoiceReview review = new InvoiceReview(invoice, "Automated check discrepancy: " + matchResult.primaryReasonCode() + ". " + matchResult.summaryMessage());
            review.setAssessmentId(assessmentId);
            reviewRepository.save(review);
            if (invoice.getReviews() == null) {
                invoice.setReviews(new ArrayList<>());
            }
            invoice.getReviews().add(review);
            auditService.logEvent(invoice.getCreatedBy(), "INVOICE_REVIEW_OPENED", "INVOICE", invoice.getId().toString(),
                    "Review opened with status " + review.getStatus());

            if (invoice.getStatus()==InvoiceStatus.REVIEW_REQUIRED || invoice.getStatus()==InvoiceStatus.PARTIAL_MATCH) {
                notificationService.sendNotification(deal.getSellerId(), "Invoice Requires Review", "Additional verification is required for invoice "+invoice.getInvoiceNumber()+".", "/vendor/projects/"+deal.getId()+"/milestones/"+milestone.getId());
            }
            if (deal.getProjectManagerId()!=null) notificationService.sendNotification(deal.getBuyerId(),"Invoice Submitted", "An invoice is available for milestone "+milestone.getTitle()+".","/corporate/projects/"+deal.getId()+"/milestones/"+milestone.getId());
            UUID pmRecipient = deal.getProjectManagerId() != null ? deal.getProjectManagerId() : deal.getBuyerId();
            notificationService.sendNotification(
                    pmRecipient,
                    "Invoice Verification Review Required",
                    "Vendor uploaded invoice " + invoice.getInvoiceNumber() + " for milestone '" + milestone.getTitle() + "' requiring review (" + matchResult.primaryReasonCode() + ").",
                    (deal.getProjectManagerId()!=null ? "/project-manager/reviews/"+milestone.getId() : "/corporate/milestones")
            );
        }
    }

    private void verifyAccess(Deal deal, UserPrincipal currentUser) {
        if (currentUser.getRole() == Role.ADMIN) return;
        boolean isBuyer = deal.getBuyerId().equals(currentUser.getId());
        boolean isSeller = deal.getSellerId().equals(currentUser.getId());
        boolean isPm = currentUser.getRole() == Role.PROJECT_MANAGER && deal.getProjectManagerId() != null && deal.getProjectManagerId().equals(currentUser.getId());
        if (!isBuyer && !isSeller && !isPm) {
            throw new UnauthorizedException("You are not authorized to view invoice details for this transaction");
        }
    }

    private void verifyReviewer(Deal deal, UserPrincipal user) {
        if (user.getRole()==Role.ADMIN) return;
        if (user.getRole()==Role.PROJECT_MANAGER && Objects.equals(user.getId(),deal.getProjectManagerId())) return;
        throw new UnauthorizedException("Only the assigned project manager or administrator can review invoices");
    }
    private InvoiceResponse response(Invoice invoice, UserPrincipal user) {
        return InvoiceResponse.fromEntity(invoice, user.getRole()==Role.ADMIN || user.getRole()==Role.PROJECT_MANAGER);
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("Unable to persist invoice evidence", e); }
    }
    private String fingerprint(InvoiceMatchingResult result) {
        return InvoiceDocumentService.sha256((encode(result)+encode(policy)).getBytes(StandardCharsets.UTF_8));
    }
    private void recordAssessment(Invoice invoice, InvoiceMatchingResult result) {
        var a = new com.finx.invoice.entity.InvoiceAssessment(); a.invoiceId=invoice.getId();
        a.fingerprint=fingerprint(result); a.resultJson=encode(result); a.policyJson=encode(policy);
        assessments.saveAndFlush(a);
    }
    private void recordRisk(Invoice invoice, Deal deal, InvoiceMatchingResult result) {
        var subject=riskEvents.subject(deal.getSellerId(),deal.getSellerOrganizationId(),com.finx.risk.dto.RiskDtos.UserType.VENDOR);
        var metrics=new java.util.EnumMap<com.finx.risk.event.RiskSignalObserved.Metric,Double>(com.finx.risk.event.RiskSignalObserved.Metric.class);
        metrics.put(com.finx.risk.event.RiskSignalObserved.Metric.DUPLICATE_INVOICES,
                result.duplicateConfidence()==DuplicateConfidence.EXACT_DUPLICATE || result.duplicateConfidence()==DuplicateConfidence.LIKELY_DUPLICATE ? 1d : 0d);
        metrics.put(com.finx.risk.event.RiskSignalObserved.Metric.INVOICE_MISMATCH,
                result.checks().stream().anyMatch(c -> c.status()==CheckStatus.FAIL && c.checkType()!=InvoiceCheckType.DUPLICATE_CHECK && c.checkType()!=InvoiceCheckType.DOCUMENT_HASH_CHECK) ? 1d : 0d);
        riskEvents.observe(new com.finx.risk.event.RiskSignalObserved(subject.id,com.finx.risk.dto.RiskDtos.EventType.INVOICE_UPLOADED,
                "invoice:"+invoice.getId(),"invoice:"+invoice.getId(),Instant.now(),metrics));
    }
    @Transactional(readOnly=true)
    public byte[] download(UUID invoiceId, UserPrincipal user) {
        getInvoiceById(invoiceId,user);
        return documentRepository.findById(invoiceId).orElseThrow(() -> new ResourceNotFoundException("Invoice document", "id", invoiceId)).content;
    }
    @Transactional(readOnly=true)
    public List<com.finx.invoice.entity.InvoiceAssessment> history(UUID invoiceId, UserPrincipal user) {
        Invoice invoice=invoiceRepository.findById(invoiceId).orElseThrow();
        verifyReviewer(dealRepository.findById(invoice.getDealId()).orElseThrow(),user);
        return assessments.findByInvoiceIdOrderByCreatedAtAsc(invoiceId);
    }
    @Transactional
    public InvoiceResponse openReview(UUID invoiceId, UserPrincipal user) {
        Invoice invoice=invoiceRepository.findByIdForUpdate(invoiceId).orElseThrow();
        Deal deal=dealRepository.findById(invoice.getDealId()).orElseThrow(); verifyReviewer(deal,user);
        if (invoice.getStatus()==InvoiceStatus.APPROVED || invoice.getStatus()==InvoiceStatus.REJECTED) throw new BadRequestException("Decision is final; submit a revision for new evidence");
        handleReviewAndNotifications(invoice,deal,milestoneRepository.findById(invoice.getMilestoneId()).orElseThrow(),runMatching(invoice,deal,milestoneRepository.findById(invoice.getMilestoneId()).orElseThrow()));
        return response(invoice,user);
    }

    public record DuplicateReference(UUID invoiceId,UUID dealId,UUID milestoneId,Instant uploadedAt) {}
    @Transactional(readOnly=true)
    public List<DuplicateReference> duplicateReferences(UUID invoiceId,UserPrincipal user) {
        Invoice invoice=invoiceRepository.findById(invoiceId).orElseThrow(() -> new ResourceNotFoundException("Invoice","id",invoiceId));
        verifyReviewer(dealRepository.findById(invoice.getDealId()).orElseThrow(),user);
        return invoiceRepository.findDuplicateCandidates(invoice.getDocumentHash(),invoice.getNormalizedInvoiceNumber()).stream()
                .filter(i -> !i.getId().equals(invoiceId))
                .filter(i -> Objects.equals(i.getDocumentHash(),invoice.getDocumentHash()) || Objects.equals(i.getVendorOrganizationId(),invoice.getVendorOrganizationId()))
                .filter(i -> {
                    Deal d=dealRepository.findById(i.getDealId()).orElseThrow();
                    return user.getRole()==Role.ADMIN || Objects.equals(d.getProjectManagerId(),user.getId());
                }).map(i -> new DuplicateReference(i.getId(),i.getDealId(),i.getMilestoneId(),i.getCreatedAt())).toList();
    }
    @Transactional(readOnly=true)
    public List<InvoiceResponse> adminPage(int page,int size) {
        return invoiceRepository.findAll(org.springframework.data.domain.PageRequest.of(Math.max(0,page),Math.max(1,Math.min(100,size)),org.springframework.data.domain.Sort.by("createdAt").descending())).stream().map(InvoiceResponse::fromEntity).toList();
    }
}
