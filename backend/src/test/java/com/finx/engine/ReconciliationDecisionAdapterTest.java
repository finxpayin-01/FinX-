package com.finx.engine;

import com.finx.banking.provider.BankingProvider;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.deal.service.DealAccessService;
import com.finx.engine.adapter.ReconciliationDecisionAdapter;
import com.finx.engine.adapter.ReconciliationDecisionEvidenceReader;
import com.finx.engine.model.*;
import com.finx.exception.UnauthorizedException;
import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.paymentorchestration.entity.PaymentInstruction;
import com.finx.paymentorchestration.entity.PaymentTransaction;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.InstructionStatus;
import com.finx.paymentorchestration.repository.PaymentInstructionRepository;
import com.finx.paymentorchestration.repository.PaymentTransactionRepository;
import com.finx.reconciliation.entity.BankStatementImport;
import com.finx.reconciliation.entity.BankStatementLine;
import com.finx.reconciliation.entity.ReconciliationItem;
import com.finx.reconciliation.entity.ReconciliationRun;
import com.finx.reconciliation.enums.BankStatementImportStatus;
import com.finx.reconciliation.enums.BankStatementLineStatus;
import com.finx.reconciliation.enums.FinancialDirection;
import com.finx.reconciliation.enums.ReconciliationEffectType;
import com.finx.reconciliation.enums.ReconciliationEnums.ReconciliationItemStatus;
import com.finx.reconciliation.enums.ReconciliationEnums.ReconciliationRunStatus;
import com.finx.reconciliation.enums.ReconciliationEnums.ResolutionStatus;
import com.finx.reconciliation.enums.ReconciliationReasonCode;
import com.finx.reconciliation.repository.BankStatementImportRepository;
import com.finx.reconciliation.repository.BankStatementLineRepository;
import com.finx.reconciliation.repository.ReconciliationItemRepository;
import com.finx.reconciliation.repository.ReconciliationRunRepository;
import com.finx.reconciliation.service.DeterministicReconciliationMatcher;
import com.finx.security.service.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReconciliationDecisionAdapterTest {

    @Mock private DealRepository dealRepository;
    @Mock private MilestoneRepository milestoneRepository;
    @Mock private DealAccessService dealAccessService;
    @Mock private PaymentInstructionRepository paymentInstructionRepository;
    @Mock private PaymentTransactionRepository paymentTransactionRepository;
    @Mock private ReconciliationRunRepository reconciliationRunRepository;
    @Mock private ReconciliationItemRepository reconciliationItemRepository;
    @Mock private BankStatementImportRepository bankStatementImportRepository;
    @Mock private BankStatementLineRepository bankStatementLineRepository;
    @Mock private BankingProvider bankingProvider;

    private ReconciliationDecisionEvidenceReader reader;
    private ReconciliationDecisionAdapter adapter;

    private UUID buyerOrgId;
    private UUID sellerOrgId;
    private UUID dealId;
    private UUID milestoneId;
    private Deal deal;
    private Milestone milestone;
    private EvaluationContext context;

    @BeforeEach
    void setUp() {
        buyerOrgId = UUID.randomUUID();
        sellerOrgId = UUID.randomUUID();
        dealId = UUID.randomUUID();
        milestoneId = UUID.randomUUID();

        deal = new Deal();
        deal.setId(dealId);
        deal.setBuyerOrganizationId(buyerOrgId);
        deal.setSellerOrganizationId(sellerOrgId);
        deal.setStatus(DealStatus.IN_PROGRESS);

        milestone = new Milestone();
        milestone.setId(milestoneId);
        milestone.setDealId(dealId);
        milestone.setAmount(new BigDecimal("10000.00"));
        milestone.setCurrency("INR");
        milestone.setStatus(MilestoneStatus.COMPLETED);

        reader = new ReconciliationDecisionEvidenceReader(
                dealRepository,
                milestoneRepository,
                dealAccessService,
                paymentInstructionRepository,
                paymentTransactionRepository,
                reconciliationRunRepository,
                reconciliationItemRepository,
                bankStatementImportRepository,
                bankStatementLineRepository
        );

        adapter = new ReconciliationDecisionAdapter(reader, reconciliationItemRepository);

        context = new EvaluationContext(
                UUID.randomUUID(),
                buyerOrgId,
                UUID.randomUUID(),
                DecisionSubjectType.MILESTONE.name(),
                milestoneId,
                Instant.now()
        );

        lenient().when(milestoneRepository.findById(milestoneId)).thenReturn(Optional.of(milestone));
        lenient().when(dealRepository.findById(dealId)).thenReturn(Optional.of(deal));
    }

    private void mockCleanMatchedFunding() {
        PaymentInstruction pi = new PaymentInstruction();
        pi.setId(UUID.randomUUID());
        pi.setDealId(dealId);
        pi.setMilestoneId(milestoneId);
        pi.setBuyerOrganizationId(buyerOrgId);
        pi.setAmount(new BigDecimal("10000.00"));
        pi.setCurrency("INR");
        pi.setStatus(InstructionStatus.SUCCESS);
        pi.setProvider("BANK");

        PaymentTransaction pt = new PaymentTransaction();
        pt.setId(UUID.randomUUID());
        pt.setPaymentInstructionId(pi.getId());
        pt.setStatus("SUCCESS");
        pt.setAmount(new BigDecimal("10000.00"));
        pt.setCurrency("INR");
        pt.setCreatedAt(Instant.now().minusSeconds(100));

        UUID runId = UUID.randomUUID();
        UUID importId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        String sourceHash = "hash123";

        ReconciliationRun run = new ReconciliationRun();
        run.setId(runId);
        run.setStatus(ReconciliationRunStatus.COMPLETED);
        run.setMatcherVersion(DeterministicReconciliationMatcher.MATCHER_VERSION);
        run.setStatementSourceHash(sourceHash);
        run.setBankStatementImportId(importId);
        run.setEndTime(Instant.now());
        run.setMatchedCount(1);
        run.setMismatchCount(0);

        BankStatementImport imp = new BankStatementImport();
        imp.setId(importId);
        imp.setSourceHash(sourceHash);
        imp.setStatus(BankStatementImportStatus.COMPLETED);

        BankStatementLine line = new BankStatementLine();
        line.setId(lineId);
        line.setNormalizedStatus(BankStatementLineStatus.FINAL_SUCCESS);

        ReconciliationItem item = new ReconciliationItem();
        item.setId(UUID.randomUUID());
        item.setReconciliationRunId(runId);
        item.setInternalEffectType(ReconciliationEffectType.PAYMENT_TRANSACTION);
        item.setInternalEffectId(pt.getId());
        item.setStatus(ReconciliationItemStatus.MATCHED);
        item.setReasonCode(ReconciliationReasonCode.MATCHED_CONFIRMED);
        item.setBankStatementLineId(lineId);

        when(paymentInstructionRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(pi));
        when(paymentTransactionRepository.findByPaymentInstructionId(pi.getId())).thenReturn(List.of(pt));
        when(reconciliationItemRepository.findByInternalEffectTypeAndInternalEffectId(
                ReconciliationEffectType.PAYMENT_TRANSACTION, pt.getId())).thenReturn(List.of(item));
        when(reconciliationRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(bankStatementImportRepository.findById(importId)).thenReturn(Optional.of(imp));
        when(bankStatementLineRepository.findById(lineId)).thenReturn(Optional.of(line));
        when(reconciliationItemRepository.findByReconciliationRunId(runId)).thenReturn(List.of(item));
    }

    @Test
    @DisplayName("1. engineType must be RECONCILIATION")
    void engineTypeReconciliation() {
        assertThat(adapter.type()).isEqualTo(EngineType.RECONCILIATION);
    }

    @Test
    @DisplayName("2. Subject is MILESTONE and owner is buyer organization")
    void subjectAndOwner() {
        mockCleanMatchedFunding();
        RecordEngineDecisionCommand cmd = adapter.evaluate(context);
        assertThat(cmd.subjectType()).isEqualTo("MILESTONE");
        assertThat(cmd.subjectId()).isEqualTo(milestoneId);
        assertThat(cmd.organizationId()).isEqualTo(buyerOrgId);
    }

    @Test
    @DisplayName("3. Clean matched funding yields ALLOW with RECONCILIATION_CONFIRMED")
    void cleanMatchedFundingYieldsAllow() {
        mockCleanMatchedFunding();
        RecordEngineDecisionCommand cmd = adapter.evaluate(context);
        assertThat(cmd.decision()).isEqualTo(DecisionStatus.ALLOW);
        assertThat(cmd.reasonCodes()).containsExactly(ReconciliationDecisionAdapter.RECONCILIATION_CONFIRMED);
        assertThat(cmd.score()).isNull();
    }

    @Test
    @DisplayName("4. No funding payment transactions yields REVIEW (RECONCILIATION_FUNDING_NOT_PROVEN)")
    void noFundingYieldsReview() {
        when(paymentInstructionRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(Collections.emptyList());
        RecordEngineDecisionCommand cmd = adapter.evaluate(context);
        assertThat(cmd.decision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(cmd.reasonCodes()).contains(ReconciliationDecisionAdapter.RECONCILIATION_FUNDING_NOT_PROVEN);
    }

    @Test
    @DisplayName("5. No R3 reconciliation run found yields REVIEW (RECONCILIATION_NO_AUTHORITATIVE_RUN)")
    void noRunYieldsReview() {
        PaymentInstruction pi = new PaymentInstruction();
        pi.setId(UUID.randomUUID());
        pi.setDealId(dealId);
        pi.setMilestoneId(milestoneId);
        pi.setBuyerOrganizationId(buyerOrgId);

        PaymentTransaction pt = new PaymentTransaction();
        pt.setId(UUID.randomUUID());
        pt.setPaymentInstructionId(pi.getId());
        pt.setStatus("SUCCESS");

        when(paymentInstructionRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(pi));
        when(paymentTransactionRepository.findByPaymentInstructionId(pi.getId())).thenReturn(List.of(pt));
        when(reconciliationItemRepository.findByInternalEffectTypeAndInternalEffectId(
                ReconciliationEffectType.PAYMENT_TRANSACTION, pt.getId())).thenReturn(Collections.emptyList());

        RecordEngineDecisionCommand cmd = adapter.evaluate(context);
        assertThat(cmd.decision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(cmd.reasonCodes()).contains(ReconciliationDecisionAdapter.RECONCILIATION_NO_AUTHORITATIVE_RUN);
    }

    @Test
    @DisplayName("6. Legacy run without matcherVersion yields REVIEW (RECONCILIATION_LEGACY_RUN_NONAUTHORITATIVE)")
    void legacyRunYieldsReview() {
        PaymentInstruction pi = new PaymentInstruction();
        pi.setId(UUID.randomUUID());
        pi.setDealId(dealId);
        pi.setMilestoneId(milestoneId);
        pi.setBuyerOrganizationId(buyerOrgId);

        PaymentTransaction pt = new PaymentTransaction();
        pt.setId(UUID.randomUUID());
        pt.setPaymentInstructionId(pi.getId());
        pt.setStatus("SUCCESS");

        UUID runId = UUID.randomUUID();
        ReconciliationRun run = new ReconciliationRun();
        run.setId(runId);
        run.setMatcherVersion(null); // legacy run

        ReconciliationItem item = new ReconciliationItem();
        item.setId(UUID.randomUUID());
        item.setReconciliationRunId(runId);

        when(paymentInstructionRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(pi));
        when(paymentTransactionRepository.findByPaymentInstructionId(pi.getId())).thenReturn(List.of(pt));
        when(reconciliationItemRepository.findByInternalEffectTypeAndInternalEffectId(
                ReconciliationEffectType.PAYMENT_TRANSACTION, pt.getId())).thenReturn(List.of(item));
        when(reconciliationRunRepository.findById(runId)).thenReturn(Optional.of(run));

        RecordEngineDecisionCommand cmd = adapter.evaluate(context);
        assertThat(cmd.decision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(cmd.reasonCodes()).contains(ReconciliationDecisionAdapter.RECONCILIATION_LEGACY_RUN_NONAUTHORITATIVE);
    }

    @Test
    @DisplayName("7. Source hash mismatch yields REVIEW (RECONCILIATION_SOURCE_SNAPSHOT_MISMATCH)")
    void sourceHashMismatchYieldsReview() {
        PaymentInstruction pi = new PaymentInstruction();
        pi.setId(UUID.randomUUID());
        pi.setDealId(dealId);
        pi.setMilestoneId(milestoneId);
        pi.setBuyerOrganizationId(buyerOrgId);

        PaymentTransaction pt = new PaymentTransaction();
        pt.setId(UUID.randomUUID());
        pt.setPaymentInstructionId(pi.getId());
        pt.setStatus("SUCCESS");
        pt.setCreatedAt(Instant.now().minusSeconds(100));

        UUID runId = UUID.randomUUID();
        UUID importId = UUID.randomUUID();

        ReconciliationRun run = new ReconciliationRun();
        run.setId(runId);
        run.setStatus(ReconciliationRunStatus.COMPLETED);
        run.setMatcherVersion(DeterministicReconciliationMatcher.MATCHER_VERSION);
        run.setStatementSourceHash("runHash");
        run.setBankStatementImportId(importId);
        run.setEndTime(Instant.now());

        BankStatementImport imp = new BankStatementImport();
        imp.setId(importId);
        imp.setSourceHash("differentHash"); // mismatch

        ReconciliationItem item = new ReconciliationItem();
        item.setId(UUID.randomUUID());
        item.setReconciliationRunId(runId);

        when(paymentInstructionRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(pi));
        when(paymentTransactionRepository.findByPaymentInstructionId(pi.getId())).thenReturn(List.of(pt));
        when(reconciliationItemRepository.findByInternalEffectTypeAndInternalEffectId(
                ReconciliationEffectType.PAYMENT_TRANSACTION, pt.getId())).thenReturn(List.of(item));
        when(reconciliationRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(bankStatementImportRepository.findById(importId)).thenReturn(Optional.of(imp));

        RecordEngineDecisionCommand cmd = adapter.evaluate(context);
        assertThat(cmd.decision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(cmd.reasonCodes()).contains(ReconciliationDecisionAdapter.RECONCILIATION_SOURCE_SNAPSHOT_MISMATCH);
    }

    @Test
    @DisplayName("8. Stale run ending before transaction created yields REVIEW (RECONCILIATION_STALE)")
    void staleRunYieldsReview() {
        PaymentInstruction pi = new PaymentInstruction();
        pi.setId(UUID.randomUUID());
        pi.setDealId(dealId);
        pi.setMilestoneId(milestoneId);
        pi.setBuyerOrganizationId(buyerOrgId);

        PaymentTransaction pt = new PaymentTransaction();
        pt.setId(UUID.randomUUID());
        pt.setPaymentInstructionId(pi.getId());
        pt.setStatus("SUCCESS");
        pt.setCreatedAt(Instant.now());

        UUID runId = UUID.randomUUID();
        UUID importId = UUID.randomUUID();
        String sourceHash = "hash123";

        ReconciliationRun run = new ReconciliationRun();
        run.setId(runId);
        run.setStatus(ReconciliationRunStatus.COMPLETED);
        run.setMatcherVersion(DeterministicReconciliationMatcher.MATCHER_VERSION);
        run.setStatementSourceHash(sourceHash);
        run.setBankStatementImportId(importId);
        run.setEndTime(Instant.now().minusSeconds(100)); // stale: ended before pt created

        BankStatementImport imp = new BankStatementImport();
        imp.setId(importId);
        imp.setSourceHash(sourceHash);

        ReconciliationItem item = new ReconciliationItem();
        item.setId(UUID.randomUUID());
        item.setReconciliationRunId(runId);

        when(paymentInstructionRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(pi));
        when(paymentTransactionRepository.findByPaymentInstructionId(pi.getId())).thenReturn(List.of(pt));
        when(reconciliationItemRepository.findByInternalEffectTypeAndInternalEffectId(
                ReconciliationEffectType.PAYMENT_TRANSACTION, pt.getId())).thenReturn(List.of(item));
        when(reconciliationRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(bankStatementImportRepository.findById(importId)).thenReturn(Optional.of(imp));

        RecordEngineDecisionCommand cmd = adapter.evaluate(context);
        assertThat(cmd.decision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(cmd.reasonCodes()).contains(ReconciliationDecisionAdapter.RECONCILIATION_STALE);
    }

    @Test
    @DisplayName("9. Amount mismatch yields BLOCK (RECONCILIATION_AMOUNT_MISMATCH)")
    void amountMismatchYieldsBlock() {
        PaymentInstruction pi = new PaymentInstruction();
        pi.setId(UUID.randomUUID());
        pi.setDealId(dealId);
        pi.setMilestoneId(milestoneId);
        pi.setBuyerOrganizationId(buyerOrgId);

        PaymentTransaction pt = new PaymentTransaction();
        pt.setId(UUID.randomUUID());
        pt.setPaymentInstructionId(pi.getId());
        pt.setStatus("SUCCESS");
        pt.setCreatedAt(Instant.now().minusSeconds(100));

        UUID runId = UUID.randomUUID();
        ReconciliationItem item = new ReconciliationItem();
        item.setId(UUID.randomUUID());
        item.setReconciliationRunId(runId);
        item.setStatus(ReconciliationItemStatus.AMOUNT_MISMATCH);
        item.setReasonCode(ReconciliationReasonCode.AMOUNT_MISMATCH);

        when(paymentInstructionRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(pi));
        when(paymentTransactionRepository.findByPaymentInstructionId(pi.getId())).thenReturn(List.of(pt));
        when(reconciliationItemRepository.findByInternalEffectTypeAndInternalEffectId(
                ReconciliationEffectType.PAYMENT_TRANSACTION, pt.getId())).thenReturn(List.of(item));

        RecordEngineDecisionCommand cmd = adapter.evaluate(context);
        assertThat(cmd.decision()).isEqualTo(DecisionStatus.BLOCK);
        assertThat(cmd.reasonCodes()).contains(ReconciliationDecisionAdapter.RECONCILIATION_AMOUNT_MISMATCH);
    }

    @Test
    @DisplayName("10. Currency mismatch yields BLOCK (RECONCILIATION_CURRENCY_MISMATCH)")
    void currencyMismatchYieldsBlock() {
        PaymentInstruction pi = new PaymentInstruction();
        pi.setId(UUID.randomUUID());
        pi.setDealId(dealId);
        pi.setMilestoneId(milestoneId);
        pi.setBuyerOrganizationId(buyerOrgId);

        PaymentTransaction pt = new PaymentTransaction();
        pt.setId(UUID.randomUUID());
        pt.setPaymentInstructionId(pi.getId());
        pt.setStatus("SUCCESS");
        pt.setCreatedAt(Instant.now().minusSeconds(100));

        UUID runId = UUID.randomUUID();
        ReconciliationItem item = new ReconciliationItem();
        item.setId(UUID.randomUUID());
        item.setReconciliationRunId(runId);
        item.setReasonCode(ReconciliationReasonCode.CURRENCY_MISMATCH);

        when(paymentInstructionRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(pi));
        when(paymentTransactionRepository.findByPaymentInstructionId(pi.getId())).thenReturn(List.of(pt));
        when(reconciliationItemRepository.findByInternalEffectTypeAndInternalEffectId(
                ReconciliationEffectType.PAYMENT_TRANSACTION, pt.getId())).thenReturn(List.of(item));

        RecordEngineDecisionCommand cmd = adapter.evaluate(context);
        assertThat(cmd.decision()).isEqualTo(DecisionStatus.BLOCK);
        assertThat(cmd.reasonCodes()).contains(ReconciliationDecisionAdapter.RECONCILIATION_CURRENCY_MISMATCH);
    }

    @Test
    @DisplayName("11. Direction mismatch yields BLOCK (RECONCILIATION_DIRECTION_MISMATCH)")
    void directionMismatchYieldsBlock() {
        PaymentInstruction pi = new PaymentInstruction();
        pi.setId(UUID.randomUUID());
        pi.setDealId(dealId);
        pi.setMilestoneId(milestoneId);
        pi.setBuyerOrganizationId(buyerOrgId);

        PaymentTransaction pt = new PaymentTransaction();
        pt.setId(UUID.randomUUID());
        pt.setPaymentInstructionId(pi.getId());
        pt.setStatus("SUCCESS");
        pt.setCreatedAt(Instant.now().minusSeconds(100));

        UUID runId = UUID.randomUUID();
        ReconciliationItem item = new ReconciliationItem();
        item.setId(UUID.randomUUID());
        item.setReconciliationRunId(runId);
        item.setReasonCode(ReconciliationReasonCode.DIRECTION_MISMATCH);

        when(paymentInstructionRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(pi));
        when(paymentTransactionRepository.findByPaymentInstructionId(pi.getId())).thenReturn(List.of(pt));
        when(reconciliationItemRepository.findByInternalEffectTypeAndInternalEffectId(
                ReconciliationEffectType.PAYMENT_TRANSACTION, pt.getId())).thenReturn(List.of(item));

        RecordEngineDecisionCommand cmd = adapter.evaluate(context);
        assertThat(cmd.decision()).isEqualTo(DecisionStatus.BLOCK);
        assertThat(cmd.reasonCodes()).contains(ReconciliationDecisionAdapter.RECONCILIATION_DIRECTION_MISMATCH);
    }

    @Test
    @DisplayName("12. Bank finality failed yields BLOCK (RECONCILIATION_BANK_FINALITY_FAILED)")
    void bankFinalityFailedYieldsBlock() {
        PaymentInstruction pi = new PaymentInstruction();
        pi.setId(UUID.randomUUID());
        pi.setDealId(dealId);
        pi.setMilestoneId(milestoneId);
        pi.setBuyerOrganizationId(buyerOrgId);

        PaymentTransaction pt = new PaymentTransaction();
        pt.setId(UUID.randomUUID());
        pt.setPaymentInstructionId(pi.getId());
        pt.setStatus("SUCCESS");
        pt.setCreatedAt(Instant.now().minusSeconds(100));

        UUID lineId = UUID.randomUUID();
        BankStatementLine line = new BankStatementLine();
        line.setId(lineId);
        line.setNormalizedStatus(BankStatementLineStatus.FAILED);

        UUID runId = UUID.randomUUID();
        ReconciliationItem item = new ReconciliationItem();
        item.setId(UUID.randomUUID());
        item.setReconciliationRunId(runId);
        item.setBankStatementLineId(lineId);
        item.setReasonCode(ReconciliationReasonCode.BANK_FINALITY_FAILED);

        when(paymentInstructionRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(pi));
        when(paymentTransactionRepository.findByPaymentInstructionId(pi.getId())).thenReturn(List.of(pt));
        when(reconciliationItemRepository.findByInternalEffectTypeAndInternalEffectId(
                ReconciliationEffectType.PAYMENT_TRANSACTION, pt.getId())).thenReturn(List.of(item));
        when(bankStatementLineRepository.findById(lineId)).thenReturn(Optional.of(line));

        RecordEngineDecisionCommand cmd = adapter.evaluate(context);
        assertThat(cmd.decision()).isEqualTo(DecisionStatus.BLOCK);
        assertThat(cmd.reasonCodes()).contains(ReconciliationDecisionAdapter.RECONCILIATION_BANK_FINALITY_FAILED);
    }

    @Test
    @DisplayName("13. Bank finality reversed yields BLOCK (RECONCILIATION_BANK_FINALITY_REVERSED)")
    void bankFinalityReversedYieldsBlock() {
        PaymentInstruction pi = new PaymentInstruction();
        pi.setId(UUID.randomUUID());
        pi.setDealId(dealId);
        pi.setMilestoneId(milestoneId);
        pi.setBuyerOrganizationId(buyerOrgId);

        PaymentTransaction pt = new PaymentTransaction();
        pt.setId(UUID.randomUUID());
        pt.setPaymentInstructionId(pi.getId());
        pt.setStatus("SUCCESS");
        pt.setCreatedAt(Instant.now().minusSeconds(100));

        UUID lineId = UUID.randomUUID();
        BankStatementLine line = new BankStatementLine();
        line.setId(lineId);
        line.setNormalizedStatus(BankStatementLineStatus.REVERSED);

        UUID runId = UUID.randomUUID();
        ReconciliationItem item = new ReconciliationItem();
        item.setId(UUID.randomUUID());
        item.setReconciliationRunId(runId);
        item.setBankStatementLineId(lineId);
        item.setReasonCode(ReconciliationReasonCode.BANK_FINALITY_REVERSED);

        when(paymentInstructionRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(pi));
        when(paymentTransactionRepository.findByPaymentInstructionId(pi.getId())).thenReturn(List.of(pt));
        when(reconciliationItemRepository.findByInternalEffectTypeAndInternalEffectId(
                ReconciliationEffectType.PAYMENT_TRANSACTION, pt.getId())).thenReturn(List.of(item));
        when(bankStatementLineRepository.findById(lineId)).thenReturn(Optional.of(line));

        RecordEngineDecisionCommand cmd = adapter.evaluate(context);
        assertThat(cmd.decision()).isEqualTo(DecisionStatus.BLOCK);
        assertThat(cmd.reasonCodes()).contains(ReconciliationDecisionAdapter.RECONCILIATION_BANK_FINALITY_REVERSED);
    }

    @Test
    @DisplayName("14. Bank finality pending yields REVIEW (RECONCILIATION_BANK_FINALITY_PENDING)")
    void bankFinalityPendingYieldsReview() {
        PaymentInstruction pi = new PaymentInstruction();
        pi.setId(UUID.randomUUID());
        pi.setDealId(dealId);
        pi.setMilestoneId(milestoneId);
        pi.setBuyerOrganizationId(buyerOrgId);

        PaymentTransaction pt = new PaymentTransaction();
        pt.setId(UUID.randomUUID());
        pt.setPaymentInstructionId(pi.getId());
        pt.setStatus("SUCCESS");
        pt.setCreatedAt(Instant.now().minusSeconds(100));

        UUID lineId = UUID.randomUUID();
        BankStatementLine line = new BankStatementLine();
        line.setId(lineId);
        line.setNormalizedStatus(BankStatementLineStatus.PENDING);

        UUID runId = UUID.randomUUID();
        UUID importId = UUID.randomUUID();
        String sourceHash = "hash123";

        ReconciliationRun run = new ReconciliationRun();
        run.setId(runId);
        run.setStatus(ReconciliationRunStatus.COMPLETED);
        run.setMatcherVersion(DeterministicReconciliationMatcher.MATCHER_VERSION);
        run.setStatementSourceHash(sourceHash);
        run.setBankStatementImportId(importId);
        run.setEndTime(Instant.now());

        BankStatementImport imp = new BankStatementImport();
        imp.setId(importId);
        imp.setSourceHash(sourceHash);

        ReconciliationItem item = new ReconciliationItem();
        item.setId(UUID.randomUUID());
        item.setReconciliationRunId(runId);
        item.setBankStatementLineId(lineId);
        item.setReasonCode(ReconciliationReasonCode.BANK_FINALITY_PENDING);

        when(paymentInstructionRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(pi));
        when(paymentTransactionRepository.findByPaymentInstructionId(pi.getId())).thenReturn(List.of(pt));
        when(reconciliationItemRepository.findByInternalEffectTypeAndInternalEffectId(
                ReconciliationEffectType.PAYMENT_TRANSACTION, pt.getId())).thenReturn(List.of(item));
        when(reconciliationRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(bankStatementImportRepository.findById(importId)).thenReturn(Optional.of(imp));
        when(bankStatementLineRepository.findById(lineId)).thenReturn(Optional.of(line));

        RecordEngineDecisionCommand cmd = adapter.evaluate(context);
        assertThat(cmd.decision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(cmd.reasonCodes()).contains(ReconciliationDecisionAdapter.RECONCILIATION_BANK_FINALITY_PENDING);
    }

    @Test
    @DisplayName("15. Run with mismatchCount > 0 yields REVIEW (RECONCILIATION_RUN_HAS_EXCEPTIONS)")
    void runWithExceptionsYieldsReview() {
        PaymentInstruction pi = new PaymentInstruction();
        pi.setId(UUID.randomUUID());
        pi.setDealId(dealId);
        pi.setMilestoneId(milestoneId);
        pi.setBuyerOrganizationId(buyerOrgId);

        PaymentTransaction pt = new PaymentTransaction();
        pt.setId(UUID.randomUUID());
        pt.setPaymentInstructionId(pi.getId());
        pt.setStatus("SUCCESS");
        pt.setCreatedAt(Instant.now().minusSeconds(100));

        UUID runId = UUID.randomUUID();
        UUID importId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        String sourceHash = "hash123";

        ReconciliationRun run = new ReconciliationRun();
        run.setId(runId);
        run.setStatus(ReconciliationRunStatus.COMPLETED);
        run.setMatcherVersion(DeterministicReconciliationMatcher.MATCHER_VERSION);
        run.setStatementSourceHash(sourceHash);
        run.setBankStatementImportId(importId);
        run.setEndTime(Instant.now());
        run.setMatchedCount(1);
        run.setMismatchCount(1); // dirty run

        BankStatementImport imp = new BankStatementImport();
        imp.setId(importId);
        imp.setSourceHash(sourceHash);

        BankStatementLine line = new BankStatementLine();
        line.setId(lineId);
        line.setNormalizedStatus(BankStatementLineStatus.FINAL_SUCCESS);

        ReconciliationItem item = new ReconciliationItem();
        item.setId(UUID.randomUUID());
        item.setReconciliationRunId(runId);
        item.setInternalEffectType(ReconciliationEffectType.PAYMENT_TRANSACTION);
        item.setInternalEffectId(pt.getId());
        item.setStatus(ReconciliationItemStatus.MATCHED);
        item.setReasonCode(ReconciliationReasonCode.MATCHED_CONFIRMED);
        item.setBankStatementLineId(lineId);

        when(paymentInstructionRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(pi));
        when(paymentTransactionRepository.findByPaymentInstructionId(pi.getId())).thenReturn(List.of(pt));
        when(reconciliationItemRepository.findByInternalEffectTypeAndInternalEffectId(
                ReconciliationEffectType.PAYMENT_TRANSACTION, pt.getId())).thenReturn(List.of(item));
        when(reconciliationRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(bankStatementImportRepository.findById(importId)).thenReturn(Optional.of(imp));
        when(bankStatementLineRepository.findById(lineId)).thenReturn(Optional.of(line));

        RecordEngineDecisionCommand cmd = adapter.evaluate(context);
        assertThat(cmd.decision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(cmd.reasonCodes()).contains(ReconciliationDecisionAdapter.RECONCILIATION_RUN_HAS_EXCEPTIONS);
    }

    @Test
    @DisplayName("16. Human RESOLVED / IGNORED exception does not make dirty run ALLOW")
    void humanResolvedDoesNotMakeAllow() {
        PaymentInstruction pi = new PaymentInstruction();
        pi.setId(UUID.randomUUID());
        pi.setDealId(dealId);
        pi.setMilestoneId(milestoneId);
        pi.setBuyerOrganizationId(buyerOrgId);

        PaymentTransaction pt = new PaymentTransaction();
        pt.setId(UUID.randomUUID());
        pt.setPaymentInstructionId(pi.getId());
        pt.setStatus("SUCCESS");
        pt.setCreatedAt(Instant.now().minusSeconds(100));

        UUID runId = UUID.randomUUID();
        UUID importId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        String sourceHash = "hash123";

        ReconciliationRun run = new ReconciliationRun();
        run.setId(runId);
        run.setStatus(ReconciliationRunStatus.COMPLETED);
        run.setMatcherVersion(DeterministicReconciliationMatcher.MATCHER_VERSION);
        run.setStatementSourceHash(sourceHash);
        run.setBankStatementImportId(importId);
        run.setEndTime(Instant.now());
        run.setMatchedCount(1);
        run.setMismatchCount(1); // dirty run

        BankStatementImport imp = new BankStatementImport();
        imp.setId(importId);
        imp.setSourceHash(sourceHash);

        BankStatementLine line = new BankStatementLine();
        line.setId(lineId);
        line.setNormalizedStatus(BankStatementLineStatus.FINAL_SUCCESS);

        ReconciliationItem item = new ReconciliationItem();
        item.setId(UUID.randomUUID());
        item.setReconciliationRunId(runId);
        item.setInternalEffectType(ReconciliationEffectType.PAYMENT_TRANSACTION);
        item.setInternalEffectId(pt.getId());
        item.setStatus(ReconciliationItemStatus.MATCHED);
        item.setReasonCode(ReconciliationReasonCode.MATCHED_CONFIRMED);
        item.setResolutionStatus(ResolutionStatus.RESOLVED); // human marked resolved
        item.setBankStatementLineId(lineId);

        when(paymentInstructionRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(pi));
        when(paymentTransactionRepository.findByPaymentInstructionId(pi.getId())).thenReturn(List.of(pt));
        when(reconciliationItemRepository.findByInternalEffectTypeAndInternalEffectId(
                ReconciliationEffectType.PAYMENT_TRANSACTION, pt.getId())).thenReturn(List.of(item));
        when(reconciliationRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(bankStatementImportRepository.findById(importId)).thenReturn(Optional.of(imp));
        when(bankStatementLineRepository.findById(lineId)).thenReturn(Optional.of(line));

        RecordEngineDecisionCommand cmd = adapter.evaluate(context);
        assertThat(cmd.decision()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(cmd.reasonCodes()).contains(ReconciliationDecisionAdapter.RECONCILIATION_RUN_HAS_EXCEPTIONS);
    }

    @Test
    @DisplayName("17. Clean rerun supersedes earlier dirty run")
    void cleanRerunSupersedesDirtyRun() {
        PaymentInstruction pi = new PaymentInstruction();
        pi.setId(UUID.randomUUID());
        pi.setDealId(dealId);
        pi.setMilestoneId(milestoneId);
        pi.setBuyerOrganizationId(buyerOrgId);

        PaymentTransaction pt = new PaymentTransaction();
        pt.setId(UUID.randomUUID());
        pt.setPaymentInstructionId(pi.getId());
        pt.setStatus("SUCCESS");
        pt.setCreatedAt(Instant.now().minusSeconds(100));

        UUID run1Id = UUID.randomUUID();
        UUID run2Id = UUID.randomUUID();
        UUID importId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        String sourceHash = "hash123";

        // Dirty run 1
        ReconciliationRun run1 = new ReconciliationRun();
        run1.setId(run1Id);
        run1.setStatus(ReconciliationRunStatus.COMPLETED);
        run1.setMatcherVersion(DeterministicReconciliationMatcher.MATCHER_VERSION);
        run1.setStatementSourceHash(sourceHash);
        run1.setBankStatementImportId(importId);
        run1.setEndTime(Instant.now().minusSeconds(50));
        run1.setMatchedCount(1);
        run1.setMismatchCount(1);

        // Clean run 2
        ReconciliationRun run2 = new ReconciliationRun();
        run2.setId(run2Id);
        run2.setStatus(ReconciliationRunStatus.COMPLETED);
        run2.setMatcherVersion(DeterministicReconciliationMatcher.MATCHER_VERSION);
        run2.setStatementSourceHash(sourceHash);
        run2.setBankStatementImportId(importId);
        run2.setEndTime(Instant.now());
        run2.setMatchedCount(1);
        run2.setMismatchCount(0);

        BankStatementImport imp = new BankStatementImport();
        imp.setId(importId);
        imp.setSourceHash(sourceHash);

        BankStatementLine line = new BankStatementLine();
        line.setId(lineId);
        line.setNormalizedStatus(BankStatementLineStatus.FINAL_SUCCESS);

        ReconciliationItem item1 = new ReconciliationItem();
        item1.setId(UUID.randomUUID());
        item1.setReconciliationRunId(run1Id);
        item1.setInternalEffectType(ReconciliationEffectType.PAYMENT_TRANSACTION);
        item1.setInternalEffectId(pt.getId());
        item1.setStatus(ReconciliationItemStatus.MATCHED);
        item1.setReasonCode(ReconciliationReasonCode.MATCHED_CONFIRMED);
        item1.setBankStatementLineId(lineId);

        ReconciliationItem item2 = new ReconciliationItem();
        item2.setId(UUID.randomUUID());
        item2.setReconciliationRunId(run2Id);
        item2.setInternalEffectType(ReconciliationEffectType.PAYMENT_TRANSACTION);
        item2.setInternalEffectId(pt.getId());
        item2.setStatus(ReconciliationItemStatus.MATCHED);
        item2.setReasonCode(ReconciliationReasonCode.MATCHED_CONFIRMED);
        item2.setBankStatementLineId(lineId);

        when(paymentInstructionRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(pi));
        when(paymentTransactionRepository.findByPaymentInstructionId(pi.getId())).thenReturn(List.of(pt));
        when(reconciliationItemRepository.findByInternalEffectTypeAndInternalEffectId(
                ReconciliationEffectType.PAYMENT_TRANSACTION, pt.getId())).thenReturn(List.of(item1, item2));
        when(reconciliationRunRepository.findById(run1Id)).thenReturn(Optional.of(run1));
        when(reconciliationRunRepository.findById(run2Id)).thenReturn(Optional.of(run2));
        when(bankStatementImportRepository.findById(importId)).thenReturn(Optional.of(imp));
        when(bankStatementLineRepository.findById(lineId)).thenReturn(Optional.of(line));
        when(reconciliationItemRepository.findByReconciliationRunId(run2Id)).thenReturn(List.of(item2));

        RecordEngineDecisionCommand cmd = adapter.evaluate(context);
        assertThat(cmd.decision()).isEqualTo(DecisionStatus.ALLOW);
        assertThat(cmd.reasonCodes()).containsExactly(ReconciliationDecisionAdapter.RECONCILIATION_CONFIRMED);
    }

    @Test
    @DisplayName("18. Whole-deal funding (milestoneId == null) satisfies target milestone if cleanly reconciled")
    void wholeDealFundingSatisfiesMilestone() {
        PaymentInstruction pi = new PaymentInstruction();
        pi.setId(UUID.randomUUID());
        pi.setDealId(dealId);
        pi.setMilestoneId(null); // whole-deal funding
        pi.setBuyerOrganizationId(buyerOrgId);
        pi.setAmount(new BigDecimal("50000.00"));
        pi.setCurrency("INR");
        pi.setStatus(InstructionStatus.SUCCESS);
        pi.setProvider("BANK");

        PaymentTransaction pt = new PaymentTransaction();
        pt.setId(UUID.randomUUID());
        pt.setPaymentInstructionId(pi.getId());
        pt.setStatus("SUCCESS");
        pt.setAmount(new BigDecimal("50000.00"));
        pt.setCurrency("INR");
        pt.setCreatedAt(Instant.now().minusSeconds(100));

        UUID runId = UUID.randomUUID();
        UUID importId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        String sourceHash = "hash123";

        ReconciliationRun run = new ReconciliationRun();
        run.setId(runId);
        run.setStatus(ReconciliationRunStatus.COMPLETED);
        run.setMatcherVersion(DeterministicReconciliationMatcher.MATCHER_VERSION);
        run.setStatementSourceHash(sourceHash);
        run.setBankStatementImportId(importId);
        run.setEndTime(Instant.now());
        run.setMatchedCount(1);
        run.setMismatchCount(0);

        BankStatementImport imp = new BankStatementImport();
        imp.setId(importId);
        imp.setSourceHash(sourceHash);

        BankStatementLine line = new BankStatementLine();
        line.setId(lineId);
        line.setNormalizedStatus(BankStatementLineStatus.FINAL_SUCCESS);

        ReconciliationItem item = new ReconciliationItem();
        item.setId(UUID.randomUUID());
        item.setReconciliationRunId(runId);
        item.setInternalEffectType(ReconciliationEffectType.PAYMENT_TRANSACTION);
        item.setInternalEffectId(pt.getId());
        item.setStatus(ReconciliationItemStatus.MATCHED);
        item.setReasonCode(ReconciliationReasonCode.MATCHED_CONFIRMED);
        item.setBankStatementLineId(lineId);

        when(paymentInstructionRepository.findByDealIdOrderByCreatedAtDesc(dealId)).thenReturn(List.of(pi));
        when(paymentTransactionRepository.findByPaymentInstructionId(pi.getId())).thenReturn(List.of(pt));
        when(reconciliationItemRepository.findByInternalEffectTypeAndInternalEffectId(
                ReconciliationEffectType.PAYMENT_TRANSACTION, pt.getId())).thenReturn(List.of(item));
        when(reconciliationRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(bankStatementImportRepository.findById(importId)).thenReturn(Optional.of(imp));
        when(bankStatementLineRepository.findById(lineId)).thenReturn(Optional.of(line));
        when(reconciliationItemRepository.findByReconciliationRunId(runId)).thenReturn(List.of(item));

        RecordEngineDecisionCommand cmd = adapter.evaluate(context);
        assertThat(cmd.decision()).isEqualTo(DecisionStatus.ALLOW);
        assertThat(cmd.reasonCodes()).containsExactly(ReconciliationDecisionAdapter.RECONCILIATION_CONFIRMED);
    }

    @Test
    @DisplayName("19. Cross-tenant context organization is rejected")
    void crossTenantOrganizationRejected() {
        EvaluationContext foreignContext = new EvaluationContext(
                UUID.randomUUID(),
                UUID.randomUUID(), // foreign org
                UUID.randomUUID(),
                DecisionSubjectType.MILESTONE.name(),
                milestoneId,
                Instant.now()
        );

        assertThatThrownBy(() -> adapter.evaluate(foreignContext))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("20. Zero banking calls, matcher calls, or financial writes during evaluation")
    void zeroSideEffects() {
        mockCleanMatchedFunding();
        adapter.evaluate(context);

        verifyNoInteractions(bankingProvider);
        verify(reconciliationItemRepository, never()).save(any());
        verify(reconciliationRunRepository, never()).save(any());
        verify(paymentTransactionRepository, never()).save(any());
        verify(paymentInstructionRepository, never()).save(any());
    }
}
