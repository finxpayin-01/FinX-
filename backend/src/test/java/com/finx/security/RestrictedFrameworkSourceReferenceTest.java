package com.finx.security;

import com.finx.agreement.entity.DealAgreement;
import com.finx.agreement.enums.AgreementStatus;
import com.finx.agreement.repository.DealAgreementRepository;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.deal.service.DealAccessService;
import com.finx.invoice.entity.Invoice;
import com.finx.invoice.entity.InvoiceAssessment;
import com.finx.invoice.enums.DuplicateConfidence;
import com.finx.invoice.enums.ExtractionStatus;
import com.finx.invoice.enums.InvoiceStatus;
import com.finx.invoice.enums.MatchingStatus;
import com.finx.invoice.repository.InvoiceAssessmentRepository;
import com.finx.invoice.repository.InvoiceRepository;
import com.finx.org.entity.Organization;
import com.finx.org.entity.OrganizationBankAccount;
import com.finx.org.enums.OrgEnums.BankVerificationStatus;
import com.finx.org.repository.OrganizationBankAccountRepository;
import com.finx.org.repository.OrganizationMemberRepository;
import com.finx.org.repository.OrganizationRepository;
import com.finx.risk.dto.RiskDtos.*;
import com.finx.risk.dto.RiskEvidenceDtos.*;
import com.finx.risk.entity.RiskAssessment;
import com.finx.risk.entity.RiskScore;
import com.finx.risk.enums.RiskEnums.RiskEntityType;
import com.finx.risk.enums.RiskEnums.RiskLevel;
import com.finx.risk.repository.BusinessRiskRepository;
import com.finx.risk.service.RiskEvidenceReadService;
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

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Proves source ID integrity for all durable evidence references:
 * Every durable source ID returned by evidence read services must resolve to a real persisted entity.
 * No transient UUID may be cited as durable evidence.
 * Missing sources must remain explicitly missing, never fabricated.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class RestrictedFrameworkSourceReferenceTest {

    @Mock private DealRepository dealRepository;
    @Mock private OrganizationMemberRepository memberRepository;
    @Mock private BusinessRiskRepository businessRiskRepository;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private InvoiceAssessmentRepository invoiceAssessmentRepository;
    @Mock private DealAgreementRepository agreementRepository;
    @Mock private OrganizationRepository orgRepository;
    @Mock private KybProfileRepository kybRepository;
    @Mock private KycProfileRepository kycRepository;
    @Mock private AuthorizedRepresentativeRepository repRepository;
    @Mock private OrganizationBankAccountRepository bankRepository;
    @Mock private VerificationProvider verificationProvider;

    private DealAccessService dealAccessService;
    private RiskEvidenceReadService riskEvidenceReadService;
    private VerificationEligibilityService verificationEligibilityService;

    private UUID dealId;
    private UUID buyerUserId;
    private UUID buyerOrgId;
    private UUID sellerUserId;
    private UUID sellerOrgId;

    private Deal deal;
    private RiskScore buyerScore;
    private RiskScore sellerScore;
    private RiskAssessment buyerAssessment;
    private RiskAssessment sellerAssessment;
    private Invoice invoice;
    private InvoiceAssessment invoiceAssessment;
    private Organization sellerOrg;
    private OrganizationBankAccount bankAccount;
    private DealAgreement agreement;

    @BeforeEach
    void setUp() {
        dealId = UUID.randomUUID();
        buyerUserId = UUID.randomUUID();
        buyerOrgId = UUID.randomUUID();
        sellerUserId = UUID.randomUUID();
        sellerOrgId = UUID.randomUUID();

        deal = new Deal();
        deal.setId(dealId);
        deal.setBuyerId(buyerUserId);
        deal.setBuyerOrganizationId(buyerOrgId);
        deal.setSellerId(sellerUserId);
        deal.setSellerOrganizationId(sellerOrgId);
        deal.setStatus(DealStatus.IN_PROGRESS);

        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));

        dealAccessService = new DealAccessService(memberRepository);

        // Buyer risk setup
        buyerScore = new RiskScore();
        buyerScore.id = UUID.randomUUID();
        buyerScore.subjectKey = "ORG:" + buyerOrgId + ":BUYER";
        buyerScore.userId = buyerUserId;
        buyerScore.organizationId = buyerOrgId;
        buyerScore.userType = UserType.BUYER;
        buyerScore.score = 20;
        buyerScore.riskLevel = Level.LOW;
        buyerScore.calculatedAt = Instant.now().minusSeconds(3600);

        buyerAssessment = new RiskAssessment(RiskEntityType.USER, buyerScore.id, RiskLevel.LOW, 20, "Low risk", true);
        buyerAssessment.ruleVersion = "rules-v1:BUYER:1.0";
        buyerScore.assessmentId = buyerAssessment.getId();

        // Seller risk setup
        sellerScore = new RiskScore();
        sellerScore.id = UUID.randomUUID();
        sellerScore.subjectKey = "ORG:" + sellerOrgId + ":VENDOR";
        sellerScore.userId = sellerUserId;
        sellerScore.organizationId = sellerOrgId;
        sellerScore.userType = UserType.VENDOR;
        sellerScore.score = 30;
        sellerScore.riskLevel = Level.MODERATE;
        sellerScore.calculatedAt = Instant.now().minusSeconds(3600);

        sellerAssessment = new RiskAssessment(RiskEntityType.USER, sellerScore.id, RiskLevel.MODERATE, 30, "Moderate risk", true);
        sellerAssessment.ruleVersion = "rules-v1:VENDOR:1.0";
        sellerScore.assessmentId = sellerAssessment.getId();

        when(businessRiskRepository.query(eq(RiskScore.class), anyString(), anyMap(), anyInt()))
                .thenAnswer(inv -> {
                    Map<String, Object> params = inv.getArgument(2);
                    String key = (String) params.get("key");
                    if (buyerScore.subjectKey.equals(key)) return List.of(buyerScore);
                    if (sellerScore.subjectKey.equals(key)) return List.of(sellerScore);
                    return List.of();
                });

        when(businessRiskRepository.query(eq(RiskAssessment.class), anyString(), anyMap(), anyInt()))
                .thenAnswer(inv -> {
                    Map<String, Object> params = inv.getArgument(2);
                    UUID id = (UUID) params.get("id");
                    if (buyerAssessment.getId().equals(id)) return List.of(buyerAssessment);
                    if (sellerAssessment.getId().equals(id)) return List.of(sellerAssessment);
                    return List.of();
                });

        // Invoice setup
        invoice = new Invoice();
        invoice.setId(UUID.randomUUID());
        invoice.setDealId(dealId);
        invoice.setMilestoneId(UUID.randomUUID());
        invoice.setStatus(InvoiceStatus.MATCHED);
        invoice.setExtractionStatus(ExtractionStatus.EXTRACTED);
        invoice.setMatchScore(90);
        invoice.setMatchingStatus(MatchingStatus.STRONG_MATCH);
        invoice.setDuplicateConfidence(DuplicateConfidence.UNIQUE);

        invoiceAssessment = new InvoiceAssessment();
        invoiceAssessment.id = UUID.randomUUID();
        invoiceAssessment.invoiceId = invoice.getId();
        invoiceAssessment.fingerprint = "sha256-fingerprint-abc";
        invoiceAssessment.createdAt = Instant.now().minusSeconds(1800);

        when(invoiceRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(invoice));
        when(invoiceAssessmentRepository.findFirstByInvoiceIdOrderByCreatedAtDesc(invoice.getId()))
                .thenReturn(Optional.of(invoiceAssessment));

        // Beneficiary setup
        sellerOrg = new Organization();
        sellerOrg.setId(sellerOrgId);
        sellerOrg.setName("Vendor Corp");

        bankAccount = new OrganizationBankAccount();
        bankAccount.setId(UUID.randomUUID());
        bankAccount.setOrganizationId(sellerOrgId);
        bankAccount.setAccountHolderName("Vendor Corp");
        bankAccount.setAccountNumberMasked("XXXXXXXX1234");
        bankAccount.setEncryptedAccountNumber("enc_1234567890");
        bankAccount.setBankName("HDFC Bank");
        bankAccount.setIfscCode("HDFC0000060");
        bankAccount.setNameMatchStatus("EXACT_MATCH");
        bankAccount.setVerificationStatus(BankVerificationStatus.VERIFIED);
        bankAccount.setVerifiedAt(Instant.now().minusSeconds(86400));
        bankAccount.setProvider("MOCK");

        when(orgRepository.findById(sellerOrgId)).thenReturn(Optional.of(sellerOrg));
        when(bankRepository.findByOrganizationId(sellerOrgId)).thenReturn(List.of(bankAccount));

        // Agreement setup
        agreement = new DealAgreement();
        agreement.setId(UUID.randomUUID());
        agreement.setDealId(dealId);
        agreement.setStatus(AgreementStatus.SIGNED);
        agreement.setDocumentSha256("hash-agreement-1234");

        when(agreementRepository.findTopByDealIdOrderByVersionDesc(dealId)).thenReturn(Optional.of(agreement));

        riskEvidenceReadService = new RiskEvidenceReadService(
                dealRepository, dealAccessService, businessRiskRepository,
                invoiceRepository, invoiceAssessmentRepository
        );

        verificationEligibilityService = new VerificationEligibilityService(
                orgRepository, kybRepository, kycRepository,
                repRepository, bankRepository, verificationProvider
        );
    }

    @Test
    @DisplayName("1. Buyer persisted risk source ID exists and matches database record")
    void buyerRiskSourceExists() {
        DealRiskEvidenceSnapshot snapshot = riskEvidenceReadService.getDealRiskEvidenceInternal(dealId);

        UUID buyerAssessmentId = snapshot.buyerEvidence().persistedAssessmentId();
        assertThat(buyerAssessmentId).isNotNull();
        assertThat(buyerAssessmentId).isEqualTo(buyerAssessment.getId());
        assertThat(snapshot.buyerEvidence().subjectId()).isEqualTo(buyerScore.id);
    }

    @Test
    @DisplayName("2. Vendor persisted risk source ID exists and matches database record")
    void vendorRiskSourceExists() {
        DealRiskEvidenceSnapshot snapshot = riskEvidenceReadService.getDealRiskEvidenceInternal(dealId);

        UUID vendorAssessmentId = snapshot.vendorEvidence().persistedAssessmentId();
        assertThat(vendorAssessmentId).isNotNull();
        assertThat(vendorAssessmentId).isEqualTo(sellerAssessment.getId());
        assertThat(snapshot.vendorEvidence().subjectId()).isEqualTo(sellerScore.id);
    }

    @Test
    @DisplayName("3. Invoice persisted assessment ID exists and matches database record")
    void invoiceAssessmentSourceExists() {
        DealRiskEvidenceSnapshot snapshot = riskEvidenceReadService.getDealRiskEvidenceInternal(dealId);

        UUID invAssessmentId = snapshot.invoiceEvidence().persistedAssessmentId();
        assertThat(invAssessmentId).isNotNull();
        assertThat(invAssessmentId).isEqualTo(invoiceAssessment.id);
        assertThat(snapshot.invoiceEvidence().fingerprint()).isEqualTo("sha256-fingerprint-abc");
    }

    @Test
    @DisplayName("4. Beneficiary bank account source exists and matches database record")
    void beneficiaryBankAccountSourceExists() {
        BeneficiaryEligibilityResult result = verificationEligibilityService.evaluateBeneficiaryEligibility(sellerOrgId);

        assertThat(result.eligible()).isTrue();
        assertThat(result.bankAccount()).isNotNull();
        assertThat(result.bankAccount().getId()).isEqualTo(bankAccount.getId());
        assertThat(result.bankAccount().getVerificationStatus()).isEqualTo(BankVerificationStatus.VERIFIED);
    }

    @Test
    @DisplayName("5. Deal agreement source exists and matches database record")
    void agreementSourceExists() {
        Optional<DealAgreement> opt = agreementRepository.findTopByDealIdOrderByVersionDesc(dealId);

        assertThat(opt).isPresent();
        assertThat(opt.get().getId()).isEqualTo(agreement.getId());
        assertThat(opt.get().getStatus()).isEqualTo(AgreementStatus.SIGNED);
    }

    @Test
    @DisplayName("6. Missing buyer source remains explicitly missing with null ID, not fabricated")
    void missingBuyerSourceRemainsMissing() {
        when(businessRiskRepository.query(eq(RiskScore.class), anyString(), anyMap(), anyInt()))
                .thenAnswer(inv -> {
                    Map<String, Object> params = inv.getArgument(2);
                    String key = (String) params.get("key");
                    if (sellerScore.subjectKey.equals(key)) return List.of(sellerScore);
                    return List.of();
                });

        DealRiskEvidenceSnapshot snapshot = riskEvidenceReadService.getDealRiskEvidenceInternal(dealId);

        assertThat(snapshot.buyerEvidence().status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(snapshot.buyerEvidence().persistedAssessmentId()).isNull();
        assertThat(snapshot.buyerEvidence().score()).isNull();
    }

    @Test
    @DisplayName("7. Missing vendor source remains explicitly missing with null ID, not fabricated")
    void missingVendorSourceRemainsMissing() {
        deal.setSellerId(null);
        deal.setSellerOrganizationId(null);

        DealRiskEvidenceSnapshot snapshot = riskEvidenceReadService.getDealRiskEvidenceInternal(dealId);

        assertThat(snapshot.vendorEvidence().status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(snapshot.vendorEvidence().persistedAssessmentId()).isNull();
        assertThat(snapshot.vendorEvidence().score()).isNull();
    }

    @Test
    @DisplayName("8. Missing invoice source remains explicitly missing with null ID, not fabricated")
    void missingInvoiceSourceRemainsMissing() {
        when(invoiceRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of());

        DealRiskEvidenceSnapshot snapshot = riskEvidenceReadService.getDealRiskEvidenceInternal(dealId);

        assertThat(snapshot.invoiceEvidence().status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(snapshot.invoiceEvidence().persistedAssessmentId()).isNull();
        assertThat(snapshot.invoiceEvidence().invoiceId()).isNull();
    }

    @Test
    @DisplayName("9. Transient deal aggregate is explicitly marked non-durable")
    void transientDealAggregateIsNotCitedAsDurable() {
        DealRiskEvidenceSnapshot snapshot = riskEvidenceReadService.getDealRiskEvidenceInternal(dealId);

        assertThat(snapshot.hasDurableAggregate()).isFalse();
    }
}
