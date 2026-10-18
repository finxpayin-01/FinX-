package com.finx.finance.controller;

import com.finx.common.response.ApiResponse;
import com.finx.finance.dto.FinanceTransactionView;
import com.finx.ledger.repository.LedgerAccountRepository;
import com.finx.ledger.service.LedgerService;
import com.finx.paymentorchestration.entity.PaymentInstruction;
import com.finx.paymentorchestration.entity.SettlementInstruction;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.InstructionStatus;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.SettlementStatus;
import com.finx.paymentorchestration.repository.PaymentInstructionRepository;
import com.finx.paymentorchestration.repository.SettlementInstructionRepository;
import com.finx.reconciliation.repository.ReconciliationRunRepository;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.repository.ReleaseInstructionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class FinanceControllerUnitTest {

    @Mock
    private PaymentInstructionRepository paymentInstructionRepository;

    @Mock
    private SettlementInstructionRepository settlementInstructionRepository;

    @Mock
    private ReleaseInstructionRepository releaseInstructionRepository;

    @Mock
    private ReconciliationRunRepository reconciliationRunRepository;

    @Mock
    private LedgerAccountRepository ledgerAccountRepository;

    @Mock
    private LedgerService ledgerService;

    private FinanceController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new FinanceController(
                paymentInstructionRepository,
                settlementInstructionRepository,
                releaseInstructionRepository,
                reconciliationRunRepository,
                ledgerAccountRepository,
                ledgerService
        );
    }

    private ReleaseInstruction createInstruction(UUID id, BigDecimal amount, ReleaseInstructionStatus status, Instant createdAt, Instant settledAt) {
        ReleaseInstruction ri = new ReleaseInstruction(
                id,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "a".repeat(64),
                UUID.randomUUID(),
                "b".repeat(64),
                UUID.randomUUID(),
                "AXIS",
                "ESCROW-POOL-1",
                amount,
                "INR",
                "idem-" + id,
                "c".repeat(64),
                UUID.randomUUID()
        );

        // Mutate status to target status
        try {
            Field statusField = ReleaseInstruction.class.getDeclaredField("status");
            statusField.setAccessible(true);
            statusField.set(ri, status);

            if (createdAt != null) {
                Field createdField = ReleaseInstruction.class.getDeclaredField("createdAt");
                createdField.setAccessible(true);
                createdField.set(ri, createdAt);
            }
            if (settledAt != null) {
                Field settledField = ReleaseInstruction.class.getDeclaredField("settledAt");
                settledField.setAccessible(true);
                settledField.set(ri, settledAt);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return ri;
    }

    @Test
    @DisplayName("Summary derives modern settled volume from ReleaseInstruction SETTLED, not SettlementInstruction")
    void summaryUsesReleaseInstructionNotSettlementInstruction() {
        // Payment instructions: 1 SUCCESS (100000), 1 PENDING (50000)
        PaymentInstruction pi1 = new PaymentInstruction();
        pi1.setAmount(new BigDecimal("100000.00"));
        pi1.setStatus(InstructionStatus.SUCCESS);

        PaymentInstruction pi2 = new PaymentInstruction();
        pi2.setAmount(new BigDecimal("50000.00"));
        pi2.setStatus(InstructionStatus.PROCESSING);

        when(paymentInstructionRepository.findAll()).thenReturn(List.of(pi1, pi2));

        // Legacy settlements has 999999 SETTLED — which MUST be ignored
        SettlementInstruction legacy = new SettlementInstruction();
        legacy.setAmount(new BigDecimal("999999.00"));
        legacy.setStatus(SettlementStatus.SETTLED);
        when(settlementInstructionRepository.findAll()).thenReturn(List.of(legacy));

        // Modern release instruction: 1 SETTLED (60000.00)
        ReleaseInstruction riSettled = createInstruction(UUID.randomUUID(), new BigDecimal("60000.00"), ReleaseInstructionStatus.SETTLED, Instant.now(), Instant.now());
        when(releaseInstructionRepository.findAll()).thenReturn(List.of(riSettled));
        when(reconciliationRunRepository.count()).thenReturn(3L);

        ResponseEntity<ApiResponse<Map<String, Object>>> response = controller.getFinanceSummary();
        Map<String, Object> data = response.getBody().getData();

        assertThat((BigDecimal) data.get("totalInflowVolume")).isEqualByComparingTo(new BigDecimal("100000.00"));
        // Modern settled volume MUST be 60000.00, NOT 999999.00
        assertThat((BigDecimal) data.get("totalSettledVolume")).isEqualByComparingTo(new BigDecimal("60000.00"));
        assertThat(data.get("totalInstructionsCount")).isEqualTo(2);
        assertThat(data.get("pendingSettlementsCount")).isEqualTo(0L);
        assertThat(data.get("totalReconciliationRuns")).isEqualTo(3L);
    }

    @Test
    @DisplayName("Summary correctly counts pending releases and excludes terminal states")
    void summaryCountsPendingReleasesCorrectly() {
        when(paymentInstructionRepository.findAll()).thenReturn(List.of());

        ReleaseInstruction settled = createInstruction(UUID.randomUUID(), new BigDecimal("1000.00"), ReleaseInstructionStatus.SETTLED, Instant.now(), Instant.now());
        ReleaseInstruction queued = createInstruction(UUID.randomUUID(), new BigDecimal("2000.00"), ReleaseInstructionStatus.QUEUED, Instant.now(), null);
        ReleaseInstruction dispatching = createInstruction(UUID.randomUUID(), new BigDecimal("3000.00"), ReleaseInstructionStatus.DISPATCHING, Instant.now(), null);
        ReleaseInstruction pendingSettlement = createInstruction(UUID.randomUUID(), new BigDecimal("4000.00"), ReleaseInstructionStatus.PENDING_SETTLEMENT, Instant.now(), null);
        ReleaseInstruction unknownRecon = createInstruction(UUID.randomUUID(), new BigDecimal("5000.00"), ReleaseInstructionStatus.UNKNOWN_RECON_REQUIRED, Instant.now(), null);
        ReleaseInstruction manualReview = createInstruction(UUID.randomUUID(), new BigDecimal("6000.00"), ReleaseInstructionStatus.MANUAL_REVIEW, Instant.now(), null);
        ReleaseInstruction failed = createInstruction(UUID.randomUUID(), new BigDecimal("7000.00"), ReleaseInstructionStatus.FAILED, Instant.now(), null);
        ReleaseInstruction cancelled = createInstruction(UUID.randomUUID(), new BigDecimal("8000.00"), ReleaseInstructionStatus.CANCELLED, Instant.now(), null);

        when(releaseInstructionRepository.findAll()).thenReturn(List.of(
                settled, queued, dispatching, pendingSettlement, unknownRecon, manualReview, failed, cancelled
        ));

        ResponseEntity<ApiResponse<Map<String, Object>>> response = controller.getFinanceSummary();
        Map<String, Object> data = response.getBody().getData();

        // 5 active pending releases (queued, dispatching, pendingSettlement, unknownRecon, manualReview)
        assertThat(data.get("pendingSettlementsCount")).isEqualTo(5L);
        // Only settled release included in settled volume
        assertThat((BigDecimal) data.get("totalSettledVolume")).isEqualByComparingTo(new BigDecimal("1000.00"));
    }

    @Test
    @DisplayName("Transaction endpoint returns funding rows and release rows with deterministic ordering")
    void transactionsReturnsFundingAndReleaseRows() {
        UUID piId = UUID.randomUUID();
        PaymentInstruction pi = new PaymentInstruction();
        pi.setId(piId);
        pi.setDealId(UUID.randomUUID());
        pi.setAmount(new BigDecimal("25000.00"));
        pi.setCurrency("INR");
        pi.setProvider("RAZORPAY");
        pi.setStatus(InstructionStatus.SUCCESS);
        Instant piTime = Instant.parse("2026-10-18T10:00:00Z");
        pi.setCreatedAt(piTime);
        pi.setConfirmedAt(piTime);

        UUID riId = UUID.randomUUID();
        Instant riTime = Instant.parse("2026-10-18T11:00:00Z");
        ReleaseInstruction ri = createInstruction(riId, new BigDecimal("20000.00"), ReleaseInstructionStatus.SETTLED, riTime, riTime);

        when(paymentInstructionRepository.findAll()).thenReturn(List.of(pi));
        when(releaseInstructionRepository.findAll()).thenReturn(List.of(ri));

        ResponseEntity<ApiResponse<List<FinanceTransactionView>>> response = controller.getFinanceTransactions();
        List<FinanceTransactionView> txs = response.getBody().getData();

        assertThat(txs).hasSize(2);
        // Sorted newest first: ri (11:00) then pi (10:00)
        assertThat(txs.get(0).getId()).isEqualTo(riId);
        assertThat(txs.get(0).getType()).isEqualTo("RELEASE");
        assertThat(txs.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("20000.00"));
        assertThat(txs.get(0).getStatus()).isEqualTo("SETTLED");

        assertThat(txs.get(1).getId()).isEqualTo(piId);
        assertThat(txs.get(1).getType()).isEqualTo("FUNDING");
        assertThat(txs.get(1).getAmount()).isEqualByComparingTo(new BigDecimal("25000.00"));
        assertThat(txs.get(1).getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("FinanceTransactionView contains no sensitive beneficiary account fields")
    void noSensitiveBeneficiaryAccountFields() {
        for (Field f : FinanceTransactionView.class.getDeclaredFields()) {
            String name = f.getName().toLowerCase();
            assertThat(name).doesNotContain("accountnumber");
            assertThat(name).doesNotContain("encrypted");
            assertThat(name).doesNotContain("ifsc");
            assertThat(name).doesNotContain("beneficiary");
            assertThat(name).doesNotContain("password");
            assertThat(name).doesNotContain("secret");
            assertThat(name).doesNotContain("rawwebhook");
        }
    }
}
