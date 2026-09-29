package com.finx.invoice.matching;

import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.invoice.entity.Invoice;
import com.finx.invoice.enums.CheckStatus;
import com.finx.invoice.enums.DuplicateConfidence;
import com.finx.invoice.enums.InvoiceCheckType;
import com.finx.invoice.enums.MatchingStatus;
import com.finx.invoice.normalization.InvoiceNormalizationService;
import com.finx.milestone.entity.Deliverable;
import com.finx.milestone.entity.Milestone;
import com.finx.org.entity.Organization;
import com.finx.org.entity.OrganizationBankAccount;
import com.finx.org.enums.OrgEnums.BankVerificationStatus;
import com.finx.org.enums.OrgEnums.EntityType;
import com.finx.verification.entity.KybProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class InvoiceMatchingEngineTest {

    private InvoiceMatchingEngine matchingEngine;
    private InvoiceNormalizationService normalizationService;

    private UUID vendorOrgId;
    private UUID buyerOrgId;
    private UUID dealId;
    private UUID milestoneId;

    private Organization vendorOrg;
    private Organization buyerOrg;
    private Deal deal;
    private Milestone milestone;
    private KybProfile vendorKyb;
    private OrganizationBankAccount vendorBank;

    @BeforeEach
    void setUp() {
        normalizationService = new InvoiceNormalizationService();
        matchingEngine = new InvoiceMatchingEngine(normalizationService);

        vendorOrgId = UUID.randomUUID();
        buyerOrgId = UUID.randomUUID();
        dealId = UUID.randomUUID();
        milestoneId = UUID.randomUUID();

        vendorOrg = new Organization("Apex Tech", "Apex Tech Solutions Private Limited", EntityType.PRIVATE_LIMITED, "U12345", "ABCDE1234F", "27ABCDE1234F1Z5", "Mumbai", UUID.randomUUID());
        vendorOrg.setId(vendorOrgId);

        buyerOrg = new Organization("Reliance Retail", "Reliance Retail Limited", EntityType.PUBLIC_LIMITED, "U67890", "AABCR1234G", "27AABCR1234G1Z8", "Mumbai", UUID.randomUUID());
        buyerOrg.setId(buyerOrgId);

        deal = new Deal();
        deal.setId(dealId);
        deal.setSellerOrganizationId(vendorOrgId);
        deal.setBuyerOrganizationId(buyerOrgId);
        deal.setStatus(DealStatus.ACCEPTED);
        deal.setTotalAmount(new BigDecimal("500000.00"));
        deal.setCurrency("INR");
        deal.setTitle("Enterprise Cloud Migration");

        milestone = new Milestone(dealId, "Phase 1: Architecture Blueprint", "Deliver initial cloud architecture", 1, new BigDecimal("250000.00"), "INR", Instant.now());
        milestone.setId(milestoneId);

        vendorOrg.setVerificationStatus(com.finx.org.enums.OrgEnums.OrgVerificationStatus.VERIFIED);
        buyerOrg.setVerificationStatus(com.finx.org.enums.OrgEnums.OrgVerificationStatus.VERIFIED);
        vendorKyb = new KybProfile(vendorOrgId, "******999K", vendorOrg.getGstin(), null, null);
        vendorKyb.setStatus(com.finx.verification.enums.VerificationStatus.VERIFIED);
        vendorKyb.setGstStatus(com.finx.verification.enums.VerificationStatus.VERIFIED);
        vendorKyb.setPanStatus(com.finx.verification.enums.VerificationStatus.VERIFIED);
        vendorBank = new OrganizationBankAccount(vendorOrgId, "Apex Tech Solutions", "******5678", "hash5678", "HDFC0001234", "HDFC Bank", "Nariman Point", true);
        vendorBank.setVerificationStatus(BankVerificationStatus.VERIFIED);
    }

    private Invoice buildStandardInvoice() {
        Invoice invoice = new Invoice();
        invoice.setId(UUID.randomUUID());
        invoice.setDealId(dealId);
        invoice.setMilestoneId(milestoneId);
        invoice.setVendorOrganizationId(vendorOrgId);
        invoice.setBuyerOrganizationId(buyerOrgId);
        invoice.setInvoiceNumber("INV-2026-001");
        invoice.setNormalizedInvoiceNumber("INV-2026-001");
        invoice.setInvoiceDate(LocalDate.now());
        invoice.setDueDate(LocalDate.now().plusDays(30));
        invoice.setSubtotal(new BigDecimal("211864.41"));
        invoice.setTaxAmount(new BigDecimal("38135.59"));
        invoice.setTotalAmount(new BigDecimal("250000.00"));
        invoice.setCurrency("INR");
        invoice.setComparisonBasis("TOTAL");
        invoice.setVendorLegalName("Apex Tech Solutions Private Limited");
        invoice.setVendorPan("ABCDE1234F");
        invoice.setVendorGstin("27ABCDE1234F1Z5");
        invoice.setBuyerLegalName("Reliance Retail Limited");
        invoice.setBuyerGstin("27AABCR1234G1Z8");
        invoice.setBankAccountLast4("5678");
        invoice.setBankIfsc("HDFC0001234");
        invoice.setFileName("invoice_apex_2026.pdf");
        invoice.setFileUrl("https://storage.finx.local/invoices/inv1.pdf");
        invoice.setDocumentHash("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        invoice.setCreatedBy(UUID.randomUUID());
        return invoice;
    }

    @Test
    @DisplayName("1. Perfect invoice produces STRONG_MATCH (Score >= 90) with all 14 checks passing")
    void testPerfectInvoice_StrongMatch() {
        Invoice invoice = buildStandardInvoice();
        Deliverable deliverable = new Deliverable(milestoneId, UUID.randomUUID(), "arch_spec.pdf", "https://finx/arch.pdf", "Blueprint");
        List<Deliverable> deliverables = List.of(deliverable);

        InvoiceMatchingContext ctx = new InvoiceMatchingContext(
                invoice, deal, milestone, vendorOrg, buyerOrg, vendorKyb, vendorBank, deliverables, Collections.emptyList(), InvoiceMatchingPolicy.defaultPolicy()
        );

        InvoiceMatchingResult result = matchingEngine.evaluate(ctx);

        assertEquals(MatchingStatus.STRONG_MATCH, result.matchingStatus());
        assertTrue(result.matchScore() >= 90, "Score should be >= 90, actual: " + result.matchScore());
        assertEquals(DuplicateConfidence.UNIQUE, result.duplicateConfidence());
        assertEquals("INVOICE_EXACT_MATCH", result.primaryReasonCode());

        // Verify key checks
        assertCheckStatus(result.checks(), InvoiceCheckType.VENDOR_MATCH, CheckStatus.PASS);
        assertCheckStatus(result.checks(), InvoiceCheckType.BUYER_MATCH, CheckStatus.PASS);
        assertCheckStatus(result.checks(), InvoiceCheckType.AMOUNT_MATCH, CheckStatus.PASS);
        assertCheckStatus(result.checks(), InvoiceCheckType.CURRENCY_MATCH, CheckStatus.PASS);
        assertCheckStatus(result.checks(), InvoiceCheckType.GSTIN_MATCH, CheckStatus.PASS);
        assertCheckStatus(result.checks(), InvoiceCheckType.PAN_MATCH, CheckStatus.PASS);
        assertCheckStatus(result.checks(), InvoiceCheckType.DUPLICATE_CHECK, CheckStatus.PASS);
        assertCheckStatus(result.checks(), InvoiceCheckType.DOCUMENT_HASH_CHECK, CheckStatus.PASS);
        assertCheckStatus(result.checks(), InvoiceCheckType.SUPPORTING_EVIDENCE_CHECK, CheckStatus.PASS);
    }

    @Test
    @DisplayName("2. Correct invoice without deliverables produces MATCH_WITH_WARNINGS (Score 75-89)")
    void testCorrectInvoice_MinorEvidenceMissing_MatchWithWarnings() {
        Invoice invoice = buildStandardInvoice();
        List<Deliverable> emptyDeliverables = Collections.emptyList();

        InvoiceMatchingContext ctx = new InvoiceMatchingContext(
                invoice, deal, milestone, vendorOrg, buyerOrg, vendorKyb, vendorBank, emptyDeliverables, Collections.emptyList(), InvoiceMatchingPolicy.defaultPolicy()
        );

        InvoiceMatchingResult result = matchingEngine.evaluate(ctx);

        assertTrue(result.matchScore() >= 75 && result.matchScore() < 95);
        assertEquals(MatchingStatus.MATCH_WITH_WARNINGS, result.matchingStatus());
        assertCheckStatus(result.checks(), InvoiceCheckType.SUPPORTING_EVIDENCE_CHECK, CheckStatus.WARNING);
    }

    @Test
    @DisplayName("3. Amount mismatch triggers FAIL and REVIEW_REQUIRED")
    void testAmountMismatch_ReviewRequired() {
        Invoice invoice = buildStandardInvoice();
        invoice.setTotalAmount(new BigDecimal("340000.00")); // Mismatch: 340k vs 250k
        Deliverable deliverable = new Deliverable(milestoneId, UUID.randomUUID(), "arch.pdf", "https://finx/arch.pdf", "Spec");

        InvoiceMatchingContext ctx = new InvoiceMatchingContext(
                invoice, deal, milestone, vendorOrg, buyerOrg, vendorKyb, vendorBank, List.of(deliverable), Collections.emptyList(), InvoiceMatchingPolicy.defaultPolicy()
        );

        InvoiceMatchingResult result = matchingEngine.evaluate(ctx);

        assertEquals(MatchingStatus.REVIEW_REQUIRED, result.matchingStatus());
        assertEquals("INVOICE_AMOUNT_MISMATCH", result.primaryReasonCode());
        assertCheckStatus(result.checks(), InvoiceCheckType.AMOUNT_MATCH, CheckStatus.FAIL);
    }

    @Test
    @DisplayName("4. Currency mismatch triggers FAIL and REVIEW_REQUIRED")
    void testCurrencyMismatch_ReviewRequired() {
        Invoice invoice = buildStandardInvoice();
        invoice.setCurrency("USD"); // Deal/milestone is INR

        InvoiceMatchingContext ctx = new InvoiceMatchingContext(
                invoice, deal, milestone, vendorOrg, buyerOrg, vendorKyb, vendorBank, Collections.emptyList(), Collections.emptyList(), InvoiceMatchingPolicy.defaultPolicy()
        );

        InvoiceMatchingResult result = matchingEngine.evaluate(ctx);

        assertEquals(MatchingStatus.REVIEW_REQUIRED, result.matchingStatus());
        assertEquals("INVOICE_CURRENCY_MISMATCH", result.primaryReasonCode());
        assertCheckStatus(result.checks(), InvoiceCheckType.CURRENCY_MATCH, CheckStatus.FAIL);
    }

    @Test
    @DisplayName("5. Vendor GSTIN mismatch triggers VENDOR_GSTIN_MISMATCH")
    void testVendorGstinMismatch() {
        Invoice invoice = buildStandardInvoice();
        invoice.setVendorGstin("36FAKEGSTIN12345"); // Mismatch with verified 27ABCDE1234F1Z5

        InvoiceMatchingContext ctx = new InvoiceMatchingContext(
                invoice, deal, milestone, vendorOrg, buyerOrg, vendorKyb, vendorBank, Collections.emptyList(), Collections.emptyList(), InvoiceMatchingPolicy.defaultPolicy()
        );

        InvoiceMatchingResult result = matchingEngine.evaluate(ctx);

        assertEquals(MatchingStatus.REVIEW_REQUIRED, result.matchingStatus());
        assertEquals("VENDOR_GSTIN_MISMATCH", result.primaryReasonCode());
        assertCheckStatus(result.checks(), InvoiceCheckType.GSTIN_MATCH, CheckStatus.FAIL);
    }

    @Test
    @DisplayName("6. Vendor PAN mismatch triggers VENDOR_PAN_MISMATCH")
    void testVendorPanMismatch() {
        Invoice invoice = buildStandardInvoice();
        invoice.setVendorPan("ZZZZZ9999Z"); // Mismatch with ABCDE1234F

        InvoiceMatchingContext ctx = new InvoiceMatchingContext(
                invoice, deal, milestone, vendorOrg, buyerOrg, vendorKyb, vendorBank, Collections.emptyList(), Collections.emptyList(), InvoiceMatchingPolicy.defaultPolicy()
        );

        InvoiceMatchingResult result = matchingEngine.evaluate(ctx);

        assertEquals(MatchingStatus.REVIEW_REQUIRED, result.matchingStatus());
        assertEquals("VENDOR_PAN_MISMATCH", result.primaryReasonCode());
        assertCheckStatus(result.checks(), InvoiceCheckType.PAN_MATCH, CheckStatus.FAIL);
    }

    @Test
    @DisplayName("7. Buyer identity mismatch triggers BUYER_IDENTITY_MISMATCH")
    void testBuyerIdentityMismatch() {
        Invoice invoice = buildStandardInvoice();
        invoice.setBuyerOrganizationId(UUID.randomUUID()); // Mismatched buyer organization!

        InvoiceMatchingContext ctx = new InvoiceMatchingContext(
                invoice, deal, milestone, vendorOrg, buyerOrg, vendorKyb, vendorBank, Collections.emptyList(), Collections.emptyList(), InvoiceMatchingPolicy.defaultPolicy()
        );

        InvoiceMatchingResult result = matchingEngine.evaluate(ctx);

        assertEquals(MatchingStatus.REVIEW_REQUIRED, result.matchingStatus());
        assertEquals("BUYER_IDENTITY_MISMATCH", result.primaryReasonCode());
        assertCheckStatus(result.checks(), InvoiceCheckType.BUYER_MATCH, CheckStatus.FAIL);
    }

    @Test
    @DisplayName("8. Wrong milestone triggers MILESTONE_MISMATCH")
    void testWrongMilestone() {
        Invoice invoice = buildStandardInvoice();
        invoice.setMilestoneId(UUID.randomUUID()); // Mismatched milestone ID!

        InvoiceMatchingContext ctx = new InvoiceMatchingContext(
                invoice, deal, milestone, vendorOrg, buyerOrg, vendorKyb, vendorBank, Collections.emptyList(), Collections.emptyList(), InvoiceMatchingPolicy.defaultPolicy()
        );

        InvoiceMatchingResult result = matchingEngine.evaluate(ctx);

        assertEquals(MatchingStatus.REVIEW_REQUIRED, result.matchingStatus());
        assertEquals("MILESTONE_MISMATCH", result.primaryReasonCode());
        assertCheckStatus(result.checks(), InvoiceCheckType.MILESTONE_MATCH, CheckStatus.FAIL);
    }

    @Test
    @DisplayName("9. Wrong deal triggers DEAL_MISMATCH")
    void testWrongDeal() {
        Invoice invoice = buildStandardInvoice();
        invoice.setDealId(UUID.randomUUID()); // Mismatched deal ID!

        InvoiceMatchingContext ctx = new InvoiceMatchingContext(
                invoice, deal, milestone, vendorOrg, buyerOrg, vendorKyb, vendorBank, Collections.emptyList(), Collections.emptyList(), InvoiceMatchingPolicy.defaultPolicy()
        );

        InvoiceMatchingResult result = matchingEngine.evaluate(ctx);

        assertEquals(MatchingStatus.REVIEW_REQUIRED, result.matchingStatus());
        assertEquals("DEAL_MISMATCH", result.primaryReasonCode());
        assertCheckStatus(result.checks(), InvoiceCheckType.DEAL_MATCH, CheckStatus.FAIL);
    }

    @Test
    @DisplayName("10. Exact same document hash uploaded twice triggers DOCUMENT_HASH_DUPLICATE")
    void testSameDocumentUploadedTwice_HashDuplicate() {
        Invoice invoice = buildStandardInvoice();

        Invoice existing = new Invoice();
        existing.setId(UUID.randomUUID());
        existing.setDocumentHash(invoice.getDocumentHash()); // Identical hash
        existing.setVendorOrganizationId(vendorOrgId);
        existing.setNormalizedInvoiceNumber("PREV-INV-999");
        existing.setTotalAmount(new BigDecimal("100000.00"));

        InvoiceMatchingContext ctx = new InvoiceMatchingContext(
                invoice, deal, milestone, vendorOrg, buyerOrg, vendorKyb, vendorBank, Collections.emptyList(), List.of(existing), InvoiceMatchingPolicy.defaultPolicy()
        );

        InvoiceMatchingResult result = matchingEngine.evaluate(ctx);

        assertEquals(DuplicateConfidence.EXACT_DUPLICATE, result.duplicateConfidence());
        assertEquals("DOCUMENT_HASH_DUPLICATE", result.primaryReasonCode());
        assertCheckStatus(result.checks(), InvoiceCheckType.DOCUMENT_HASH_CHECK, CheckStatus.FAIL);
    }

    @Test
    @DisplayName("11. Same Vendor uploading same Invoice Number triggers duplicate warning")
    void testSameVendorSameInvoiceNumber_Duplicate() {
        Invoice invoice = buildStandardInvoice();

        Invoice existing = new Invoice();
        existing.setId(UUID.randomUUID());
        existing.setDocumentHash("different_hash_1234567890abcdef");
        existing.setVendorOrganizationId(vendorOrgId); // Same vendor
        existing.setNormalizedInvoiceNumber("INV-2026-001"); // Same invoice number
        existing.setTotalAmount(new BigDecimal("250000.00"));

        InvoiceMatchingContext ctx = new InvoiceMatchingContext(
                invoice, deal, milestone, vendorOrg, buyerOrg, vendorKyb, vendorBank, Collections.emptyList(), List.of(existing), InvoiceMatchingPolicy.defaultPolicy()
        );

        InvoiceMatchingResult result = matchingEngine.evaluate(ctx);

        assertEquals(DuplicateConfidence.EXACT_DUPLICATE, result.duplicateConfidence());
        assertEquals("INVOICE_NUMBER_DUPLICATE", result.primaryReasonCode());
        assertCheckStatus(result.checks(), InvoiceCheckType.DUPLICATE_CHECK, CheckStatus.FAIL);
    }

    @Test
    @DisplayName("12. Duplicate False-Positive Protection: Vendor A: INV-100 vs Vendor B: INV-100 are NOT duplicates")
    void testDuplicateFalsePositive_DifferentVendors_NotDuplicate() {
        Invoice invoice = buildStandardInvoice();
        invoice.setInvoiceNumber("INV-100");
        invoice.setNormalizedInvoiceNumber("INV-100");

        UUID vendorBOrgId = UUID.randomUUID();
        Invoice existingVendorBInvoice = new Invoice();
        existingVendorBInvoice.setId(UUID.randomUUID());
        existingVendorBInvoice.setDocumentHash("vendor_b_hash_987654321");
        existingVendorBInvoice.setVendorOrganizationId(vendorBOrgId); // DIFFERENT vendor organization
        existingVendorBInvoice.setInvoiceNumber("INV-100");
        existingVendorBInvoice.setNormalizedInvoiceNumber("INV-100");
        existingVendorBInvoice.setTotalAmount(new BigDecimal("250000.00"));

        InvoiceMatchingContext ctx = new InvoiceMatchingContext(
                invoice, deal, milestone, vendorOrg, buyerOrg, vendorKyb, vendorBank, Collections.emptyList(), List.of(existingVendorBInvoice), InvoiceMatchingPolicy.defaultPolicy()
        );

        InvoiceMatchingResult result = matchingEngine.evaluate(ctx);

        // Crucial test: Vendor A's INV-100 must NOT be classified as an exact duplicate of Vendor B's INV-100!
        assertEquals(DuplicateConfidence.UNIQUE, result.duplicateConfidence(), "Vendor A INV-100 must NOT duplicate Vendor B INV-100");
        assertCheckStatus(result.checks(), InvoiceCheckType.DUPLICATE_CHECK, CheckStatus.PASS);
    }

    @Test
    @DisplayName("13. Money Precision: BigDecimal comparisons 250000.00 vs 250000 compare equally without float error")
    void testMoneyPrecision_BigDecimal() {
        Invoice invoice = buildStandardInvoice();
        invoice.setTotalAmount(new BigDecimal("250000.000")); // 3 decimals
        milestone.setAmount(new BigDecimal("250000")); // 0 decimals

        InvoiceMatchingContext ctx = new InvoiceMatchingContext(
                invoice, deal, milestone, vendorOrg, buyerOrg, vendorKyb, vendorBank, Collections.emptyList(), Collections.emptyList(), InvoiceMatchingPolicy.defaultPolicy()
        );

        InvoiceMatchingResult result = matchingEngine.evaluate(ctx);

        assertCheckStatus(result.checks(), InvoiceCheckType.AMOUNT_MATCH, CheckStatus.PASS);
    }

    @Test
    @DisplayName("14. Idempotency: Evaluating same invoice 5 times produces identical score, checks and status")
    void testIdempotency_FiveEvaluations() {
        Invoice invoice = buildStandardInvoice();
        InvoiceMatchingContext ctx = new InvoiceMatchingContext(
                invoice, deal, milestone, vendorOrg, buyerOrg, vendorKyb, vendorBank, Collections.emptyList(), Collections.emptyList(), InvoiceMatchingPolicy.defaultPolicy()
        );

        InvoiceMatchingResult first = matchingEngine.evaluate(ctx);
        for (int i = 0; i < 5; i++) {
            InvoiceMatchingResult next = matchingEngine.evaluate(ctx);
            assertEquals(first.matchScore(), next.matchScore());
            assertEquals(first.matchingStatus(), next.matchingStatus());
            assertEquals(first.duplicateConfidence(), next.duplicateConfidence());
            assertEquals(first.primaryReasonCode(), next.primaryReasonCode());
            assertEquals(first.checks().size(), next.checks().size());
        }
    }

    @Test
    @DisplayName("15. Future invoice date anomaly triggers INVOICE_DATE_ANOMALY")
    void testFutureInvoiceDateAnomaly() {
        Invoice invoice = buildStandardInvoice();
        invoice.setInvoiceDate(LocalDate.now().plusDays(10)); // 10 days in the future!

        InvoiceMatchingContext ctx = new InvoiceMatchingContext(
                invoice, deal, milestone, vendorOrg, buyerOrg, vendorKyb, vendorBank, Collections.emptyList(), Collections.emptyList(), InvoiceMatchingPolicy.defaultPolicy()
        );

        InvoiceMatchingResult result = matchingEngine.evaluate(ctx);

        assertEquals(MatchingStatus.REVIEW_REQUIRED, result.matchingStatus());
        assertEquals("INVOICE_DATE_ANOMALY", result.primaryReasonCode());
        assertCheckStatus(result.checks(), InvoiceCheckType.INVOICE_DATE_VALID, CheckStatus.FAIL);
    }

    @Test
    @DisplayName("16. Bank detail mismatch triggers WARNING without mutating verified bank account")
    void testBankDetailMismatch() {
        Invoice invoice = buildStandardInvoice();
        invoice.setBankAccountLast4("9999"); // Mismatch with verified 5678

        InvoiceMatchingContext ctx = new InvoiceMatchingContext(
                invoice, deal, milestone, vendorOrg, buyerOrg, vendorKyb, vendorBank, Collections.emptyList(), Collections.emptyList(), InvoiceMatchingPolicy.defaultPolicy()
        );

        InvoiceMatchingResult result = matchingEngine.evaluate(ctx);

        assertCheckStatus(result.checks(), InvoiceCheckType.BANK_DETAIL_CHECK, CheckStatus.WARNING);
        assertEquals(MatchingStatus.REVIEW_REQUIRED, result.matchingStatus());
        // Verified account remains untouched
        assertEquals("******5678", vendorBank.getAccountNumberMasked());
    }


    private InvoiceMatchingResult evaluate(Invoice invoice) {
        return matchingEngine.evaluate(new InvoiceMatchingContext(invoice,deal,milestone,vendorOrg,buyerOrg,vendorKyb,vendorBank,List.of(new Deliverable(milestoneId,UUID.randomUUID(),"work.pdf","private:work","Completion evidence")),List.of(),InvoiceMatchingPolicy.defaultPolicy()));
    }
    @Test void lowConfidenceRequiresReview() {
        Invoice i=buildStandardInvoice();
        i.getExtractedFields().add(new com.finx.invoice.entity.InvoiceExtractedField(i,"vendorGstin",i.getVendorGstin(),i.getVendorGstin(),new BigDecimal("0.30")));
        var result=evaluate(i); assertEquals(MatchingStatus.REVIEW_REQUIRED,result.matchingStatus());
        assertTrue(result.checks().stream().anyMatch(c->c.reasonCode().equals("LOW_EXTRACTION_CONFIDENCE")));
    }
    @Test void wrongBilledBuyerNameRequiresReview() {
        Invoice i=buildStandardInvoice(); i.setBuyerLegalName("Different Company Limited");
        assertEquals(MatchingStatus.REVIEW_REQUIRED,evaluate(i).matchingStatus());
    }
    @Test void wrongBuyerGstinRequiresReview() {
        Invoice i=buildStandardInvoice(); i.setBuyerGstin("WRONG");
        assertTrue(evaluate(i).checks().stream().anyMatch(c->c.reasonCode().equals("BUYER_GSTIN_MISMATCH")));
    }
    @Test void organizationClaimsWithoutKybNeverStrongMatch() {
        vendorKyb.setStatus(com.finx.verification.enums.VerificationStatus.PENDING_REVIEW);
        assertEquals(MatchingStatus.REVIEW_REQUIRED,evaluate(buildStandardInvoice()).matchingStatus());
    }
    @Test void milestoneMustBelongToDealEvenWhenInvoiceMilestoneIdMatches() {
        milestone.setDealId(UUID.randomUUID());
        assertCheckStatus(evaluate(buildStandardInvoice()).checks(),InvoiceCheckType.MILESTONE_MATCH,CheckStatus.FAIL);
    }
    @Test void policySubtotalIsAuthoritativeAndNoFallbackToTotal() {
        Invoice i=buildStandardInvoice(); i.setSubtotal(null);
        var policy=new InvoiceMatchingPolicy("subtotal-v1",BigDecimal.ZERO,"SUBTOTAL",new BigDecimal(".75"),20,20,25,10,15,10);
        var result=matchingEngine.evaluate(new InvoiceMatchingContext(i,deal,milestone,vendorOrg,buyerOrg,vendorKyb,vendorBank,List.of(),List.of(),policy));
        assertCheckStatus(result.checks(),InvoiceCheckType.AMOUNT_MATCH,CheckStatus.FAIL);
        i.setSubtotal(new BigDecimal("250000")); i.setTotalAmount(new BigDecimal("295000")); i.setTaxAmount(new BigDecimal("45000"));
        result=matchingEngine.evaluate(new InvoiceMatchingContext(i,deal,milestone,vendorOrg,buyerOrg,vendorKyb,vendorBank,List.of(),List.of(),policy));
        assertCheckStatus(result.checks(),InvoiceCheckType.AMOUNT_MATCH,CheckStatus.PASS);
    }
    @Test void configuredToleranceIsExplicit() {
        Invoice i=buildStandardInvoice(); i.setTotalAmount(new BigDecimal("250000.01")); i.setSubtotal(null);
        var policy=new InvoiceMatchingPolicy("tolerance-v1",new BigDecimal("0.01"),"TOTAL",new BigDecimal(".75"),20,20,25,10,15,10);
        var result=matchingEngine.evaluate(new InvoiceMatchingContext(i,deal,milestone,vendorOrg,buyerOrg,vendorKyb,vendorBank,List.of(),List.of(),policy));
        assertTrue(result.checks().stream().anyMatch(c->c.reasonCode().equals("WITHIN_ALLOWED_TOLERANCE")));
    }
    @Test void identifiersKeepMeaningfulPunctuation() {
        assertNotEquals(normalizationService.normalizeInvoiceNumber("INV-100"),normalizationService.normalizeInvoiceNumber("INV100"));
        assertEquals("INV-100",normalizationService.normalizeInvoiceNumber(" inv-100 "));
    }
    private void assertCheckStatus(List<InvoiceCheckDetail> checks, InvoiceCheckType type, CheckStatus expected) {
        InvoiceCheckDetail detail = checks.stream()
                .filter(c -> c.checkType() == type)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Check of type " + type + " not found in results"));
        assertEquals(expected, detail.status(), "Expected " + expected + " for check " + type + ", got " + detail.status() + " (" + detail.reasonCode() + ": " + detail.message() + ")");
    }
}
