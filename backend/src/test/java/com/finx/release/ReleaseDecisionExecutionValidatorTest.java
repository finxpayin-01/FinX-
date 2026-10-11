package com.finx.release;

import com.finx.banking.provider.BankingProvider;
import com.finx.deal.entity.Deal;
import com.finx.engine.entity.EngineDecision;
import com.finx.engine.entity.EnginePolicyVersion;
import com.finx.engine.model.*;
import com.finx.engine.policy.release.ReleasePolicyDefinition;
import com.finx.engine.release.ReleaseDecisionEngine;
import com.finx.engine.release.ReleaseEvaluationResult;
import com.finx.engine.repository.EngineDecisionRepository;
import com.finx.engine.repository.EnginePolicyVersionRepository;
import com.finx.milestone.entity.Milestone;
import com.finx.org.entity.OrganizationBankAccount;
import com.finx.release.service.ReleaseDecisionExecutionValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ReleaseDecisionExecutionValidatorTest {

    private EngineDecisionRepository decisionRepo;
    private EnginePolicyVersionRepository policyRepo;
    private ReleaseDecisionEngine releaseDecisionEngine;
    private BankingProvider bankingProvider;
    private ReleaseDecisionExecutionValidator validator;

    private Deal deal;
    private Milestone milestone;
    private OrganizationBankAccount beneficiary;
    private EnginePolicyVersion validPolicy;
    private EngineDecision validReleaseDecision;

    private UUID dealId;
    private UUID milestoneId;
    private UUID buyerOrgId;
    private UUID sellerOrgId;
    private UUID beneficiaryBankAccountId;
    private UUID releaseDecisionId;
    private UUID policyVersionId;
    private UUID evaluationId;
    private UUID correlationId;
    private String inputFingerprint;
    private String decisionFingerprint;

    @BeforeEach
    void setUp() throws Exception {
        decisionRepo = mock(EngineDecisionRepository.class);
        policyRepo = mock(EnginePolicyVersionRepository.class);
        releaseDecisionEngine = mock(ReleaseDecisionEngine.class);
        bankingProvider = mock(BankingProvider.class);

        validator = new ReleaseDecisionExecutionValidator(decisionRepo, policyRepo, releaseDecisionEngine);

        dealId = UUID.randomUUID();
        milestoneId = UUID.randomUUID();
        buyerOrgId = UUID.randomUUID();
        sellerOrgId = UUID.randomUUID();
        beneficiaryBankAccountId = UUID.randomUUID();
        releaseDecisionId = UUID.randomUUID();
        policyVersionId = UUID.randomUUID();
        evaluationId = UUID.randomUUID();
        correlationId = UUID.randomUUID();
        inputFingerprint = "a".repeat(64);
        decisionFingerprint = "b".repeat(64);

        deal = new Deal();
        setField(deal, "id", dealId);
        setField(deal, "buyerOrganizationId", buyerOrgId);
        setField(deal, "sellerOrganizationId", sellerOrgId);

        milestone = new Milestone();
        setField(milestone, "id", milestoneId);
        setField(milestone, "dealId", dealId);

        beneficiary = new OrganizationBankAccount();
        setField(beneficiary, "id", beneficiaryBankAccountId);
        setField(beneficiary, "organizationId", sellerOrgId);

        validPolicy = new EnginePolicyVersion(
                null,
                PolicyScope.PLATFORM,
                EngineType.RELEASE,
                ReleasePolicyDefinition.POLICY_KEY,
                ReleasePolicyDefinition.POLICY_VERSION,
                ReleasePolicyDefinition.getExpectedContentHash(),
                "1.0",
                ReleasePolicyDefinition.DESCRIPTION,
                ReleasePolicyDefinition.getCanonicalPolicyContent(),
                null,
                Instant.now()
        );
        setField(validPolicy, "id", policyVersionId);

        when(policyRepo.findById(policyVersionId)).thenReturn(Optional.of(validPolicy));

        validReleaseDecision = createValidReleaseDecision();
        when(decisionRepo.findById(releaseDecisionId)).thenReturn(Optional.of(validReleaseDecision));
        when(decisionRepo.findFirstByOrganizationIdAndEngineTypeAndSubjectTypeAndSubjectIdOrderByEvaluatedAtDesc(
                buyerOrgId, EngineType.RELEASE, "MILESTONE", milestoneId))
                .thenReturn(Optional.of(validReleaseDecision));

        setupComponentDecisions(validReleaseDecision);
        setupMockReEvaluation(DecisionStatus.ALLOW, EvaluationOutcome.SUCCESS, inputFingerprint, List.of(ReleasePolicyDefinition.REASON_ALL_GATES_ALLOW));
    }

    private EngineDecision createValidReleaseDecision() throws Exception {
        EngineDecision d = new EngineDecision();
        setField(d, "id", releaseDecisionId);
        setField(d, "organizationId", buyerOrgId);
        setField(d, "engineType", EngineType.RELEASE);
        setField(d, "subjectType", "MILESTONE");
        setField(d, "subjectId", milestoneId);
        setField(d, "decision", DecisionStatus.ALLOW);
        setField(d, "evaluationOutcome", EvaluationOutcome.SUCCESS);
        setField(d, "inputFingerprint", inputFingerprint);
        setField(d, "decisionFingerprint", decisionFingerprint);
        setField(d, "policyVersionId", policyVersionId);
        setField(d, "policyContentHash", ReleasePolicyDefinition.getExpectedContentHash());
        setField(d, "evaluationId", evaluationId);
        setField(d, "correlationId", correlationId);
        setField(d, "ruleVersion", ReleasePolicyDefinition.POLICY_KEY + ":" + ReleasePolicyDefinition.POLICY_VERSION);
        setField(d, "evaluatedAt", Instant.now().minusSeconds(10));
        setField(d, "validUntil", Instant.now().plusSeconds(3600));

        // Build evidence references JSON
        List<Map<String, Object>> refs = new ArrayList<>();
        refs.add(Map.of("sourceType", "ORGANIZATION_BANK_ACCOUNT", "sourceId", beneficiaryBankAccountId.toString(), "provenance", "MOCK"));
        for (EngineType reqType : ReleasePolicyDefinition.REQUIRED_ENGINES) {
            refs.add(Map.of("sourceType", "ENGINE_DECISION", "sourceId", UUID.randomUUID().toString(), "provenance", reqType.name()));
        }
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        setField(d, "evidenceReferencesJson", mapper.writeValueAsString(refs));

        return d;
    }

    private void setupComponentDecisions(EngineDecision releaseDecision) throws Exception {
        for (EvidenceReference ref : releaseDecision.getEvidenceReferences()) {
            if ("ENGINE_DECISION".equalsIgnoreCase(ref.sourceType())) {
                EngineDecision comp = new EngineDecision();
                setField(comp, "id", ref.sourceId());
                setField(comp, "organizationId", buyerOrgId);
                setField(comp, "engineType", EngineType.valueOf(ref.provenance()));
                setField(comp, "decision", DecisionStatus.ALLOW);
                setField(comp, "evaluationOutcome", EvaluationOutcome.SUCCESS);
                setField(comp, "evaluationId", evaluationId);
                setField(comp, "correlationId", correlationId);
                when(decisionRepo.findById(ref.sourceId())).thenReturn(Optional.of(comp));
            }
        }
    }

    private void setupMockReEvaluation(DecisionStatus decision, EvaluationOutcome outcome, String inFp, List<String> reasons) {
        RecordEngineDecisionCommand cmd = RecordEngineDecisionCommand.builder()
                .organizationId(buyerOrgId)
                .engineType(EngineType.RELEASE)
                .subjectType(DecisionSubjectType.MILESTONE)
                .subjectId(milestoneId)
                .decision(decision)
                .evaluationOutcome(outcome)
                .errorCode(outcome == EvaluationOutcome.ERROR ? "ERR_1" : null)
                .policyVersionId(policyVersionId)
                .policyContentHash(ReleasePolicyDefinition.getExpectedContentHash())
                .ruleVersion(ReleasePolicyDefinition.POLICY_KEY + ":" + ReleasePolicyDefinition.POLICY_VERSION)
                .inputFingerprint(inFp)
                .reasonCodes(reasons)
                .evaluationId(evaluationId)
                .correlationId(correlationId)
                .evaluatedAt(Instant.now())
                .build();

        ReleaseEvaluationResult res = new ReleaseEvaluationResult(
                evaluationId, correlationId, null, List.of(), cmd, List.of()
        );
        when(releaseDecisionEngine.evaluateMilestone(any(), any(), any(), any(), any(), any()))
                .thenReturn(res);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @DisplayName("1. Real stored RELEASE ALLOW accepted")
    void test01_validReleaseDecisionAccepted() {
        assertThatCode(() -> validator.validateForCreation(validReleaseDecision, deal, milestone, beneficiary))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateForExecution(releaseDecisionId, deal, milestone, beneficiaryBankAccountId))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("2. REVIEW rejected")
    void test02_reviewDecisionRejected() throws Exception {
        setField(validReleaseDecision, "decision", DecisionStatus.REVIEW);
        assertThatThrownBy(() -> validator.validateForCreation(validReleaseDecision, deal, milestone, beneficiary))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Expected decision ALLOW");
    }

    @Test
    @DisplayName("3. BLOCK rejected")
    void test03_blockDecisionRejected() throws Exception {
        setField(validReleaseDecision, "decision", DecisionStatus.BLOCK);
        assertThatThrownBy(() -> validator.validateForCreation(validReleaseDecision, deal, milestone, beneficiary))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Expected decision ALLOW");
    }

    @Test
    @DisplayName("4. ERROR rejected")
    void test04_errorOutcomeRejected() throws Exception {
        setField(validReleaseDecision, "evaluationOutcome", EvaluationOutcome.ERROR);
        assertThatThrownBy(() -> validator.validateForCreation(validReleaseDecision, deal, milestone, beneficiary))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Expected evaluationOutcome SUCCESS");
    }

    @Test
    @DisplayName("5. Wrong policy rejected")
    void test05_wrongPolicyRejected() throws Exception {
        setField(validPolicy, "contentHash", "c".repeat(64));
        assertThatThrownBy(() -> validator.validateForCreation(validReleaseDecision, deal, milestone, beneficiary))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Policy content hash mismatch");
    }

    @Test
    @DisplayName("6. Latest release mismatch rejected")
    void test06_latestReleaseMismatchRejected() {
        EngineDecision newerDecision = mock(EngineDecision.class);
        when(newerDecision.getId()).thenReturn(UUID.randomUUID());
        when(decisionRepo.findFirstByOrganizationIdAndEngineTypeAndSubjectTypeAndSubjectIdOrderByEvaluatedAtDesc(
                buyerOrgId, EngineType.RELEASE, "MILESTONE", milestoneId))
                .thenReturn(Optional.of(newerDecision));

        assertThatThrownBy(() -> validator.validateForCreation(validReleaseDecision, deal, milestone, beneficiary))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("newer release decision exists");
    }

    @Test
    @DisplayName("7. Deal changed after release -> input fingerprint mismatch")
    void test07_dealChangedInputFingerprintMismatch() {
        setupMockReEvaluation(DecisionStatus.ALLOW, EvaluationOutcome.SUCCESS, "c".repeat(64), List.of(ReleasePolicyDefinition.REASON_ALL_GATES_ALLOW));

        assertThatThrownBy(() -> validator.validateForCreation(validReleaseDecision, deal, milestone, beneficiary))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inputFingerprint mismatch");
    }

    @Test
    @DisplayName("8. Milestone changed -> mismatch")
    void test08_milestoneChangedMismatch() throws Exception {
        UUID otherMilestoneId = UUID.randomUUID();
        setField(milestone, "id", otherMilestoneId);

        assertThatThrownBy(() -> validator.validateForCreation(validReleaseDecision, deal, milestone, beneficiary))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SubjectId mismatch");
    }

    @Test
    @DisplayName("9. Durable approval binding stale -> mismatch")
    void test09_approvalBindingStaleMismatch() {
        // Stale binding causes in-memory re-evaluation to yield non-ALLOW (e.g. REVIEW)
        setupMockReEvaluation(DecisionStatus.REVIEW, EvaluationOutcome.SUCCESS, inputFingerprint, List.of("RELEASE_REVIEW_APPROVAL"));

        assertThatThrownBy(() -> validator.validateForCreation(validReleaseDecision, deal, milestone, beneficiary))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("yielded non-ALLOW");
    }

    @Test
    @DisplayName("10. Beneficiary changed -> mismatch")
    void test10_beneficiaryChangedMismatch() throws Exception {
        UUID newBankAccountId = UUID.randomUUID();
        OrganizationBankAccount newBeneficiary = new OrganizationBankAccount();
        setField(newBeneficiary, "id", newBankAccountId);

        assertThatThrownBy(() -> validator.validateForCreation(validReleaseDecision, deal, milestone, newBeneficiary))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Canonical beneficiary account changed");
    }

    @Test
    @DisplayName("11. Reconciliation evidence changed -> mismatch")
    void test11_reconciliationEvidenceChangedMismatch() {
        // Reconciliation evidence change causes re-evaluation reason to reflect mismatch
        setupMockReEvaluation(DecisionStatus.REVIEW, EvaluationOutcome.SUCCESS, inputFingerprint, List.of("RELEASE_REVIEW_RECONCILIATION"));

        assertThatThrownBy(() -> validator.validateForCreation(validReleaseDecision, deal, milestone, beneficiary))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("yielded non-ALLOW");
    }

    @Test
    @DisplayName("12. Component decision ref missing -> reject")
    void test12_componentDecisionRefMissingReject() throws Exception {
        // Only 7 component refs
        List<Map<String, Object>> refs = new ArrayList<>();
        refs.add(Map.of("sourceType", "ORGANIZATION_BANK_ACCOUNT", "sourceId", beneficiaryBankAccountId.toString(), "provenance", "MOCK"));
        for (int i = 0; i < 7; i++) {
            refs.add(Map.of("sourceType", "ENGINE_DECISION", "sourceId", UUID.randomUUID().toString(), "provenance", ReleasePolicyDefinition.REQUIRED_ENGINES.get(i).name()));
        }
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        setField(validReleaseDecision, "evidenceReferencesJson", mapper.writeValueAsString(refs));

        assertThatThrownBy(() -> validator.validateForCreation(validReleaseDecision, deal, milestone, beneficiary))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Expected exactly 8 component ENGINE_DECISION evidence references");
    }

    @Test
    @DisplayName("13. Component evalId mismatch -> reject")
    void test13_componentEvalIdMismatchReject() throws Exception {
        EvidenceReference firstCompRef = validReleaseDecision.getEvidenceReferences().stream()
                .filter(r -> "ENGINE_DECISION".equalsIgnoreCase(r.sourceType()))
                .findFirst().orElseThrow();

        EngineDecision comp = decisionRepo.findById(firstCompRef.sourceId()).orElseThrow();
        setField(comp, "evaluationId", UUID.randomUUID());

        assertThatThrownBy(() -> validator.validateForCreation(validReleaseDecision, deal, milestone, beneficiary))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Component evaluationId mismatch");
    }

    @Test
    @DisplayName("14. Component correlationId mismatch -> reject")
    void test14_componentCorrelationIdMismatchReject() throws Exception {
        EvidenceReference firstCompRef = validReleaseDecision.getEvidenceReferences().stream()
                .filter(r -> "ENGINE_DECISION".equalsIgnoreCase(r.sourceType()))
                .findFirst().orElseThrow();

        EngineDecision comp = decisionRepo.findById(firstCompRef.sourceId()).orElseThrow();
        setField(comp, "correlationId", UUID.randomUUID());

        assertThatThrownBy(() -> validator.validateForCreation(validReleaseDecision, deal, milestone, beneficiary))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Component correlationId mismatch");
    }

    @Test
    @DisplayName("15. Component non-ALLOW -> reject")
    void test15_componentNonAllowReject() throws Exception {
        EvidenceReference firstCompRef = validReleaseDecision.getEvidenceReferences().stream()
                .filter(r -> "ENGINE_DECISION".equalsIgnoreCase(r.sourceType()))
                .findFirst().orElseThrow();

        EngineDecision comp = decisionRepo.findById(firstCompRef.sourceId()).orElseThrow();
        setField(comp, "decision", DecisionStatus.REVIEW);

        assertThatThrownBy(() -> validator.validateForCreation(validReleaseDecision, deal, milestone, beneficiary))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Component decision must be ALLOW");
    }

    @Test
    @DisplayName("16. Re-evaluation is NOT persisted")
    void test16_reEvaluationNotPersisted() {
        validator.validateForCreation(validReleaseDecision, deal, milestone, beneficiary);

        verify(releaseDecisionEngine, times(1)).evaluateMilestone(any(), any(), any(), any(), any(), any());
        verify(releaseDecisionEngine, never()).evaluate(any());
        verify(decisionRepo, never()).save(any());
    }

    @Test
    @DisplayName("17. EngineDecision row count unchanged during validation (no saves)")
    void test17_engineDecisionRowCountUnchanged() {
        validator.validateForExecution(releaseDecisionId, deal, milestone, beneficiaryBankAccountId);

        verify(decisionRepo, never()).save(any());
        verify(decisionRepo, never()).saveAndFlush(any());
        verify(decisionRepo, never()).delete(any());
    }

    @Test
    @DisplayName("18. Bank calls = 0")
    void test18_zeroBankCalls() {
        validator.validateForCreation(validReleaseDecision, deal, milestone, beneficiary);
        validator.validateForExecution(releaseDecisionId, deal, milestone, beneficiaryBankAccountId);

        verifyNoInteractions(bankingProvider);
    }
}
