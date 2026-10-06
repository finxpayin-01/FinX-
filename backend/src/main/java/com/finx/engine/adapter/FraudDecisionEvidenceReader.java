package com.finx.engine.adapter;

import com.finx.deal.entity.Deal;
import com.finx.deal.repository.DealRepository;
import com.finx.deal.service.DealAccessService;
import com.finx.engine.entity.EnginePolicyVersion;
import com.finx.engine.model.DecisionSubjectType;
import com.finx.engine.model.EngineType;
import com.finx.engine.model.EvaluationContext;
import com.finx.engine.policy.fraud.FraudPolicyDefinition;
import com.finx.engine.service.EnginePolicyVersionService;
import com.finx.exception.UnauthorizedException;
import com.finx.invoice.entity.Invoice;
import com.finx.invoice.entity.InvoiceCheck;
import com.finx.invoice.repository.InvoiceCheckRepository;
import com.finx.invoice.repository.InvoiceRepository;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.org.entity.OrganizationBankAccount;
import com.finx.org.repository.OrganizationBankAccountRepository;
import com.finx.org.repository.OrganizationRepository;
import com.finx.security.service.UserPrincipal;
import com.finx.verification.service.VerificationEligibilityService;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Pure read-only evidence reader for the FRAUD decision engine.
 * <p>
 * Responsibilities:
 * - Scoped resolution of Milestone, Deal, and Buyer Organization
 * - Retrieval of latest Invoice and persisted InvoiceChecks
 * - Safe cycle-detecting traversal of legitimate invoice revision lineage
 * - Candidate duplicate collision discovery for S01 and S02 without bytea hashing
 * - Seller organization beneficiary verification evidence retrieval
 * - Resolution of platform-scoped Fraud policy v1.0.1
 * <p>
 * Guarantees:
 * - Zero writes or mutations
 * - Strictly zero banking, ledger, escrow, or settlement calls
 * - Strictly zero invocation of InvoiceMatchingEngine or Risk scoring
 */
@Component
public class FraudDecisionEvidenceReader {

    private final DealRepository deals;
    private final MilestoneRepository milestones;
    private final OrganizationRepository organizations;
    private final DealAccessService access;
    private final InvoiceRepository invoices;
    private final InvoiceCheckRepository invoiceChecks;
    private final OrganizationBankAccountRepository bankAccounts;
    private final VerificationEligibilityService eligibilityService;
    private final EnginePolicyVersionService policyVersionService;

    public FraudDecisionEvidenceReader(
            DealRepository deals,
            MilestoneRepository milestones,
            OrganizationRepository organizations,
            DealAccessService access,
            InvoiceRepository invoices,
            InvoiceCheckRepository invoiceChecks,
            OrganizationBankAccountRepository bankAccounts,
            VerificationEligibilityService eligibilityService,
            EnginePolicyVersionService policyVersionService
    ) {
        this.deals = Objects.requireNonNull(deals, "deals must not be null");
        this.milestones = Objects.requireNonNull(milestones, "milestones must not be null");
        this.organizations = Objects.requireNonNull(organizations, "organizations must not be null");
        this.access = Objects.requireNonNull(access, "access must not be null");
        this.invoices = Objects.requireNonNull(invoices, "invoices must not be null");
        this.invoiceChecks = Objects.requireNonNull(invoiceChecks, "invoiceChecks must not be null");
        this.bankAccounts = Objects.requireNonNull(bankAccounts, "bankAccounts must not be null");
        this.eligibilityService = Objects.requireNonNull(eligibilityService, "eligibilityService must not be null");
        this.policyVersionService = Objects.requireNonNull(policyVersionService, "policyVersionService must not be null");
    }

    public record FraudSources(
            Deal deal,
            Milestone milestone,
            Invoice invoice,
            List<InvoiceCheck> invoiceChecks,
            Set<UUID> legitimateRevisionAncestors,
            boolean revisionLineageCorrupted,
            List<Invoice> s01DuplicateCandidates,
            List<Invoice> s02DuplicateCandidates,
            List<OrganizationBankAccount> sellerBankAccounts,
            VerificationEligibilityService.BeneficiaryEligibilityResult beneficiaryResult,
            EnginePolicyVersion policyVersion,
            DecisionEvidence evidence
    ) {}

