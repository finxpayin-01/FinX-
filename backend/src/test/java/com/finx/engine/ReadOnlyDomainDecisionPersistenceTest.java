package com.finx.engine;

import com.finx.banking.provider.BankingProvider;
import com.finx.deal.entity.Deal;
import com.finx.deal.repository.DealRepository;
import com.finx.engine.adapter.DocumentDecisionAdapter;
import com.finx.engine.adapter.ReadOnlyDomainDecisionCoordinator;
import com.finx.engine.adapter.RiskDecisionAdapter;
import com.finx.engine.entity.EngineDecision;
import com.finx.engine.model.DecisionStatus;
import com.finx.engine.model.EngineType;
import com.finx.engine.repository.EngineDecisionRepository;
import com.finx.engine.service.EngineDecisionService;
import com.finx.escrow.service.EscrowService;
import com.finx.invoice.entity.Invoice;
import com.finx.invoice.entity.InvoiceAssessment;
import com.finx.invoice.entity.InvoiceReview;
import com.finx.invoice.enums.*;
import com.finx.invoice.repository.InvoiceAssessmentRepository;
import com.finx.invoice.repository.InvoiceRepository;
import com.finx.invoice.repository.InvoiceReviewRepository;
import com.finx.ledger.service.LedgerService;
import com.finx.paymentorchestration.service.SettlementAuthorizationService;
import com.finx.risk.dto.RiskDtos.Level;
import com.finx.risk.dto.RiskDtos.ReviewStatus;
import com.finx.risk.dto.RiskDtos.UserType;
import com.finx.risk.dto.RiskEvidenceDtos.EvidenceStatus;
import com.finx.risk.dto.RiskEvidenceDtos.SubjectRiskEvidence;
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
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReadOnlyDomainDecisionPersistenceTest {

    @Mock
    private EngineDecisionRepository decisionRepository;

    @Mock
    private DealRepository dealRepository;

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private InvoiceAssessmentRepository invoiceAssessmentRepository;

    @Mock
    private InvoiceReviewRepository invoiceReviewRepository;

    @Mock
    private RiskEvidenceReadService riskEvidenceReadService;

    @Mock
    private BankingProvider bankingProvider;

    @Mock
    private SettlementAuthorizationService settlementAuthorizationService;

    @Mock
    private LedgerService ledgerService;

    @Mock
    private EscrowService escrowService;

    private EngineDecisionService decisionService;
    private RiskDecisionAdapter riskAdapter;
    private DocumentDecisionAdapter documentAdapter;
    private ReadOnlyDomainDecisionCoordinator coordinator;

    private UUID buyerOrgId;
    private UUID vendorOrgId;
    private UUID buyerUserId;
    private UUID vendorUserId;
    private UUID dealId;
    private UUID milestoneId;
    private UUID invoiceId;
    private UUID evaluationId;
    private UUID correlationId;

    private Deal deal;
    private Invoice invoice;
    private List<EngineDecision> persistedStore;

    @BeforeEach
    void setUp() {
        persistedStore = new ArrayList<>();

        when(decisionRepository.save(any(EngineDecision.class))).thenAnswer(invocation -> {
            EngineDecision d = invocation.getArgument(0);
            persistedStore.add(d);
            return d;
        });

        when(decisionRepository.findByOrganizationIdAndEvaluationIdAndEngineTypeAndSubjectTypeAndSubjectId(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    UUID orgId = inv.getArgument(0);
                    UUID evalId = inv.getArgument(1);
                    EngineType type = inv.getArgument(2);
                    String subType = inv.getArgument(3);
                    UUID subId = inv.getArgument(4);
                    return persistedStore.stream()
                            .filter(d -> Objects.equals(d.getOrganizationId(), orgId)
                                    && Objects.equals(d.getEvaluationId(), evalId)
                                    && d.getEngineType() == type
                                    && Objects.equals(d.getSubjectType(), subType)
                                    && Objects.equals(d.getSubjectId(), subId))
                            .findFirst();
                });

        when(decisionRepository.findByOrganizationIdAndEvaluationIdOrderByEvaluatedAtAsc(any(), any())).thenAnswer(inv -> {
            UUID org = inv.getArgument(0);
            UUID eval = inv.getArgument(1);
            return persistedStore.stream()
                    .filter(d -> Objects.equals(d.getOrganizationId(), org) && Objects.equals(d.getEvaluationId(), eval))
                    .toList();
        });

        decisionService = new EngineDecisionService(decisionRepository);
        riskAdapter = new RiskDecisionAdapter(riskEvidenceReadService);
        documentAdapter = new DocumentDecisionAdapter(invoiceRepository, invoiceAssessmentRepository, invoiceReviewRepository);
        coordinator = new ReadOnlyDomainDecisionCoordinator(riskAdapter, documentAdapter, dealRepository, invoiceRepository, decisionService);

        buyerOrgId = UUID.randomUUID();
        vendorOrgId = UUID.randomUUID();
        buyerUserId = UUID.randomUUID();
        vendorUserId = UUID.randomUUID();
        dealId = UUID.randomUUID();
        milestoneId = UUID.randomUUID();
        invoiceId = UUID.randomUUID();
        evaluationId = UUID.randomUUID();
        correlationId = UUID.randomUUID();

        deal = new Deal();
        deal.setId(dealId);
        deal.setBuyerOrganizationId(buyerOrgId);
        deal.setBuyerId(buyerUserId);
        deal.setSellerOrganizationId(vendorOrgId);
        deal.setSellerId(vendorUserId);

        invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setDealId(dealId);
        invoice.setMilestoneId(milestoneId);
        invoice.setBuyerOrganizationId(buyerOrgId);
        invoice.setVendorOrganizationId(vendorOrgId);
        invoice.setStatus(InvoiceStatus.APPROVED);
        invoice.setExtractionStatus(ExtractionStatus.EXTRACTED);
        invoice.setMatchingStatus(MatchingStatus.STRONG_MATCH);
        invoice.setDuplicateConfidence(DuplicateConfidence.UNIQUE);
        invoice.setMatchScore(92);
        invoice.setDocumentHash("doc_hash_valid_777");
        invoice.setPolicyVersion("inv-v1");
        invoice.setCreatedAt(Instant.now().minusSeconds(600));
        invoice.setUpdatedAt(Instant.now().minusSeconds(100));

        when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(invoice));
        when(invoiceRepository.findFirstByMilestoneIdOrderByCreatedAtDesc(milestoneId)).thenReturn(Optional.of(invoice));
    }

    @Test
    @DisplayName("Persists Buyer Risk, Vendor Risk, and Document decisions under one evaluation cycle")
    void testCoordinatedDomainEvaluationAndPersistence() {
        // Buyer evidence
        UUID buyerRiskScoreId = UUID.randomUUID();
        UUID buyerAssId = UUID.randomUUID();
        SubjectRiskEvidence buyerEvidence = new SubjectRiskEvidence(
                buyerRiskScoreId, buyerUserId, buyerOrgId, UserType.BUYER,
                buyerAssId, EvidenceStatus.PRESENT, 15, Level.LOW,
                false, ReviewStatus.CLEARED, false, null, null, null,
                "rules:buyer:1.0", Instant.now().minusSeconds(300), Instant.now().plusSeconds(3600),
                List.of(), List.of()
        );
        when(riskEvidenceReadService.getSubjectRiskEvidence(buyerUserId, buyerOrgId, UserType.BUYER))
                .thenReturn(buyerEvidence);

        // Vendor evidence
        UUID vendorRiskScoreId = UUID.randomUUID();
        UUID vendorAssId = UUID.randomUUID();
        SubjectRiskEvidence vendorEvidence = new SubjectRiskEvidence(
                vendorRiskScoreId, vendorUserId, vendorOrgId, UserType.VENDOR,
                vendorAssId, EvidenceStatus.PRESENT, 25, Level.LOW,
                false, ReviewStatus.CLEARED, false, null, null, null,
                "rules:vendor:1.0", Instant.now().minusSeconds(300), Instant.now().plusSeconds(3600),
                List.of(), List.of()
        );
        when(riskEvidenceReadService.getSubjectRiskEvidence(vendorUserId, vendorOrgId, UserType.VENDOR))
                .thenReturn(vendorEvidence);

        // Invoice assessment & review
        InvoiceAssessment invoiceAssessment = new InvoiceAssessment();
        invoiceAssessment.id = UUID.randomUUID();
        invoiceAssessment.invoiceId = invoiceId;
        invoiceAssessment.fingerprint = "inv_assessment_fp_999";
        invoiceAssessment.createdAt = Instant.now().minusSeconds(200);

        InvoiceReview review = new InvoiceReview();
        review.setId(UUID.randomUUID());
        review.setStatus(InvoiceReviewStatus.APPROVED);
        review.setAssessmentId(invoiceAssessment.id);
        review.setReviewedAt(Instant.now());

        when(invoiceAssessmentRepository.findFirstByInvoiceIdOrderByCreatedAtDesc(invoiceId))
                .thenReturn(Optional.of(invoiceAssessment));
        when(invoiceReviewRepository.findFirstByInvoiceIdAndStatus(invoiceId, InvoiceReviewStatus.APPROVED))
                .thenReturn(Optional.of(review));

        // Execute coordinated evaluation and recording
        List<EngineDecision> recordedDecisions = coordinator.evaluateAndRecordDeal(dealId, invoiceId, evaluationId, correlationId);

        // 1. Assert exactly 3 EngineDecision records created
        assertThat(recordedDecisions).hasSize(3);

        EngineDecision buyerRiskDecision = recordedDecisions.stream()
                .filter(d -> d.getEngineType() == EngineType.RISK && d.getOrganizationId().equals(buyerOrgId))
                .findFirst().orElseThrow();

        EngineDecision vendorRiskDecision = recordedDecisions.stream()
                .filter(d -> d.getEngineType() == EngineType.RISK && d.getOrganizationId().equals(vendorOrgId))
                .findFirst().orElseThrow();

        EngineDecision documentDecision = recordedDecisions.stream()
                .filter(d -> d.getEngineType() == EngineType.DOCUMENT)
                .findFirst().orElseThrow();

        // 2. Assert shared evaluationId and correlationId
        assertThat(buyerRiskDecision.getEvaluationId()).isEqualTo(evaluationId);
        assertThat(vendorRiskDecision.getEvaluationId()).isEqualTo(evaluationId);
        assertThat(documentDecision.getEvaluationId()).isEqualTo(evaluationId);

        assertThat(buyerRiskDecision.getCorrelationId()).isEqualTo(correlationId);
        assertThat(vendorRiskDecision.getCorrelationId()).isEqualTo(correlationId);
        assertThat(documentDecision.getCorrelationId()).isEqualTo(correlationId);

        // 3. Assert distinct logical identities
        assertThat(buyerRiskDecision.getSubjectId()).isEqualTo(buyerOrgId);
        assertThat(buyerRiskDecision.getSubjectType()).isEqualTo("ORGANIZATION");

        assertThat(vendorRiskDecision.getSubjectId()).isEqualTo(vendorOrgId);
        assertThat(vendorRiskDecision.getSubjectType()).isEqualTo("ORGANIZATION");

        assertThat(documentDecision.getSubjectId()).isEqualTo(invoiceId);
        assertThat(documentDecision.getSubjectType()).isEqualTo("INVOICE");

        // 4. Assert decisions are ALLOW
        assertThat(buyerRiskDecision.getDecision()).isEqualTo(DecisionStatus.ALLOW);
        assertThat(vendorRiskDecision.getDecision()).isEqualTo(DecisionStatus.ALLOW);
        assertThat(documentDecision.getDecision()).isEqualTo(DecisionStatus.ALLOW);

        // 5. Assert RELEASE decision row count is strictly ZERO
        long releaseCount = persistedStore.stream()
                .filter(d -> d.getEngineType() == EngineType.RELEASE)
                .count();
        assertThat(releaseCount).isZero();

        // 6. Assert strictly zero financial side effects
        verifyNoInteractions(bankingProvider);
        verifyNoInteractions(settlementAuthorizationService);
        verifyNoInteractions(ledgerService);
        verifyNoInteractions(escrowService);
    }

    @Test
    @DisplayName("Deterministic replay returns existing record without duplicate key error")
    void testDeterministicReplay() {
        // Setup buyer evidence
        SubjectRiskEvidence buyerEvidence = new SubjectRiskEvidence(
                UUID.randomUUID(), buyerUserId, buyerOrgId, UserType.BUYER,
                UUID.randomUUID(), EvidenceStatus.PRESENT, 10, Level.LOW,
                false, ReviewStatus.CLEARED, false, null, null, null,
                "rules:1.0", Instant.now(), Instant.now().plusSeconds(3600), List.of(), List.of()
        );
        when(riskEvidenceReadService.getSubjectRiskEvidence(buyerUserId, buyerOrgId, UserType.BUYER)).thenReturn(buyerEvidence);

        // First execution
        List<EngineDecision> firstRun = coordinator.evaluateAndRecordDeal(dealId, null, evaluationId, correlationId);
        int storeSizeAfterFirst = persistedStore.size();

        // Second execution under same evaluationId and unchanged data
        List<EngineDecision> secondRun = coordinator.evaluateAndRecordDeal(dealId, null, evaluationId, correlationId);

        // Store size should remain unchanged (replayed, not duplicated)
        assertThat(persistedStore.size()).isEqualTo(storeSizeAfterFirst);
        assertThat(secondRun.getFirst().getId()).isEqualTo(firstRun.getFirst().getId());
    }

    @Test
    @DisplayName("Tenant isolation: Org A cannot read Org B decisions")
    void testTenantIsolation() {
        UUID orgC = UUID.randomUUID();

        // Setup buyer evidence
        SubjectRiskEvidence buyerEvidence = new SubjectRiskEvidence(
                UUID.randomUUID(), buyerUserId, buyerOrgId, UserType.BUYER,
                UUID.randomUUID(), EvidenceStatus.PRESENT, 10, Level.LOW,
                false, ReviewStatus.CLEARED, false, null, null, null,
                "rules:1.0", Instant.now(), Instant.now().plusSeconds(3600), List.of(), List.of()
        );
        when(riskEvidenceReadService.getSubjectRiskEvidence(buyerUserId, buyerOrgId, UserType.BUYER)).thenReturn(buyerEvidence);

        coordinator.evaluateAndRecordDeal(dealId, null, evaluationId, correlationId);

        // Scoped query by buyerOrgId finds records
        List<EngineDecision> buyerDecisions = decisionService.findByEvaluation(buyerOrgId, evaluationId);
        assertThat(buyerDecisions).isNotEmpty();
        assertThat(buyerDecisions).allMatch(d -> d.getOrganizationId().equals(buyerOrgId));

        // Scoped query by unrelated orgC finds ZERO records
        List<EngineDecision> orgCDecisions = decisionService.findByEvaluation(orgC, evaluationId);
        assertThat(orgCDecisions).isEmpty();
    }
}
