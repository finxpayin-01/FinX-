package com.finx.security;

import com.finx.agreement.entity.DealAgreement;
import com.finx.agreement.enums.AgreementStatus;
import com.finx.agreement.repository.DealAgreementRepository;
import com.finx.agreement.service.AgreementService;
import com.finx.banking.provider.BankingProvider;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.dispute.entity.Dispute;
import com.finx.dispute.entity.DisputeStatus;
import com.finx.dispute.repository.DisputeRepository;
import com.finx.dispute.service.DisputeService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import com.finx.common.enums.UserStatus;
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
import com.finx.milestone.service.MilestoneService;
import com.finx.org.entity.Organization;
import com.finx.org.entity.OrganizationBankAccount;
import com.finx.org.enums.OrgEnums.BankVerificationStatus;
import com.finx.org.enums.OrgEnums.EntityType;
import com.finx.org.repository.OrganizationBankAccountRepository;
import com.finx.paymentorchestration.dto.PaymentOrchestrationDtos.PayoutAuthorizationRequest;
import com.finx.paymentorchestration.service.ApprovalApplicabilityService;
import com.finx.paymentorchestration.service.ApprovalApplicabilityService.*;
import com.finx.paymentorchestration.service.SettlementAuthorizationService;
import com.finx.risk.dto.RiskDtos.ReviewStatus;
import com.finx.risk.entity.RiskReview;
import com.finx.risk.repository.BusinessRiskRepository;
import com.finx.risk.service.RiskActionService;
import com.finx.security.service.UserPrincipal;
import com.finx.verification.service.VerificationEligibilityService;
import com.finx.verification.service.VerificationEligibilityService.BeneficiaryEligibilityResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * B10 Containment Tests: Existing approvals can become stale without invalidation.
 *
 * Verifies that historical approvals remain visible for audit but cannot be treated
 * as current financial permission when material inputs (milestone, invoice, agreement,
 * beneficiary, PM assignment, dispute, risk/policy) change or are unbound.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApprovalFreshnessContainmentTest {

    @Mock private DealRepository dealRepository;
    @Mock private MilestoneRepository milestoneRepository;
    @Mock private DeliverableRepository deliverableRepository;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private InvoiceReviewRepository invoiceReviewRepository;
    @Mock private InvoiceAssessmentRepository invoiceAssessmentRepository;
    @Mock private DealAgreementRepository agreementRepository;
    @Mock private VerificationEligibilityService eligibilityService;
    @Mock private DisputeRepository disputeRepository;
    @Mock private RiskActionService riskActionService;
    @Mock private BusinessRiskRepository businessRiskRepository;
    @Mock private BankingProvider bankingProvider;
    @Mock private OrganizationBankAccountRepository bankAccountRepository;

    private ApprovalApplicabilityService applicabilityService;
    private SettlementAuthorizationService settlementAuthorizationService;

    private UUID dealId;
    private UUID milestoneId;
    private UUID buyerId;
    private UUID sellerId;
    private UUID buyerOrgId;
    private UUID sellerOrgId;
    private UUID pmId;
    private UUID invoiceId;
    private UUID assessmentId;
    private UUID reviewId;
    private UUID agreementId;
    private UUID bankAccountId;

    private Deal deal;
    private Milestone milestone;
    private Deliverable deliverable;
    private Invoice invoice;
    private InvoiceAssessment assessment;
    private InvoiceReview review;
    private DealAgreement agreement;
    private OrganizationBankAccount bankAccount;

    @BeforeEach
    void setUp() {
        dealId = UUID.randomUUID();
        milestoneId = UUID.randomUUID();
        buyerId = UUID.randomUUID();
        sellerId = UUID.randomUUID();
        buyerOrgId = UUID.randomUUID();
        sellerOrgId = UUID.randomUUID();
        pmId = UUID.randomUUID();
        invoiceId = UUID.randomUUID();
        assessmentId = UUID.randomUUID();
        reviewId = UUID.randomUUID();
        agreementId = UUID.randomUUID();
        bankAccountId = UUID.randomUUID();

        // 1. Deal Fixture
        deal = new Deal();
        deal.setId(dealId);
        deal.setTitle("Alpha Project");
        deal.setBuyerId(buyerId);
        deal.setSellerId(sellerId);
        deal.setBuyerOrganizationId(buyerOrgId);
        deal.setSellerOrganizationId(sellerOrgId);
        deal.setProjectManagerId(pmId);
        deal.setStatus(DealStatus.IN_PROGRESS);
        deal.setCurrency("INR");
        deal.setTotalAmount(new BigDecimal("100000.00"));

        // 2. Milestone Fixture
        Instant baseTime = Instant.now().minus(2, ChronoUnit.DAYS);
        milestone = new Milestone(dealId, "Milestone 1", "Implementation", 1, new BigDecimal("50000.00"), "INR", baseTime.plus(30, ChronoUnit.DAYS));
        milestone.setId(milestoneId);
        milestone.setStatus(MilestoneStatus.APPROVED);
        milestone.setInvoiceRequired(true);
        milestone.setCreatedAt(baseTime);
        milestone.setUpdatedAt(baseTime.plus(1, ChronoUnit.HOURS));

        // 3. Deliverable Fixture
        deliverable = new Deliverable(milestoneId, sellerId, "deliverable.pdf", "https://finx.test/deliverable.pdf", "Final deliverables");
        deliverable.setId(UUID.randomUUID());
        deliverable.setStatus(DeliverableStatus.APPROVED);
        deliverable.setReviewedAt(baseTime.plus(2, ChronoUnit.HOURS));
        deliverable.setReviewedBy(pmId);

        // 4. Invoice & Review & Assessment Fixtures
        invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setMilestoneId(milestoneId);
        invoice.setDealId(dealId);
        invoice.setStatus(InvoiceStatus.APPROVED);
        invoice.setInvoiceNumber("INV-2026-001");
        invoice.setTotalAmount(new BigDecimal("50000.00"));
        invoice.setCurrency("INR");
        invoice.setCreatedAt(baseTime.plus(3, ChronoUnit.HOURS));
        invoice.setUpdatedAt(baseTime.plus(4, ChronoUnit.HOURS));

        assessment = new InvoiceAssessment();
        assessment.id = assessmentId;
        assessment.invoiceId = invoiceId;
        assessment.fingerprint = "fp_abc123_assessment";
        assessment.createdAt = baseTime.plus(3, ChronoUnit.HOURS);

        review = new InvoiceReview();
        review.setId(reviewId);
        review.setInvoice(invoice);
        review.setAssessmentId(assessmentId);
        review.setStatus(InvoiceReviewStatus.APPROVED);
        review.setReviewedAt(baseTime.plus(4, ChronoUnit.HOURS));
        review.setReviewerId(buyerId);

        // 5. Agreement Fixture
        agreement = new DealAgreement(dealId, 1, "Master Services Agreement", "Full Terms Content", "sha256_mock_hash_terms");
        agreement.setId(agreementId);
        agreement.setStatus(AgreementStatus.SIGNED);

        // 6. Bank Account Fixture
        bankAccount = new OrganizationBankAccount(sellerOrgId, "Vendor Pvt Ltd", "********9012", "hash123", "HDFC0000060", "HDFC Bank", "BKC", true);
        bankAccount.setId(bankAccountId);
        bankAccount.setEncryptedAccountNumber("enc_123456789012");
        bankAccount.setVerificationStatus(BankVerificationStatus.VERIFIED);
        bankAccount.setVerifiedAt(baseTime);
        bankAccount.setProvider("SETU");

        // Service wiring
        applicabilityService = new ApprovalApplicabilityService(
                dealRepository,
                milestoneRepository,
                deliverableRepository,
                invoiceRepository,
                invoiceReviewRepository,
                invoiceAssessmentRepository,
                agreementRepository,
                eligibilityService,
                disputeRepository,
                riskActionService,
                businessRiskRepository
        );

        settlementAuthorizationService = new SettlementAuthorizationService(
                null, dealRepository, milestoneRepository, bankAccountRepository,
                null, disputeRepository, null, null, null, null,
                bankingProvider, null, null, null, invoiceRepository,
                null, eligibilityService, applicabilityService
        );

        // Default mock setups
        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));
        when(milestoneRepository.findById(milestoneId)).thenReturn(Optional.of(milestone));
        when(deliverableRepository.findByMilestoneIdOrderBySubmittedAtDesc(milestoneId)).thenReturn(List.of(deliverable));
        when(invoiceRepository.findFirstByMilestoneIdOrderByCreatedAtDesc(milestoneId)).thenReturn(Optional.of(invoice));
        when(invoiceReviewRepository.findFirstByInvoiceIdAndStatus(invoiceId, InvoiceReviewStatus.APPROVED)).thenReturn(Optional.of(review));
        when(invoiceAssessmentRepository.findFirstByInvoiceIdOrderByCreatedAtDesc(invoiceId)).thenReturn(Optional.of(assessment));
        when(agreementRepository.findTopByDealIdOrderByVersionDesc(dealId)).thenReturn(Optional.of(agreement));
        when(eligibilityService.evaluateBeneficiaryEligibility(sellerOrgId))
                .thenReturn(new BeneficiaryEligibilityResult(true, bankAccount, null, false));
        when(disputeRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(Collections.emptyList());
        when(businessRiskRepository.query(eq(RiskReview.class), anyString(), anyMap(), anyInt())).thenReturn(Collections.emptyList());
    }

    // =========================================================================
    // 1-12. MATERIAL INPUT CHANGE TESTS
    // =========================================================================

    @Test
    @DisplayName("1. Milestone material change: modification after approval invalidates applicability")
    void test01_milestoneMaterialChange_invalidatesApplicability() {
        // Milestone updated after deliverable review
        milestone.setUpdatedAt(Instant.now());

        ApprovalApplicabilityResult result = applicabilityService.evaluateApprovalApplicability(dealId, milestoneId);
        assertThat(result.applicable()).isFalse();
        assertThat(result.reasonCodes()).anyMatch(r -> r.contains("MILESTONE_MODIFIED_AFTER_APPROVAL"));
    }

    @Test
    @DisplayName("2. Deadline/date change: deadline updated after review marks approval stale")
    void test02_deadlineDateChange_marksApprovalStale() {
        milestone.setSubmissionDeadline(Instant.now().plus(60, ChronoUnit.DAYS));
        milestone.setUpdatedAt(Instant.now());

        ApprovalApplicabilityResult result = applicabilityService.evaluateApprovalApplicability(dealId, milestoneId);
        assertThat(result.applicable()).isFalse();
        assertThat(result.reasonCodes()).anyMatch(r -> r.contains("MILESTONE_MODIFIED_AFTER_APPROVAL"));
    }

    @Test
    @DisplayName("3. Agreement modification: agreement draft update invalidates agreement applicability")
    void test03_agreementModification_invalidatesAgreementApplicability() {
        // Agreement was edited back to DRAFT for modifications
        agreement.setStatus(AgreementStatus.DRAFT);

        AgreementApplicabilityResult agreementResult = applicabilityService.evaluateAgreementApplicability(dealId);
        assertThat(agreementResult.applicable()).isFalse();
        assertThat(agreementResult.reasonCode()).contains("AGREEMENT_NOT_SIGNED");

        ApprovalApplicabilityResult overall = applicabilityService.evaluateApprovalApplicability(dealId, milestoneId);
        assertThat(overall.applicable()).isFalse();
        assertThat(overall.agreementApplicable()).isFalse();
    }

    @Test
    @DisplayName("4. Agreement resign/replacement: new unsigned version supersedes signed version")
    void test04_agreementResignReplacement_newVersionSupersedesOldVersion() {
        // Version 2 was drafted to replace version 1, but is not yet signed
        DealAgreement agreementV2 = new DealAgreement(dealId, 2, "MSA v2", "Updated terms", "sha256_v2_hash");
        agreementV2.setStatus(AgreementStatus.DRAFT);
        when(agreementRepository.findTopByDealIdOrderByVersionDesc(dealId)).thenReturn(Optional.of(agreementV2));

        AgreementApplicabilityResult agreementResult = applicabilityService.evaluateAgreementApplicability(dealId);
        assertThat(agreementResult.applicable()).isFalse();
        assertThat(agreementResult.reasonCode()).contains("v2 status is DRAFT");

        ApprovalApplicabilityResult overall = applicabilityService.evaluateApprovalApplicability(dealId, milestoneId);
        assertThat(overall.agreementApplicable()).isFalse();
    }

    @Test
    @DisplayName("5. Invoice new revision: new invoice revision makes previous approval stale")
    void test05_invoiceNewRevision_makesPreviousApprovalStale() {
        // A new unapproved invoice revision was uploaded
        Invoice revision2 = new Invoice();
        revision2.setId(UUID.randomUUID());
        revision2.setMilestoneId(milestoneId);
        revision2.setStatus(InvoiceStatus.PROCESSING);
        revision2.setRevisionOf(invoiceId);
        when(invoiceRepository.findFirstByMilestoneIdOrderByCreatedAtDesc(milestoneId)).thenReturn(Optional.of(revision2));

        InvoiceApplicabilityResult invoiceResult = applicabilityService.evaluateInvoiceApplicability(milestone);
        assertThat(invoiceResult.applicable()).isFalse();
        assertThat(invoiceResult.reasonCode()).contains("INVOICE_NOT_APPROVED");

        ApprovalApplicabilityResult overall = applicabilityService.evaluateApprovalApplicability(dealId, milestoneId);
        assertThat(overall.invoiceApplicable()).isFalse();
    }

    @Test
    @DisplayName("6. Invoice fingerprint change: review bound to older assessment fails freshness")
    void test06_invoiceFingerprintChange_reviewBoundToOlderAssessmentFailsFreshness() {
        // A newer assessment with different fingerprint was created after review
        InvoiceAssessment newAssessment = new InvoiceAssessment();
        newAssessment.id = UUID.randomUUID();
        newAssessment.invoiceId = invoiceId;
        newAssessment.fingerprint = "fp_xyz999_recalculated";
        when(invoiceAssessmentRepository.findFirstByInvoiceIdOrderByCreatedAtDesc(invoiceId)).thenReturn(Optional.of(newAssessment));

        InvoiceApplicabilityResult invoiceResult = applicabilityService.evaluateInvoiceApplicability(milestone);
        assertThat(invoiceResult.applicable()).isFalse();
        assertThat(invoiceResult.reasonCode()).contains("INVOICE_REVIEW_SUPERSEDED");

        ApprovalApplicabilityResult overall = applicabilityService.evaluateApprovalApplicability(dealId, milestoneId);
        assertThat(overall.invoiceApplicable()).isFalse();
    }

    @Test
    @DisplayName("7. Beneficiary verification change: invalidated verification fails approval applicability")
    void test07_beneficiaryVerificationChange_failsApprovalApplicability() {
        // Beneficiary became unverified / revoked
        when(eligibilityService.evaluateBeneficiaryEligibility(sellerOrgId))
                .thenReturn(new BeneficiaryEligibilityResult(false, null, "Bank account verification revoked", false));

        BeneficiaryApplicabilityResult beneficiaryResult = applicabilityService.evaluateBeneficiaryApplicability(sellerOrgId);
        assertThat(beneficiaryResult.applicable()).isFalse();
        assertThat(beneficiaryResult.reasonCode()).contains("BENEFICIARY_INELIGIBLE");

        ApprovalApplicabilityResult overall = applicabilityService.evaluateApprovalApplicability(dealId, milestoneId);
        assertThat(overall.beneficiaryApplicable()).isFalse();
    }

    @Test
    @DisplayName("8. Beneficiary account ambiguity: multiple verified accounts fail closed")
    void test08_beneficiaryAccountAmbiguity_failsClosed() {
        // Organization has multiple verified accounts -> ambiguous
        when(eligibilityService.evaluateBeneficiaryEligibility(sellerOrgId))
                .thenReturn(new BeneficiaryEligibilityResult(false, null, "Ambiguous beneficiary: multiple eligible verified bank accounts found", false));

        BeneficiaryApplicabilityResult beneficiaryResult = applicabilityService.evaluateBeneficiaryApplicability(sellerOrgId);
        assertThat(beneficiaryResult.applicable()).isFalse();
        assertThat(beneficiaryResult.reasonCode()).contains("Ambiguous beneficiary");

        ApprovalApplicabilityResult overall = applicabilityService.evaluateApprovalApplicability(dealId, milestoneId);
        assertThat(overall.beneficiaryApplicable()).isFalse();
    }

    @Test
    @DisplayName("9. PM reassignment: approval by prior PM is not treated as current PM approval")
    void test09_pmReassignment_priorPmApprovalNotTreatedAsCurrentPmApproval() {
        // Deal PM changed from pmId to a newly assigned PM
        UUID newPmId = UUID.randomUUID();
        deal.setProjectManagerId(newPmId);

        PmAssignmentApplicabilityResult pmResult = applicabilityService.evaluatePmAssignmentApplicability(deal, List.of(deliverable));
        assertThat(pmResult.applicable()).isFalse();
        assertThat(pmResult.reasonCode()).contains("PM_ASSIGNMENT_CHANGED");

        ApprovalApplicabilityResult overall = applicabilityService.evaluateApprovalApplicability(dealId, milestoneId);
        assertThat(overall.pmAssignmentApplicable()).isFalse();
    }

    @Test
    @DisplayName("10. Dispute reopened: open dispute invalidates prior approval applicability")
    void test10_disputeReopened_invalidatesPriorApprovalApplicability() {
        Dispute activeDispute = new Dispute(dealId, milestoneId, buyerId, "Deliverables incomplete");
        activeDispute.setId(UUID.randomUUID());
        activeDispute.setStatus(DisputeStatus.OPEN);
        when(disputeRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(activeDispute));

        DisputeApplicabilityResult disputeResult = applicabilityService.evaluateDisputeApplicability(dealId, milestoneId);
        assertThat(disputeResult.applicable()).isFalse();
        assertThat(disputeResult.reasonCode()).contains("DISPUTE_OPEN");

        ApprovalApplicabilityResult overall = applicabilityService.evaluateApprovalApplicability(dealId, milestoneId);
        assertThat(overall.disputeApplicable()).isFalse();
    }

    @Test
    @DisplayName("11. Dispute materially changed: deal-level dispute invalidates milestone approvals")
    void test11_disputeMateriallyChanged_dealDisputeInvalidatesApprovals() {
        Dispute dealDispute = new Dispute(dealId, null, sellerId, "Payment terms breached");
        dealDispute.setId(UUID.randomUUID());
        dealDispute.setStatus(DisputeStatus.OPEN);
        when(disputeRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(dealDispute));

        DisputeApplicabilityResult disputeResult = applicabilityService.evaluateDisputeApplicability(dealId, milestoneId);
        assertThat(disputeResult.applicable()).isFalse();
        assertThat(disputeResult.reasonCode()).contains("DISPUTE_OPEN_ON_DEAL");

        ApprovalApplicabilityResult overall = applicabilityService.evaluateApprovalApplicability(dealId, milestoneId);
        assertThat(overall.disputeApplicable()).isFalse();
    }

    @Test
    @DisplayName("12. Risk/policy restriction introduced: later active restriction overrides prior approval")
    void test12_riskRestrictionIntroduced_overridesPriorApproval() {
        RiskReview activeRestriction = new RiskReview();
        activeRestriction.id = UUID.randomUUID();
        activeRestriction.status = ReviewStatus.RESTRICTED;
        activeRestriction.restrictionUntil = Instant.now().plus(7, ChronoUnit.DAYS);

        when(businessRiskRepository.query(eq(RiskReview.class), anyString(), anyMap(), anyInt()))
                .thenReturn(List.of(activeRestriction));

        RiskApplicabilityResult riskResult = applicabilityService.evaluateRiskApplicability(deal, milestone);
        assertThat(riskResult.applicable()).isFalse();
        assertThat(riskResult.reasonCode()).contains("RISK_RESTRICTION_ACTIVE");

        ApprovalApplicabilityResult overall = applicabilityService.evaluateApprovalApplicability(dealId, milestoneId);
        assertThat(overall.riskApplicable()).isFalse();
    }

    // =========================================================================
    // 13-18. CURRENT SOURCE AND HISTORICAL EVIDENCE TESTS
    // =========================================================================

    @Test
    @DisplayName("13. Unchanged current invoice review remains applicable when fingerprint matches")
    void test13_unchangedInvoiceReview_remainsApplicable() {
        InvoiceApplicabilityResult invoiceResult = applicabilityService.evaluateInvoiceApplicability(milestone);
        assertThat(invoiceResult.applicable()).isTrue();
        assertThat(invoiceResult.invoiceId()).isEqualTo(invoiceId);
        assertThat(invoiceResult.invoiceFingerprint()).isEqualTo("fp_abc123_assessment");
        assertThat(invoiceResult.assessmentId()).isEqualTo(assessmentId);
        assertThat(invoiceResult.reviewId()).isEqualTo(reviewId);
    }

    @Test
    @DisplayName("14. Stale invoice review fails: invoice modified after review was recorded")
    void test14_staleInvoiceReview_fails() {
        invoice.setUpdatedAt(Instant.now());

        InvoiceApplicabilityResult invoiceResult = applicabilityService.evaluateInvoiceApplicability(milestone);
        assertThat(invoiceResult.applicable()).isFalse();
        assertThat(invoiceResult.reasonCode()).contains("INVOICE_MODIFIED_AFTER_REVIEW");
    }

    @Test
    @DisplayName("15. Historical milestone approval remains readable without modification")
    void test15_historicalMilestoneApproval_remainsReadable() {
        assertThat(milestone.getStatus()).isEqualTo(MilestoneStatus.APPROVED);
        assertThat(deliverable.getStatus()).isEqualTo(DeliverableStatus.APPROVED);
        assertThat(deliverable.getReviewedBy()).isEqualTo(pmId);

        // Applicability evaluation does NOT mutate milestone or deliverable
        applicabilityService.evaluateApprovalApplicability(dealId, milestoneId);

        assertThat(milestone.getStatus()).isEqualTo(MilestoneStatus.APPROVED);
        assertThat(deliverable.getStatus()).isEqualTo(DeliverableStatus.APPROVED);
    }

    @Test
    @DisplayName("16. Historical agreement signature remains readable without mutation")
    void test16_historicalAgreementSignature_remainsReadable() {
        assertThat(agreement.getStatus()).isEqualTo(AgreementStatus.SIGNED);
        assertThat(agreement.getVersion()).isEqualTo(1);
        assertThat(agreement.getDocumentSha256()).isEqualTo("sha256_mock_hash_terms");

        applicabilityService.evaluateAgreementApplicability(dealId);

        assertThat(agreement.getStatus()).isEqualTo(AgreementStatus.SIGNED);
    }

    @Test
    @DisplayName("17. Historical dispute resolution remains readable")
    void test17_historicalDisputeResolution_remainsReadable() {
        Dispute resolvedDispute = new Dispute(dealId, milestoneId, buyerId, "Clarification on deliverable");
        resolvedDispute.setId(UUID.randomUUID());
        resolvedDispute.setStatus(DisputeStatus.RESOLVED);
        resolvedDispute.setResolutionNotes("Resolved amicably");
        when(disputeRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(resolvedDispute));

        DisputeApplicabilityResult result = applicabilityService.evaluateDisputeApplicability(dealId, milestoneId);
        assertThat(result.applicable()).isTrue(); // No open disputes
        assertThat(resolvedDispute.getStatus()).isEqualTo(DisputeStatus.RESOLVED);
    }

    @Test
    @DisplayName("18. Old approval is not deleted when marked non-current")
    void test18_oldApprovalNotDeleted_whenMarkedNonCurrent() {
        // Trigger non-current state via new invoice revision
        Invoice revision2 = new Invoice();
        revision2.setId(UUID.randomUUID());
        revision2.setMilestoneId(milestoneId);
        revision2.setStatus(InvoiceStatus.PROCESSING);
        when(invoiceRepository.findFirstByMilestoneIdOrderByCreatedAtDesc(milestoneId)).thenReturn(Optional.of(revision2));

        ApprovalApplicabilityResult result = applicabilityService.evaluateApprovalApplicability(dealId, milestoneId);
        assertThat(result.applicable()).isFalse();

        // Historical review, assessment, and milestone records remain intact
        assertThat(review.getStatus()).isEqualTo(InvoiceReviewStatus.APPROVED);
        assertThat(assessment.id).isEqualTo(assessmentId);
        assertThat(milestone.getStatus()).isEqualTo(MilestoneStatus.APPROVED);
    }

    // =========================================================================
    // 19. OLD MILESTONE STATUS ALONE IS NOT FINANCIAL ALLOW
    // =========================================================================

    @Test
    @DisplayName("19. Milestone status APPROVED alone must NOT produce trusted financial ALLOW")
    void test19_milestoneStatusApprovedAlone_mustNotProduceFinancialAllow() {
        // Milestone is APPROVED, invoice is fresh, agreement is signed, beneficiary is valid...
        // But because milestone approval lacks durable revision/fingerprint binding (unbound),
        // overall financial applicability MUST fail closed.
        ApprovalApplicabilityResult result = applicabilityService.evaluateApprovalApplicability(dealId, milestoneId);

        assertThat(result.milestoneApproved()).isTrue();
        assertThat(result.milestoneUnbound()).isTrue();
        assertThat(result.applicable()).isFalse();
        assertThat(result.reasonCodes()).anyMatch(r -> r.contains("UNBOUND_MILESTONE_APPROVAL"));
    }

    // =========================================================================
    // 20-22. EXECUTION SAFETY AND B04 FAIL-CLOSED INTEGRATION
    // =========================================================================

    @Test
    @DisplayName("20. Settlement authorization remains fail closed under B04")
    void test20_settlementAuthorization_remainsFailClosedUnderB04() {
        PayoutAuthorizationRequest request = new PayoutAuthorizationRequest(
                milestoneId, "Authorize release", "GLOBAL_IDEMP_TEST_123"
        );
        UserPrincipal buyer = new UserPrincipal(buyerId, "Buyer Corp", "buyer@corp.test", "hash", com.finx.common.enums.Role.BUYER, UserStatus.ACTIVE, List.of(new SimpleGrantedAuthority("ROLE_BUYER")));

        assertThatThrownBy(() -> settlementAuthorizationService.authorizeAndInitiateSettlement(request, buyer))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Settlement payout execution is disabled");

        verifyNoInteractions(bankingProvider);
    }

    @Test
    @DisplayName("21. Zero banking execution calls occur during approval applicability evaluation")
    void test21_zeroBankingExecutionCalls_duringApplicabilityEvaluation() {
        ApprovalApplicabilityResult result = settlementAuthorizationService.evaluateApprovalApplicability(dealId, milestoneId);
        assertThat(result).isNotNull();
        verifyNoInteractions(bankingProvider);
    }

    @Test
    @DisplayName("22. BankingProvider.initiateSettlement calls strictly remain zero")
    void test22_bankingProvider_initiateSettlementCallsStrictlyZero() {
        verify(bankingProvider, never()).initiateSettlement(any());
    }
}