    public FraudSources read(EvaluationContext context, UUID expectedDealId, UserPrincipal caller) {
        Objects.requireNonNull(context, "context must not be null");
        if (!DecisionSubjectType.MILESTONE.name().equals(context.subjectType())) {
            throw new IllegalArgumentException("Expected MILESTONE subject for FRAUD engine, but was: " + context.subjectType());
        }

        Milestone milestone = milestones.findById(context.subjectId())
                .orElseThrow(FraudDecisionEvidenceReader::scopeDenied);

        if (milestone.getDealId() == null || !context.subjectId().equals(milestone.getId())
                || (expectedDealId != null && !expectedDealId.equals(milestone.getDealId()))) {
            throw scopeDenied();
        }

        Deal deal = deals.findById(milestone.getDealId())
                .orElseThrow(FraudDecisionEvidenceReader::scopeDenied);

        if (!milestone.getDealId().equals(deal.getId()) || deal.getBuyerOrganizationId() == null
                || context.organizationId() == null
                || !context.organizationId().equals(deal.getBuyerOrganizationId())
                || !organizations.existsById(deal.getBuyerOrganizationId())) {
            throw scopeDenied();
        }

        if (caller != null) {
            access.requireDealAccess(deal, caller);
        }

        DecisionEvidence evidence = new DecisionEvidence();
        evidence.reference("DEAL", deal.getId(), deal.getUpdatedAt(), null, "DEAL_REPOSITORY", null);
        evidence.input("DEAL", deal.getId(), deal.getBuyerOrganizationId(), deal.getSellerOrganizationId(),
                deal.getUpdatedAt(), deal.getTotalAmount(), deal.getCurrency());

        evidence.reference("MILESTONE", milestone.getId(), milestone.getUpdatedAt(), null, "MILESTONE_REPOSITORY", null);
        evidence.input("MILESTONE", milestone.getId(), milestone.getDealId(), milestone.getUpdatedAt(),
                milestone.getAmount(), milestone.getCurrency(), milestone.getSequence(), milestone.getStatus(),
                milestone.isInvoiceRequired());

        // 1. Resolve latest invoice for milestone
        Invoice invoice = invoices.findFirstByMilestoneIdOrderByCreatedAtDesc(milestone.getId()).orElse(null);
        List<InvoiceCheck> checks = List.of();
        Set<UUID> legitimateAncestors = new HashSet<>();
        boolean revisionCorrupted = false;
        List<Invoice> s01Candidates = List.of();
        List<Invoice> s02Candidates = List.of();

        if (invoice != null) {
            if (!deal.getId().equals(invoice.getDealId())
                    || !milestone.getId().equals(invoice.getMilestoneId())
                    || !context.organizationId().equals(invoice.getBuyerOrganizationId())
                    || !Objects.equals(deal.getSellerOrganizationId(), invoice.getVendorOrganizationId())) {
                throw scopeDenied();
            }

            String provenance = invoice.getExtractionStatus() == null ? "UNKNOWN" : invoice.getExtractionStatus().name();
            String revision = invoice.getRevisionOf() == null ? "ORIGINAL" : invoice.getRevisionOf().toString();
            evidence.reference("INVOICE", invoice.getId(), invoice.getUpdatedAt(), invoice.getDocumentHash(), provenance, revision);
            evidence.input("INVOICE", invoice.getId(), invoice.getRevisionOf(), invoice.getUpdatedAt(),
                    invoice.getStatus(), invoice.getDocumentHash());

            // Load persisted checks
            checks = invoiceChecks.findByInvoiceIdOrderByCreatedAtAsc(invoice.getId());
            for (InvoiceCheck c : checks) {
                requireScope(c.getInvoice() != null && invoice.getId().equals(c.getInvoice().getId()));
                evidence.reference("INVOICE_CHECK", c.getId(), c.getCreatedAt(), null,
                        c.getCheckType() != null ? c.getCheckType().name() : "UNKNOWN",
                        c.getStatus() != null ? c.getStatus().name() : "UNKNOWN");
                evidence.input("INVOICE_CHECK", c.getId(), c.getCheckType(), c.getStatus(),
                        c.getReasonCode(), c.getCreatedAt());
            }

            // Safe cycle-detecting traversal of legitimate revision lineage
            UUID cursor = invoice.getRevisionOf();
            Set<UUID> visited = new HashSet<>();
            visited.add(invoice.getId());

            while (cursor != null) {
                if (visited.contains(cursor)) {
                    revisionCorrupted = true;
                    break;
                }
                visited.add(cursor);
                legitimateAncestors.add(cursor);

                Optional<Invoice> parentOpt = invoices.findById(cursor);
                if (parentOpt.isEmpty()) {
                    break;
                }
                Invoice parent = parentOpt.get();
                // Ensure parent belongs to same deal, milestone, and vendor organization
                if (!Objects.equals(deal.getId(), parent.getDealId())
                        || !Objects.equals(milestone.getId(), parent.getMilestoneId())
                        || !Objects.equals(deal.getSellerOrganizationId(), parent.getVendorOrganizationId())) {
                    revisionCorrupted = true;
                    break;
                }
                cursor = parent.getRevisionOf();
            }

            // S01 candidates: same document hash, excluding self
            if (invoice.getDocumentHash() != null && !invoice.getDocumentHash().isBlank()) {
                s01Candidates = invoices.findByDocumentHash(invoice.getDocumentHash()).stream()
                        .filter(cand -> !cand.getId().equals(invoice.getId()))
                        .sorted(Comparator.comparing(Invoice::getId))
                        .toList();

                for (Invoice cand : s01Candidates) {
                    evidence.reference("INVOICE_DUPLICATE_CANDIDATE", cand.getId(), cand.getCreatedAt(),
                            cand.getDocumentHash(), "DOCUMENT_HASH_COLLISION", null);
                    evidence.input("S01_CANDIDATE", cand.getId(), cand.getDocumentHash());
                }
            }

            // S02 candidates: same vendor org + same normalized invoice number, excluding self and legitimate lineage
            if (deal.getSellerOrganizationId() != null
                    && invoice.getNormalizedInvoiceNumber() != null
                    && !invoice.getNormalizedInvoiceNumber().isBlank()) {
                s02Candidates = invoices.findByVendorOrganizationIdAndNormalizedInvoiceNumber(
                        deal.getSellerOrganizationId(), invoice.getNormalizedInvoiceNumber()
                ).stream()
                        .filter(cand -> !cand.getId().equals(invoice.getId()))
                        .filter(cand -> !legitimateAncestors.contains(cand.getId()))
                        .sorted(Comparator.comparing(Invoice::getId))
                        .toList();

                for (Invoice cand : s02Candidates) {
                    evidence.reference("INVOICE_DUPLICATE_CANDIDATE", cand.getId(), cand.getCreatedAt(),
                            cand.getDocumentHash(), "INVOICE_NUMBER_COLLISION", null);
                    evidence.input("S02_CANDIDATE", cand.getId(), cand.getNormalizedInvoiceNumber());
                }
            }
        } else {
            evidence.input(milestone.isInvoiceRequired() ? "INVOICE_MISSING" : "INVOICE_NOT_REQUIRED");
        }

        // 2. Beneficiary evidence for seller organization
        UUID sellerOrgId = deal.getSellerOrganizationId();
        List<OrganizationBankAccount> sellerAccounts = List.of();
        VerificationEligibilityService.BeneficiaryEligibilityResult beneficiaryResult = null;

        if (sellerOrgId != null) {
            sellerAccounts = bankAccounts.findByOrganizationId(sellerOrgId).stream()
                    .sorted(Comparator.comparing(OrganizationBankAccount::getId))
                    .toList();

            for (OrganizationBankAccount acc : sellerAccounts) {
                evidence.reference("ORGANIZATION_BANK_ACCOUNT", acc.getId(),
                        acc.getVerifiedAt() != null ? acc.getVerifiedAt() : acc.getUpdatedAt(),
                        null, "ORGANIZATION_BANK_ACCOUNT_REPOSITORY", null);
                evidence.input("BANK_ACCOUNT", acc.getId(), acc.getOrganizationId(),
                        acc.getVerificationStatus(), acc.getVerifiedAt(), acc.getNameMatchStatus(),
                        acc.getEncryptedAccountNumber() != null && !acc.getEncryptedAccountNumber().isBlank());
            }

            beneficiaryResult = eligibilityService.evaluateBeneficiaryEligibility(sellerOrgId);
            if (beneficiaryResult.eligible() && beneficiaryResult.bankAccount() != null) {
                OrganizationBankAccount single = beneficiaryResult.bankAccount();
                evidence.input("ELIGIBLE_BENEFICIARY", single.getId(), single.getVerificationStatus(),
                        single.getNameMatchStatus(), beneficiaryResult.isSandbox());
            } else {
                evidence.input("INELIGIBLE_BENEFICIARY", beneficiaryResult.failureReason());
            }
        } else {
            evidence.input("SELLER_ORGANIZATION_MISSING");
        }

        // 3. Resolve exact Platform Fraud Policy v1.0.1
        EnginePolicyVersion policyVersion = policyVersionService.getPlatformPolicy(
                EngineType.FRAUD,
                FraudPolicyDefinition.POLICY_KEY,
                FraudPolicyDefinition.POLICY_VERSION
        ).orElse(null);

        if (policyVersion != null) {
            evidence.reference("ENGINE_POLICY_VERSION", policyVersion.getId(),
                    policyVersion.getActivatedAt() != null ? policyVersion.getActivatedAt() : policyVersion.getCreatedAt(),
                    policyVersion.getContentHash(), "PLATFORM_POLICY", policyVersion.getVersion());
            evidence.input("POLICY", policyVersion.getId(), policyVersion.getPolicyKey(),
                    policyVersion.getVersion(), policyVersion.getContentHash(), policyVersion.getActivatedAt());
        } else {
            evidence.input("POLICY_MISSING", FraudPolicyDefinition.POLICY_KEY, FraudPolicyDefinition.POLICY_VERSION);
        }

        return new FraudSources(
                deal,
                milestone,
                invoice,
                checks,
                legitimateAncestors,
                revisionCorrupted,
                s01Candidates,
                s02Candidates,
                sellerAccounts,
                beneficiaryResult,
                policyVersion,
                evidence
        );
    }

    private static void requireScope(boolean matches) {
        if (!matches) {
            throw scopeDenied();
        }
    }

    private static UnauthorizedException scopeDenied() {
        return new UnauthorizedException("Milestone fraud evaluation scope is unavailable or unauthorized");
    }
}
