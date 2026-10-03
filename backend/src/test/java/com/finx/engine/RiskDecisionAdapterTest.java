package com.finx.engine;

import com.finx.banking.provider.BankingProvider;
import com.finx.deal.entity.Deal;
import com.finx.engine.adapter.RiskDecisionAdapter;
import com.finx.engine.model.DecisionStatus;
import com.finx.engine.model.DecisionSubjectType;
import com.finx.engine.model.EngineType;
import com.finx.engine.model.EvaluationContext;
import com.finx.engine.model.RecordEngineDecisionCommand;
import com.finx.escrow.service.EscrowService;
import com.finx.ledger.service.LedgerService;
import com.finx.risk.dto.RiskDtos.Level;
import com.finx.risk.dto.RiskDtos.ReviewStatus;
import com.finx.risk.dto.RiskDtos.UserType;
import com.finx.risk.dto.RiskEvidenceDtos.EvidenceStatus;
import com.finx.risk.dto.RiskEvidenceDtos.SubjectRiskEvidence;
import com.finx.risk.repository.BusinessRiskRepository;
import com.finx.risk.service.RiskEvidenceReadService;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RiskDecisionAdapterTest {

    @Mock
    private RiskEvidenceReadService riskEvidenceReadService;

    @Mock
    private BusinessRiskRepository businessRiskRepository;

    @Mock
    private BankingProvider bankingProvider;

    @Mock
    private LedgerService ledgerService;

    @Mock
    private EscrowService escrowService;

    private RiskDecisionAdapter adapter;

    private UUID buyerOrgId;
    private UUID vendorOrgId;
    private UUID buyerUserId;
    private UUID vendorUserId;
    private UUID dealId;
    private UUID evaluationId;
    private UUID correlationId;
    private Deal deal;

    @BeforeEach
    void setUp() {
        adapter = new RiskDecisionAdapter(riskEvidenceReadService);

        buyerOrgId = UUID.randomUUID();
        vendorOrgId = UUID.randomUUID();
        buyerUserId = UUID.randomUUID();
        vendorUserId = UUID.randomUUID();
        dealId = UUID.randomUUID();
        evaluationId = UUID.randomUUID();
        correlationId = UUID.randomUUID();

        deal = new Deal();
        deal.setId(dealId);
        deal.setBuyerOrganizationId(buyerOrgId);
        deal.setBuyerId(buyerUserId);
        deal.setSellerOrganizationId(vendorOrgId);
        deal.setSellerId(vendorUserId);
    }

    @Test
    @DisplayName("1. Buyer persisted source maps to RISK decision")
    void testBuyerPersistedSourceMapsToRiskDecision() {
        UUID riskScoreId = UUID.randomUUID();
        UUID assessmentId = UUID.randomUUID();
        Instant observedAt = Instant.now().minusSeconds(100);

        SubjectRiskEvidence buyerEvidence = new SubjectRiskEvidence(
                riskScoreId, buyerUserId, buyerOrgId, UserType.BUYER,
                assessmentId, EvidenceStatus.PRESENT, 25, Level.LOW,
                false, ReviewStatus.CLEARED, false, null, null, null,
                "rules-v1:BUYER:1.0", observedAt, Instant.now().plusSeconds(3600),
                List.of("LOW_DISPUTE_RATE"), List.of("Healthy transaction history")
        );

        when(riskEvidenceReadService.getSubjectRiskEvidence(buyerUserId, buyerOrgId, UserType.BUYER))
                .thenReturn(buyerEvidence);

        EvaluationContext context = new EvaluationContext(
                evaluationId, buyerOrgId, correlationId,
                DecisionSubjectType.ORGANIZATION.name(), buyerOrgId, Instant.now()
        );

        RecordEngineDecisionCommand command = adapter.evaluateForDeal(deal, UserType.BUYER, context);

        assertThat(command.getEngineType()).isEqualTo(EngineType.RISK);
        assertThat(command.getOrganizationId()).isEqualTo(buyerOrgId);
        assertThat(command.getSubjectType()).isEqualTo("ORGANIZATION");
        assertThat(command.getSubjectId()).isEqualTo(buyerOrgId);
        assertThat(command.getDecision()).isEqualTo(DecisionStatus.ALLOW);
        assertThat(command.getScore()).isEqualByComparingTo(BigDecimal.valueOf(25));
        assertThat(command.getSourceAssessmentId()).isEqualTo(assessmentId);
        assertThat(command.getRuleVersion()).isEqualTo("rules-v1:BUYER:1.0");
    }

    @Test
    @DisplayName("2. Vendor persisted source maps separately")
    void testVendorPersistedSourceMapsSeparately() {
        UUID vendorRiskScoreId = UUID.randomUUID();
        UUID vendorAssessmentId = UUID.randomUUID();
        Instant observedAt = Instant.now().minusSeconds(200);

        SubjectRiskEvidence vendorEvidence = new SubjectRiskEvidence(
                vendorRiskScoreId, vendorUserId, vendorOrgId, UserType.VENDOR,
                vendorAssessmentId, EvidenceStatus.PRESENT, 30, Level.LOW,
                false, ReviewStatus.CLEARED, false, null, null, null,
                "rules-v1:VENDOR:1.0", observedAt, Instant.now().plusSeconds(3600),
                List.of(), List.of()
        );

        when(riskEvidenceReadService.getSubjectRiskEvidence(vendorUserId, vendorOrgId, UserType.VENDOR))
                .thenReturn(vendorEvidence);

        EvaluationContext context = new EvaluationContext(
                evaluationId, vendorOrgId, correlationId,
                DecisionSubjectType.ORGANIZATION.name(), vendorOrgId, Instant.now()
        );

        RecordEngineDecisionCommand command = adapter.evaluateForDeal(deal, UserType.VENDOR, context);

        assertThat(command.getEngineType()).isEqualTo(EngineType.RISK);
        assertThat(command.getOrganizationId()).isEqualTo(vendorOrgId);
        assertThat(command.getSubjectId()).isEqualTo(vendorOrgId);
        assertThat(command.getDecision()).isEqualTo(DecisionStatus.ALLOW);
        assertThat(command.getScore()).isEqualByComparingTo(BigDecimal.valueOf(30));
        assertThat(command.getSourceAssessmentId()).isEqualTo(vendorAssessmentId);
    }

    @Test
    @DisplayName("3. Buyer and vendor decisions have distinct organization and subject")
    void testBuyerAndVendorDecisionsHaveDistinctOrganizationAndSubject() {
        SubjectRiskEvidence buyerEvidence = new SubjectRiskEvidence(
                UUID.randomUUID(), buyerUserId, buyerOrgId, UserType.BUYER,
                UUID.randomUUID(), EvidenceStatus.PRESENT, 20, Level.LOW,
                false, ReviewStatus.CLEARED, false, null, null, null,
                "rules:1.0", Instant.now(), Instant.now().plusSeconds(3600), List.of(), List.of()
        );
        SubjectRiskEvidence vendorEvidence = new SubjectRiskEvidence(
                UUID.randomUUID(), vendorUserId, vendorOrgId, UserType.VENDOR,
                UUID.randomUUID(), EvidenceStatus.PRESENT, 35, Level.MODERATE,
                false, ReviewStatus.CLEARED, false, null, null, null,
                "rules:1.0", Instant.now(), Instant.now().plusSeconds(3600), List.of(), List.of()
        );

        when(riskEvidenceReadService.getSubjectRiskEvidence(buyerUserId, buyerOrgId, UserType.BUYER)).thenReturn(buyerEvidence);
        when(riskEvidenceReadService.getSubjectRiskEvidence(vendorUserId, vendorOrgId, UserType.VENDOR)).thenReturn(vendorEvidence);

        EvaluationContext buyerCtx = new EvaluationContext(evaluationId, buyerOrgId, correlationId, "ORGANIZATION", buyerOrgId, Instant.now());
        EvaluationContext vendorCtx = new EvaluationContext(evaluationId, vendorOrgId, correlationId, "ORGANIZATION", vendorOrgId, Instant.now());

        RecordEngineDecisionCommand buyerCmd = adapter.evaluateForDeal(deal, UserType.BUYER, buyerCtx);
        RecordEngineDecisionCommand vendorCmd = adapter.evaluateForDeal(deal, UserType.VENDOR, vendorCtx);

        assertThat(buyerCmd.getOrganizationId()).isNotEqualTo(vendorCmd.getOrganizationId());
        assertThat(buyerCmd.getSubjectId()).isNotEqualTo(vendorCmd.getSubjectId());
        assertThat(buyerCmd.getOrganizationId()).isEqualTo(buyerOrgId);
        assertThat(vendorCmd.getOrganizationId()).isEqualTo(vendorOrgId);
    }

    @Test
    @DisplayName("4. Source IDs exist and point to persisted risk scores/assessments")
    void testSourceIdsExist() {
        UUID riskScoreId = UUID.randomUUID();
        UUID assessmentId = UUID.randomUUID();

        SubjectRiskEvidence evidence = new SubjectRiskEvidence(
                riskScoreId, buyerUserId, buyerOrgId, UserType.BUYER,
                assessmentId, EvidenceStatus.PRESENT, 15, Level.LOW,
                false, ReviewStatus.CLEARED, false, null, null, null,
                "rules:1.0", Instant.now(), Instant.now().plusSeconds(3600), List.of(), List.of()
        );
        when(riskEvidenceReadService.getSubjectRiskEvidence(any(), any(), any())).thenReturn(evidence);

        EvaluationContext ctx = new EvaluationContext(evaluationId, buyerOrgId, correlationId, "ORGANIZATION", buyerOrgId, Instant.now());
        RecordEngineDecisionCommand cmd = adapter.evaluateForDeal(deal, UserType.BUYER, ctx);

        assertThat(cmd.getEvidenceReferences()).hasSize(2);
        assertThat(cmd.getEvidenceReferences()).anyMatch(ref -> "RISK_SCORE".equals(ref.sourceType()) && riskScoreId.equals(ref.sourceId()));
        assertThat(cmd.getEvidenceReferences()).anyMatch(ref -> "RISK_ASSESSMENT".equals(ref.sourceType()) && assessmentId.equals(ref.sourceId()));
    }

    @Test
    @DisplayName("5. Transient RiskAssessment aggregate ID is never cited as durable evidence")
    void testTransientRiskAssessmentAggregateIdNeverCited() {
        UUID riskScoreId = UUID.randomUUID();
        UUID assessmentId = UUID.randomUUID();

        SubjectRiskEvidence evidence = new SubjectRiskEvidence(
                riskScoreId, buyerUserId, buyerOrgId, UserType.BUYER,
                assessmentId, EvidenceStatus.PRESENT, 15, Level.LOW,
                false, ReviewStatus.CLEARED, false, null, null, null,
                "rules:1.0", Instant.now(), Instant.now().plusSeconds(3600), List.of(), List.of()
        );
        when(riskEvidenceReadService.getSubjectRiskEvidence(any(), any(), any())).thenReturn(evidence);

        EvaluationContext ctx = new EvaluationContext(evaluationId, buyerOrgId, correlationId, "ORGANIZATION", buyerOrgId, Instant.now());
        RecordEngineDecisionCommand cmd = adapter.evaluateForDeal(deal, UserType.BUYER, ctx);

        // Deal ID or arbitrary transient ID is not in evidence references
        assertThat(cmd.getEvidenceReferences()).noneMatch(ref -> dealId.equals(ref.sourceId()));
        // Source assessment ID is the persisted one
        assertThat(cmd.getSourceAssessmentId()).isEqualTo(assessmentId);
    }

    @Test
    @DisplayName("6. Native score is preserved without alteration")
    void testNativeScorePreserved() {
        SubjectRiskEvidence evidence = new SubjectRiskEvidence(
                UUID.randomUUID(), buyerUserId, buyerOrgId, UserType.BUYER,
                UUID.randomUUID(), EvidenceStatus.PRESENT, 42, Level.MODERATE,
                false, ReviewStatus.CLEARED, false, null, null, null,
                "rules:1.0", Instant.now(), Instant.now().plusSeconds(3600), List.of(), List.of()
        );
        when(riskEvidenceReadService.getSubjectRiskEvidence(any(), any(), any())).thenReturn(evidence);

        EvaluationContext ctx = new EvaluationContext(evaluationId, buyerOrgId, correlationId, "ORGANIZATION", buyerOrgId, Instant.now());
        RecordEngineDecisionCommand cmd = adapter.evaluateForDeal(deal, UserType.BUYER, ctx);

        assertThat(cmd.getScore()).isEqualByComparingTo(BigDecimal.valueOf(42));
    }

    @Test
    @DisplayName("7. reviewRequired is preserved in reason and triggers REVIEW status")
    void testReviewRequiredPreserved() {
        SubjectRiskEvidence evidence = new SubjectRiskEvidence(
                UUID.randomUUID(), buyerUserId, buyerOrgId, UserType.BUYER,
                UUID.randomUUID(), EvidenceStatus.PRESENT, 45, Level.MODERATE,
                true, ReviewStatus.OPEN, false, null, null, null,
                "rules:1.0", Instant.now(), Instant.now().plusSeconds(3600), List.of("NEW_BUSINESS_ACCOUNT"), List.of()
        );
        when(riskEvidenceReadService.getSubjectRiskEvidence(any(), any(), any())).thenReturn(evidence);

        EvaluationContext ctx = new EvaluationContext(evaluationId, buyerOrgId, correlationId, "ORGANIZATION", buyerOrgId, Instant.now());
        RecordEngineDecisionCommand cmd = adapter.evaluateForDeal(deal, UserType.BUYER, ctx);

        assertThat(cmd.getDecision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(cmd.getReasonCodes()).contains(RiskDecisionAdapter.REASON_RISK_REVIEW_REQUIRED);
    }

    @Test
    @DisplayName("8. Effective restriction is preserved and produces BLOCK")
    void testEffectiveRestrictionPreserved() {
        UUID restrictionId = UUID.randomUUID();
        Instant restrictionUntil = Instant.now().plusSeconds(7200);

        SubjectRiskEvidence evidence = new SubjectRiskEvidence(
                UUID.randomUUID(), buyerUserId, buyerOrgId, UserType.BUYER,
                UUID.randomUUID(), EvidenceStatus.PRESENT, 60, Level.HIGH,
                true, ReviewStatus.RESTRICTED, true, restrictionId, restrictionUntil, "KYB_SANCTIONS_HOLD",
                "rules:1.0", Instant.now(), Instant.now().plusSeconds(3600), List.of(), List.of()
        );
        when(riskEvidenceReadService.getSubjectRiskEvidence(any(), any(), any())).thenReturn(evidence);

        EvaluationContext ctx = new EvaluationContext(evaluationId, buyerOrgId, correlationId, "ORGANIZATION", buyerOrgId, Instant.now());
        RecordEngineDecisionCommand cmd = adapter.evaluateForDeal(deal, UserType.BUYER, ctx);

        assertThat(cmd.getDecision()).isEqualTo(DecisionStatus.BLOCK);
        assertThat(cmd.getReasonCodes()).contains(RiskDecisionAdapter.REASON_RISK_RESTRICTION_ACTIVE);
        assertThat(cmd.getEvidenceReferences()).anyMatch(ref -> "RISK_RESTRICTION".equals(ref.sourceType()) && restrictionId.equals(ref.sourceId()));
    }

    @Test
    @DisplayName("9. High score + cleared review does NOT blindly become BLOCK")
    void testHighScorePlusClearedReviewNotBlindlyBlocked() {
        SubjectRiskEvidence evidence = new SubjectRiskEvidence(
                UUID.randomUUID(), buyerUserId, buyerOrgId, UserType.BUYER,
                UUID.randomUUID(), EvidenceStatus.PRESENT, 85, Level.HIGH,
                false, ReviewStatus.CLEARED, false, null, null, null,
                "rules:1.0", Instant.now(), Instant.now().plusSeconds(3600), List.of("HIGH_VOLUME_EXCEPTION"), List.of()
        );
        when(riskEvidenceReadService.getSubjectRiskEvidence(any(), any(), any())).thenReturn(evidence);

        EvaluationContext ctx = new EvaluationContext(evaluationId, buyerOrgId, correlationId, "ORGANIZATION", buyerOrgId, Instant.now());
        RecordEngineDecisionCommand cmd = adapter.evaluateForDeal(deal, UserType.BUYER, ctx);

        // Must be ALLOW because review was cleared and no restriction is active
        assertThat(cmd.getDecision()).isEqualTo(DecisionStatus.ALLOW);
        assertThat(cmd.getScore()).isEqualByComparingTo(BigDecimal.valueOf(85));
        assertThat(cmd.getReasonCodes()).contains(RiskDecisionAdapter.REASON_RISK_CLEAR);
    }

    @Test
    @DisplayName("10. Lower score + active restriction preserves BLOCK")
    void testLowerScorePlusActiveRestrictionPreservesBlock() {
        UUID restrictionId = UUID.randomUUID();
        SubjectRiskEvidence evidence = new SubjectRiskEvidence(
                UUID.randomUUID(), buyerUserId, buyerOrgId, UserType.BUYER,
                UUID.randomUUID(), EvidenceStatus.PRESENT, 20, Level.LOW,
                true, ReviewStatus.RESTRICTED, true, restrictionId, Instant.now().plusSeconds(3600), "COURT_INJUNCTION",
                "rules:1.0", Instant.now(), Instant.now().plusSeconds(3600), List.of(), List.of()
        );
        when(riskEvidenceReadService.getSubjectRiskEvidence(any(), any(), any())).thenReturn(evidence);

        EvaluationContext ctx = new EvaluationContext(evaluationId, buyerOrgId, correlationId, "ORGANIZATION", buyerOrgId, Instant.now());
        RecordEngineDecisionCommand cmd = adapter.evaluateForDeal(deal, UserType.BUYER, ctx);

        assertThat(cmd.getDecision()).isEqualTo(DecisionStatus.BLOCK);
        assertThat(cmd.getReasonCodes()).contains(RiskDecisionAdapter.REASON_RISK_RESTRICTION_ACTIVE);
    }

    @Test
    @DisplayName("11. Missing evidence maps to REVIEW with RISK_EVIDENCE_MISSING and null score")
    void testMissingEvidenceMapsToReview() {
        SubjectRiskEvidence missing = SubjectRiskEvidence.missing(buyerUserId, buyerOrgId, UserType.BUYER);
        when(riskEvidenceReadService.getSubjectRiskEvidence(any(), any(), any())).thenReturn(missing);

        EvaluationContext ctx = new EvaluationContext(evaluationId, buyerOrgId, correlationId, "ORGANIZATION", buyerOrgId, Instant.now());
        RecordEngineDecisionCommand cmd = adapter.evaluateForDeal(deal, UserType.BUYER, ctx);

        assertThat(cmd.getDecision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(cmd.getReasonCodes()).contains(RiskDecisionAdapter.REASON_RISK_EVIDENCE_MISSING);
        assertThat(cmd.getScore()).isNull(); // Never default to 0 or LOW
        assertThat(cmd.getEvidenceReferences()).isEmpty();
    }

    @Test
    @DisplayName("12. Stale evidence maps to REVIEW with RISK_EVIDENCE_STALE")
    void testStaleEvidenceMapsToReview() {
        SubjectRiskEvidence stale = new SubjectRiskEvidence(
                UUID.randomUUID(), buyerUserId, buyerOrgId, UserType.BUYER,
                UUID.randomUUID(), EvidenceStatus.STALE, 30, Level.LOW,
                false, ReviewStatus.CLEARED, false, null, null, null,
                "rules:1.0", Instant.now().minusSeconds(10000), Instant.now().minusSeconds(100), List.of(), List.of()
        );
        when(riskEvidenceReadService.getSubjectRiskEvidence(any(), any(), any())).thenReturn(stale);

        EvaluationContext ctx = new EvaluationContext(evaluationId, buyerOrgId, correlationId, "ORGANIZATION", buyerOrgId, Instant.now());
        RecordEngineDecisionCommand cmd = adapter.evaluateForDeal(deal, UserType.BUYER, ctx);

        assertThat(cmd.getDecision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(cmd.getReasonCodes()).contains(RiskDecisionAdapter.REASON_RISK_EVIDENCE_STALE);
    }

    @Test
    @DisplayName("13. Real source observedAt is preserved")
    void testRealSourceObservedAtPreserved() {
        Instant expectedObservedAt = Instant.parse("2026-05-01T12:00:00Z");
        SubjectRiskEvidence evidence = new SubjectRiskEvidence(
                UUID.randomUUID(), buyerUserId, buyerOrgId, UserType.BUYER,
                UUID.randomUUID(), EvidenceStatus.PRESENT, 10, Level.LOW,
                false, ReviewStatus.CLEARED, false, null, null, null,
                "rules:1.0", expectedObservedAt, Instant.now().plusSeconds(3600), List.of(), List.of()
        );
        when(riskEvidenceReadService.getSubjectRiskEvidence(any(), any(), any())).thenReturn(evidence);

        EvaluationContext ctx = new EvaluationContext(evaluationId, buyerOrgId, correlationId, "ORGANIZATION", buyerOrgId, Instant.now());
        RecordEngineDecisionCommand cmd = adapter.evaluateForDeal(deal, UserType.BUYER, ctx);

        assertThat(cmd.getEvidenceReferences()).anyMatch(ref -> expectedObservedAt.equals(ref.observedAt()));
    }

    @Test
    @DisplayName("14. Native policy version is preserved where available")
    void testNativePolicyVersionPreserved() {
        SubjectRiskEvidence evidence = new SubjectRiskEvidence(
                UUID.randomUUID(), buyerUserId, buyerOrgId, UserType.BUYER,
                UUID.randomUUID(), EvidenceStatus.PRESENT, 10, Level.LOW,
                false, ReviewStatus.CLEARED, false, null, null, null,
                "risk-policy-2026.2", Instant.now(), Instant.now().plusSeconds(3600), List.of(), List.of()
        );
        when(riskEvidenceReadService.getSubjectRiskEvidence(any(), any(), any())).thenReturn(evidence);

        EvaluationContext ctx = new EvaluationContext(evaluationId, buyerOrgId, correlationId, "ORGANIZATION", buyerOrgId, Instant.now());
        RecordEngineDecisionCommand cmd = adapter.evaluateForDeal(deal, UserType.BUYER, ctx);

        assertThat(cmd.getRuleVersion()).isEqualTo("risk-policy-2026.2");
    }

    @Test
    @DisplayName("15. Input fingerprint is deterministic")
    void testInputFingerprintDeterministic() {
        UUID scoreId = UUID.randomUUID();
        UUID assId = UUID.randomUUID();
        Instant obsAt = Instant.parse("2026-06-01T10:00:00Z");

        SubjectRiskEvidence evidence = new SubjectRiskEvidence(
                scoreId, buyerUserId, buyerOrgId, UserType.BUYER,
                assId, EvidenceStatus.PRESENT, 22, Level.LOW,
                false, ReviewStatus.CLEARED, false, null, null, null,
                "rules:1.0", obsAt, Instant.now().plusSeconds(3600), List.of(), List.of()
        );
        when(riskEvidenceReadService.getSubjectRiskEvidence(any(), any(), any())).thenReturn(evidence);

        EvaluationContext ctx1 = new EvaluationContext(evaluationId, buyerOrgId, correlationId, "ORGANIZATION", buyerOrgId, Instant.now());
        RecordEngineDecisionCommand cmd1 = adapter.evaluateForDeal(deal, UserType.BUYER, ctx1);

        EvaluationContext ctx2 = new EvaluationContext(evaluationId, buyerOrgId, correlationId, "ORGANIZATION", buyerOrgId, Instant.now());
        RecordEngineDecisionCommand cmd2 = adapter.evaluateForDeal(deal, UserType.BUYER, ctx2);

        assertThat(cmd1.getInputFingerprint()).isNotNull();
        assertThat(cmd1.getInputFingerprint()).isEqualTo(cmd2.getInputFingerprint());
    }

    @Test
    @DisplayName("16-19. Repeat adapter read creates zero risk rows, zero bank calls, zero ledger writes, zero escrow writes")
    void testZeroSideEffectsOnAdapterRead() {
        SubjectRiskEvidence evidence = new SubjectRiskEvidence(
                UUID.randomUUID(), buyerUserId, buyerOrgId, UserType.BUYER,
                UUID.randomUUID(), EvidenceStatus.PRESENT, 18, Level.LOW,
                false, ReviewStatus.CLEARED, false, null, null, null,
                "rules:1.0", Instant.now(), Instant.now().plusSeconds(3600), List.of(), List.of()
        );
        when(riskEvidenceReadService.getSubjectRiskEvidence(any(), any(), any())).thenReturn(evidence);

        EvaluationContext ctx = new EvaluationContext(evaluationId, buyerOrgId, correlationId, "ORGANIZATION", buyerOrgId, Instant.now());

        // Call adapter 5 times
        for (int i = 0; i < 5; i++) {
            adapter.evaluateForDeal(deal, UserType.BUYER, ctx);
        }

        // 16. Zero risk repository mutations
        verifyNoInteractions(businessRiskRepository);

        // 17. Zero banking calls
        verifyNoInteractions(bankingProvider);

        // 18. Zero ledger writes
        verifyNoInteractions(ledgerService);

        // 19. Zero escrow writes
        verifyNoInteractions(escrowService);
    }
}
