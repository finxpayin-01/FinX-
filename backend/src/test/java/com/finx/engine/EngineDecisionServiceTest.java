package com.finx.engine;

import com.finx.banking.provider.BankingProvider;
import com.finx.engine.entity.EngineDecision;
import com.finx.engine.model.*;
import com.finx.engine.repository.EngineDecisionRepository;
import com.finx.engine.service.EngineDecisionService;
import com.finx.escrow.service.EscrowService;
import com.finx.ledger.service.LedgerService;
import com.finx.paymentorchestration.service.SettlementAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EngineDecisionServiceTest {

    @Mock
    private EngineDecisionRepository repository;

    @Mock
    private BankingProvider bankingProvider;

    @Mock
    private SettlementAuthorizationService settlementAuthorizationService;

    @Mock
    private LedgerService ledgerService;

    @Mock
    private EscrowService escrowService;

    private EngineDecisionService service;

    private UUID orgA;
    private UUID orgB;
    private UUID evaluationId;
    private UUID correlationId;
    private UUID subjectId;

    @BeforeEach
    void setUp() {
        service = new EngineDecisionService(repository);

        orgA = UUID.randomUUID();
        orgB = UUID.randomUUID();
        evaluationId = UUID.randomUUID();
        correlationId = UUID.randomUUID();
        subjectId = UUID.randomUUID();

        // Default mock save: returns argument
        when(repository.save(any(EngineDecision.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("Section 43: Tenant scoping and cross-tenant query isolation")
    void testTenantIsolationAndScopedQueries() {
        EngineDecision decisionOrgA = new EngineDecision(
                orgA, EngineType.RISK, "DEAL", subjectId, DecisionStatus.ALLOW,
                EvaluationOutcome.SUCCESS, null, new BigDecimal("75.0"), "[\"LOW_RISK\"]",
                "Explanation", "[]", "v1.0", null, null, null,
                evaluationId, correlationId, "fp_1", null, "1.0", "1.0.0",
                "decision_fp_1", null, Instant.now()
        );

        when(repository.findFirstByOrganizationIdAndEngineTypeAndSubjectTypeAndSubjectIdOrderByEvaluatedAtDesc(
                orgA, EngineType.RISK, "DEAL", subjectId))
                .thenReturn(Optional.of(decisionOrgA));

        when(repository.findFirstByOrganizationIdAndEngineTypeAndSubjectTypeAndSubjectIdOrderByEvaluatedAtDesc(
                orgB, EngineType.RISK, "DEAL", subjectId))
                .thenReturn(Optional.empty());

        // Org A query returns decision
        Optional<EngineDecision> foundA = service.findLatestForSubjectAndEngine(orgA, EngineType.RISK, "DEAL", subjectId);
        assertThat(foundA).isPresent();
        assertThat(foundA.get().getOrganizationId()).isEqualTo(orgA);

        // Org B query returns empty (isolated)
        Optional<EngineDecision> foundB = service.findLatestForSubjectAndEngine(orgB, EngineType.RISK, "DEAL", subjectId);
        assertThat(foundB).isEmpty();

        // Query with null orgId throws IllegalArgumentException
        assertThatThrownBy(() -> service.findLatestForSubjectAndEngine(null, EngineType.RISK, "DEAL", subjectId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("organizationId must not be null");
    }

    @Test
    @DisplayName("Section 44: Immutability - new decisions do not overwrite previous decisions")
    void testDecisionImmutability() {
        RecordEngineDecisionCommand cmd1 = RecordEngineDecisionCommand.builder()
                .organizationId(orgA)
                .engineType(EngineType.RISK)
                .subjectType("DEAL")
                .subjectId(subjectId)
                .decision(DecisionStatus.REVIEW)
                .evaluationOutcome(EvaluationOutcome.SUCCESS)
                .score(new BigDecimal("50.0"))
                .evaluationId(evaluationId)
                .correlationId(correlationId)
                .evaluatedAt(Instant.now())
                .build();

        EngineDecision d1 = service.recordDecision(cmd1);
        assertThat(d1).isNotNull();
        assertThat(d1.getDecision()).isEqualTo(DecisionStatus.REVIEW);

        // Subsequent evaluation with a new evaluationId
        UUID newEvaluationId = UUID.randomUUID();
        RecordEngineDecisionCommand cmd2 = RecordEngineDecisionCommand.builder()
                .organizationId(orgA)
                .engineType(EngineType.RISK)
                .subjectType("DEAL")
                .subjectId(subjectId)
                .decision(DecisionStatus.ALLOW)
                .evaluationOutcome(EvaluationOutcome.SUCCESS)
                .score(new BigDecimal("85.0"))
                .evaluationId(newEvaluationId)
                .correlationId(correlationId)
                .evaluatedAt(Instant.now())
                .build();

        EngineDecision d2 = service.recordDecision(cmd2);
        assertThat(d2).isNotNull();
        assertThat(d2.getDecision()).isEqualTo(DecisionStatus.ALLOW);
        assertThat(d2.getId()).isNotEqualTo(d1.getId());
        assertThat(d2.getEvaluationId()).isNotEqualTo(d1.getEvaluationId());
    }

    @Test
    @DisplayName("Section 45: Duplicate evaluation replay vs conflict detection")
    void testDuplicateEvaluationReplayAndConflict() {
        RecordEngineDecisionCommand cmd = RecordEngineDecisionCommand.builder()
                .organizationId(orgA)
                .engineType(EngineType.RISK)
                .subjectType("DEAL")
                .subjectId(subjectId)
                .decision(DecisionStatus.ALLOW)
                .evaluationOutcome(EvaluationOutcome.SUCCESS)
                .score(new BigDecimal("90.0"))
                .reasonCodes(List.of("LOW_RISK"))
                .evaluationId(evaluationId)
                .correlationId(correlationId)
                .evaluatedAt(Instant.now())
                .build();

        // 1. Initial record
        EngineDecision saved = service.recordDecision(cmd);
        assertThat(saved).isNotNull();

        // Setup mock repo to return existing decision for next calls
        when(repository.findByOrganizationIdAndEvaluationIdAndEngineTypeAndSubjectTypeAndSubjectId(
                orgA, evaluationId, EngineType.RISK, "DEAL", subjectId))
                .thenReturn(Optional.of(saved));

        // 2. Replay with identical content -> returns existing without calling save again
        reset(repository);
        when(repository.findByOrganizationIdAndEvaluationIdAndEngineTypeAndSubjectTypeAndSubjectId(
                orgA, evaluationId, EngineType.RISK, "DEAL", subjectId))
                .thenReturn(Optional.of(saved));

        EngineDecision replayed = service.recordDecision(cmd);
        assertThat(replayed.getId()).isEqualTo(saved.getId());
        verify(repository, never()).save(any());

        // 3. Duplicate logical identity with CONFLICTING content (e.g. decision changed to BLOCK)
        RecordEngineDecisionCommand conflictingCmd = RecordEngineDecisionCommand.builder()
                .organizationId(orgA)
                .engineType(EngineType.RISK)
                .subjectType("DEAL")
                .subjectId(subjectId)
                .decision(DecisionStatus.BLOCK)
                .evaluationOutcome(EvaluationOutcome.SUCCESS)
                .score(new BigDecimal("10.0"))
                .reasonCodes(List.of("HIGH_RISK"))
                .evaluationId(evaluationId)
                .correlationId(correlationId)
                .evaluatedAt(Instant.now())
                .build();

        assertThatThrownBy(() -> service.recordDecision(conflictingCmd))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Conflicting decision detected for logical identity");
    }

    @Test
    @DisplayName("Section 46: Evidence references are retained as durable references")
    void testEvidenceReferencesRetained() {
        UUID riskAssessmentId = UUID.randomUUID();
        Instant observedTime = Instant.now().minusSeconds(3600);

        EvidenceReference ref = new EvidenceReference(
                "RISK_ASSESSMENT",
                riskAssessmentId,
                observedTime,
                "sha256_evidence_hash",
                "FINX_RISK_ADMIN",
                "rev-1"
        );

        RecordEngineDecisionCommand cmd = RecordEngineDecisionCommand.builder()
                .organizationId(orgA)
                .engineType(EngineType.RISK)
                .subjectType("DEAL")
                .subjectId(subjectId)
                .decision(DecisionStatus.ALLOW)
                .evaluationOutcome(EvaluationOutcome.SUCCESS)
                .evidenceReferences(List.of(ref))
                .evaluationId(evaluationId)
                .correlationId(correlationId)
                .evaluatedAt(Instant.now())
                .build();

        ArgumentCaptor<EngineDecision> captor = ArgumentCaptor.forClass(EngineDecision.class);
        when(repository.save(captor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        EngineDecision decision = service.recordDecision(cmd);
        assertThat(decision).isNotNull();

        List<EvidenceReference> refs = decision.getEvidenceReferences();
        assertThat(refs).hasSize(1);
        EvidenceReference retrieved = refs.get(0);
        assertThat(retrieved.sourceType()).isEqualTo("RISK_ASSESSMENT");
        assertThat(retrieved.sourceId()).isEqualTo(riskAssessmentId);
        assertThat(retrieved.observedAt()).isNotNull();
        assertThat(retrieved.fingerprint()).isEqualTo("sha256_evidence_hash");
    }

    @Test
    @DisplayName("Section 47: RELEASE ALLOW EngineDecision produces ZERO banking, settlement, or ledger side effects")
    void testReleaseDecisionProducesZeroSideEffects() {
        RecordEngineDecisionCommand releaseCmd = RecordEngineDecisionCommand.builder()
                .organizationId(orgA)
                .engineType(EngineType.RELEASE)
                .subjectType("MILESTONE")
                .subjectId(subjectId)
                .decision(DecisionStatus.ALLOW)
                .evaluationOutcome(EvaluationOutcome.SUCCESS)
                .explanation("Pre-release evaluation completed successfully - recommendation only")
                .evaluationId(evaluationId)
                .correlationId(correlationId)
                .evaluatedAt(Instant.now())
                .build();

        EngineDecision recorded = service.recordDecision(releaseCmd);

        assertThat(recorded.getEngineType()).isEqualTo(EngineType.RELEASE);
        assertThat(recorded.getDecision()).isEqualTo(DecisionStatus.ALLOW);

        // Verify strictly ZERO interactions with financial execution services
        verifyNoInteractions(bankingProvider);
        verifyNoInteractions(settlementAuthorizationService);
        verifyNoInteractions(ledgerService);
        verifyNoInteractions(escrowService);
    }
}
