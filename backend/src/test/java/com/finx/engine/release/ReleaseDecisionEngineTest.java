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

    @BeforeEach
    void setUp() {
        buyerOrgId = UUID.randomUUID();
        sellerOrgId = UUID.randomUUID();
        dealId = UUID.randomUUID();
        milestoneId = UUID.randomUUID();
        invoiceId = UUID.randomUUID();
        agreementId = UUID.randomUUID();
        bankAccountId = UUID.randomUUID();

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

        // Default all 8 adapters to return ALLOW
        mockAdapter(riskAdapter, EngineType.RISK, DecisionStatus.ALLOW, List.of("RISK_POLICY_CLEAR"));
        mockAdapter(documentAdapter, EngineType.DOCUMENT, DecisionStatus.ALLOW, List.of("DOCUMENT_APPROVED"));
        mockAdapter(milestoneRulesAdapter, EngineType.MILESTONE_RULES, DecisionStatus.ALLOW, List.of("MILESTONE_CONTRACT_COMPLIANT"));
        mockAdapter(approvalAdapter, EngineType.APPROVAL, DecisionStatus.ALLOW, List.of("APPROVAL_GRANTED"));
        mockAdapter(trustAdapter, EngineType.TRUST, DecisionStatus.ALLOW, List.of("TRUST_COUNTERPARTY_VERIFIED"));
        mockAdapter(fraudAdapter, EngineType.FRAUD, DecisionStatus.ALLOW, List.of("FRAUD_POLICY_CLEAR"));
        mockAdapter(disputeAdapter, EngineType.DISPUTE, DecisionStatus.ALLOW, List.of("DISPUTE_NONE_ACTIVE"));
        mockAdapter(reconciliationAdapter, EngineType.RECONCILIATION, DecisionStatus.ALLOW, List.of("RECONCILIATION_CONFIRMED"));

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
                decisionService,
                dealAccessService
        );
    }

    private void mockAdapter(ReadOnlyDecisionAdapter adapter, EngineType type, DecisionStatus decision, List<String> reasons) {
        RecordEngineDecisionCommand cmd = RecordEngineDecisionCommand.builder()
                .organizationId(buyerOrgId)
                .engineType(type)
                .subjectType(DecisionSubjectType.MILESTONE)
                .subjectId(milestoneId)
                .decision(decision)
                .evaluationOutcome(EvaluationOutcome.SUCCESS)
                .score(null)
                .reasonCodes(reasons)
                .explanation("Mock explanation for " + type)
                .evidenceReferences(List.of())
                .ruleVersion("1.0.0")
                .evaluationId(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .inputFingerprint("mock_fingerprint_" + type)
                .engineImplementationVersion("1.0.0")
                .evaluatedAt(Instant.now())
                .build();

        if (adapter instanceof RiskDecisionAdapter) {
            when(riskAdapter.evaluateForDeal(any(), any(), any())).thenReturn(cmd);
        } else if (adapter instanceof DocumentDecisionAdapter) {
            when(documentAdapter.evaluateInvoice(any(), any())).thenReturn(cmd);
        } else if (adapter instanceof TrustDecisionAdapter) {
            when(trustAdapter.evaluateForOrganization(any(), any())).thenReturn(cmd);
        } else if (adapter instanceof DisputeDecisionAdapter) {
            when(disputeAdapter.evaluateForMilestone(any(), any(), any())).thenReturn(cmd);
        } else {
            when(adapter.evaluate(any())).thenReturn(cmd);
        }
    }

    @Test
    @DisplayName("1. All eight prerequisite engines ALLOW -> Overall RELEASE is ALLOW")
    void testAllEightAllowProducesAllow() {
        ReleaseEvaluationResult result = engine.evaluateMilestone(dealId, milestoneId, null, null, null, null);

        assertThat(result).isNotNull();
        RecordEngineDecisionCommand cmd = result.releaseCommand();
        assertThat(cmd.getEngineType()).isEqualTo(EngineType.RELEASE);
        assertThat(cmd.getSubjectType()).isEqualTo(DecisionSubjectType.MILESTONE.name());
        assertThat(cmd.getSubjectId()).isEqualTo(milestoneId);
        assertThat(cmd.getOrganizationId()).isEqualTo(buyerOrgId);
        assertThat(cmd.getDecision()).isEqualTo(DecisionStatus.ALLOW);
        assertThat(cmd.getScore()).isNull(); // strictly null
        assertThat(cmd.getReasonCodes()).containsExactly(ReleaseDecisionEngine.REASON_RELEASE_ALL_GATES_ALLOW);
        assertThat(cmd.getPolicyVersionId()).isEqualTo(policy.getId());
        assertThat(cmd.getPolicyContentHash()).isEqualTo(ReleasePolicyDefinition.getExpectedContentHash());
        assertThat(cmd.getInputFingerprint()).isNotBlank();
        assertThat(result.componentCommands()).hasSize(8);
    }

    @Test
    @DisplayName("2. Single BLOCK (e.g. FRAUD) -> Overall RELEASE is BLOCK")
    void testSingleBlockProducesBlock() {
        mockAdapter(fraudAdapter, EngineType.FRAUD, DecisionStatus.BLOCK, List.of("FRAUD_DUPLICATE_DOCUMENT_HASH"));

        ReleaseEvaluationResult result = engine.evaluateMilestone(dealId, milestoneId, null, null, null, null);

        RecordEngineDecisionCommand cmd = result.releaseCommand();
        assertThat(cmd.getDecision()).isEqualTo(DecisionStatus.BLOCK);
        assertThat(cmd.getReasonCodes()).containsExactly(ReleaseDecisionEngine.REASON_BLOCKED_BY_FRAUD);
    }

    @Test
    @DisplayName("3. Multiple BLOCKs -> Deterministic reason ordering according to EngineType order")
    void testMultipleBlocksProduceOrderedReasons() {
        mockAdapter(riskAdapter, EngineType.RISK, DecisionStatus.BLOCK, List.of("RISK_RESTRICTION_ACTIVE"));
        mockAdapter(fraudAdapter, EngineType.FRAUD, DecisionStatus.BLOCK, List.of("FRAUD_DUPLICATE_DOCUMENT_HASH"));
        mockAdapter(reconciliationAdapter, EngineType.RECONCILIATION, DecisionStatus.BLOCK, List.of("RECONCILIATION_AMOUNT_MISMATCH"));

        ReleaseEvaluationResult result = engine.evaluateMilestone(dealId, milestoneId, null, null, null, null);

        RecordEngineDecisionCommand cmd = result.releaseCommand();
        assertThat(cmd.getDecision()).isEqualTo(DecisionStatus.BLOCK);
        assertThat(cmd.getReasonCodes()).containsExactly(
                ReleaseDecisionEngine.REASON_BLOCKED_BY_RISK,
                ReleaseDecisionEngine.REASON_BLOCKED_BY_FRAUD,
                ReleaseDecisionEngine.REASON_BLOCKED_BY_RECONCILIATION
        );
    }

    @Test
    @DisplayName("4. BLOCK takes precedence over REVIEW")
    void testBlockTakesPrecedenceOverReview() {
        mockAdapter(documentAdapter, EngineType.DOCUMENT, DecisionStatus.REVIEW, List.of("DOCUMENT_REVIEW_REQUIRED"));
        mockAdapter(reconciliationAdapter, EngineType.RECONCILIATION, DecisionStatus.BLOCK, List.of("RECONCILIATION_BANK_FINALITY_FAILED"));

        ReleaseEvaluationResult result = engine.evaluateMilestone(dealId, milestoneId, null, null, null, null);

        RecordEngineDecisionCommand cmd = result.releaseCommand();
        assertThat(cmd.getDecision()).isEqualTo(DecisionStatus.BLOCK);
        assertThat(cmd.getReasonCodes()).containsExactly(ReleaseDecisionEngine.REASON_BLOCKED_BY_RECONCILIATION);
    }

    @Test
    @DisplayName("5. No BLOCK + Single REVIEW -> Overall RELEASE is REVIEW")
    void testNoBlockSingleReviewProducesReview() {
        mockAdapter(reconciliationAdapter, EngineType.RECONCILIATION, DecisionStatus.REVIEW, List.of("RECONCILIATION_FUNDING_NOT_PROVEN"));

        ReleaseEvaluationResult result = engine.evaluateMilestone(dealId, milestoneId, null, null, null, null);

        RecordEngineDecisionCommand cmd = result.releaseCommand();
        assertThat(cmd.getDecision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(cmd.getReasonCodes()).containsExactly(ReleaseDecisionEngine.REASON_REVIEW_RECONCILIATION);
    }

    @Test
    @DisplayName("6. Missing/Unregistered Release Policy fails closed as REVIEW + ERROR")
    void testMissingPolicyFailsClosed() {
        when(policyRepository.findByEngineTypeAndPolicyKeyAndVersionAndOrganizationIdIsNull(
                EngineType.RELEASE, ReleasePolicyDefinition.POLICY_KEY, ReleasePolicyDefinition.POLICY_VERSION
        )).thenReturn(Optional.empty());

        ReleaseEvaluationResult result = engine.evaluateMilestone(dealId, milestoneId, null, null, null, null);

        RecordEngineDecisionCommand cmd = result.releaseCommand();
        assertThat(cmd.getDecision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(cmd.getEvaluationOutcome()).isEqualTo(EvaluationOutcome.ERROR);
        assertThat(cmd.getErrorCode()).isEqualTo(ReleaseDecisionEngine.ERROR_POLICY_UNAVAILABLE);
    }

    @Test
    @DisplayName("7. Inactive Release Policy fails closed as REVIEW + ERROR")
    void testInactivePolicyFailsClosed() {
        EnginePolicyVersion inactivePolicy = new EnginePolicyVersion(
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
                null // null activatedAt
        );

        when(policyRepository.findByEngineTypeAndPolicyKeyAndVersionAndOrganizationIdIsNull(
                EngineType.RELEASE, ReleasePolicyDefinition.POLICY_KEY, ReleasePolicyDefinition.POLICY_VERSION
        )).thenReturn(Optional.of(inactivePolicy));

        ReleaseEvaluationResult result = engine.evaluateMilestone(dealId, milestoneId, null, null, null, null);

        RecordEngineDecisionCommand cmd = result.releaseCommand();
        assertThat(cmd.getDecision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(cmd.getEvaluationOutcome()).isEqualTo(EvaluationOutcome.ERROR);
        assertThat(cmd.getErrorCode()).isEqualTo(ReleaseDecisionEngine.ERROR_POLICY_INACTIVE);
    }

    @Test
    @DisplayName("8. Corrupted Release Policy Hash fails closed as REVIEW + ERROR")
    void testCorruptedPolicyHashFailsClosed() {
        EnginePolicyVersion corruptedPolicy = new EnginePolicyVersion(
                null,
                PolicyScope.PLATFORM,
                EngineType.RELEASE,
                ReleasePolicyDefinition.POLICY_KEY,
                ReleasePolicyDefinition.POLICY_VERSION,
                "corrupted_hash_value_1234567890abcdef1234567890abcdef1234567890abcdef",
                ReleasePolicyDefinition.SCHEMA_VERSION,
                ReleasePolicyDefinition.DESCRIPTION,
                "{}",
                null,
                Instant.now().minusSeconds(100)
        );

        when(policyRepository.findByEngineTypeAndPolicyKeyAndVersionAndOrganizationIdIsNull(
                EngineType.RELEASE, ReleasePolicyDefinition.POLICY_KEY, ReleasePolicyDefinition.POLICY_VERSION
        )).thenReturn(Optional.of(corruptedPolicy));

        ReleaseEvaluationResult result = engine.evaluateMilestone(dealId, milestoneId, null, null, null, null);

        RecordEngineDecisionCommand cmd = result.releaseCommand();
        assertThat(cmd.getDecision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(cmd.getEvaluationOutcome()).isEqualTo(EvaluationOutcome.ERROR);
        assertThat(cmd.getErrorCode()).isEqualTo(ReleaseDecisionEngine.ERROR_POLICY_INTEGRITY_FAILURE);
    }

    @Test
    @DisplayName("9. Snapshot fingerprint is deterministic and mutates on underlying entity change")
    void testSnapshotFingerprintDeterminism() {
        Instant fixedTime = Instant.parse("2026-10-09T12:00:00Z");
        UUID evalId = UUID.randomUUID();
        UUID corrId = UUID.randomUUID();

        ReleaseEvaluationResult r1 = engine.evaluateMilestone(dealId, milestoneId, evalId, corrId, fixedTime, null);
        ReleaseEvaluationResult r2 = engine.evaluateMilestone(dealId, milestoneId, evalId, corrId, fixedTime, null);

        assertThat(r1.releaseCommand().getInputFingerprint()).isEqualTo(r2.releaseCommand().getInputFingerprint());

        // Mutate milestone amount and verify fingerprint changes
        milestone.setAmount(new BigDecimal("75000.00"));
        ReleaseEvaluationResult r3 = engine.evaluateMilestone(dealId, milestoneId, evalId, corrId, fixedTime, null);
        assertThat(r3.releaseCommand().getInputFingerprint()).isNotEqualTo(r1.releaseCommand().getInputFingerprint());
    }

    @Test
    @DisplayName("10. Zero financial writes, zero bank calls, zero ReleaseInstruction")
    void testZeroFinancialSideEffects() {
        ReleaseEvaluationResult result = engine.evaluateMilestone(dealId, milestoneId, null, null, null, null);

        assertThat(result.releaseCommand().getScore()).isNull();
        verifyNoInteractions(decisionService);
    }
}
