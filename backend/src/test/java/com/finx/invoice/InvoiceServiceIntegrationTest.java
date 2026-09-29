package com.finx.invoice;

import com.finx.common.enums.Role;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.exception.BadRequestException;
import com.finx.exception.UnauthorizedException;
import com.finx.invoice.dto.InvoiceDashboardResponse;
import com.finx.invoice.dto.InvoiceResponse;
import com.finx.invoice.dto.InvoiceReviewDecisionRequest;
import com.finx.invoice.dto.InvoiceUploadRequest;
import com.finx.invoice.enums.DuplicateConfidence;
import com.finx.invoice.enums.InvoiceStatus;
import com.finx.invoice.enums.MatchingStatus;
import com.finx.invoice.enums.ReviewDecision;
import com.finx.invoice.repository.InvoiceRepository;
import com.finx.invoice.service.InvoiceService;
import com.finx.milestone.entity.Deliverable;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.DeliverableRepository;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.org.entity.Organization;
import com.finx.org.enums.OrgEnums.EntityType;
import com.finx.org.repository.OrganizationRepository;
import com.finx.paymentorchestration.dto.PaymentOrchestrationDtos.PayoutAuthorizationRequest;
import com.finx.paymentorchestration.service.SettlementAuthorizationService;
import com.finx.security.service.UserPrincipal;
import com.finx.user.entity.User;
import com.finx.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class InvoiceServiceIntegrationTest {

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private DealRepository dealRepository;

    @Autowired
    private MilestoneRepository milestoneRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private DeliverableRepository deliverableRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SettlementAuthorizationService settlementAuthorizationService;

    @Autowired private com.finx.verification.repository.KybProfileRepository kybProfiles;
    @Autowired private com.finx.org.repository.OrganizationBankAccountRepository banks;
    @Autowired private com.finx.agreement.repository.DealAgreementRepository agreements;
    @Autowired private com.finx.invoice.repository.InvoiceAssessmentRepository assessments;
    private User buyer;
    private User vendor;
    private User otherVendor;
    private User pm;
    private User admin;

    private Organization buyerOrg;
    private Organization vendorOrg;
    private Organization otherVendorOrg;

    private Deal deal;
    private Milestone milestone;

    @BeforeEach
    void setUp() {
        buyer = createTestUser("buyer_inv@finx.local", Role.BUYER);
        vendor = createTestUser("vendor_inv@finx.local", Role.SELLER);
        otherVendor = createTestUser("other_vendor@finx.local", Role.SELLER);
        pm = createTestUser("pm_inv@finx.local", Role.PROJECT_MANAGER);
        admin = createTestUser("admin_inv@finx.local", Role.ADMIN);

        buyerOrg = new Organization("Tata Motors", "Tata Motors Limited", EntityType.PUBLIC_LIMITED, "L12345", "AABCT1234F", "27AABCT1234F1Z1", "Mumbai", buyer.getId());
        buyerOrg.setVerificationStatus(com.finx.org.enums.OrgEnums.OrgVerificationStatus.VERIFIED);
        buyerOrg = organizationRepository.save(buyerOrg);

        vendorOrg = new Organization("Bosch Auto", "Bosch Automotive Private Limited", EntityType.PRIVATE_LIMITED, "U99999", "BBBBZ9999K", "29BBBBZ9999K1Z4", "Bengaluru", vendor.getId());
        vendorOrg.setVerificationStatus(com.finx.org.enums.OrgEnums.OrgVerificationStatus.VERIFIED);
        vendorOrg = organizationRepository.save(vendorOrg);
        var kyb = new com.finx.verification.entity.KybProfile(vendorOrg.getId(), "******999K", vendorOrg.getGstin(), null, null);
        kyb.setStatus(com.finx.verification.enums.VerificationStatus.VERIFIED);
        kyb.setPanStatus(com.finx.verification.enums.VerificationStatus.VERIFIED);
        kyb.setGstStatus(com.finx.verification.enums.VerificationStatus.VERIFIED);
        kybProfiles.save(kyb);
        var bank = new com.finx.org.entity.OrganizationBankAccount(vendorOrg.getId(),vendorOrg.getLegalName(),"******1234","testhash","HDFC0001234","HDFC",null,true);
        bank.setVerificationStatus(com.finx.org.enums.OrgEnums.BankVerificationStatus.VERIFIED); banks.save(bank);

        otherVendorOrg = new Organization("Denso Corp", "Denso India Private Limited", EntityType.PRIVATE_LIMITED, "U88888", "DDDDD8888K", "07DDDDD8888K1Z2", "Delhi", otherVendor.getId());
        otherVendorOrg = organizationRepository.save(otherVendorOrg);

        deal = new Deal();
        deal.setTitle("Braking Systems Manufacturing SLA");
        deal.setBuyerId(buyer.getId());
        deal.setSellerId(vendor.getId());
        deal.setBuyerOrganizationId(buyerOrg.getId());
        deal.setSellerOrganizationId(vendorOrgOrgId());
        deal.setProjectManagerId(pm.getId());
        deal.setTotalAmount(new BigDecimal("1000000.00"));
        deal.setCurrency("INR");
        deal.setStatus(DealStatus.ACCEPTED);
        deal = dealRepository.save(deal);
        var agreement = new com.finx.agreement.entity.DealAgreement(deal.getId(),1,"Invoice fixture agreement","Terms","a".repeat(64));
        agreement.setStatus(com.finx.agreement.enums.AgreementStatus.SIGNED);
        agreement.setBuyerAcceptedBy(buyer.getId()); agreement.setBuyerAcceptedAt(Instant.now());
        agreement.setSellerAcceptedBy(vendor.getId()); agreement.setSellerAcceptedAt(Instant.now()); agreements.save(agreement);

        milestone = new Milestone(deal.getId(), "Milestone 1: Calibration Test", "Test brake systems", 1, new BigDecimal("350000.00"), "INR", Instant.now().plusSeconds(86400));
        milestone = milestoneRepository.save(milestone);
    }

    private InvoiceUploadRequest validRequest() {
        InvoiceUploadRequest req = new InvoiceUploadRequest();
        req.setFileName("invoice.pdf"); req.setMimeType("application/pdf");
        req.setFileContentBase64(java.util.Base64.getEncoder().encodeToString(("%PDF-1.4\n"+UUID.randomUUID()+"\n%%EOF").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        req.setInvoiceNumber("INV-"+UUID.randomUUID()); req.setInvoiceDate(LocalDate.now());
        req.setTotalAmount(new BigDecimal("350000.00")); req.setCurrency("INR");
        req.setVendorLegalName(vendorOrg.getLegalName()); req.setVendorPan(vendorOrg.getTaxId()); req.setVendorGstin(vendorOrg.getGstin());
        req.setBuyerLegalName(buyerOrg.getLegalName()); req.setBuyerGstin(buyerOrg.getGstin());
        return req;
    }

    private UUID vendorOrgOrgId() {
        return vendorOrg.getId();
    }

    private User createTestUser(String email, Role role) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User u = new User();
            u.setEmail(email);
            u.setPasswordHash(passwordEncoder.encode("Password123!"));
            u.setName(email.split("@")[0]);
            u.setRole(role);
            u.setStatus(com.finx.common.enums.UserStatus.ACTIVE);
            return userRepository.save(u);
        });
    }

    private UserPrincipal toPrincipal(User u) {
        return UserPrincipal.create(u);
    }

    @Test
    @DisplayName("Vendor uploads matching invoice -> STRONG_MATCH & MATCHED status")
    void testVendorUploadsValidInvoice() {
        // Attach supporting deliverable
        Deliverable d = new Deliverable(milestone.getId(), vendor.getId(), "calibration_report.pdf", "https://finx.local/report.pdf", "Calibration");
        deliverableRepository.save(d);

        InvoiceUploadRequest req = validRequest();
        req.setFileName("bosch_inv_1001.pdf");
        req.setFileUrl("https://finx.local/invoices/bosch_inv_1001.pdf");
        req.setInvoiceNumber("INV-BOSCH-1001");
        req.setTotalAmount(new BigDecimal("350000.00"));
        req.setCurrency("INR");
        req.setVendorLegalName(vendorOrg.getLegalName());
        req.setVendorGstin(vendorOrg.getGstin());
        req.setVendorPan(vendorOrg.getTaxId());
        req.setBuyerLegalName(buyerOrg.getLegalName());
        req.setBuyerGstin(buyerOrg.getGstin());

        InvoiceResponse response = invoiceService.uploadAndProcessInvoice(milestone.getId(), req, toPrincipal(vendor));

        assertNotNull(response);
        assertEquals("INV-BOSCH-1001", response.invoiceNumber());
        assertEquals(InvoiceStatus.MATCHED, response.status());
        assertEquals(MatchingStatus.STRONG_MATCH, response.matchingStatus());
        assertTrue(response.matchScore() >= 90);
        assertNotNull(response.documentHash());
    }

    @Test
    @DisplayName("Buyer attempts Vendor invoice upload -> Throws UnauthorizedException")
    void testBuyerCannotUploadInvoice() {
        InvoiceUploadRequest req = validRequest();
        req.setFileName("fake.pdf");
        req.setFileUrl("https://finx/fake.pdf");

        assertThrows(UnauthorizedException.class, () ->
                invoiceService.uploadAndProcessInvoice(milestone.getId(), req, toPrincipal(buyer))
        );
    }

    @Test
    @DisplayName("Unauthorized Vendor attempts invoice upload to another Vendor's deal -> Blocked (IDOR defense)")
    void testOtherVendorCannotUploadInvoice() {
        InvoiceUploadRequest req = validRequest();
        req.setFileName("unauth.pdf");
        req.setFileUrl("https://finx/unauth.pdf");

        assertThrows(UnauthorizedException.class, () ->
                invoiceService.uploadAndProcessInvoice(milestone.getId(), req, toPrincipal(otherVendor))
        );
    }

    @Test
    @DisplayName("Re-uploading exact same document file -> Triggers EXACT_DUPLICATE and REVIEW_REQUIRED")
    void testDuplicateDocumentDetection() {
        InvoiceUploadRequest req1 = validRequest();
        req1.setFileName("bosch_inv_doc.pdf");
        req1.setFileUrl("https://finx.local/invoices/same_file.pdf");
        req1.setFileContentBase64(java.util.Base64.getEncoder().encodeToString("%PDF-1.4\nDuplicate invoice fixture\n%%EOF".getBytes(java.nio.charset.StandardCharsets.UTF_8))); // Exact identical base64 bytes
        req1.setInvoiceNumber("INV-DOC-1");
        req1.setTotalAmount(new BigDecimal("350000.00"));

        invoiceService.uploadAndProcessInvoice(milestone.getId(), req1, toPrincipal(vendor));

        // Re-upload identical file content
        InvoiceUploadRequest req2 = validRequest();
        req2.setFileName("bosch_inv_doc_copy.pdf");
        req2.setFileUrl("https://finx.local/invoices/same_file_copy.pdf");
        req2.setFileContentBase64(java.util.Base64.getEncoder().encodeToString("%PDF-1.4\nDuplicate invoice fixture\n%%EOF".getBytes(java.nio.charset.StandardCharsets.UTF_8))); // Same bytes -> identical hash
        req2.setInvoiceNumber("INV-DOC-2");
        req2.setTotalAmount(new BigDecimal("350000.00"));

        InvoiceResponse response2 = invoiceService.uploadAndProcessInvoice(milestone.getId(), req2, toPrincipal(vendor));

        assertEquals(DuplicateConfidence.EXACT_DUPLICATE, response2.duplicateConfidence());
        assertEquals(InvoiceStatus.REVIEW_REQUIRED, response2.status());
    }

    @Test
    @DisplayName("Review workflow: PM approves invoice -> Invoice status transitions to APPROVED")
    void testReviewWorkflow_Approval() {
        InvoiceUploadRequest req = validRequest();
        req.setFileName("mismatched_amount.pdf");
        req.setFileUrl("https://finx/mismatch.pdf");
        req.setInvoiceNumber("INV-REV-01");
        req.setTotalAmount(new BigDecimal("390000.00")); // Mismatch forces review

        InvoiceResponse response = invoiceService.uploadAndProcessInvoice(milestone.getId(), req, toPrincipal(vendor));
        assertEquals(InvoiceStatus.REVIEW_REQUIRED, response.status());
        assertFalse(response.reviews().isEmpty());

        UUID reviewId = response.reviews().get(0).id();

        InvoiceReviewDecisionRequest dec = new InvoiceReviewDecisionRequest(ReviewDecision.APPROVE, "APPROVED_BY_EXCEPTION", "Discrepancy verified against change order PO-45");
        InvoiceResponse approvedResponse = invoiceService.submitReviewDecision(response.id(), reviewId, dec, toPrincipal(pm));

        assertEquals(InvoiceStatus.APPROVED, approvedResponse.status());
    }

    @Test
    @DisplayName("Settlement Gate: Settlement authorization blocked when invoice is in REVIEW_REQUIRED status")
    void testSettlementBlockedWhenInvoiceReviewUnresolved() {
        InvoiceUploadRequest req = validRequest();
        req.setFileName("mismatch.pdf");
        req.setFileUrl("https://finx/mismatch.pdf");
        req.setInvoiceNumber("INV-BLOCK-01");
        req.setTotalAmount(new BigDecimal("500000.00")); // Mismatch

        InvoiceResponse response = invoiceService.uploadAndProcessInvoice(milestone.getId(), req, toPrincipal(vendor));
        assertEquals(InvoiceStatus.REVIEW_REQUIRED, response.status());

        // Milestone is approved
        milestone.setStatus(MilestoneStatus.APPROVED);
        milestoneRepository.save(milestone);

        PayoutAuthorizationRequest payoutReq = new PayoutAuthorizationRequest(milestone.getId(), "idem-key-123", "Release payout");

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                settlementAuthorizationService.authorizeAndInitiateSettlement(payoutReq, toPrincipal(buyer))
        );

        assertTrue(ex.getMessage().contains("invoice verification is unresolved"), "Message should mention invoice verification unresolved, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Admin dashboard metrics aggregate invoices and queues correctly")
    void testAdminDashboardMetrics() {
        InvoiceUploadRequest req = validRequest();
        req.setFileName("admin_test.pdf");
        req.setFileUrl("https://finx/admin.pdf");
        req.setInvoiceNumber("INV-ADM-01");
        req.setTotalAmount(new BigDecimal("350000.00"));

        invoiceService.uploadAndProcessInvoice(milestone.getId(), req, toPrincipal(vendor));

        InvoiceDashboardResponse metrics = invoiceService.getAdminDashboardMetrics();
        assertNotNull(metrics);
        assertTrue(metrics.totalInvoicesProcessed() >= 1);
    }

    @Test void privateDocumentBytesAndServerHash() {
        var request=validRequest(); request.setFileSizeBytes(1L); request.setFileUrl("https://untrusted.example/document");
        var result=invoiceService.uploadAndProcessInvoice(milestone.getId(),request,toPrincipal(vendor));
        byte[] actual=java.util.Base64.getDecoder().decode(request.getFileContentBase64());
        assertArrayEquals(actual,invoiceService.download(result.id(),toPrincipal(buyer)));
        assertEquals(com.finx.invoice.service.InvoiceDocumentService.sha256(actual),result.documentHash());
        assertEquals((long)actual.length,result.fileSizeBytes());
        assertFalse(result.fileUrl().contains("untrusted"));
        assertThrows(UnauthorizedException.class,()->invoiceService.download(result.id(),toPrincipal(otherVendor)));
        assertThrows(UnauthorizedException.class,()->invoiceService.getInvoiceById(result.id(),toPrincipal(otherVendor)));
    }
    @Test void rejectsUrlOnlyAndSpoofedMime() {
        var request=validRequest(); request.setFileContentBase64(null);
        assertThrows(BadRequestException.class,()->invoiceService.uploadAndProcessInvoice(milestone.getId(),request,toPrincipal(vendor)));
        var spoof=validRequest(); spoof.setMimeType("image/png");
        assertThrows(BadRequestException.class,()->invoiceService.uploadAndProcessInvoice(milestone.getId(),spoof,toPrincipal(vendor)));
    }
    @Test void crossInvoiceReviewIdIsBlocked() {
        var first=invoiceService.uploadAndProcessInvoice(milestone.getId(),validRequest(),toPrincipal(vendor));
        var second=invoiceService.uploadAndProcessInvoice(milestone.getId(),validRequest(),toPrincipal(vendor));
        assertThrows(UnauthorizedException.class,()->invoiceService.submitReviewDecision(second.id(),first.reviews().getFirst().id(),new InvoiceReviewDecisionRequest(ReviewDecision.APPROVE,"APPROVE","Checked documents"),toPrincipal(pm)));
    }
    @Test void unassignedPmAndBuyerCannotReview() {
        var result=invoiceService.uploadAndProcessInvoice(milestone.getId(),validRequest(),toPrincipal(vendor));
        var otherPm=createTestUser("unassigned-pm@finx.local",Role.PROJECT_MANAGER);
        var decision=new InvoiceReviewDecisionRequest(ReviewDecision.APPROVE,"APPROVE","Checked documents");
        assertThrows(UnauthorizedException.class,()->invoiceService.submitReviewDecision(result.id(),result.reviews().getFirst().id(),decision,toPrincipal(otherPm)));
        assertThrows(UnauthorizedException.class,()->invoiceService.submitReviewDecision(result.id(),result.reviews().getFirst().id(),decision,toPrincipal(buyer)));
    }
    @Test void repeatedRecalculationPreservesDecisionAndHistory() {
        var result=invoiceService.uploadAndProcessInvoice(milestone.getId(),validRequest(),toPrincipal(vendor));
        invoiceService.submitReviewDecision(result.id(),result.reviews().getFirst().id(),new InvoiceReviewDecisionRequest(ReviewDecision.APPROVE,"APPROVE","Checked against source document"),toPrincipal(pm));
        var original=assessments.findByInvoiceIdOrderByCreatedAtAsc(result.id());
        for(int j=0;j<5;j++) {
            var next=invoiceService.recalculateInvoiceMatching(result.id(),toPrincipal(pm));
            assertEquals(InvoiceStatus.APPROVED,next.status());
            assertEquals(result.matchScore(),next.matchScore());
            assertEquals(1,next.reviews().size());
        }
        assertEquals(1,assessments.findByInvoiceIdOrderByCreatedAtAsc(result.id()).size());
        assertEquals(original.getFirst().resultJson,assessments.findByInvoiceIdOrderByCreatedAtAsc(result.id()).getFirst().resultJson);
        assertThrows(BadRequestException.class,()->invoiceService.submitReviewDecision(result.id(),result.reviews().getFirst().id(),new InvoiceReviewDecisionRequest(ReviewDecision.REJECT,"REJECT","Overwrite attempt"),toPrincipal(pm)));
    }
    @Test void manualRejectionAndRevisionKeepHistory() {
        var request=validRequest(); var first=invoiceService.uploadAndProcessInvoice(milestone.getId(),request,toPrincipal(vendor));
        var rejected=invoiceService.submitReviewDecision(first.id(),first.reviews().getFirst().id(),new InvoiceReviewDecisionRequest(ReviewDecision.REJECT,"CORRECT_DOCUMENT","Please correct the attached document"),toPrincipal(pm));
        assertEquals(InvoiceStatus.REJECTED,rejected.status());
        var revision=validRequest(); revision.setInvoiceNumber(request.getInvoiceNumber()); revision.setRevisionOf(first.id());
        var second=invoiceService.uploadAndProcessInvoice(milestone.getId(),revision,toPrincipal(vendor));
        assertEquals(DuplicateConfidence.UNIQUE,second.duplicateConfidence());
        assertEquals(InvoiceStatus.REJECTED,invoiceService.getInvoiceById(first.id(),toPrincipal(vendor)).status());
        assertEquals(2,invoiceService.getInvoicesForMilestone(milestone.getId(),toPrincipal(buyer)).size());
    }
    @Test void informationRequestCanReceiveNewReviewWithoutOverwritingDecision() {
        var first=invoiceService.uploadAndProcessInvoice(milestone.getId(),validRequest(),toPrincipal(vendor));
        var requested=invoiceService.submitReviewDecision(first.id(),first.reviews().getFirst().id(),new InvoiceReviewDecisionRequest(ReviewDecision.REQUEST_INFORMATION,"MORE_INFO","Please clarify invoice amount"),toPrincipal(pm));
        assertEquals(InvoiceStatus.REVIEW_REQUIRED,requested.status());
        var reopened=invoiceService.openReview(first.id(),toPrincipal(pm));
        assertEquals(2,reopened.reviews().size());
        assertEquals(ReviewDecision.REQUEST_INFORMATION,reopened.reviews().getFirst().decision());
    }
    @Test void clientCannotChooseSubtotalBasisOrExposeWeights() {
        var request=validRequest(); request.setTotalAmount(new BigDecimal("400000")); request.setSubtotal(new BigDecimal("350000")); request.setComparisonBasis("SUBTOTAL");
        var result=invoiceService.uploadAndProcessInvoice(milestone.getId(),request,toPrincipal(vendor));
        assertEquals("TOTAL",result.comparisonBasis()); assertEquals(InvoiceStatus.REVIEW_REQUIRED,result.status());
        assertTrue(result.checks().stream().allMatch(c -> c.weight()==0 && c.details()==null));
        assertTrue(invoiceService.getInvoiceById(result.id(),toPrincipal(pm)).checks().stream().anyMatch(c -> c.weight()>0));
    }
    @Test void changedEvidenceAppendsAssessmentAndReopensReview() {
        var request=validRequest(); var first=invoiceService.uploadAndProcessInvoice(milestone.getId(),request,toPrincipal(vendor));
        invoiceService.submitReviewDecision(first.id(),first.reviews().getFirst().id(),new InvoiceReviewDecisionRequest(ReviewDecision.APPROVE,"APPROVE","Verified document"),toPrincipal(pm));
        milestone.setAmount(new BigDecimal("400000")); milestoneRepository.saveAndFlush(milestone);
        var next=invoiceService.recalculateInvoiceMatching(first.id(),toPrincipal(pm));
        assertEquals(InvoiceStatus.REVIEW_REQUIRED,next.status());
        assertEquals(2,assessments.findByInvoiceIdOrderByCreatedAtAsc(first.id()).size());
        assertEquals(2,next.reviews().size());
        assertEquals(ReviewDecision.APPROVE,next.reviews().getFirst().decision());
    }
}
