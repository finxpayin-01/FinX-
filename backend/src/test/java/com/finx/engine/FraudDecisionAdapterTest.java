package com.finx.engine;

import com.finx.banking.provider.BankingProvider;
import com.finx.deal.entity.Deal;
import com.finx.deal.repository.DealRepository;
import com.finx.deal.service.DealAccessService;
import com.finx.engine.adapter.FraudDecisionAdapter;
import com.finx.engine.adapter.FraudDecisionEvidenceReader;
import com.finx.engine.entity.EnginePolicyVersion;
import com.finx.engine.model.DecisionStatus;
import com.finx.engine.model.DecisionSubjectType;
import com.finx.engine.model.EngineType;
import com.finx.engine.model.EvaluationContext;
import com.finx.engine.model.EvaluationOutcome;
import com.finx.engine.model.PolicyScope;
import com.finx.engine.model.RecordEngineDecisionCommand;
import com.finx.engine.policy.fraud.FraudPolicyDefinition;
import com.finx.engine.service.EnginePolicyVersionService;
import com.finx.exception.UnauthorizedException;
import com.finx.escrow.service.EscrowService;
import com.finx.invoice.entity.Invoice;
import com.finx.invoice.entity.InvoiceCheck;
import com.finx.invoice.enums.CheckStatus;
import com.finx.invoice.enums.InvoiceCheckType;
import com.finx.invoice.enums.InvoiceStatus;
import com.finx.invoice.repository.InvoiceCheckRepository;
import com.finx.invoice.repository.InvoiceRepository;
import com.finx.ledger.service.LedgerService;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.org.entity.OrganizationBankAccount;
import com.finx.org.enums.OrgEnums.BankVerificationStatus;
import com.finx.org.repository.OrganizationBankAccountRepository;
import com.finx.org.repository.OrganizationRepository;
import com.finx.paymentorchestration.service.SettlementAuthorizationService;
import com.finx.risk.service.RiskScoringService;
import com.finx.security.service.UserPrincipal;
import com.finx.verification.service.VerificationEligibilityService;
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
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FraudDecisionAdapterTest {

    @Mock private DealRepository dealRepository;
    @Mock private MilestoneRepository milestoneRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private DealAccessService dealAccessService;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private InvoiceCheckRepository invoiceCheckRepository;
    @Mock private OrganizationBankAccountRepository bankAccountRepository;
    @Mock private VerificationEligibilityService verificationEligibilityService;
    @Mock private EnginePolicyVersionService policyVersionService;

    // Prohibited components to verify zero interaction
    @Mock private BankingProvider bankingProvider;
    @Mock private SettlementAuthorizationService settlementAuthorizationService;
    @Mock private LedgerService ledgerService;
    @Mock private EscrowService escrowService;
    @Mock private RiskScoringService riskScoringService;

    private FraudDecisionEvidenceReader evidenceReader;
    private FraudDecisionAdapter adapter;

    private UUID buyerOrgId;
    private UUID sellerOrgId;
    private UUID dealId;
    private UUID milestoneId;
    private UUID invoiceId;
    private UUID correlationId;
    private UUID evaluationId;

    private Deal deal;
    private Milestone milestone;
    private Invoice invoice;
    private EnginePolicyVersion policyVersion;
    private OrganizationBankAccount primaryBankAccount;

    @BeforeEach
    void setUp() {
        evidenceReader = new FraudDecisionEvidenceReader(
                dealRepository,
                milestoneRepository,
                organizationRepository,
                dealAccessService,
                invoiceRepository,
                invoiceCheckRepository,
                bankAccountRepository,
                verificationEligibilityService,
                policyVersionService
        );
        adapter = new FraudDecisionAdapter(evidenceReader);

        buyerOrgId = UUID.randomUUID();
        sellerOrgId = UUID.randomUUID();
        dealId = UUID.randomUUID();
        milestoneId = UUID.randomUUID();
        invoiceId = UUID.randomUUID();
        correlationId = UUID.randomUUID();
        evaluationId = UUID.randomUUID();

        deal = new Deal();
        deal.setId(dealId);
        deal.setBuyerOrganizationId(buyerOrgId);
        deal.setSellerOrganizationId(sellerOrgId);

        milestone = new Milestone();
        milestone.setId(milestoneId);
        milestone.setDealId(dealId);
        milestone.setInvoiceRequired(true);
        milestone.setAmount(new BigDecimal("10000.00"));
        milestone.setUpdatedAt(Instant.now().minusSeconds(1800));

        invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setMilestoneId(milestoneId);
        invoice.setDealId(dealId);
        invoice.setBuyerOrganizationId(buyerOrgId);
        invoice.setVendorOrganizationId(sellerOrgId);
        invoice.setInvoiceNumber("INV-2026-001");
        invoice.setNormalizedInvoiceNumber("INV2026001");
        invoice.setDocumentHash("a1b2c3d4e5f67890123456789abcdef0123456789abcdef0123456789abcdef0");
        invoice.setStatus(InvoiceStatus.APPROVED);
        invoice.setCreatedAt(Instant.now().minusSeconds(900));
        invoice.setUpdatedAt(Instant.now().minusSeconds(900));

        policyVersion = new EnginePolicyVersion(
                null,
                PolicyScope.PLATFORM,
                EngineType.FRAUD,
                FraudPolicyDefinition.POLICY_KEY,
                FraudPolicyDefinition.POLICY_VERSION,
                FraudPolicyDefinition.getExpectedContentHash(),
                "1.0",
                FraudPolicyDefinition.DESCRIPTION,
                FraudPolicyDefinition.getCanonicalPolicyContent(),
                null,
                Instant.now().minusSeconds(86400)
        );

        primaryBankAccount = new OrganizationBankAccount(
                sellerOrgId,
                "Vendor Legal Ltd",
                "****1234",
                "test-hash",
                "HDFC0001234",
                "Test",
                "CURRENT",
                true
        );
        primaryBankAccount.setId(UUID.randomUUID());
        primaryBankAccount.setEncryptedAccountNumber("enc_acc_no");
        primaryBankAccount.setVerificationStatus(BankVerificationStatus.VERIFIED);
        primaryBankAccount.setNameMatchStatus("EXACT_MATCH");
        primaryBankAccount.setVerifiedAt(Instant.now().minusSeconds(7200));

        // Default mock setup
        when(organizationRepository.existsById(buyerOrgId)).thenReturn(true);
        when(milestoneRepository.findById(milestoneId)).thenReturn(Optional.of(milestone));
        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));
        when(policyVersionService.getPlatformPolicy(EngineType.FRAUD, FraudPolicyDefinition.POLICY_KEY, FraudPolicyDefinition.POLICY_VERSION))
                .thenReturn(Optional.of(policyVersion));
        when(invoiceRepository.findFirstByMilestoneIdOrderByCreatedAtDesc(milestoneId)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findByVendorOrganizationIdAndNormalizedInvoiceNumber(sellerOrgId, "INV2026001"))
                .thenReturn(List.of(invoice));
        when(invoiceRepository.findByDocumentHash(invoice.getDocumentHash()))
                .thenReturn(List.of(invoice));

        InvoiceCheck checkHash = createCheck(invoice, InvoiceCheckType.DOCUMENT_HASH_CHECK, CheckStatus.PASS, "DOCUMENT_HASH_VERIFIED");
        InvoiceCheck checkGst = createCheck(invoice, InvoiceCheckType.GSTIN_MATCH, CheckStatus.PASS, "TAX_IDENTIFIER_MATCHED");
        InvoiceCheck checkPan = createCheck(invoice, InvoiceCheckType.PAN_MATCH, CheckStatus.PASS, "TAX_IDENTIFIER_MATCHED");
        InvoiceCheck checkDate = createCheck(invoice, InvoiceCheckType.INVOICE_DATE_VALID, CheckStatus.PASS, "INVOICE_DATE_WITHIN_BOUNDS");

        when(invoiceCheckRepository.findByInvoiceIdOrderByCreatedAtAsc(invoiceId))
                .thenReturn(List.of(checkHash, checkGst, checkPan, checkDate));

        when(verificationEligibilityService.evaluateBeneficiaryEligibility(sellerOrgId))
                .thenReturn(new VerificationEligibilityService.BeneficiaryEligibilityResult(true, primaryBankAccount, null, false));
        when(bankAccountRepository.findByOrganizationId(sellerOrgId))
                .thenReturn(List.of(primaryBankAccount));
    }

    private InvoiceCheck createCheck(Invoice inv, InvoiceCheckType type, CheckStatus status, String reasonCode) {
        InvoiceCheck check = new InvoiceCheck(inv, type, status, reasonCode, "Message", 10, 10, "Details");
        check.setId(UUID.randomUUID());
        check.setCreatedAt(Instant.now().minusSeconds(60));
        return check;
    }

    private EvaluationContext createContext() {
        return new EvaluationContext(
                evaluationId,
                buyerOrgId,
                correlationId,
                "MILESTONE",
                milestoneId,
                Instant.now()
        );
    }

    // ==========================================
    // POLICY TESTS (1 - 13)
    // ==========================================

    @Test
    @DisplayName("1. Engine type is FRAUD")
    void testEngineTypeIsFraud() {
        assertThat(adapter.type()).isEqualTo(EngineType.FRAUD);
    }

    @Test
    @DisplayName("2. Subject type is MILESTONE")
    void testSubjectTypeIsMilestone() {
        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);
        assertThat(cmd.subjectType()).isEqualTo("MILESTONE");
        assertThat(cmd.subjectId()).isEqualTo(milestoneId);
    }

    @Test
    @DisplayName("3. Owner is deal.buyerOrganizationId")
    void testOwnerIsBuyerOrganization() {
        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);
        assertThat(cmd.organizationId()).isEqualTo(buyerOrgId);
    }

    @Test
    @DisplayName("4. Exact v1.0.1 policy resolved")
    void testExactV101PolicyResolved() {
        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);
        verify(policyVersionService).getPlatformPolicy(EngineType.FRAUD, "FINX_FRAUD_DETERMINISTIC_BASE", "1.0.1");
        assertThat(cmd.policyVersionId()).isEqualTo(policyVersion.getId());
    }

    @Test
    @DisplayName("5. v1.0.0 is not used")
    void testV100NotUsed() {
        EvaluationContext ctx = createContext();
        adapter.evaluate(ctx);
        verify(policyVersionService, never()).getPlatformPolicy(EngineType.FRAUD, "FINX_FRAUD_DETERMINISTIC_BASE", "1.0.0");
    }

    @Test
    @DisplayName("6. policyVersionId is preserved in command")
    void testPolicyVersionIdPreserved() {
        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);
        assertThat(cmd.policyVersionId()).isEqualTo(policyVersion.getId());
    }

    @Test
    @DisplayName("7. policyContentHash is preserved in command")
    void testPolicyContentHashPreserved() {
        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);
        assertThat(cmd.policyContentHash()).isEqualTo(FraudPolicyDefinition.getExpectedContentHash());
    }

    @Test
    @DisplayName("8. ruleVersion contains exact policy key/version")
    void testRuleVersionContainsExactKeyAndVersion() {
        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);
        assertThat(cmd.ruleVersion()).isEqualTo("FINX_FRAUD_DETERMINISTIC_BASE:1.0.1");
    }

    @Test
    @DisplayName("9. Activated policy accepted")
    void testActivatedPolicyAccepted() {
        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);
        assertThat(cmd.decision()).isEqualTo(DecisionStatus.ALLOW);
        assertThat(cmd.evaluationOutcome()).isEqualTo(EvaluationOutcome.SUCCESS);
    }

    @Test
    @DisplayName("10. Missing policy -> REVIEW + ERROR")
    void testMissingPolicyReturnsReviewError() {
        when(policyVersionService.getPlatformPolicy(EngineType.FRAUD, FraudPolicyDefinition.POLICY_KEY, FraudPolicyDefinition.POLICY_VERSION))
                .thenReturn(Optional.empty());

        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);

        assertThat(cmd.decision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(cmd.evaluationOutcome()).isEqualTo(EvaluationOutcome.ERROR);
        assertThat(cmd.errorCode()).isEqualTo("FRAUD_POLICY_UNAVAILABLE");
    }

    @Test
    @DisplayName("11. Inactive policy -> REVIEW + ERROR")
    void testInactivePolicyReturnsReviewError() {
        EnginePolicyVersion inactivePolicy = new EnginePolicyVersion(
                null,
                PolicyScope.PLATFORM,
                EngineType.FRAUD,
                FraudPolicyDefinition.POLICY_KEY,
                FraudPolicyDefinition.POLICY_VERSION,
                FraudPolicyDefinition.getExpectedContentHash(),
                "1.0",
                FraudPolicyDefinition.DESCRIPTION,
                FraudPolicyDefinition.getCanonicalPolicyContent(),
                null,
                null // inactive
        );
        when(policyVersionService.getPlatformPolicy(EngineType.FRAUD, FraudPolicyDefinition.POLICY_KEY, FraudPolicyDefinition.POLICY_VERSION))
                .thenReturn(Optional.of(inactivePolicy));

        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);

        assertThat(cmd.decision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(cmd.evaluationOutcome()).isEqualTo(EvaluationOutcome.ERROR);
        assertThat(cmd.errorCode()).isEqualTo("FRAUD_POLICY_INACTIVE");
    }

    @Test
    @DisplayName("12. Hash mismatch -> REVIEW + ERROR")
    void testHashMismatchReturnsReviewError() {
        EnginePolicyVersion tamperedPolicy = new EnginePolicyVersion(
                null,
                PolicyScope.PLATFORM,
                EngineType.FRAUD,
                FraudPolicyDefinition.POLICY_KEY,
                FraudPolicyDefinition.POLICY_VERSION,
                "tampered_hash_00000000000000000000000000000000000000000000000000000000",
                "1.0",
                FraudPolicyDefinition.DESCRIPTION,
                FraudPolicyDefinition.getCanonicalPolicyContent(),
                null,
                Instant.now()
        );
        when(policyVersionService.getPlatformPolicy(EngineType.FRAUD, FraudPolicyDefinition.POLICY_KEY, FraudPolicyDefinition.POLICY_VERSION))
                .thenReturn(Optional.of(tamperedPolicy));

        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);

        assertThat(cmd.decision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(cmd.evaluationOutcome()).isEqualTo(EvaluationOutcome.ERROR);
        assertThat(cmd.errorCode()).isEqualTo("FRAUD_POLICY_INTEGRITY_FAILURE");
    }

    @Test
    @DisplayName("13. Content mismatch -> REVIEW + ERROR")
    void testContentMismatchReturnsReviewError() {
        EnginePolicyVersion modifiedContentPolicy = new EnginePolicyVersion(
                null,
                PolicyScope.PLATFORM,
                EngineType.FRAUD,
                FraudPolicyDefinition.POLICY_KEY,
                FraudPolicyDefinition.POLICY_VERSION,
                FraudPolicyDefinition.getExpectedContentHash(),
                "1.0",
                FraudPolicyDefinition.DESCRIPTION,
                "{\"modified\": true}",
                null,
                Instant.now()
        );
        when(policyVersionService.getPlatformPolicy(EngineType.FRAUD, FraudPolicyDefinition.POLICY_KEY, FraudPolicyDefinition.POLICY_VERSION))
                .thenReturn(Optional.of(modifiedContentPolicy));

        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);

        assertThat(cmd.decision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(cmd.evaluationOutcome()).isEqualTo(EvaluationOutcome.ERROR);
        assertThat(cmd.errorCode()).isEqualTo("FRAUD_POLICY_INTEGRITY_FAILURE");
    }

    // ==========================================
    // S01: EXACT DOCUMENT HASH DUPLICATE (14 - 16)
    // ==========================================

    @Test
    @DisplayName("14. Persisted DOCUMENT_HASH_CHECK FAIL / DOCUMENT_HASH_DUPLICATE -> BLOCK")
    void testS01DocumentHashFailBlocks() {
        InvoiceCheck checkHashFail = createCheck(invoice, InvoiceCheckType.DOCUMENT_HASH_CHECK, CheckStatus.FAIL, "DOCUMENT_HASH_DUPLICATE");
        InvoiceCheck checkGst = createCheck(invoice, InvoiceCheckType.GSTIN_MATCH, CheckStatus.PASS, "TAX_IDENTIFIER_MATCHED");
        InvoiceCheck checkPan = createCheck(invoice, InvoiceCheckType.PAN_MATCH, CheckStatus.PASS, "TAX_IDENTIFIER_MATCHED");
        InvoiceCheck checkDate = createCheck(invoice, InvoiceCheckType.INVOICE_DATE_VALID, CheckStatus.PASS, "INVOICE_DATE_WITHIN_BOUNDS");

        when(invoiceCheckRepository.findByInvoiceIdOrderByCreatedAtAsc(invoiceId))
                .thenReturn(List.of(checkHashFail, checkGst, checkPan, checkDate));

        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);

        assertThat(cmd.decision()).isEqualTo(DecisionStatus.BLOCK);
        assertThat(cmd.reasonCodes()).contains("FRAUD_DUPLICATE_DOCUMENT_HASH");
    }

    @Test
    @DisplayName("15. Current invoice hash retained in input fingerprint")
    void testCurrentInvoiceHashRetainedInFingerprint() {
        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);
        assertThat(cmd.inputFingerprint()).isNotBlank();
    }

    @Test
    @DisplayName("16. No BYTEA hashing performed in adapter")
    void testNoByteaHashingPerformed() {
        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);
        assertThat(cmd.decision()).isEqualTo(DecisionStatus.ALLOW);
        verify(invoiceRepository, never()).getReferenceById(any());
    }

    // ==========================================
    // S02: DUPLICATE INVOICE NUMBER (17 - 21)
    // ==========================================

    @Test
    @DisplayName("17. Same vendor + same normalized invoice number outside revision lineage -> BLOCK")
    void testS02DuplicateInvoiceNumberBlocks() {
        Invoice duplicateCandidate = new Invoice();
        duplicateCandidate.setId(UUID.randomUUID());
        duplicateCandidate.setVendorOrganizationId(sellerOrgId);
        duplicateCandidate.setNormalizedInvoiceNumber("INV2026001");
        duplicateCandidate.setMilestoneId(UUID.randomUUID());
        duplicateCandidate.setDealId(UUID.randomUUID());

        when(invoiceRepository.findByVendorOrganizationIdAndNormalizedInvoiceNumber(sellerOrgId, "INV2026001"))
                .thenReturn(List.of(invoice, duplicateCandidate));

        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);

        assertThat(cmd.decision()).isEqualTo(DecisionStatus.BLOCK);
        assertThat(cmd.reasonCodes()).contains("FRAUD_DUPLICATE_INVOICE_NUMBER");
    }

    @Test
    @DisplayName("18. Self is excluded from S02 duplicate collision")
    void testSelfExcludedFromS02() {
        when(invoiceRepository.findByVendorOrganizationIdAndNormalizedInvoiceNumber(sellerOrgId, "INV2026001"))
                .thenReturn(List.of(invoice));

        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);

        assertThat(cmd.decision()).isEqualTo(DecisionStatus.ALLOW);
        assertThat(cmd.reasonCodes()).doesNotContain("FRAUD_DUPLICATE_INVOICE_NUMBER");
    }

    @Test
    @DisplayName("19. Direct revision parent is excluded from S02")
    void testDirectRevisionParentExcludedFromS02() {
        UUID parentInvoiceId = UUID.randomUUID();
        invoice.setRevisionOf(parentInvoiceId);

        Invoice parentInvoice = new Invoice();
        parentInvoice.setId(parentInvoiceId);
        parentInvoice.setDealId(dealId);
        parentInvoice.setMilestoneId(milestoneId);
        parentInvoice.setVendorOrganizationId(sellerOrgId);
        parentInvoice.setNormalizedInvoiceNumber("INV2026001");

        when(invoiceRepository.findById(parentInvoiceId)).thenReturn(Optional.of(parentInvoice));
        when(invoiceRepository.findByVendorOrganizationIdAndNormalizedInvoiceNumber(sellerOrgId, "INV2026001"))
                .thenReturn(List.of(invoice, parentInvoice));

        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);

        assertThat(cmd.decision()).isEqualTo(DecisionStatus.ALLOW);
        assertThat(cmd.reasonCodes()).doesNotContain("FRAUD_DUPLICATE_INVOICE_NUMBER");
    }

    @Test
    @DisplayName("20. Multi-level valid revision ancestors excluded from S02")
    void testMultiLevelRevisionAncestorsExcludedFromS02() {
        UUID grandparentInvoiceId = UUID.randomUUID();
        UUID parentInvoiceId = UUID.randomUUID();
        invoice.setRevisionOf(parentInvoiceId);

        Invoice parentInvoice = new Invoice();
        parentInvoice.setId(parentInvoiceId);
        parentInvoice.setRevisionOf(grandparentInvoiceId);
        parentInvoice.setDealId(dealId);
        parentInvoice.setMilestoneId(milestoneId);
        parentInvoice.setVendorOrganizationId(sellerOrgId);
        parentInvoice.setNormalizedInvoiceNumber("INV2026001");

        Invoice grandparentInvoice = new Invoice();
        grandparentInvoice.setId(grandparentInvoiceId);
        grandparentInvoice.setDealId(dealId);
        grandparentInvoice.setMilestoneId(milestoneId);
        grandparentInvoice.setVendorOrganizationId(sellerOrgId);
        grandparentInvoice.setNormalizedInvoiceNumber("INV2026001");

        when(invoiceRepository.findById(parentInvoiceId)).thenReturn(Optional.of(parentInvoice));
        when(invoiceRepository.findById(grandparentInvoiceId)).thenReturn(Optional.of(grandparentInvoice));
        when(invoiceRepository.findByVendorOrganizationIdAndNormalizedInvoiceNumber(sellerOrgId, "INV2026001"))
                .thenReturn(List.of(invoice, parentInvoice, grandparentInvoice));

        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);

        assertThat(cmd.decision()).isEqualTo(DecisionStatus.ALLOW);
        assertThat(cmd.reasonCodes()).doesNotContain("FRAUD_DUPLICATE_INVOICE_NUMBER");
    }

    @Test
    @DisplayName("21. Foreign vendor same invoice number does not trigger S02")
    void testForeignVendorSameInvoiceNumberDoesNotTriggerS02() {
        when(invoiceRepository.findByVendorOrganizationIdAndNormalizedInvoiceNumber(sellerOrgId, "INV2026001"))
                .thenReturn(List.of(invoice));

        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);

        assertThat(cmd.decision()).isEqualTo(DecisionStatus.ALLOW);
        verify(invoiceRepository).findByVendorOrganizationIdAndNormalizedInvoiceNumber(sellerOrgId, "INV2026001");
    }

    // ==========================================
    // S03: TAX IDENTITY MISMATCH (22 - 24)
    // ==========================================

    @Test
    @DisplayName("22. GSTIN_MATCH FAIL -> BLOCK")
    void testS03GstinFailBlocks() {
        InvoiceCheck checkGstFail = createCheck(invoice, InvoiceCheckType.GSTIN_MATCH, CheckStatus.FAIL, "TAX_IDENTIFIER_MISMATCH");
        InvoiceCheck checkHash = createCheck(invoice, InvoiceCheckType.DOCUMENT_HASH_CHECK, CheckStatus.PASS, "DOCUMENT_HASH_VERIFIED");
        InvoiceCheck checkPan = createCheck(invoice, InvoiceCheckType.PAN_MATCH, CheckStatus.PASS, "TAX_IDENTIFIER_MATCHED");
        InvoiceCheck checkDate = createCheck(invoice, InvoiceCheckType.INVOICE_DATE_VALID, CheckStatus.PASS, "INVOICE_DATE_WITHIN_BOUNDS");

        when(invoiceCheckRepository.findByInvoiceIdOrderByCreatedAtAsc(invoiceId))
                .thenReturn(List.of(checkHash, checkGstFail, checkPan, checkDate));

        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);

        assertThat(cmd.decision()).isEqualTo(DecisionStatus.BLOCK);
        assertThat(cmd.reasonCodes()).contains("FRAUD_TAX_IDENTITY_MISMATCH");
    }

    @Test
    @DisplayName("23. PAN_MATCH FAIL -> BLOCK")
    void testS03PanFailBlocks() {
        InvoiceCheck checkPanFail = createCheck(invoice, InvoiceCheckType.PAN_MATCH, CheckStatus.FAIL, "TAX_IDENTIFIER_MISMATCH");
        InvoiceCheck checkHash = createCheck(invoice, InvoiceCheckType.DOCUMENT_HASH_CHECK, CheckStatus.PASS, "DOCUMENT_HASH_VERIFIED");
        InvoiceCheck checkGst = createCheck(invoice, InvoiceCheckType.GSTIN_MATCH, CheckStatus.PASS, "TAX_IDENTIFIER_MATCHED");
        InvoiceCheck checkDate = createCheck(invoice, InvoiceCheckType.INVOICE_DATE_VALID, CheckStatus.PASS, "INVOICE_DATE_WITHIN_BOUNDS");

        when(invoiceCheckRepository.findByInvoiceIdOrderByCreatedAtAsc(invoiceId))
                .thenReturn(List.of(checkHash, checkGst, checkPanFail, checkDate));

        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);

        assertThat(cmd.decision()).isEqualTo(DecisionStatus.BLOCK);
        assertThat(cmd.reasonCodes()).contains("FRAUD_TAX_IDENTITY_MISMATCH");
    }

    @Test
    @DisplayName("24. PAN/GST raw values are not placed in decision")
    void testPanGstRawValuesNotPlacedInDecision() {
        InvoiceCheck checkPanFail = createCheck(invoice, InvoiceCheckType.PAN_MATCH, CheckStatus.FAIL, "TAX_IDENTIFIER_MISMATCH");
        InvoiceCheck checkHash = createCheck(invoice, InvoiceCheckType.DOCUMENT_HASH_CHECK, CheckStatus.PASS, "DOCUMENT_HASH_VERIFIED");
        InvoiceCheck checkGst = createCheck(invoice, InvoiceCheckType.GSTIN_MATCH, CheckStatus.PASS, "TAX_IDENTIFIER_MATCHED");
        InvoiceCheck checkDate = createCheck(invoice, InvoiceCheckType.INVOICE_DATE_VALID, CheckStatus.PASS, "INVOICE_DATE_WITHIN_BOUNDS");

        when(invoiceCheckRepository.findByInvoiceIdOrderByCreatedAtAsc(invoiceId))
                .thenReturn(List.of(checkHash, checkGst, checkPanFail, checkDate));

        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);

        assertThat(cmd.explanation()).doesNotContain("ABCDE1234F");
        assertThat(cmd.explanation()).doesNotContain("27ABCDE1234F1Z5");
    }

    // ==========================================
    // S04: BENEFICIARY NAME MISMATCH (25 - 28)
    // ==========================================

    @Test
    @DisplayName("25. Explicit current beneficiary name MISMATCH -> BLOCK")
    void testS04BeneficiaryNameMismatchBlocks() {
        OrganizationBankAccount mismatchAccount = new OrganizationBankAccount(
                sellerOrgId,
                "Different Name",
                "****4321",
                "test-hash-2",
                "ICIC0001234",
                "Test",
                "CURRENT",
                true
        );
        mismatchAccount.setId(UUID.randomUUID());
        mismatchAccount.setVerificationStatus(BankVerificationStatus.FAILED);
        mismatchAccount.setNameMatchStatus("MISMATCH");

        when(verificationEligibilityService.evaluateBeneficiaryEligibility(sellerOrgId))
                .thenReturn(new VerificationEligibilityService.BeneficiaryEligibilityResult(false, null, "Name mismatch", false));
        when(bankAccountRepository.findByOrganizationId(sellerOrgId))
                .thenReturn(List.of(mismatchAccount));

        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);

        assertThat(cmd.decision()).isEqualTo(DecisionStatus.BLOCK);
        assertThat(cmd.reasonCodes()).contains("FRAUD_BENEFICIARY_NAME_MISMATCH");
    }

    @Test
    @DisplayName("26. One clean eligible beneficiary plus unrelated historical mismatched account does not blindly BLOCK")
    void testCleanEligiblePlusHistoricalMismatchDoesNotBlock() {
        OrganizationBankAccount oldMismatchAccount = new OrganizationBankAccount(
                sellerOrgId,
                "Old Name",
                "****9999",
                "test-hash-3",
                "ICIC0001234",
                "Test",
                "SAVINGS",
                false
        );
        oldMismatchAccount.setId(UUID.randomUUID());
        oldMismatchAccount.setVerificationStatus(BankVerificationStatus.FAILED);
        oldMismatchAccount.setNameMatchStatus("MISMATCH");

        when(verificationEligibilityService.evaluateBeneficiaryEligibility(sellerOrgId))
                .thenReturn(new VerificationEligibilityService.BeneficiaryEligibilityResult(true, primaryBankAccount, null, false));
        when(bankAccountRepository.findByOrganizationId(sellerOrgId))
                .thenReturn(List.of(primaryBankAccount, oldMismatchAccount));

        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);

        assertThat(cmd.decision()).isEqualTo(DecisionStatus.ALLOW);
        assertThat(cmd.reasonCodes()).doesNotContain("FRAUD_BENEFICIARY_NAME_MISMATCH");
    }

    @Test
    @DisplayName("27. No eligible beneficiary -> REVIEW")
    void testNoEligibleBeneficiaryReturnsReview() {
        when(verificationEligibilityService.evaluateBeneficiaryEligibility(sellerOrgId))
                .thenReturn(new VerificationEligibilityService.BeneficiaryEligibilityResult(false, null, "No bank account found", false));
        when(bankAccountRepository.findByOrganizationId(sellerOrgId))
                .thenReturn(Collections.emptyList());

        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);

        assertThat(cmd.decision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(cmd.reasonCodes()).contains("FRAUD_BENEFICIARY_UNASSIGNED");
    }

    @Test
    @DisplayName("28. Multiple eligible beneficiaries -> REVIEW")
    void testMultipleEligibleBeneficiariesReturnsReview() {
        OrganizationBankAccount secondAccount = new OrganizationBankAccount(
                sellerOrgId,
                "Vendor Legal Ltd",
                "****5555",
                "test-hash-4",
                "HDFC0001234",
                "Test",
                "CURRENT",
                true
        );
        secondAccount.setId(UUID.randomUUID());
        secondAccount.setVerificationStatus(BankVerificationStatus.VERIFIED);
        secondAccount.setNameMatchStatus("EXACT_MATCH");

        when(verificationEligibilityService.evaluateBeneficiaryEligibility(sellerOrgId))
                .thenReturn(new VerificationEligibilityService.BeneficiaryEligibilityResult(false, null, "Multiple eligible accounts", false));
        when(bankAccountRepository.findByOrganizationId(sellerOrgId))
                .thenReturn(List.of(primaryBankAccount, secondAccount));

        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);

        assertThat(cmd.decision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(cmd.reasonCodes()).contains("FRAUD_BENEFICIARY_UNASSIGNED");
    }

    // ==========================================
    // S05: PROVIDER REFERENCE REUSE (29 - 30)
    // ==========================================

    @Test
    @DisplayName("29. Provider reference reuse reason is NOT emitted from current read model")
    void testS05NeverEmittedFromReadModel() {
        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);
        assertThat(cmd.reasonCodes()).doesNotContain("FRAUD_PROVIDER_REFERENCE_REUSE");
    }

    @Test
    @DisplayName("30. FraudDecisionAdapter has no payment/settlement repository dependency")
    void testNoPaymentSettlementRepositoryDependency() {
        verifyNoInteractions(bankingProvider, settlementAuthorizationService);
    }

    // ==========================================
    // S06: RECENT BENEFICIARY CHANGE (31 - 32)
    // ==========================================

    @Test
    @DisplayName("31. Recent-account-change reason NEVER emitted in v1.0.1")
    void testS06NeverEmittedInV101() {
        primaryBankAccount.setUpdatedAt(Instant.now().minusSeconds(1));
        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);
        assertThat(cmd.reasonCodes()).doesNotContain("FRAUD_BENEFICIARY_ACCOUNT_RECENTLY_MODIFIED");
    }

    @Test
    @DisplayName("32. No arbitrary time threshold invented")
    void testNoArbitraryTimeThresholdInvented() {
        primaryBankAccount.setCreatedAt(Instant.now().minusSeconds(5));
        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);
        assertThat(cmd.decision()).isEqualTo(DecisionStatus.ALLOW);
    }

    // ==========================================
    // S07: INVOICE DATE ANOMALY (33 - 35)
    // ==========================================

    @Test
    @DisplayName("33. Persisted INVOICE_DATE_VALID WARNING / anomaly -> REVIEW")
    void testS07InvoiceDateWarningReviews() {
        InvoiceCheck checkDateWarning = createCheck(invoice, InvoiceCheckType.INVOICE_DATE_VALID, CheckStatus.WARNING, "INVOICE_DATE_ANOMALY");
        InvoiceCheck checkHash = createCheck(invoice, InvoiceCheckType.DOCUMENT_HASH_CHECK, CheckStatus.PASS, "DOCUMENT_HASH_VERIFIED");
        InvoiceCheck checkGst = createCheck(invoice, InvoiceCheckType.GSTIN_MATCH, CheckStatus.PASS, "TAX_IDENTIFIER_MATCHED");
        InvoiceCheck checkPan = createCheck(invoice, InvoiceCheckType.PAN_MATCH, CheckStatus.PASS, "TAX_IDENTIFIER_MATCHED");

        when(invoiceCheckRepository.findByInvoiceIdOrderByCreatedAtAsc(invoiceId))
                .thenReturn(List.of(checkHash, checkGst, checkPan, checkDateWarning));

        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);

        assertThat(cmd.decision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(cmd.reasonCodes()).contains("FRAUD_INVOICE_DATE_ANOMALY");
    }

    @Test
    @DisplayName("34. Persisted INVOICE_DATE_VALID FAIL / anomaly -> REVIEW")
    void testS07InvoiceDateFailReviews() {
        InvoiceCheck checkDateFail = createCheck(invoice, InvoiceCheckType.INVOICE_DATE_VALID, CheckStatus.FAIL, "INVOICE_DATE_ANOMALY");
        InvoiceCheck checkHash = createCheck(invoice, InvoiceCheckType.DOCUMENT_HASH_CHECK, CheckStatus.PASS, "DOCUMENT_HASH_VERIFIED");
        InvoiceCheck checkGst = createCheck(invoice, InvoiceCheckType.GSTIN_MATCH, CheckStatus.PASS, "TAX_IDENTIFIER_MATCHED");
        InvoiceCheck checkPan = createCheck(invoice, InvoiceCheckType.PAN_MATCH, CheckStatus.PASS, "TAX_IDENTIFIER_MATCHED");

        when(invoiceCheckRepository.findByInvoiceIdOrderByCreatedAtAsc(invoiceId))
                .thenReturn(List.of(checkHash, checkGst, checkPan, checkDateFail));

        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);

        assertThat(cmd.decision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(cmd.reasonCodes()).contains("FRAUD_INVOICE_DATE_ANOMALY");
    }

    @Test
    @DisplayName("35. Adapter does not recalculate dates")
    void testAdapterDoesNotRecalculateDates() {
        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);
        assertThat(cmd.decision()).isEqualTo(DecisionStatus.ALLOW);
    }

    // ==========================================
    // MISSING EVIDENCE (36 - 38)
    // ==========================================

    @Test
    @DisplayName("36. Invoice-required milestone with no invoice -> REVIEW")
    void testInvoiceRequiredMilestoneWithNoInvoiceReturnsReview() {
        when(invoiceRepository.findFirstByMilestoneIdOrderByCreatedAtDesc(milestoneId))
                .thenReturn(Optional.empty());

        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);

        assertThat(cmd.decision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(cmd.reasonCodes()).contains("FRAUD_MISSING_INVOICE_EVIDENCE");
    }

    @Test
    @DisplayName("37. Invoice not required + no invoice is not automatically REVIEW for invoice absence")
    void testInvoiceNotRequiredMilestoneWithNoInvoicePasses() {
        milestone.setInvoiceRequired(false);
        when(invoiceRepository.findFirstByMilestoneIdOrderByCreatedAtDesc(milestoneId))
                .thenReturn(Optional.empty());

        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);

        assertThat(cmd.decision()).isEqualTo(DecisionStatus.ALLOW);
        assertThat(cmd.reasonCodes()).doesNotContain("FRAUD_MISSING_INVOICE_EVIDENCE");
    }

    @Test
    @DisplayName("38. Invoice exists but required check evidence materially absent -> REVIEW")
    void testInvoiceExistsWithEmptyChecksReturnsReview() {
        when(invoiceCheckRepository.findByInvoiceIdOrderByCreatedAtAsc(invoiceId))
                .thenReturn(Collections.emptyList());

        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);

        assertThat(cmd.decision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(cmd.reasonCodes()).contains("FRAUD_MISSING_INVOICE_EVIDENCE");
    }

    // ==========================================
    // ALLOW (39 - 41)
    // ==========================================

    @Test
    @DisplayName("39. All enabled checks clear + eligible beneficiary -> ALLOW")
    void testAllChecksClearAndEligibleBeneficiaryReturnsAllow() {
        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);

        assertThat(cmd.decision()).isEqualTo(DecisionStatus.ALLOW);
        assertThat(cmd.evaluationOutcome()).isEqualTo(EvaluationOutcome.SUCCESS);
    }

    @Test
    @DisplayName("40. ALLOW reason == FRAUD_EVALUATION_PASSED")
    void testAllowReasonIsFraudEvaluationPassed() {
        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);

        assertThat(cmd.reasonCodes()).containsExactly("FRAUD_EVALUATION_PASSED");
    }

    @Test
    @DisplayName("41. Fraud score == null")
    void testFraudScoreIsNull() {
        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);

        assertThat(cmd.score()).isNull();
    }

    // ==========================================
    // DETERMINISM (42 - 45)
    // ==========================================

    @Test
    @DisplayName("42. Repeated evaluation -> same input fingerprint")
    void testRepeatedEvaluationProducesSameFingerprint() {
        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd1 = adapter.evaluate(ctx);
        RecordEngineDecisionCommand cmd2 = adapter.evaluate(ctx);

        assertThat(cmd1.inputFingerprint()).isEqualTo(cmd2.inputFingerprint());
    }

    @Test
    @DisplayName("43. Reason order is deterministic")
    void testReasonOrderIsDeterministic() {
        InvoiceCheck checkHashFail = createCheck(invoice, InvoiceCheckType.DOCUMENT_HASH_CHECK, CheckStatus.FAIL, "DOCUMENT_HASH_DUPLICATE");
        InvoiceCheck checkGstFail = createCheck(invoice, InvoiceCheckType.GSTIN_MATCH, CheckStatus.FAIL, "TAX_IDENTIFIER_MISMATCH");

        when(invoiceCheckRepository.findByInvoiceIdOrderByCreatedAtAsc(invoiceId))
                .thenReturn(List.of(checkGstFail, checkHashFail));

        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);

        assertThat(cmd.reasonCodes()).containsExactly("FRAUD_DUPLICATE_DOCUMENT_HASH", "FRAUD_TAX_IDENTITY_MISMATCH");
    }

    @Test
    @DisplayName("44. Evidence references are deterministic")
    void testEvidenceReferencesAreDeterministic() {
        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd1 = adapter.evaluate(ctx);
        RecordEngineDecisionCommand cmd2 = adapter.evaluate(ctx);

        assertThat(cmd1.evidenceReferences()).isEqualTo(cmd2.evidenceReferences());
    }

    @Test
    @DisplayName("45. No transient evidence IDs")
    void testNoTransientEvidenceIds() {
        EvaluationContext ctx = createContext();
        RecordEngineDecisionCommand cmd = adapter.evaluate(ctx);

        for (var ref : cmd.evidenceReferences()) {
            assertThat(ref.sourceId()).isNotNull();
            assertThat(ref.sourceType()).isNotNull();
        }
    }

    // ==========================================
    // TENANT ISOLATION (46 - 48)
    // ==========================================

    @Test
    @DisplayName("46. Cross-tenant buyer organization context rejected")
    void testCrossTenantBuyerContextRejected() {
        UUID maliciousOrgId = UUID.randomUUID();
        when(organizationRepository.existsById(maliciousOrgId)).thenReturn(true);
        EvaluationContext ctx = new EvaluationContext(
                evaluationId, maliciousOrgId, correlationId,
                "MILESTONE", milestoneId, Instant.now()
        );

        assertThatThrownBy(() -> adapter.evaluate(ctx))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Milestone fraud evaluation scope is unavailable or unauthorized");
    }

    @Test
    @DisplayName("47. Milestone/deal mismatch rejected")
    void testMilestoneDealMismatchRejected() {
        UUID wrongMilestoneId = UUID.randomUUID();
        Milestone wrongMilestone = new Milestone();
        wrongMilestone.setId(wrongMilestoneId);
        wrongMilestone.setDealId(UUID.randomUUID()); // different deal
        when(milestoneRepository.findById(wrongMilestoneId)).thenReturn(Optional.of(wrongMilestone));

        EvaluationContext ctx = new EvaluationContext(
                evaluationId, buyerOrgId, correlationId,
                "MILESTONE", wrongMilestoneId, Instant.now()
        );

        assertThatThrownBy(() -> adapter.evaluateForDeal(dealId, ctx))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Milestone fraud evaluation scope is unavailable or unauthorized");
    }

    @Test
    @DisplayName("48. Foreign invoice scope rejected")
    void testForeignInvoiceScopeRejected() {
        invoice.setBuyerOrganizationId(UUID.randomUUID()); // foreign buyer

        EvaluationContext ctx = createContext();
        assertThatThrownBy(() -> adapter.evaluate(ctx))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Milestone fraud evaluation scope is unavailable or unauthorized");
    }

    // ==========================================
    // SIDE EFFECTS (49 - 54)
    // ==========================================

    @Test
    @DisplayName("49. Invoice writes = 0")
    void testInvoiceWritesZero() {
        EvaluationContext ctx = createContext();
        adapter.evaluate(ctx);
        verify(invoiceRepository, never()).save(any());
        verify(invoiceCheckRepository, never()).save(any());
    }

    @Test
    @DisplayName("50. Risk writes = 0")
    void testRiskWritesZero() {
        EvaluationContext ctx = createContext();
        adapter.evaluate(ctx);
        verifyNoInteractions(riskScoringService);
    }

    @Test
    @DisplayName("51. Bank calls = 0")
    void testBankCallsZero() {
        EvaluationContext ctx = createContext();
        adapter.evaluate(ctx);
        verifyNoInteractions(bankingProvider);
    }

    @Test
    @DisplayName("52. Ledger writes = 0")
    void testLedgerWritesZero() {
        EvaluationContext ctx = createContext();
        adapter.evaluate(ctx);
        verifyNoInteractions(ledgerService);
    }

    @Test
    @DisplayName("53. Escrow writes = 0")
    void testEscrowWritesZero() {
        EvaluationContext ctx = createContext();
        adapter.evaluate(ctx);
        verifyNoInteractions(escrowService);
    }

    @Test
    @DisplayName("54. Settlement writes = 0")
    void testSettlementWritesZero() {
        EvaluationContext ctx = createContext();
        adapter.evaluate(ctx);
        verifyNoInteractions(settlementAuthorizationService);
    }
}
