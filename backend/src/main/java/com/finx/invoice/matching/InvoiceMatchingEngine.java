package com.finx.invoice.matching;

import com.finx.invoice.entity.Invoice;
import com.finx.invoice.enums.CheckStatus;
import com.finx.invoice.enums.DuplicateConfidence;
import com.finx.invoice.enums.InvoiceCheckType;
import com.finx.invoice.enums.MatchingStatus;
import com.finx.invoice.normalization.InvoiceNormalizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class InvoiceMatchingEngine {

    private static final Logger log = LoggerFactory.getLogger(InvoiceMatchingEngine.class);
    @org.springframework.beans.factory.annotation.Value("${finx.invoice.require-supporting-evidence:false}")
    private boolean requireSupportingEvidence;
    private final InvoiceNormalizationService normalization;

    public InvoiceMatchingEngine(InvoiceNormalizationService normalization) {
        this.normalization = normalization;
    }

    public InvoiceMatchingResult evaluate(InvoiceMatchingContext ctx) {
        Invoice invoice = ctx.invoice();
        InvoiceMatchingPolicy policy = ctx.policy() != null ? ctx.policy() : InvoiceMatchingPolicy.defaultPolicy();

        List<InvoiceCheckDetail> checks = new ArrayList<>();
        int totalScore = 0;
        boolean hasCriticalFailure = false;
        DuplicateConfidence duplicateConfidence = DuplicateConfidence.UNIQUE;
        String primaryReasonCode = "INVOICE_EXACT_MATCH";

        // 1. VENDOR_MATCH (5 pts)
        String normInvoiceVendorName = normalization.normalizeLegalName(invoice.getVendorLegalName());
        String normOrgVendorName = ctx.vendorOrg() != null ? normalization.normalizeLegalName(ctx.vendorOrg().getLegalName()) : null;
        boolean vendorOrgIdMatches = ctx.deal() != null && Objects.equals(ctx.deal().getSellerOrganizationId(), invoice.getVendorOrganizationId());

        if (vendorOrgIdMatches && (normInvoiceVendorName != null && !normInvoiceVendorName.isBlank() && normInvoiceVendorName.equals(normOrgVendorName))) {
            checks.add(new InvoiceCheckDetail(InvoiceCheckType.VENDOR_MATCH, CheckStatus.PASS, "VENDOR_IDENTITY_MATCH",
                    "Vendor organization identity matches verified seller profile", 5, 5, "Vendor: " + (ctx.vendorOrg() != null ? ctx.vendorOrg().getLegalName() : "Verified")));
            totalScore += 5;
        } else if (vendorOrgIdMatches) {
            checks.add(new InvoiceCheckDetail(InvoiceCheckType.VENDOR_MATCH, CheckStatus.WARNING, "VENDOR_NAME_SIMILARITY_LOW",
                    "Vendor organization ID matches deal but legal name text differs slightly", 5, 3, "Invoice: " + invoice.getVendorLegalName() + ", Org: " + (ctx.vendorOrg() != null ? ctx.vendorOrg().getLegalName() : "N/A")));
            totalScore += 3;
        } else {
            checks.add(new InvoiceCheckDetail(InvoiceCheckType.VENDOR_MATCH, CheckStatus.FAIL, "VENDOR_IDENTITY_MISMATCH",
                    "Vendor organization does not match deal seller party", 5, 0, "Invoice Vendor Org: " + invoice.getVendorOrganizationId()));
            hasCriticalFailure = true;
            primaryReasonCode = "VENDOR_IDENTITY_MISMATCH";
        }

        // 2. BUYER_MATCH (5 pts)
        String normInvoiceBuyerName = normalization.normalizeLegalName(invoice.getBuyerLegalName());
        String normOrgBuyerName = ctx.buyerOrg() != null ? normalization.normalizeLegalName(ctx.buyerOrg().getLegalName()) : null;
        boolean buyerOrgIdMatches = ctx.deal() != null && Objects.equals(ctx.deal().getBuyerOrganizationId(), invoice.getBuyerOrganizationId());

        if (buyerOrgIdMatches && (normInvoiceBuyerName != null && !normInvoiceBuyerName.isBlank() && normInvoiceBuyerName.equals(normOrgBuyerName))) {
            checks.add(new InvoiceCheckDetail(InvoiceCheckType.BUYER_MATCH, CheckStatus.PASS, "BUYER_IDENTITY_MATCH",
                    "Buyer organization identity matches authorized deal buyer", 5, 5, "Buyer: " + (ctx.buyerOrg() != null ? ctx.buyerOrg().getLegalName() : "Verified")));
            totalScore += 5;
        } else if (buyerOrgIdMatches) {
            checks.add(new InvoiceCheckDetail(InvoiceCheckType.BUYER_MATCH, CheckStatus.WARNING, "BUYER_NAME_SIMILARITY_LOW",
                    "Buyer organization ID matches deal but billed name text differs slightly", 5, 3, "Invoice: " + invoice.getBuyerLegalName() + ", Org: " + (ctx.buyerOrg() != null ? ctx.buyerOrg().getLegalName() : "N/A")));
            totalScore += 3;
        } else {
            checks.add(new InvoiceCheckDetail(InvoiceCheckType.BUYER_MATCH, CheckStatus.FAIL, "BUYER_IDENTITY_MISMATCH",
                    "Invoice is billed to a different buyer than deal buyer organization", 5, 0, "Invoice Buyer Org: " + invoice.getBuyerOrganizationId()));
            hasCriticalFailure = true;
            primaryReasonCode = "BUYER_IDENTITY_MISMATCH";
        }

        // 3. GSTIN_MATCH (5 pts)
        String normInvoiceGstin = normalization.normalizeGstin(invoice.getVendorGstin());
        String normVerifiedGstin = null;
        if (ctx.vendorKyb() != null && ctx.vendorKyb().getGstin() != null) {
            normVerifiedGstin = normalization.normalizeGstin(ctx.vendorKyb().getGstin());
        } else if (ctx.vendorOrg() != null) { normVerifiedGstin = normalization.normalizeGstin(ctx.vendorOrg().getGstin()); }

        if (normInvoiceGstin != null && normVerifiedGstin != null) {
            if (normInvoiceGstin.equals(normVerifiedGstin)) {
                checks.add(new InvoiceCheckDetail(InvoiceCheckType.GSTIN_MATCH, CheckStatus.PASS, "GSTIN_MATCH",
                        "Vendor GSTIN matches verified corporate tax registry", 5, 5, "GSTIN: " + normVerifiedGstin));
                totalScore += 5;
            } else {
                checks.add(new InvoiceCheckDetail(InvoiceCheckType.GSTIN_MATCH, CheckStatus.FAIL, "VENDOR_GSTIN_MISMATCH",
                        "Invoice GSTIN (" + normInvoiceGstin + ") does not match verified KYB profile (" + normVerifiedGstin + ")", 5, 0, "Mismatch"));
                hasCriticalFailure = true;
                primaryReasonCode = "VENDOR_GSTIN_MISMATCH";
            }
        } else if (normVerifiedGstin != null && normInvoiceGstin == null) {
            checks.add(new InvoiceCheckDetail(InvoiceCheckType.GSTIN_MATCH, CheckStatus.WARNING, "GSTIN_NOT_EXTRACTED",
                    "Vendor GSTIN was not extracted from invoice document", 5, 2, "Verified GSTIN on file: " + normVerifiedGstin));
            totalScore += 2;
        } else {
            checks.add(new InvoiceCheckDetail(InvoiceCheckType.GSTIN_MATCH, CheckStatus.NOT_APPLICABLE, "GSTIN_NOT_REQUIRED",
                    "Vendor corporate profile does not require mandatory GSTIN", 5, 5, "N/A"));
            totalScore += 5;
        }

        // 4. PAN_MATCH (5 pts)
        String normInvoicePan = normalization.normalizePan(invoice.getVendorPan());
        String normVerifiedPan = ctx.vendorOrg() != null && ctx.vendorOrg().getTaxId() != null
                ? normalization.normalizePan(ctx.vendorOrg().getTaxId())
                : null;

        if (normInvoicePan != null && normVerifiedPan != null) {
            if (normInvoicePan.equals(normVerifiedPan)) {
                checks.add(new InvoiceCheckDetail(InvoiceCheckType.PAN_MATCH, CheckStatus.PASS, "PAN_MATCH",
                        "Vendor PAN matches verified organizational identity", 5, 5, "PAN: " + normVerifiedPan));
                totalScore += 5;
            } else {
                checks.add(new InvoiceCheckDetail(InvoiceCheckType.PAN_MATCH, CheckStatus.FAIL, "VENDOR_PAN_MISMATCH",
                        "Invoice PAN (" + normInvoicePan + ") does not match verified organizational tax record (" + normVerifiedPan + ")", 5, 0, "Mismatch"));
                hasCriticalFailure = true;
                primaryReasonCode = "VENDOR_PAN_MISMATCH";
            }
        } else if (normVerifiedPan != null && normInvoicePan == null) {
            checks.add(new InvoiceCheckDetail(InvoiceCheckType.PAN_MATCH, CheckStatus.WARNING, "PAN_NOT_EXTRACTED",
                    "Vendor PAN was not extracted from invoice document", 5, 2, "Verified PAN on file: " + normVerifiedPan));
            totalScore += 2;
        } else {
            checks.add(new InvoiceCheckDetail(InvoiceCheckType.PAN_MATCH, CheckStatus.PASS, "PAN_VERIFIED",
                    "Vendor organization verification is in order", 5, 5, "N/A"));
            totalScore += 5;
        }

        // 5. DEAL_MATCH (10 pts)
        if (ctx.deal() != null && ctx.deal().getId().equals(invoice.getDealId())) {
            checks.add(new InvoiceCheckDetail(InvoiceCheckType.DEAL_MATCH, CheckStatus.PASS, "DEAL_MATCH",
                    "Invoice correctly references authorized deal " + ctx.deal().getDealReference(), 10, 10, "Deal ID: " + ctx.deal().getId()));
            totalScore += 10;
        } else {
            checks.add(new InvoiceCheckDetail(InvoiceCheckType.DEAL_MATCH, CheckStatus.FAIL, "DEAL_MISMATCH",
                    "Invoice attached to an invalid or mismatched deal", 10, 0, "Invoice Deal: " + invoice.getDealId()));
            hasCriticalFailure = true;
            primaryReasonCode = "DEAL_MISMATCH";
        }

        // 6. MILESTONE_MATCH (10 pts)
        if (ctx.milestone() != null && ctx.milestone().getId().equals(invoice.getMilestoneId()) && ctx.deal() != null && ctx.milestone().getDealId().equals(ctx.deal().getId())) {
            checks.add(new InvoiceCheckDetail(InvoiceCheckType.MILESTONE_MATCH, CheckStatus.PASS, "MILESTONE_MATCH",
                    "Invoice matches milestone '" + ctx.milestone().getTitle() + "' (Sequence " + ctx.milestone().getSequence() + ")", 10, 10, "Milestone ID: " + ctx.milestone().getId()));
            totalScore += 10;
        } else {
            checks.add(new InvoiceCheckDetail(InvoiceCheckType.MILESTONE_MATCH, CheckStatus.FAIL, "MILESTONE_MISMATCH",
                    "Invoice attached to an invalid or cross-project milestone", 10, 0, "Invoice Milestone: " + invoice.getMilestoneId()));
            hasCriticalFailure = true;
            primaryReasonCode = "MILESTONE_MISMATCH";
        }

        // 7. AMOUNT_MATCH (15 pts)
        BigDecimal milestoneAmount = ctx.milestone() != null ? ctx.milestone().getAmount() : BigDecimal.ZERO;
        BigDecimal invoiceAmount = "SUBTOTAL".equals(policy.comparisonBasis())
                ? invoice.getSubtotal()
                : invoice.getTotalAmount();

        if (invoiceAmount != null && milestoneAmount != null) {
            BigDecimal diff = invoiceAmount.subtract(milestoneAmount).abs();
            BigDecimal allowedTolerance = policy.allowedTolerance() != null ? policy.allowedTolerance() : BigDecimal.ZERO;

            if (diff.compareTo(BigDecimal.ZERO) == 0) {
                checks.add(new InvoiceCheckDetail(InvoiceCheckType.AMOUNT_MATCH, CheckStatus.PASS, "INVOICE_EXACT_MATCH",
                        "Invoice amount exactly matches milestone expected value (" + invoiceAmount + " " + invoice.getCurrency() + ")", 15, 15, "Exact match: " + invoiceAmount));
                totalScore += 15;
            } else if (diff.compareTo(allowedTolerance) <= 0) {
                checks.add(new InvoiceCheckDetail(InvoiceCheckType.AMOUNT_MATCH, CheckStatus.PASS, "WITHIN_ALLOWED_TOLERANCE",
                        "Invoice amount (" + invoiceAmount + ") is within configured allowed tolerance of milestone amount (" + milestoneAmount + ")", 15, 12, "Diff: " + diff));
                totalScore += 12;
            } else {
                checks.add(new InvoiceCheckDetail(InvoiceCheckType.AMOUNT_MATCH, CheckStatus.FAIL, "INVOICE_AMOUNT_MISMATCH",
                        "Invoice amount (" + invoiceAmount + ") does not match milestone contracted amount (" + milestoneAmount + ")", 15, 0, "Difference: " + diff));
                hasCriticalFailure = true;
                primaryReasonCode = "INVOICE_AMOUNT_MISMATCH";
            }
        } else {
            checks.add(new InvoiceCheckDetail(InvoiceCheckType.AMOUNT_MATCH, CheckStatus.FAIL, "INVOICE_AMOUNT_MISSING",
                    "Invoice total amount is missing or invalid", 15, 0, "Amount missing"));
            hasCriticalFailure = true;
            primaryReasonCode = "INVOICE_AMOUNT_MISMATCH";
        }

        // 8. CURRENCY_MATCH (10 pts)
        String expectedCurrency = ctx.milestone() != null && ctx.milestone().getCurrency() != null
                ? normalization.normalizeCurrency(ctx.milestone().getCurrency())
                : "INR";
        String invoiceCurrency = normalization.normalizeCurrency(invoice.getCurrency());

        if (invoiceCurrency != null && invoiceCurrency.equalsIgnoreCase(expectedCurrency) && ctx.deal() != null && invoiceCurrency.equalsIgnoreCase(ctx.deal().getCurrency())) {
            checks.add(new InvoiceCheckDetail(InvoiceCheckType.CURRENCY_MATCH, CheckStatus.PASS, "CURRENCY_MATCH",
                    "Invoice currency (" + invoiceCurrency + ") matches milestone currency", 10, 10, "Currency: " + invoiceCurrency));
            totalScore += 10;
        } else {
            checks.add(new InvoiceCheckDetail(InvoiceCheckType.CURRENCY_MATCH, CheckStatus.FAIL, "INVOICE_CURRENCY_MISMATCH",
                    "Invoice currency (" + invoiceCurrency + ") does not match contracted milestone currency (" + expectedCurrency + ")", 10, 0, "Mismatch: " + invoiceCurrency + " vs " + expectedCurrency));
            hasCriticalFailure = true;
            primaryReasonCode = "INVOICE_CURRENCY_MISMATCH";
        }

        // 9. INVOICE_NUMBER_VALID (5 pts)
        if (invoice.getInvoiceNumber() != null && !invoice.getInvoiceNumber().isBlank()) {
            checks.add(new InvoiceCheckDetail(InvoiceCheckType.INVOICE_NUMBER_VALID, CheckStatus.PASS, "INVOICE_NUMBER_VALID",
                    "Invoice identifier is well-formed (" + invoice.getInvoiceNumber() + ")", 5, 5, "Number: " + invoice.getInvoiceNumber()));
            totalScore += 5;
        } else {
            checks.add(new InvoiceCheckDetail(InvoiceCheckType.INVOICE_NUMBER_VALID, CheckStatus.FAIL, "INVOICE_NUMBER_MISSING",
                    "Invoice identifier is missing or blank", 5, 0, "Blank"));
            hasCriticalFailure = true;
            primaryReasonCode = "INVOICE_NUMBER_MISSING";
        }

        // 10. INVOICE_DATE_VALID (5 pts)
        LocalDate invDate = invoice.getInvoiceDate() != null ? invoice.getInvoiceDate() : LocalDate.now(ZoneOffset.UTC);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (invoice.getInvoiceDate() == null || invDate.isAfter(today.plusDays(1)) || (invoice.getDueDate() != null && invDate.isAfter(invoice.getDueDate()))) {
            checks.add(new InvoiceCheckDetail(InvoiceCheckType.INVOICE_DATE_VALID, CheckStatus.FAIL, "INVOICE_DATE_ANOMALY",
                    "Invoice date is future-dated (" + invDate + ") which violates issuance timeline", 5, 0, "Future date: " + invDate));
            hasCriticalFailure = true;
            primaryReasonCode = "INVOICE_DATE_ANOMALY";
        } else if (ctx.deal() != null && ctx.deal().getCreatedAt() != null) {
            LocalDate dealCreated = ctx.deal().getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
            if (invDate.isBefore(dealCreated.minusDays(180))) {
                checks.add(new InvoiceCheckDetail(InvoiceCheckType.INVOICE_DATE_VALID, CheckStatus.WARNING, "INVOICE_DATE_ANOMALY",
                        "Invoice date (" + invDate + ") significantly precedes deal initiation date (" + dealCreated + ")", 5, 2, "Precedes deal"));
                totalScore += 2;
            } else {
                checks.add(new InvoiceCheckDetail(InvoiceCheckType.INVOICE_DATE_VALID, CheckStatus.PASS, "INVOICE_DATE_VALID",
                        "Invoice date is plausible and aligned with project duration", 5, 5, "Date: " + invDate));
                totalScore += 5;
            }
        } else {
            checks.add(new InvoiceCheckDetail(InvoiceCheckType.INVOICE_DATE_VALID, CheckStatus.PASS, "INVOICE_DATE_VALID",
                    "Invoice date is valid", 5, 5, "Date: " + invDate));
            totalScore += 5;
        }

        // 11. DUPLICATE_CHECK & DOCUMENT_HASH_CHECK (15 pts: 10 + 5)
        boolean exactHashDuplicate = false;
        boolean sameVendorInvoiceNumDuplicate = false;
        boolean likelyDuplicate = false;
        boolean gstDuplicate = false;

        String currentHash = invoice.getDocumentHash();
        String currentNormNumber = invoice.getNormalizedInvoiceNumber();
        UUID currentVendorOrg = invoice.getVendorOrganizationId();
        UUID currentInvoiceId = invoice.getId();

        if (ctx.existingInvoices() != null) {
            for (Invoice existing : ctx.existingInvoices()) {
                if (currentInvoiceId != null && currentInvoiceId.equals(existing.getId())) {
                    continue; // Skip self
                }

                // Signal 1: Exact Document Hash
                if (currentHash != null && currentHash.equalsIgnoreCase(existing.getDocumentHash())) {
                    exactHashDuplicate = true;
                }

                // Signal 2: Same Vendor Org + Same Invoice Number
                if (currentVendorOrg != null && currentVendorOrg.equals(existing.getVendorOrganizationId())
                        && !Objects.equals(invoice.getRevisionOf(), existing.getId()) && currentNormNumber != null && currentNormNumber.equalsIgnoreCase(existing.getNormalizedInvoiceNumber())) {
                    sameVendorInvoiceNumDuplicate = true;
                }

                if (!Objects.equals(invoice.getRevisionOf(),existing.getId()) && invoice.getVendorGstin()!=null && !invoice.getVendorGstin().isBlank()
                        && Objects.equals(normalization.normalizeGstin(invoice.getVendorGstin()),normalization.normalizeGstin(existing.getVendorGstin()))
                        && currentNormNumber!=null && currentNormNumber.equals(existing.getNormalizedInvoiceNumber())) gstDuplicate=true;
                // Signal 3: Same Number + Same Amount + close date (within 30 days) across any vendor deal
                if (!Objects.equals(invoice.getRevisionOf(), existing.getId()) && currentNormNumber != null && currentNormNumber.equalsIgnoreCase(existing.getNormalizedInvoiceNumber())
                        && invoice.getTotalAmount() != null && existing.getTotalAmount() != null
                        && invoice.getTotalAmount().compareTo(existing.getTotalAmount()) == 0) {
                    if (existing.getInvoiceDate() != null && invDate != null) {
                        long daysBetween = Math.abs(ChronoUnit.DAYS.between(existing.getInvoiceDate(), invDate));
                        if (daysBetween <= 30) {
                            likelyDuplicate = true;
                        }
                    }
                }
            }
        }

        // Evaluate Document Hash Check (5 pts)
        if (exactHashDuplicate) {
            duplicateConfidence = DuplicateConfidence.EXACT_DUPLICATE;
            checks.add(new InvoiceCheckDetail(InvoiceCheckType.DOCUMENT_HASH_CHECK, CheckStatus.FAIL, "DOCUMENT_HASH_DUPLICATE",
                    "Identical document SHA-256 hash already exists in FinX records. Reused invoice file detected.", 5, 0, "Hash: " + currentHash));
            hasCriticalFailure = true;
            primaryReasonCode = "DOCUMENT_HASH_DUPLICATE";
        } else {
            checks.add(new InvoiceCheckDetail(InvoiceCheckType.DOCUMENT_HASH_CHECK, CheckStatus.PASS, "DOCUMENT_HASH_UNIQUE",
                    "Document SHA-256 cryptographic fingerprint is unique", 5, 5, "Hash: " + currentHash));
            totalScore += 5;
        }

        // Evaluate Duplicate Signal Check (10 pts)
        if (exactHashDuplicate || sameVendorInvoiceNumDuplicate) {
            if (duplicateConfidence == DuplicateConfidence.UNIQUE) {
                duplicateConfidence = sameVendorInvoiceNumDuplicate ? DuplicateConfidence.EXACT_DUPLICATE : DuplicateConfidence.LIKELY_DUPLICATE;
            }
            checks.add(new InvoiceCheckDetail(InvoiceCheckType.DUPLICATE_CHECK, CheckStatus.FAIL, "INVOICE_NUMBER_DUPLICATE",
                    "This invoice number has already been used by your organization in another submission.", 10, 0, "Duplicate Number: " + invoice.getInvoiceNumber()));
            hasCriticalFailure = true;
            if ("INVOICE_EXACT_MATCH".equals(primaryReasonCode)) {
                primaryReasonCode = "INVOICE_NUMBER_DUPLICATE";
            }
        } else if (gstDuplicate) {
            duplicateConfidence = DuplicateConfidence.LIKELY_DUPLICATE;
            checks.add(new InvoiceCheckDetail(InvoiceCheckType.DUPLICATE_CHECK, CheckStatus.WARNING, "INVOICE_NUMBER_DUPLICATE", "Invoice tax identifier and number were seen in another submission; review required.", 10, 0, null));
            hasCriticalFailure=true;
        } else if (likelyDuplicate) {
            duplicateConfidence = DuplicateConfidence.POSSIBLE_DUPLICATE;
            checks.add(new InvoiceCheckDetail(InvoiceCheckType.DUPLICATE_CHECK, CheckStatus.WARNING, "POSSIBLE_DUPLICATE_INVOICE",
                    "Similar invoice number and amount detected within recent billing cycle. Review recommended.", 10, 5, "Possible duplicate"));
            totalScore += 5;
            if ("INVOICE_EXACT_MATCH".equals(primaryReasonCode)) {
                primaryReasonCode = "POSSIBLE_DUPLICATE_INVOICE";
            }
        } else {
            checks.add(new InvoiceCheckDetail(InvoiceCheckType.DUPLICATE_CHECK, CheckStatus.PASS, "UNIQUE_INVOICE",
                    "No duplicate invoice numbers or identical documents detected for vendor", 10, 10, "Unique"));
            totalScore += 10;
        }

        // 12. BANK_DETAIL_CHECK (Bonus / Neutral check)
        if (invoice.getBankAccountLast4() != null || invoice.getBankIfsc() != null) {
            if (ctx.vendorBank() != null) {
                String masked = ctx.vendorBank().getAccountNumberMasked();
                String verifiedLast4 = (masked != null && masked.length() >= 4) ? masked.substring(masked.length() - 4) : null;
                String verifiedIfsc = ctx.vendorBank().getIfscCode();

                boolean last4Match = invoice.getBankAccountLast4() == null || (verifiedLast4 != null && verifiedLast4.equals(invoice.getBankAccountLast4()));
                boolean ifscMatch = invoice.getBankIfsc() == null || (verifiedIfsc != null && verifiedIfsc.equalsIgnoreCase(invoice.getBankIfsc()));

                if (last4Match && ifscMatch) {
                    checks.add(new InvoiceCheckDetail(InvoiceCheckType.BANK_DETAIL_CHECK, CheckStatus.PASS, "BANK_DETAILS_MATCH",
                            "Invoice payout bank details match vendor verified bank account on file", 0, 0, "Verified Bank Match"));
                } else {
                    checks.add(new InvoiceCheckDetail(InvoiceCheckType.BANK_DETAIL_CHECK, CheckStatus.WARNING, "BANK_DETAILS_MISMATCH",
                            "Invoice bank details differ from verified payout account on file. Release will use verified account only.", 0, 0, "Mismatch noted"));
                }
            } else {
                checks.add(new InvoiceCheckDetail(InvoiceCheckType.BANK_DETAIL_CHECK, CheckStatus.WARNING, "BANK_ACCOUNT_NOT_VERIFIED",
                        "Vendor does not have an active verified bank account in FinX records", 0, 0, "No verified bank"));
            }
        } else {
            checks.add(new InvoiceCheckDetail(InvoiceCheckType.BANK_DETAIL_CHECK, CheckStatus.NOT_APPLICABLE, "BANK_DETAILS_NOT_EXTRACTED",
                    "Invoice does not specify separate bank account instructions", 0, 0, "N/A"));
        }

        // 13. SUPPORTING_EVIDENCE_CHECK (10 pts)
        if (ctx.deliverables() != null && ctx.deliverables().stream().anyMatch(d -> Objects.equals(d.getMilestoneId(), invoice.getMilestoneId()) && d.getStatus() != com.finx.milestone.entity.DeliverableStatus.REJECTED)) {
            checks.add(new InvoiceCheckDetail(InvoiceCheckType.SUPPORTING_EVIDENCE_CHECK, CheckStatus.PASS, "SUPPORTING_EVIDENCE_PRESENT",
                    "Milestone deliverables and supporting work artifacts are attached (" + ctx.deliverables().size() + " files)", 10, 10, "Deliverables attached: " + ctx.deliverables().size()));
            totalScore += 10;
        } else {
            checks.add(new InvoiceCheckDetail(InvoiceCheckType.SUPPORTING_EVIDENCE_CHECK, CheckStatus.WARNING, "MISSING_REQUIRED_EVIDENCE",
                    "No milestone deliverable documents are currently attached to this milestone", 10, 4, "No deliverables"));
            totalScore += 4;
            if ("INVOICE_EXACT_MATCH".equals(primaryReasonCode)) {
                primaryReasonCode = "MISSING_REQUIRED_EVIDENCE";
            }
        }

        // Verified provenance is required: organization claims alone are not KYB evidence.
        boolean vendorVerified = ctx.vendorOrg() != null && ctx.vendorOrg().getVerificationStatus() == com.finx.org.enums.OrgEnums.OrgVerificationStatus.VERIFIED
                && ctx.vendorKyb() != null && ctx.vendorKyb().getStatus() == com.finx.verification.enums.VerificationStatus.VERIFIED
                && (ctx.vendorKyb().getExpiresAt() == null || ctx.vendorKyb().getExpiresAt().isAfter(java.time.Instant.now()));
        if (!vendorVerified) {
            checks.add(new InvoiceCheckDetail(InvoiceCheckType.VENDOR_MATCH, CheckStatus.WARNING, "VENDOR_KYB_UNVERIFIED", "Verified vendor business identity evidence is unavailable or expired.", 0, 0, null));
            hasCriticalFailure = true;
        }
        if (ctx.buyerOrg() == null || ctx.buyerOrg().getVerificationStatus() != com.finx.org.enums.OrgEnums.OrgVerificationStatus.VERIFIED) {
            checks.add(new InvoiceCheckDetail(InvoiceCheckType.BUYER_MATCH, CheckStatus.WARNING, "BUYER_IDENTITY_UNVERIFIED", "Verified buyer identity evidence is unavailable.", 0, 0, null));
            hasCriticalFailure = true;
        }
        String buyerGstin = ctx.buyerOrg() == null ? null : normalization.normalizeGstin(ctx.buyerOrg().getGstin());
        if (buyerGstin != null && !buyerGstin.equals(normalization.normalizeGstin(invoice.getBuyerGstin()))) {
            checks.add(new InvoiceCheckDetail(InvoiceCheckType.BUYER_MATCH, CheckStatus.FAIL, "BUYER_GSTIN_MISMATCH", "Invoice buyer GSTIN does not match the buyer on this deal.", 0, 0, null));
            hasCriticalFailure = true;
            primaryReasonCode = "BUYER_GSTIN_MISMATCH";
        }
        if (invoice.getSubtotal() != null && invoice.getTaxAmount() != null && invoice.getTotalAmount() != null
                && invoice.getSubtotal().add(invoice.getTaxAmount()).compareTo(invoice.getTotalAmount()) != 0) {
            checks.add(new InvoiceCheckDetail(InvoiceCheckType.TAX_ARITHMETIC_CHECK, CheckStatus.FAIL, "TAX_ARITHMETIC_MISMATCH", "Invoice subtotal plus tax does not equal total.", 0, 0, null));
            hasCriticalFailure = true;
        }
        boolean lowConfidence = invoice.getExtractedFields().stream().anyMatch(f -> f.getConfidence() == null
                || f.getConfidence().compareTo(policy.minExtractionConfidence()) < 0 || f.getConfidence().compareTo(BigDecimal.ONE) > 0);
        checks.add(new InvoiceCheckDetail(InvoiceCheckType.EXTRACTION_CONFIDENCE, lowConfidence ? CheckStatus.WARNING : CheckStatus.PASS,
                lowConfidence ? "LOW_EXTRACTION_CONFIDENCE" : "EXTRACTION_CONFIDENCE_ACCEPTABLE",
                lowConfidence ? "One or more extracted fields require manual verification." : "Field confidence meets policy; manual entry remains a vendor declaration.", 0, 0, null));
        // Every non-evidence warning is review-worthy, including bank and uncertain identity.
        hasCriticalFailure |= checks.stream().anyMatch(c -> c.status() == CheckStatus.WARNING && c.checkType() != InvoiceCheckType.SUPPORTING_EVIDENCE_CHECK);
        // Apply configurable category weights instead of treating the UI or built-in point values as policy.
        List<InvoiceCheckDetail> weighted = new ArrayList<>();
        for (InvoiceCheckDetail check : checks) {
            int categoryWeight = switch (check.checkType()) {
                case VENDOR_MATCH, BUYER_MATCH, GSTIN_MATCH, PAN_MATCH -> policy.businessIdentityWeight();
                case DEAL_MATCH, MILESTONE_MATCH -> policy.dealMilestoneWeight();
                case AMOUNT_MATCH, CURRENCY_MATCH -> policy.amountCurrencyWeight();
                case INVOICE_NUMBER_VALID, INVOICE_DATE_VALID -> policy.identifierDateWeight();
                case DUPLICATE_CHECK, DOCUMENT_HASH_CHECK -> policy.duplicateWeight();
                case SUPPORTING_EVIDENCE_CHECK -> policy.supportingEvidenceWeight();
                default -> 0;
            };
            int originalCategoryWeight = switch (check.checkType()) {
                case VENDOR_MATCH, BUYER_MATCH, GSTIN_MATCH, PAN_MATCH, DEAL_MATCH, MILESTONE_MATCH -> 20;
                case AMOUNT_MATCH, CURRENCY_MATCH -> 25;
                case DUPLICATE_CHECK, DOCUMENT_HASH_CHECK -> 15;
                default -> 10;
            };
            int weight = check.weight() * categoryWeight / originalCategoryWeight;
            int earned = check.weight() == 0 ? 0 : check.scoreEarned() * weight / check.weight();
            weighted.add(new InvoiceCheckDetail(check.checkType(), check.status(), check.reasonCode(), check.message(), weight, earned,
                    check.checkType() == InvoiceCheckType.AMOUNT_MATCH ? "basis=" + policy.comparisonBasis() + ";milestone=" + milestoneAmount + ";invoice=" + invoiceAmount + ";tolerance=" + policy.allowedTolerance() : check.details()));
        }
        checks = weighted;
        int possibleScore = checks.stream().mapToInt(InvoiceCheckDetail::weight).sum();
        totalScore = possibleScore == 0 ? 0 : checks.stream().mapToInt(InvoiceCheckDetail::scoreEarned).sum() * 100 / possibleScore;
        hasCriticalFailure |= requireSupportingEvidence && checks.stream().anyMatch(c -> c.checkType()==InvoiceCheckType.SUPPORTING_EVIDENCE_CHECK && c.status()!=CheckStatus.PASS);
        boolean hasWarning = checks.stream().anyMatch(c -> c.status() == CheckStatus.WARNING);
        // 14. Determine Status based on Score & Critical Failures
        MatchingStatus matchingStatus;
        if (hasCriticalFailure) {
            matchingStatus = totalScore < 50 ? MatchingStatus.MAJOR_MISMATCH : MatchingStatus.REVIEW_REQUIRED;
        } else if (totalScore >= 90 && !hasWarning) {
            matchingStatus = MatchingStatus.STRONG_MATCH;
        } else if (totalScore >= 75) {
            matchingStatus = MatchingStatus.MATCH_WITH_WARNINGS;
        } else if (totalScore >= 50) {
            matchingStatus = MatchingStatus.REVIEW_REQUIRED;
        } else {
            matchingStatus = MatchingStatus.MAJOR_MISMATCH;
        }

        String summaryMessage = switch (matchingStatus) {
            case STRONG_MATCH -> "Invoice matches all milestone and contractual parameters. Ready for PM approval.";
            case MATCH_WITH_WARNINGS -> "Invoice matches core contract parameters with minor warnings. Review recommended.";
            case REVIEW_REQUIRED -> "Invoice contains discrepancies or duplicate indicators. Requires PM arbitration.";
            case MAJOR_MISMATCH -> "Significant mismatch in financial amount, currency, or corporate identity.";
            default -> "Invoice evaluation pending.";
        };

        return new InvoiceMatchingResult(
                totalScore,
                matchingStatus,
                duplicateConfidence,
                primaryReasonCode,
                checks,
                summaryMessage,
                policy.version()
        );
    }
}
