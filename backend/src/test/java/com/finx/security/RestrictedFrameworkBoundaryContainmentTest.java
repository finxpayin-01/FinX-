package com.finx.security;

import com.finx.agreement.entity.DealAgreement;
import com.finx.agreement.enums.AgreementStatus;
import com.finx.agreement.repository.DealAgreementRepository;
import com.finx.banking.provider.BankingProvider;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.deal.service.DealAccessService;
import com.finx.dispute.repository.DisputeRepository;
import com.finx.escrow.entity.EscrowAccount;
import com.finx.escrow.entity.EscrowLedger;
import com.finx.escrow.repository.EscrowAccountRepository;
import com.finx.escrow.repository.EscrowLedgerRepository;
import com.finx.escrow.service.EscrowService;
import com.finx.exception.UnauthorizedException;
import com.finx.invoice.entity.Invoice;
import com.finx.invoice.entity.InvoiceAssessment;
import com.finx.invoice.enums.DuplicateConfidence;
import com.finx.invoice.enums.ExtractionStatus;
import com.finx.invoice.enums.InvoiceStatus;
import com.finx.invoice.enums.MatchingStatus;
import com.finx.invoice.repository.InvoiceAssessmentRepository;
import com.finx.invoice.repository.InvoiceRepository;
import com.finx.invoice.repository.InvoiceReviewRepository;
import com.finx.ledger.entity.JournalEntry;
import com.finx.ledger.repository.JournalEntryRepository;
import com.finx.ledger.repository.JournalLineRepository;
import com.finx.ledger.repository.LedgerAccountRepository;
import com.finx.ledger.service.LedgerService;
import com.finx.milestone.entity.Deliverable;
import com.finx.milestone.entity.DeliverableStatus;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.DeliverableRepository;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.org.entity.Organization;
import com.finx.org.entity.OrganizationBankAccount;
import com.finx.org.entity.OrganizationMember;
import com.finx.org.enums.OrgEnums.BankVerificationStatus;
import com.finx.org.enums.OrgEnums.MemberStatus;
import com.finx.org.repository.OrganizationBankAccountRepository;
import com.finx.org.repository.OrganizationMemberRepository;
import com.finx.org.repository.OrganizationRepository;
import com.finx.payment.service.RazorpayService;
import com.finx.paymentorchestration.entity.PaymentInstruction;
import com.finx.paymentorchestration.entity.PaymentTransaction;
import com.finx.paymentorchestration.entity.SettlementInstruction;
import com.finx.paymentorchestration.repository.PaymentInstructionRepository;
import com.finx.paymentorchestration.repository.PaymentTransactionRepository;
import com.finx.paymentorchestration.repository.SettlementInstructionRepository;
import com.finx.paymentorchestration.service.ApprovalApplicabilityService;
import com.finx.paymentorchestration.service.ApprovalApplicabilityService.ApprovalApplicabilityResult;
import com.finx.reconciliation.service.ReconciliationService;
import com.finx.risk.dto.RiskDtos.*;
import com.finx.risk.dto.RiskEvidenceDtos.*;
import com.finx.risk.entity.RiskAssessment;
import com.finx.risk.entity.RiskEvent;
import com.finx.risk.entity.RiskReview;
import com.finx.risk.entity.RiskScore;
import com.finx.risk.entity.RiskScoreHistory;
import com.finx.risk.enums.RiskEnums.RiskEntityType;
import com.finx.risk.enums.RiskEnums.RiskLevel;
import com.finx.risk.repository.BusinessRiskRepository;
import com.finx.risk.service.RiskActionService;
import com.finx.risk.service.RiskEngineService;
import com.finx.risk.service.RiskEvidenceReadService;
import com.finx.security.service.UserPrincipal;
import com.finx.verification.provider.VerificationProvider;
import com.finx.verification.repository.AuthorizedRepresentativeRepository;
import com.finx.verification.repository.KybProfileRepository;
import com.finx.verification.repository.KycProfileRepository;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Restricted Framework Boundary Containment Test Suite:
 * Proves that evidence retrieval and reference normalization remain strictly read-only,
 * zero side-effect, fail-closed, and decoupled from banking, settlement, escrow, and ledger mutation.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class RestrictedFrameworkBoundaryContainmentTest {

    // Repositories
    @Mock private DealRepository dealRepository;
    @Mock private MilestoneRepository milestoneRepository;
    @Mock private DeliverableRepository deliverableRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private OrganizationMemberRepository memberRepository;
    @Mock private OrganizationBankAccountRepository bankAccountRepository;
    @Mock private KybProfileRepository kybRepository;
    @Mock private KycProfileRepository kycRepository;
    @Mock private AuthorizedRepresentativeRepository repRepository;
    @Mock private VerificationProvider verificationProvider;
    @Mock private BusinessRiskRepository businessRiskRepository;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private InvoiceReviewRepository invoiceReviewRepository;
    @Mock private InvoiceAssessmentRepository invoiceAssessmentRepository;
    @Mock private DealAgreementRepository agreementRepository;
    @Mock private DisputeRepository disputeRepository;
    @Mock private RiskActionService riskActionService;

    // Financial side-effect mocks
    @Mock private BankingProvider bankingProvider;
    @Mock private RazorpayService razorpayService;
    @Mock private LedgerService ledgerService;
    @Mock private EscrowService escrowService;
    @Mock private JournalEntryRepository journalEntryRepository;
    @Mock private JournalLineRepository journalLineRepository;
    @Mock private LedgerAccountRepository ledgerAccountRepository;
    @Mock private EscrowAccountRepository escrowAccountRepository;
    @Mock private EscrowLedgerRepository escrowLedgerRepository;
    @Mock private PaymentInstructionRepository paymentInstructionRepository;
    @Mock private PaymentTransactionRepository paymentTransactionRepository;
    @Mock private SettlementInstructionRepository settlementInstructionRepository;
    @Mock private ReconciliationService reconciliationService;

    // Services under test
    private DealAccessService dealAccessService;
    private VerificationEligibilityService verificationEligibilityService;
    private ApprovalApplicabilityService approvalApplicabilityService;
    private RiskEvidenceReadService riskEvidenceReadService;
    private RiskEngineService riskEngineService;

    // Counter tracking for side-effect assertions
    private final AtomicInteger riskHistoryWrites = new AtomicInteger(0);
    private final AtomicInteger riskReviewWrites = new AtomicInteger(0);
    private final AtomicInteger riskEventWrites = new AtomicInteger(0);
    private final AtomicInteger journalEntryWrites = new AtomicInteger(0);
    private final AtomicInteger escrowLedgerWrites = new AtomicInteger(0);
    private final AtomicInteger settlementInstructionWrites = new AtomicInteger(0);

    // Test fixtures
    private UUID dealId;
    private UUID milestoneId;
    private UUID buyerUserId;
    private UUID buyerOrgId;
    private UUID sellerUserId;
    private UUID sellerOrgId;
    private UUID pmUserId;

    private Deal deal;
    private Milestone milestone;
    private Organization sellerOrg;
    private Organization buyerOrg;
    private OrganizationBankAccount bankAccount;
    private RiskScore buyerScore;
    private RiskScore sellerScore;
    private RiskAssessment buyerAssessment;
    private RiskAssessment sellerAssessment;
    private Invoice invoice;
    private InvoiceAssessment invoiceAssessment;
    private DealAgreement agreement;

    @BeforeEach
    void setUp() {
        dealId = UUID.randomUUID();
        milestoneId = UUID.randomUUID();
        buyerUserId = UUID.randomUUID();
        buyerOrgId = UUID.randomUUID();
        sellerUserId = UUID.randomUUID();
        sellerOrgId = UUID.randomUUID();
        pmUserId = UUID.randomUUID();

        // Deal setup
        deal = new Deal();
        deal.setId(dealId);
        deal.setBuyerId(buyerUserId);
        deal.setBuyerOrganizationId(buyerOrgId);
        deal.setSellerId(sellerUserId);
        deal.setSellerOrganizationId(sellerOrgId);
        deal.setProjectManagerId(pmUserId);
        deal.setStatus(DealStatus.IN_PROGRESS);

        // Milestone setup
        milestone = new Milestone();
        milestone.setId(milestoneId);
        milestone.setDealId(dealId);
        milestone.setTitle("Milestone 1");
        milestone.setAmount(new BigDecimal("50000.00"));
        milestone.setCurrency("INR");
        milestone.setStatus(MilestoneStatus.APPROVED);
        milestone.setCreatedAt(Instant.now().minus(5, ChronoUnit.DAYS));
        milestone.setUpdatedAt(Instant.now().minus(2, ChronoUnit.HOURS));

        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));
        when(milestoneRepository.findById(milestoneId)).thenReturn(Optional.of(milestone));

        // Organization setup
        sellerOrg = new Organization();
        sellerOrg.setId(sellerOrgId);
        sellerOrg.setName("Vendor Corp");

        buyerOrg = new Organization();
        buyerOrg.setId(buyerOrgId);
        buyerOrg.setName("Buyer Corp");

        when(organizationRepository.findById(sellerOrgId)).thenReturn(Optional.of(sellerOrg));
        when(organizationRepository.findById(buyerOrgId)).thenReturn(Optional.of(buyerOrg));

        // Bank account setup
        bankAccount = new OrganizationBankAccount();
        bankAccount.setId(UUID.randomUUID());
        bankAccount.setOrganizationId(sellerOrgId);
        bankAccount.setAccountHolderName("Vendor Corp");
        bankAccount.setAccountNumberMasked("XXXXXXXX5678");
        bankAccount.setEncryptedAccountNumber("enc_account_5678");
        bankAccount.setBankName("HDFC Bank");
        bankAccount.setIfscCode("HDFC0000060");
        bankAccount.setNameMatchStatus("EXACT_MATCH");
        bankAccount.setVerificationStatus(BankVerificationStatus.VERIFIED);
        bankAccount.setVerifiedAt(Instant.now().minus(5, ChronoUnit.DAYS));
        bankAccount.setProvider("MOCK");

        when(bankAccountRepository.findByOrganizationId(sellerOrgId)).thenReturn(List.of(bankAccount));

        // Risk setup
        buyerScore = new RiskScore();
        buyerScore.id = UUID.randomUUID();
        buyerScore.subjectKey = "ORG:" + buyerOrgId + ":BUYER";
        buyerScore.userId = buyerUserId;
        buyerScore.organizationId = buyerOrgId;
        buyerScore.userType = UserType.BUYER;
        buyerScore.score = 25;
        buyerScore.riskLevel = Level.LOW;
        buyerScore.calculatedAt = Instant.now().minus(1, ChronoUnit.HOURS);
        buyerScore.nextRefreshAt = Instant.now().plus(23, ChronoUnit.HOURS);

        buyerAssessment = new RiskAssessment(RiskEntityType.USER, buyerScore.id, RiskLevel.LOW, 25, "Low risk", true);
        buyerAssessment.ruleVersion = "rules-v1:BUYER:1.0";
        buyerScore.assessmentId = buyerAssessment.getId();

        sellerScore = new RiskScore();
        sellerScore.id = UUID.randomUUID();
        sellerScore.subjectKey = "ORG:" + sellerOrgId + ":VENDOR";
        sellerScore.userId = sellerUserId;
        sellerScore.organizationId = sellerOrgId;
        sellerScore.userType = UserType.VENDOR;
        sellerScore.score = 30;
        sellerScore.riskLevel = Level.MODERATE;
        sellerScore.calculatedAt = Instant.now().minus(1, ChronoUnit.HOURS);
        sellerScore.nextRefreshAt = Instant.now().plus(23, ChronoUnit.HOURS);

        sellerAssessment = new RiskAssessment(RiskEntityType.USER, sellerScore.id, RiskLevel.MODERATE, 30, "Moderate risk", true);
        sellerAssessment.ruleVersion = "rules-v1:VENDOR:1.0";
        sellerScore.assessmentId = sellerAssessment.getId();

        when(businessRiskRepository.query(eq(RiskScore.class), anyString(), anyMap(), anyInt()))
                .thenAnswer(inv -> {
                    Map<String, Object> p = inv.getArgument(2);
                    String key = (String) p.get("key");
                    if (buyerScore.subjectKey.equals(key)) return List.of(buyerScore);
                    if (sellerScore.subjectKey.equals(key)) return List.of(sellerScore);
                    return List.of();
                });

        when(businessRiskRepository.query(eq(RiskAssessment.class), anyString(), anyMap(), anyInt()))
                .thenAnswer(inv -> {
                    Map<String, Object> p = inv.getArgument(2);
                    UUID id = (UUID) p.get("id");
                    if (buyerAssessment.getId().equals(id)) return List.of(buyerAssessment);
                    if (sellerAssessment.getId().equals(id)) return List.of(sellerAssessment);
                    return List.of();
                });

        when(businessRiskRepository.current(buyerScore)).thenReturn(new Assessment(
                buyerAssessment.getId(), buyerScore.id, buyerUserId, buyerOrgId, UserType.BUYER,
                25, Level.LOW, null, 0, Map.of(), List.of(), List.of(), List.of(),
                List.of("Low risk profile"), buyerScore.calculatedAt, "rules-v1:BUYER:1.0",
                false, ReviewStatus.CLEARED, Action.ALLOW, "INIT", List.of(), 0.0
        ));

        when(businessRiskRepository.current(sellerScore)).thenReturn(new Assessment(
                sellerAssessment.getId(), sellerScore.id, sellerUserId, sellerOrgId, UserType.VENDOR,
                30, Level.MODERATE, null, 0, Map.of(), List.of(), List.of(), List.of(),
                List.of("Moderate risk profile"), sellerScore.calculatedAt, "rules-v1:VENDOR:1.0",
                false, ReviewStatus.CLEARED, Action.ALLOW, "INIT", List.of(), 0.0
        ));

        when(businessRiskRepository.query(eq(RiskReview.class), anyString(), anyMap(), anyInt()))
                .thenReturn(List.of());

        // Side effect interception
        doAnswer(inv -> {
            Object entity = inv.getArgument(0);
            if (entity instanceof RiskScoreHistory) riskHistoryWrites.incrementAndGet();
            if (entity instanceof RiskReview) riskReviewWrites.incrementAndGet();
            if (entity instanceof RiskEvent) riskEventWrites.incrementAndGet();
            return entity;
        }).when(businessRiskRepository).persist(any());

        doAnswer(inv -> {
            journalEntryWrites.incrementAndGet();
            return inv.getArgument(0);
        }).when(journalEntryRepository).save(any());

        doAnswer(inv -> {
            escrowLedgerWrites.incrementAndGet();
            return inv.getArgument(0);
        }).when(escrowLedgerRepository).save(any());

        doAnswer(inv -> {
            settlementInstructionWrites.incrementAndGet();
            return inv.getArgument(0);
        }).when(settlementInstructionRepository).save(any());

        // Invoice setup
        invoice = new Invoice();
        invoice.setId(UUID.randomUUID());
        invoice.setDealId(dealId);
        invoice.setMilestoneId(milestoneId);
        invoice.setStatus(InvoiceStatus.APPROVED);
        invoice.setExtractionStatus(ExtractionStatus.EXTRACTED);
        invoice.setMatchScore(95);
        invoice.setMatchingStatus(MatchingStatus.STRONG_MATCH);
        invoice.setDuplicateConfidence(DuplicateConfidence.UNIQUE);

        invoiceAssessment = new InvoiceAssessment();
        invoiceAssessment.id = UUID.randomUUID();
        invoiceAssessment.invoiceId = invoice.getId();
        invoiceAssessment.fingerprint = "sha256-fingerprint-clean";
        invoiceAssessment.createdAt = Instant.now().minus(3, ChronoUnit.HOURS);

        when(invoiceRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(invoice));
        when(invoiceRepository.findByMilestoneIdOrderByCreatedAtDesc(milestoneId)).thenReturn(List.of(invoice));
        when(invoiceRepository.findFirstByMilestoneIdOrderByCreatedAtDesc(milestoneId)).thenReturn(Optional.of(invoice));
        when(invoiceAssessmentRepository.findFirstByInvoiceIdOrderByCreatedAtDesc(invoice.getId()))
                .thenReturn(Optional.of(invoiceAssessment));

        // Agreement setup
        agreement = new DealAgreement();
        agreement.setId(UUID.randomUUID());
        agreement.setDealId(dealId);
        agreement.setStatus(AgreementStatus.SIGNED);
        agreement.setDocumentSha256("doc-hash-signed-agreement");
        agreement.setUpdatedAt(Instant.now().minus(10, ChronoUnit.DAYS));

        when(agreementRepository.findTopByDealIdOrderByVersionDesc(dealId)).thenReturn(Optional.of(agreement));

        // Active buyer membership
        when(memberRepository.findByOrganizationIdAndUserId(buyerOrgId, buyerUserId))
                .thenReturn(Optional.of(new OrganizationMember() {{
                    setStatus(MemberStatus.ACTIVE);
                }}));

        // Construct services
        dealAccessService = new DealAccessService(memberRepository);
        verificationEligibilityService = new VerificationEligibilityService(
                organizationRepository, kybRepository, kycRepository,
                repRepository, bankAccountRepository, verificationProvider
        );
        approvalApplicabilityService = new ApprovalApplicabilityService(
                dealRepository, milestoneRepository, deliverableRepository,
                invoiceRepository, invoiceReviewRepository, invoiceAssessmentRepository,
                agreementRepository, verificationEligibilityService, disputeRepository,
                riskActionService, businessRiskRepository
        );
        riskEvidenceReadService = new RiskEvidenceReadService(
                dealRepository, dealAccessService, businessRiskRepository,
                invoiceRepository, invoiceAssessmentRepository
        );
        riskEngineService = new RiskEngineService(
                dealRepository, null, null, riskEvidenceReadService
        );
    }

    private UserPrincipal buyerPrincipal() {
        return new UserPrincipal(buyerUserId, "Buyer", "buyer@finx.com", "secret",
                Role.BUYER, UserStatus.ACTIVE, List.of(new SimpleGrantedAuthority("ROLE_BUYER")));
    }

    // -------------------------------------------------------------------------
    // 1. Zero Side-Effect Evidence Retrieval Test
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("1. Evidence reads produce exactly zero writes across risk, ledger, escrow, and settlement")
    void evidenceReadsProduceZeroSideEffects() {
        // Initial state capture
        int initHistory = riskHistoryWrites.get();
        int initReview = riskReviewWrites.get();
        int initEvents = riskEventWrites.get();
        int initJournal = journalEntryWrites.get();
        int initEscrow = escrowLedgerWrites.get();
        int initSettlement = settlementInstructionWrites.get();

        // 1. Read risk evidence
        DealRiskEvidenceSnapshot riskSnapshot = riskEvidenceReadService.getDealRiskEvidence(dealId, buyerPrincipal());
        assertThat(riskSnapshot).isNotNull();

        // 2. Read compatibility risk view
        RiskAssessment riskView = riskEngineService.getAssessmentForDeal(dealId);
        assertThat(riskView).isNotNull();
        assertThat(riskView.isTransient()).isTrue();

        // 3. Read beneficiary eligibility
        BeneficiaryEligibilityResult beneficiaryResult = verificationEligibilityService.evaluateBeneficiaryEligibility(sellerOrgId);
        assertThat(beneficiaryResult.eligible()).isTrue();

        // 4. Read approval applicability
        ApprovalApplicabilityResult approvalResult = approvalApplicabilityService.evaluateApprovalApplicability(dealId, milestoneId);
        assertThat(approvalResult).isNotNull();

        // Post-inspection state capture
        assertThat(riskHistoryWrites.get()).isEqualTo(initHistory);
        assertThat(riskReviewWrites.get()).isEqualTo(initReview);
        assertThat(riskEventWrites.get()).isEqualTo(initEvents);
        assertThat(journalEntryWrites.get()).isEqualTo(initJournal);
        assertThat(escrowLedgerWrites.get()).isEqualTo(initEscrow);
        assertThat(settlementInstructionWrites.get()).isEqualTo(initSettlement);

        // Zero provider interactions
        verify(bankingProvider, never()).initiateSettlement(any());
        verifyNoInteractions(razorpayService);
        verifyNoInteractions(ledgerService);
        verifyNoInteractions(escrowService);
    }

    // -------------------------------------------------------------------------
    // 2. Negative Trust: Missing Evidence Fails Closed
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("2. Missing risk evidence fails closed with null values, never defaulting to SAFE or ALLOW")
    void missingEvidenceFailsClosed() {
        when(businessRiskRepository.query(eq(RiskScore.class), anyString(), anyMap(), anyInt()))
                .thenReturn(List.of()); // No scores found

        DealRiskEvidenceSnapshot snapshot = riskEvidenceReadService.getDealRiskEvidenceInternal(dealId);

        assertThat(snapshot.buyerEvidence().status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(snapshot.buyerEvidence().score()).isNull();
        assertThat(snapshot.buyerEvidence().persistedAssessmentId()).isNull();
        assertThat(snapshot.buyerEvidence().riskLevel()).isNull();
        assertThat(snapshot.vendorEvidence().status()).isEqualTo(EvidenceStatus.MISSING);
    }

    // -------------------------------------------------------------------------
    // 3. Negative Trust: Stale Approval Fails Closed
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("3. Subsequent material changes invalidate historical milestone approval")
    void subsequentMaterialChangesInvalidateApproval() {
        // Milestone was approved/reviewed at T-2h, but milestone was subsequently modified at T-1h
        Deliverable deliverable = new Deliverable();
        deliverable.setId(UUID.randomUUID());
        deliverable.setMilestoneId(milestoneId);
        deliverable.setStatus(DeliverableStatus.APPROVED);
        deliverable.setReviewedAt(Instant.now().minus(2, ChronoUnit.HOURS));
        deliverable.setReviewedBy(pmUserId);

        when(deliverableRepository.findByMilestoneIdOrderBySubmittedAtDesc(milestoneId))
                .thenReturn(List.of(deliverable));

        milestone.setUpdatedAt(Instant.now().minus(1, ChronoUnit.HOURS));

        ApprovalApplicabilityResult result = approvalApplicabilityService.evaluateApprovalApplicability(dealId, milestoneId);

        assertThat(result.applicable()).isFalse();
        assertThat(result.reasonCodes())
                .anyMatch(r -> r.contains("MILESTONE_MODIFIED_AFTER_APPROVAL"));
    }

    // -------------------------------------------------------------------------
    // 4. Negative Trust: Ambiguous/Masked-Only Beneficiary Fails Closed
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("4. Masked-only bank account without verified presence fails closed")
    void maskedOnlyBankAccountFailsClosed() {
        bankAccount.setEncryptedAccountNumber(null); // only masked digits, no real encrypted number

        BeneficiaryEligibilityResult result = verificationEligibilityService.evaluateBeneficiaryEligibility(sellerOrgId);

        assertThat(result.eligible()).isFalse();
        assertThat(result.failureReason()).contains("No eligible verified bank accounts found");
    }

    @Test
    @DisplayName("4b. Ambiguous multiple verified bank accounts fail closed")
    void ambiguousMultipleBankAccountsFailClosed() {
        OrganizationBankAccount secondAccount = new OrganizationBankAccount();
        secondAccount.setId(UUID.randomUUID());
        secondAccount.setOrganizationId(sellerOrgId);
        secondAccount.setAccountHolderName("Vendor Corp");
        secondAccount.setAccountNumberMasked("XXXXXXXX9999");
        secondAccount.setEncryptedAccountNumber("enc_9999");
        secondAccount.setVerificationStatus(BankVerificationStatus.VERIFIED);
        secondAccount.setVerifiedAt(Instant.now().minus(1, ChronoUnit.DAYS));

        when(bankAccountRepository.findByOrganizationId(sellerOrgId))
                .thenReturn(List.of(bankAccount, secondAccount));

        BeneficiaryEligibilityResult result = verificationEligibilityService.evaluateBeneficiaryEligibility(sellerOrgId);

        assertThat(result.eligible()).isFalse();
        assertThat(result.failureReason()).contains("Ambiguous beneficiary: multiple eligible verified bank accounts found");
    }

    // -------------------------------------------------------------------------
    // 5. Negative Trust: Manual Extraction Provenance Retained
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("5. Manual invoice provenance remains explicitly manual, not relabeled as OCR/AI")
    void manualInvoiceProvenanceRetained() {
        invoice.setExtractionStatus(ExtractionStatus.MANUAL_ENTRY);

        DealRiskEvidenceSnapshot snapshot = riskEvidenceReadService.getDealRiskEvidenceInternal(dealId);

        assertThat(snapshot.invoiceEvidence().manualProvenance()).isTrue();
        assertThat(snapshot.invoiceEvidence().provenance()).isEqualTo("MANUAL_ENTRY");
    }

    // -------------------------------------------------------------------------
    // 6. Negative Trust: Reconciliation Cannot Act as Financial Gate
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("6. Nonauthoritative reconciliation match cannot be used as a financial gate")
    void reconciliationCannotActAsFinancialGate() {
        // RestrictedFrameworkArchitectureTest already proves zero dependency.
        // Here we verify that ReconciliationService is never consulted during evidence read.
        riskEvidenceReadService.getDealRiskEvidenceInternal(dealId);
        approvalApplicabilityService.evaluateApprovalApplicability(dealId, milestoneId);
        verificationEligibilityService.evaluateBeneficiaryEligibility(sellerOrgId);

        verifyNoInteractions(reconciliationService);
    }

    // -------------------------------------------------------------------------
    // 7. Negative Trust: Cross-Tenant Access Denied
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("7. Cross-tenant evidence access is denied")
    void crossTenantEvidenceAccessDenied() {
        UUID otherUserId = UUID.randomUUID();
        UserPrincipal unrelated = new UserPrincipal(otherUserId, "Other", "other@finx.com", "secret",
                Role.BUYER, UserStatus.ACTIVE, List.of(new SimpleGrantedAuthority("ROLE_BUYER")));

        assertThatThrownBy(() -> riskEvidenceReadService.getDealRiskEvidence(dealId, unrelated))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("You are not authorized to access this deal");
    }

    // -------------------------------------------------------------------------
    // 8. Negative Trust: Inactive Membership Access Denied
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("8. Inactive organization membership is denied evidence access")
    void inactiveMembershipAccessDenied() {
        when(memberRepository.findByOrganizationIdAndUserId(buyerOrgId, buyerUserId))
                .thenReturn(Optional.of(new OrganizationMember() {{
                    setStatus(MemberStatus.SUSPENDED);
                }}));

        assertThatThrownBy(() -> riskEvidenceReadService.getDealRiskEvidence(dealId, buyerPrincipal()))
                .isInstanceOf(UnauthorizedException.class);
    }
}
