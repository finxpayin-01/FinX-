package com.finx.engine.release;

import com.finx.agreement.entity.DealAgreement;
import com.finx.agreement.enums.AgreementStatus;
import com.finx.agreement.repository.DealAgreementRepository;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.deal.service.DealAccessService;
import com.finx.engine.adapter.*;
import com.finx.engine.entity.EnginePolicyVersion;
import com.finx.engine.model.*;
import com.finx.engine.policy.release.ReleasePolicyDefinition;
import com.finx.engine.repository.EnginePolicyVersionRepository;
import com.finx.engine.service.EngineDecisionService;
import com.finx.invoice.entity.Invoice;
import com.finx.invoice.enums.ExtractionStatus;
import com.finx.invoice.enums.InvoiceStatus;
import com.finx.invoice.repository.InvoiceRepository;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.org.entity.OrganizationBankAccount;
import com.finx.org.enums.OrgEnums.BankVerificationStatus;
import com.finx.org.repository.OrganizationBankAccountRepository;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReleaseDecisionEngineTest {

    @Mock private EnginePolicyVersionRepository policyRepository;
    @Mock private RiskDecisionAdapter riskAdapter;
    @Mock private DocumentDecisionAdapter documentAdapter;
    @Mock private MilestoneRulesDecisionAdapter milestoneRulesAdapter;
    @Mock private ApprovalDecisionAdapter approvalAdapter;
    @Mock private TrustDecisionAdapter trustAdapter;
    @Mock private FraudDecisionAdapter fraudAdapter;
    @Mock private DisputeDecisionAdapter disputeAdapter;
    @Mock private ReconciliationDecisionAdapter reconciliationAdapter;
    @Mock private DealRepository dealRepository;
    @Mock private MilestoneRepository milestoneRepository;
    @Mock private DealAgreementRepository agreementRepository;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private OrganizationBankAccountRepository bankAccountRepository;
    @Mock private VerificationEligibilityService eligibilityService;
    @Mock private EngineDecisionService decisionService;
    @Mock private DealAccessService dealAccessService;

    private ReleaseDecisionEngine engine;

    private UUID buyerOrgId;
    private UUID sellerOrgId;
    private UUID dealId;
    private UUID milestoneId;
    private UUID invoiceId;
    private UUID agreementId;
    private UUID bankAccountId;

    private Deal deal;
    private Milestone milestone;
    private DealAgreement agreement;
    private Invoice invoice;
    private OrganizationBankAccount bankAccount;
    private EnginePolicyVersion policy;

    private Instant defaultTime;
    private UUID defaultEvalId;
    private UUID defaultCorrId;

    @BeforeEach
    void setUp() {
        buyerOrgId = UUID.randomUUID();
        sellerOrgId = UUID.randomUUID();
        dealId = UUID.randomUUID();
        milestoneId = UUID.randomUUID();
        invoiceId = UUID.randomUUID();
        agreementId = UUID.randomUUID();
        bankAccountId = UUID.randomUUID();

        defaultTime = Instant.parse("2026-10-09T12:00:00Z");
        defaultEvalId = UUID.randomUUID();
        defaultCorrId = UUID.randomUUID();

        deal = new Deal("Test Deal", "Description", UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("100000.00"), "INR", DealStatus.IN_PROGRESS);
        deal.setId(dealId);
        deal.setBuyerOrganizationId(buyerOrgId);
        deal.setSellerOrganizationId(sellerOrgId);

        milestone = new Milestone();
        milestone.setId(milestoneId);
        milestone.setDealId(dealId);
        milestone.setSequence(1);
        milestone.setAmount(new BigDecimal("50000.00"));
        milestone.setCurrency("INR");
        milestone.setStatus(MilestoneStatus.APPROVED);
        milestone.setInvoiceRequired(true);

        agreement = new DealAgreement();
        agreement.setId(agreementId);
        agreement.setDealId(dealId);
        agreement.setVersion(1);
        agreement.setStatus(AgreementStatus.SIGNED);
        agreement.setDocumentSha256("agreement_sha_256_mock_hash");

        invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setDealId(dealId);
        invoice.setMilestoneId(milestoneId);
        invoice.setBuyerOrganizationId(buyerOrgId);
        invoice.setVendorOrganizationId(sellerOrgId);
        invoice.setStatus(InvoiceStatus.APPROVED);
        invoice.setDocumentHash("invoice_doc_hash_mock");
        invoice.setExtractionStatus(ExtractionStatus.EXTRACTED);

        bankAccount = new OrganizationBankAccount();
        bankAccount.setId(bankAccountId);
        bankAccount.setOrganizationId(sellerOrgId);
        bankAccount.setVerificationStatus(BankVerificationStatus.VERIFIED);
        bankAccount.setNameMatchStatus("MATCH");
        bankAccount.setVerifiedAt(Instant.now());

        policy = new EnginePolicyVersion(
                null,
                PolicyScope.PLATFORM,
                EngineType.RELEASE,
                ReleasePolicyDefinition.POLICY_KEY,
                ReleasePolicyDefinition.POLICY_VERSION,
                ReleasePolicyDefinition.getExpectedContentHash(),
                ReleasePolicyDefinition.SCHEMA_VERSION,
                ReleasePolicyDefinition.DESCRIPTION,
                ReleasePolicyDefinition.getCanonicalPolicyContent(),
                null,
                Instant.now().minusSeconds(100)
        );

        when(policyRepository.findByEngineTypeAndPolicyKeyAndVersionAndOrganizationIdIsNull(
                EngineType.RELEASE, ReleasePolicyDefinition.POLICY_KEY, ReleasePolicyDefinition.POLICY_VERSION
        )).thenReturn(Optional.of(policy));

        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));
        when(milestoneRepository.findById(milestoneId)).thenReturn(Optional.of(milestone));
        when(agreementRepository.findTopByDealIdOrderByVersionDesc(dealId)).thenReturn(Optional.of(agreement));
        when(invoiceRepository.findFirstByMilestoneIdOrderByCreatedAtDesc(milestoneId)).thenReturn(Optional.of(invoice));
        when(bankAccountRepository.findByOrganizationId(sellerOrgId)).thenReturn(List.of(bankAccount));

        when(eligibilityService.evaluateBeneficiaryEligibility(sellerOrgId))
                .thenReturn(new BeneficiaryEligibilityResult(true, bankAccount, null, false));

        // Setup default valid mocks for all 8 adapters matching incoming evaluation context
        setupDefaultAdapters();

        engine = new ReleaseDecisionEngine(
                policyRepository,
                riskAdapter,
                documentAdapter,
                milestoneRulesAdapter,
                approvalAdapter,
                trustAdapter,
                fraudAdapter,
                disputeAdapter,
                reconciliationAdapter,
                dealRepository,
                milestoneRepository,
                agreementRepository,
                invoiceRepository,
                bankAccountRepository,
                eligibilityService,
                decisionService,
                dealAccessService
        );
    }

    private void setupDefaultAdapters() {
        when(riskAdapter.evaluateForDeal(any(), any(), any())).thenAnswer(inv -> {
            EvaluationContext ctx = inv.getArgument(2);
            return createCommand(EngineType.RISK, DecisionSubjectType.ORGANIZATION, buyerOrgId, buyerOrgId, DecisionStatus.ALLOW, List.of("RISK_POLICY_CLEAR"), ctx);
        });
        when(documentAdapter.evaluateInvoice(any(), any())).thenAnswer(inv -> {
            EvaluationContext ctx = inv.getArgument(1);
            return createCommand(EngineType.DOCUMENT, DecisionSubjectType.INVOICE, invoiceId, buyerOrgId, DecisionStatus.ALLOW, List.of("DOCUMENT_APPROVED"), ctx);
        });
        when(milestoneRulesAdapter.evaluate(any())).thenAnswer(inv -> {
            EvaluationContext ctx = inv.getArgument(0);
            return createCommand(EngineType.MILESTONE_RULES, DecisionSubjectType.MILESTONE, milestoneId, buyerOrgId, DecisionStatus.ALLOW, List.of("MILESTONE_CONTRACT_COMPLIANT"), ctx);
        });
        when(approvalAdapter.evaluate(any())).thenAnswer(inv -> {
            EvaluationContext ctx = inv.getArgument(0);
            return createCommand(EngineType.APPROVAL, DecisionSubjectType.MILESTONE, milestoneId, buyerOrgId, DecisionStatus.ALLOW, List.of("APPROVAL_GRANTED"), ctx);
        });
        when(trustAdapter.evaluateForOrganization(any(), any())).thenAnswer(inv -> {
            EvaluationContext ctx = inv.getArgument(1);
            return createCommand(EngineType.TRUST, DecisionSubjectType.ORGANIZATION, sellerOrgId, sellerOrgId, DecisionStatus.ALLOW, List.of("TRUST_COUNTERPARTY_VERIFIED"), ctx);
        });
        when(fraudAdapter.evaluate(any())).thenAnswer(inv -> {
            EvaluationContext ctx = inv.getArgument(0);
            return createCommand(EngineType.FRAUD, DecisionSubjectType.MILESTONE, milestoneId, buyerOrgId, DecisionStatus.ALLOW, List.of("FRAUD_POLICY_CLEAR"), ctx);
        });
        when(disputeAdapter.evaluateForMilestone(any(), any(), any())).thenAnswer(inv -> {
            EvaluationContext ctx = inv.getArgument(2);
            return createCommand(EngineType.DISPUTE, DecisionSubjectType.MILESTONE, milestoneId, buyerOrgId, DecisionStatus.ALLOW, List.of("DISPUTE_NONE_ACTIVE"), ctx);
        });
        when(reconciliationAdapter.evaluate(any())).thenAnswer(inv -> {
            EvaluationContext ctx = inv.getArgument(0);
            return createCommand(EngineType.RECONCILIATION, DecisionSubjectType.MILESTONE, milestoneId, buyerOrgId, DecisionStatus.ALLOW, List.of("RECONCILIATION_CONFIRMED"), ctx);
        });
    }

    private RecordEngineDecisionCommand createCommand(
            EngineType type,
            DecisionSubjectType subjectType,
            UUID subjectId,
            UUID orgId,
            DecisionStatus decision,
            List<String> reasons,
            EvaluationContext ctx
    ) {
        UUID evalId = (ctx != null && ctx.evaluationId() != null) ? ctx.evaluationId() : defaultEvalId;
        UUID corrId = (ctx != null && ctx.correlationId() != null) ? ctx.correlationId() : defaultCorrId;
        Instant evalTime = (ctx != null && ctx.evaluationTime() != null) ? ctx.evaluationTime() : defaultTime;

        return RecordEngineDecisionCommand.builder()
                .organizationId(orgId)
                .engineType(type)
                .subjectType(subjectType.name())
                .subjectId(subjectId)
                .decision(decision)
                .evaluationOutcome(EvaluationOutcome.SUCCESS)
                .score(null)
                .reasonCodes(reasons)
                .explanation("Mock explanation for " + type)
                .evidenceReferences(List.of())
                .ruleVersion("1.0.0")
                .evaluationId(evalId)
                .correlationId(corrId)
                .inputFingerprint("mock_fp_" + type)
                .engineImplementationVersion("1.0.0")
                .evaluatedAt(evalTime)
                .build();
    }

    @Test
    @DisplayName("1. Seller organization null -> REVIEW (RELEASE_SELLER_ORGANIZATION_MISSING), no buyer fallback")
    void testSellerOrganizationNullFailsClosedWithoutFallback() {
        deal.setSellerOrganizationId(null);

        ReleaseEvaluationResult result = engine.evaluateMilestone(dealId, milestoneId, defaultEvalId, defaultCorrId, defaultTime, null);

        assertThat(result.releaseCommand().getDecision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(result.releaseCommand().getReasonCodes()).contains(ReleaseDecisionEngine.REASON_SELLER_ORGANIZATION_MISSING);
        verify(trustAdapter, never()).evaluateForOrganization(any(), any());
    }

    @Test
    @DisplayName("2. Exactly one canonical eligible beneficiary selected")
    void testSingleEligibleBeneficiarySelected() {
        ReleaseEvaluationResult result = engine.evaluateMilestone(dealId, milestoneId, defaultEvalId, defaultCorrId, defaultTime, null);

        assertThat(result.releaseCommand().getDecision()).isEqualTo(DecisionStatus.ALLOW);
        verify(eligibilityService).evaluateBeneficiaryEligibility(sellerOrgId);
        assertThat(result.releaseCommand().getEvidenceReferences().stream()
                .anyMatch(r -> "ORGANIZATION_BANK_ACCOUNT".equals(r.sourceType()) && bankAccountId.equals(r.sourceId()))).isTrue();
    }

    @Test
    @DisplayName("3. Multiple eligible beneficiaries -> Ambiguous, no arbitrary findFirst selection")
    void testMultipleBeneficiariesAmbiguousNoArbitrarySelection() {
        when(eligibilityService.evaluateBeneficiaryEligibility(sellerOrgId))
                .thenReturn(new BeneficiaryEligibilityResult(false, null, "Ambiguous beneficiary: multiple eligible verified bank accounts found (2)", false));

        ReleaseEvaluationResult result = engine.evaluateMilestone(dealId, milestoneId, defaultEvalId, defaultCorrId, defaultTime, null);

        // No beneficiary is snapshotted into evidence references
        assertThat(result.releaseCommand().getEvidenceReferences().stream()
                .noneMatch(r -> "ORGANIZATION_BANK_ACCOUNT".equals(r.sourceType()))).isTrue();
    }

    @Test
    @DisplayName("4. No eligible beneficiary -> Beneficiary snapshot is NONE")
    void testNoEligibleBeneficiaryProducesNoneSnapshot() {
        when(eligibilityService.evaluateBeneficiaryEligibility(sellerOrgId))
                .thenReturn(new BeneficiaryEligibilityResult(false, null, "No eligible verified bank accounts found", false));

        ReleaseEvaluationResult result = engine.evaluateMilestone(dealId, milestoneId, defaultEvalId, defaultCorrId, defaultTime, null);

        assertThat(result.releaseCommand().getEvidenceReferences().stream()
                .noneMatch(r -> "ORGANIZATION_BANK_ACCOUNT".equals(r.sourceType()))).isTrue();
    }

    @Test
    @DisplayName("5. Component evaluationId mismatch -> REVIEW (RELEASE_COMPONENT_CONTEXT_MISMATCH)")
    void testComponentEvaluationIdMismatch() {
        when(fraudAdapter.evaluate(any())).thenAnswer(inv -> {
            EvaluationContext ctx = inv.getArgument(0);
            return RecordEngineDecisionCommand.builder()
                    .organizationId(buyerOrgId)
                    .engineType(EngineType.FRAUD)
                    .subjectType(DecisionSubjectType.MILESTONE.name())
                    .subjectId(milestoneId)
                    .decision(DecisionStatus.ALLOW)
                    .evaluationOutcome(EvaluationOutcome.SUCCESS)
                    .reasonCodes(List.of("FRAUD_POLICY_CLEAR"))
                    .evaluationId(UUID.randomUUID()) // Mismatched evaluationId
                    .correlationId(ctx.correlationId())
                    .inputFingerprint("fp")
                    .evaluatedAt(ctx.evaluationTime())
                    .build();
        });

        ReleaseEvaluationResult result = engine.evaluateMilestone(dealId, milestoneId, defaultEvalId, defaultCorrId, defaultTime, null);

        assertThat(result.releaseCommand().getDecision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(result.releaseCommand().getReasonCodes()).contains(ReleaseDecisionEngine.REASON_COMPONENT_CONTEXT_MISMATCH);
    }

    @Test
    @DisplayName("6. Component correlationId mismatch -> REVIEW (RELEASE_COMPONENT_CONTEXT_MISMATCH)")
    void testComponentCorrelationIdMismatch() {
        when(approvalAdapter.evaluate(any())).thenAnswer(inv -> {
            EvaluationContext ctx = inv.getArgument(0);
            return RecordEngineDecisionCommand.builder()
                    .organizationId(buyerOrgId)
                    .engineType(EngineType.APPROVAL)
                    .subjectType(DecisionSubjectType.MILESTONE.name())
                    .subjectId(milestoneId)
                    .decision(DecisionStatus.ALLOW)
                    .evaluationOutcome(EvaluationOutcome.SUCCESS)
                    .reasonCodes(List.of("APPROVAL_GRANTED"))
                    .evaluationId(ctx.evaluationId())
                    .correlationId(UUID.randomUUID()) // Mismatched correlationId
                    .inputFingerprint("fp")
                    .evaluatedAt(ctx.evaluationTime())
                    .build();
        });

        ReleaseEvaluationResult result = engine.evaluateMilestone(dealId, milestoneId, defaultEvalId, defaultCorrId, defaultTime, null);

        assertThat(result.releaseCommand().getDecision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(result.releaseCommand().getReasonCodes()).contains(ReleaseDecisionEngine.REASON_COMPONENT_CONTEXT_MISMATCH);
    }

    @Test
    @DisplayName("7. Component evaluationTime mismatch -> REVIEW (RELEASE_COMPONENT_CONTEXT_MISMATCH)")
    void testComponentEvaluationTimeMismatch() {
        when(reconciliationAdapter.evaluate(any())).thenAnswer(inv -> {
            EvaluationContext ctx = inv.getArgument(0);
            return RecordEngineDecisionCommand.builder()
                    .organizationId(buyerOrgId)
                    .engineType(EngineType.RECONCILIATION)
                    .subjectType(DecisionSubjectType.MILESTONE.name())
                    .subjectId(milestoneId)
                    .decision(DecisionStatus.ALLOW)
                    .evaluationOutcome(EvaluationOutcome.SUCCESS)
                    .reasonCodes(List.of("RECONCILIATION_CONFIRMED"))
                    .evaluationId(ctx.evaluationId())
                    .correlationId(ctx.correlationId())
                    .inputFingerprint("fp")
                    .evaluatedAt(ctx.evaluationTime().minusSeconds(3600)) // Stale evaluationTime
                    .build();
        });

        ReleaseEvaluationResult result = engine.evaluateMilestone(dealId, milestoneId, defaultEvalId, defaultCorrId, defaultTime, null);

        assertThat(result.releaseCommand().getDecision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(result.releaseCommand().getReasonCodes()).contains(ReleaseDecisionEngine.REASON_COMPONENT_CONTEXT_MISMATCH);
    }

    @Test
    @DisplayName("8. Wrong returned EngineType -> REVIEW (RELEASE_COMPONENT_CONTEXT_MISMATCH)")
    void testWrongReturnedEngineType() {
        when(disputeAdapter.evaluateForMilestone(any(), any(), any())).thenAnswer(inv -> {
            EvaluationContext ctx = inv.getArgument(2);
            return RecordEngineDecisionCommand.builder()
                    .organizationId(buyerOrgId)
                    .engineType(EngineType.RISK) // Returned RISK instead of DISPUTE
                    .subjectType(DecisionSubjectType.MILESTONE.name())
                    .subjectId(milestoneId)
                    .decision(DecisionStatus.ALLOW)
                    .evaluationOutcome(EvaluationOutcome.SUCCESS)
                    .reasonCodes(List.of("DISPUTE_NONE_ACTIVE"))
                    .evaluationId(ctx.evaluationId())
                    .correlationId(ctx.correlationId())
                    .inputFingerprint("fp")
                    .evaluatedAt(ctx.evaluationTime())
                    .build();
        });

        ReleaseEvaluationResult result = engine.evaluateMilestone(dealId, milestoneId, defaultEvalId, defaultCorrId, defaultTime, null);

        assertThat(result.releaseCommand().getDecision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(result.releaseCommand().getReasonCodes()).contains(ReleaseDecisionEngine.REASON_COMPONENT_CONTEXT_MISMATCH);
    }

    @Test
    @DisplayName("9. Wrong RISK subject binding -> REVIEW (RELEASE_COMPONENT_CONTEXT_MISMATCH)")
    void testWrongRiskSubjectBinding() {
        when(riskAdapter.evaluateForDeal(any(), any(), any())).thenAnswer(inv -> {
            EvaluationContext ctx = inv.getArgument(2);
            return RecordEngineDecisionCommand.builder()
                    .organizationId(buyerOrgId)
                    .engineType(EngineType.RISK)
                    .subjectType(DecisionSubjectType.MILESTONE.name()) // Must be ORGANIZATION
                    .subjectId(milestoneId)
                    .decision(DecisionStatus.ALLOW)
                    .evaluationOutcome(EvaluationOutcome.SUCCESS)
                    .reasonCodes(List.of("RISK_POLICY_CLEAR"))
                    .evaluationId(ctx.evaluationId())
                    .correlationId(ctx.correlationId())
                    .inputFingerprint("fp")
                    .evaluatedAt(ctx.evaluationTime())
                    .build();
        });

        ReleaseEvaluationResult result = engine.evaluateMilestone(dealId, milestoneId, defaultEvalId, defaultCorrId, defaultTime, null);

        assertThat(result.releaseCommand().getDecision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(result.releaseCommand().getReasonCodes()).contains(ReleaseDecisionEngine.REASON_COMPONENT_CONTEXT_MISMATCH);
    }

    @Test
    @DisplayName("10. Wrong TRUST seller organization binding -> REVIEW (RELEASE_COMPONENT_CONTEXT_MISMATCH)")
    void testWrongTrustSellerOrgBinding() {
        when(trustAdapter.evaluateForOrganization(any(), any())).thenAnswer(inv -> {
            EvaluationContext ctx = inv.getArgument(1);
            return RecordEngineDecisionCommand.builder()
                    .organizationId(buyerOrgId) // Mismatched: must be sellerOrgId
                    .engineType(EngineType.TRUST)
                    .subjectType(DecisionSubjectType.ORGANIZATION.name())
                    .subjectId(buyerOrgId)
                    .decision(DecisionStatus.ALLOW)
                    .evaluationOutcome(EvaluationOutcome.SUCCESS)
                    .reasonCodes(List.of("TRUST_COUNTERPARTY_VERIFIED"))
                    .evaluationId(ctx.evaluationId())
                    .correlationId(ctx.correlationId())
                    .inputFingerprint("fp")
                    .evaluatedAt(ctx.evaluationTime())
                    .build();
        });

        ReleaseEvaluationResult result = engine.evaluateMilestone(dealId, milestoneId, defaultEvalId, defaultCorrId, defaultTime, null);

        assertThat(result.releaseCommand().getDecision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(result.releaseCommand().getReasonCodes()).contains(ReleaseDecisionEngine.REASON_COMPONENT_CONTEXT_MISMATCH);
    }

    @Test
    @DisplayName("11. Wrong DOCUMENT invoice subject binding -> REVIEW (RELEASE_COMPONENT_CONTEXT_MISMATCH)")
    void testWrongDocumentInvoiceSubjectBinding() {
        when(documentAdapter.evaluateInvoice(any(), any())).thenAnswer(inv -> {
            EvaluationContext ctx = inv.getArgument(1);
            return RecordEngineDecisionCommand.builder()
                    .organizationId(buyerOrgId)
                    .engineType(EngineType.DOCUMENT)
                    .subjectType(DecisionSubjectType.INVOICE.name())
                    .subjectId(UUID.randomUUID()) // Wrong invoice subjectId
                    .decision(DecisionStatus.ALLOW)
                    .evaluationOutcome(EvaluationOutcome.SUCCESS)
                    .reasonCodes(List.of("DOCUMENT_APPROVED"))
                    .evaluationId(ctx.evaluationId())
                    .correlationId(ctx.correlationId())
                    .inputFingerprint("fp")
                    .evaluatedAt(ctx.evaluationTime())
                    .build();
        });

        ReleaseEvaluationResult result = engine.evaluateMilestone(dealId, milestoneId, defaultEvalId, defaultCorrId, defaultTime, null);

        assertThat(result.releaseCommand().getDecision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(result.releaseCommand().getReasonCodes()).contains(ReleaseDecisionEngine.REASON_COMPONENT_CONTEXT_MISMATCH);
    }

    @Test
    @DisplayName("12. Missing-invoice synthetic DOCUMENT subject is semantically correct (MILESTONE, milestone.id)")
    void testMissingInvoiceSyntheticDocumentSubjectBinding() {
        when(invoiceRepository.findFirstByMilestoneIdOrderByCreatedAtDesc(milestoneId)).thenReturn(Optional.empty());
        milestone.setInvoiceRequired(true);

        ReleaseEvaluationResult result = engine.evaluateMilestone(dealId, milestoneId, defaultEvalId, defaultCorrId, defaultTime, null);

        assertThat(result.releaseCommand().getDecision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(result.releaseCommand().getReasonCodes()).contains(ReleaseDecisionEngine.REASON_REVIEW_DOCUMENT);
        RecordEngineDecisionCommand docCmd = result.componentCommands().stream()
                .filter(c -> c.getEngineType() == EngineType.DOCUMENT)
                .findFirst().orElseThrow();
        assertThat(docCmd.getSubjectType()).isEqualTo(DecisionSubjectType.MILESTONE.name());
        assertThat(docCmd.getSubjectId()).isEqualTo(milestoneId);
        assertThat(docCmd.getReasonCodes()).contains(DocumentDecisionAdapter.REASON_DOCUMENT_ASSESSMENT_MISSING);
    }

    @Test
    @DisplayName("13. Invoice-not-required synthetic subject is semantically correct (MILESTONE, milestone.id)")
    void testInvoiceNotRequiredSyntheticDocumentSubjectBinding() {
        when(invoiceRepository.findFirstByMilestoneIdOrderByCreatedAtDesc(milestoneId)).thenReturn(Optional.empty());
        milestone.setInvoiceRequired(false);

        ReleaseEvaluationResult result = engine.evaluateMilestone(dealId, milestoneId, defaultEvalId, defaultCorrId, defaultTime, null);

        assertThat(result.releaseCommand().getDecision()).isEqualTo(DecisionStatus.ALLOW);
        RecordEngineDecisionCommand docCmd = result.componentCommands().stream()
                .filter(c -> c.getEngineType() == EngineType.DOCUMENT)
                .findFirst().orElseThrow();
        assertThat(docCmd.getSubjectType()).isEqualTo(DecisionSubjectType.MILESTONE.name());
        assertThat(docCmd.getSubjectId()).isEqualTo(milestoneId);
        assertThat(docCmd.getReasonCodes()).contains("DOCUMENT_NOT_REQUIRED");
    }

    @Test
    @DisplayName("14. Beneficiary selection ordering cannot change snapshot fingerprint")
    void testBeneficiarySelectionOrderingDeterminism() {
        ReleaseEvaluationResult r1 = engine.evaluateMilestone(dealId, milestoneId, defaultEvalId, defaultCorrId, defaultTime, null);
        ReleaseEvaluationResult r2 = engine.evaluateMilestone(dealId, milestoneId, defaultEvalId, defaultCorrId, defaultTime, null);

        assertThat(r1.releaseCommand().getInputFingerprint()).isEqualTo(r2.releaseCommand().getInputFingerprint());
    }

    @Test
    @DisplayName("15. Irrelevant bank account changes do not affect canonical snapshot when single eligible remains identical")
    void testIrrelevantBankAccountUnchanged() {
        ReleaseEvaluationResult r1 = engine.evaluateMilestone(dealId, milestoneId, defaultEvalId, defaultCorrId, defaultTime, null);

        // Add unrelated unverified bank accounts to repository list; eligibility still returns canonical single
        when(bankAccountRepository.findByOrganizationId(sellerOrgId)).thenReturn(List.of(bankAccount, new OrganizationBankAccount()));

        ReleaseEvaluationResult r2 = engine.evaluateMilestone(dealId, milestoneId, defaultEvalId, defaultCorrId, defaultTime, null);

        assertThat(r1.releaseCommand().getInputFingerprint()).isEqualTo(r2.releaseCommand().getInputFingerprint());
    }

    @Test
    @DisplayName("16. Canonical beneficiary change changes release fingerprint")
    void testCanonicalBeneficiaryChangeMutatesFingerprint() {
        ReleaseEvaluationResult r1 = engine.evaluateMilestone(dealId, milestoneId, defaultEvalId, defaultCorrId, defaultTime, null);

        OrganizationBankAccount newAccount = new OrganizationBankAccount();
        newAccount.setId(UUID.randomUUID());
        newAccount.setOrganizationId(sellerOrgId);
        newAccount.setVerificationStatus(BankVerificationStatus.VERIFIED);
        newAccount.setNameMatchStatus("MATCH");
        newAccount.setVerifiedAt(Instant.now());

        when(eligibilityService.evaluateBeneficiaryEligibility(sellerOrgId))
                .thenReturn(new BeneficiaryEligibilityResult(true, newAccount, null, false));

        ReleaseEvaluationResult r2 = engine.evaluateMilestone(dealId, milestoneId, defaultEvalId, defaultCorrId, defaultTime, null);

        assertThat(r1.releaseCommand().getInputFingerprint()).isNotEqualTo(r2.releaseCommand().getInputFingerprint());
    }

    @Test
    @DisplayName("17. All eight valid ALLOW -> Overall RELEASE is ALLOW")
    void testAllEightValidAllow() {
        ReleaseEvaluationResult result = engine.evaluateMilestone(dealId, milestoneId, defaultEvalId, defaultCorrId, defaultTime, null);

        assertThat(result.releaseCommand().getDecision()).isEqualTo(DecisionStatus.ALLOW);
        assertThat(result.releaseCommand().getReasonCodes()).containsExactly(ReleaseDecisionEngine.REASON_RELEASE_ALL_GATES_ALLOW);
    }

    @Test
    @DisplayName("18. One BLOCK (e.g. DISPUTE) -> Overall RELEASE is BLOCK")
    void testOneBlockProducesBlock() {
        when(disputeAdapter.evaluateForMilestone(any(), any(), any())).thenAnswer(inv -> {
            EvaluationContext ctx = inv.getArgument(2);
            return createCommand(EngineType.DISPUTE, DecisionSubjectType.MILESTONE, milestoneId, buyerOrgId, DecisionStatus.BLOCK, List.of("DISPUTE_ACTIVE_ON_MILESTONE"), ctx);
        });

        ReleaseEvaluationResult result = engine.evaluateMilestone(dealId, milestoneId, defaultEvalId, defaultCorrId, defaultTime, null);

        assertThat(result.releaseCommand().getDecision()).isEqualTo(DecisionStatus.BLOCK);
        assertThat(result.releaseCommand().getReasonCodes()).containsExactly(ReleaseDecisionEngine.REASON_BLOCKED_BY_DISPUTE);
    }

    @Test
    @DisplayName("19. One REVIEW -> Overall RELEASE is REVIEW")
    void testOneReviewProducesReview() {
        when(reconciliationAdapter.evaluate(any())).thenAnswer(inv -> {
            EvaluationContext ctx = inv.getArgument(0);
            return createCommand(EngineType.RECONCILIATION, DecisionSubjectType.MILESTONE, milestoneId, buyerOrgId, DecisionStatus.REVIEW, List.of("RECONCILIATION_FUNDING_NOT_PROVEN"), ctx);
        });

        ReleaseEvaluationResult result = engine.evaluateMilestone(dealId, milestoneId, defaultEvalId, defaultCorrId, defaultTime, null);

        assertThat(result.releaseCommand().getDecision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(result.releaseCommand().getReasonCodes()).containsExactly(ReleaseDecisionEngine.REASON_REVIEW_RECONCILIATION);
    }

    @Test
    @DisplayName("20. Component ERROR -> REVIEW unless a separately valid BLOCK produces conservative BLOCK")
    void testComponentErrorWithBlockProducesConservativeBlock() {
        when(fraudAdapter.evaluate(any())).thenAnswer(inv -> {
            EvaluationContext ctx = inv.getArgument(0);
            return RecordEngineDecisionCommand.builder()
                    .organizationId(buyerOrgId)
                    .engineType(EngineType.FRAUD)
                    .subjectType(DecisionSubjectType.MILESTONE.name())
                    .subjectId(milestoneId)
                    .decision(DecisionStatus.REVIEW)
                    .evaluationOutcome(EvaluationOutcome.ERROR)
                    .errorCode("FRAUD_ERROR")
                    .reasonCodes(List.of("FRAUD_ERROR"))
                    .evaluationId(ctx.evaluationId())
                    .correlationId(ctx.correlationId())
                    .inputFingerprint("fp")
                    .evaluatedAt(ctx.evaluationTime())
                    .build();
        });

        when(disputeAdapter.evaluateForMilestone(any(), any(), any())).thenAnswer(inv -> {
            EvaluationContext ctx = inv.getArgument(2);
            return createCommand(EngineType.DISPUTE, DecisionSubjectType.MILESTONE, milestoneId, buyerOrgId, DecisionStatus.BLOCK, List.of("DISPUTE_ACTIVE_ON_DEAL"), ctx);
        });

        ReleaseEvaluationResult result = engine.evaluateMilestone(dealId, milestoneId, defaultEvalId, defaultCorrId, defaultTime, null);

        assertThat(result.releaseCommand().getDecision()).isEqualTo(DecisionStatus.BLOCK);
        assertThat(result.releaseCommand().getReasonCodes()).contains(ReleaseDecisionEngine.REASON_BLOCKED_BY_DISPUTE);
    }

    @Test
    @DisplayName("21. Zero bank calls, zero financial mutations, zero ReleaseInstruction")
    void testZeroSideEffectsAndNoReleaseInstruction() {
        ReleaseEvaluationResult result = engine.evaluateMilestone(dealId, milestoneId, defaultEvalId, defaultCorrId, defaultTime, null);

        assertThat(result.releaseCommand().getScore()).isNull();
        verifyNoInteractions(decisionService);
    }
}
