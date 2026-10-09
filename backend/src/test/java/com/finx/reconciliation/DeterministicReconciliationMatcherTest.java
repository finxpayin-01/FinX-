package com.finx.reconciliation;

import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.paymentorchestration.entity.PaymentInstruction;
import com.finx.paymentorchestration.entity.PaymentTransaction;
import com.finx.paymentorchestration.entity.SettlementInstruction;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.InstructionStatus;
import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.SettlementStatus;
import com.finx.paymentorchestration.repository.PaymentInstructionRepository;
import com.finx.paymentorchestration.repository.PaymentTransactionRepository;
import com.finx.paymentorchestration.repository.SettlementInstructionRepository;
import com.finx.reconciliation.dto.DeterministicReconciliationResult;
import com.finx.reconciliation.entity.BankStatementImport;
import com.finx.reconciliation.entity.BankStatementLine;
import com.finx.reconciliation.entity.ReconciliationItem;
import com.finx.reconciliation.entity.ReconciliationRun;
import com.finx.reconciliation.enums.BankStatementImportStatus;
import com.finx.reconciliation.enums.BankStatementLineStatus;
import com.finx.reconciliation.enums.FinancialDirection;
import com.finx.reconciliation.enums.ReconciliationEnums.ReconciliationItemStatus;
import com.finx.reconciliation.enums.ReconciliationEnums.ReconciliationRunStatus;
import com.finx.reconciliation.enums.ReconciliationReasonCode;
import com.finx.reconciliation.repository.BankStatementImportRepository;
import com.finx.reconciliation.repository.BankStatementLineRepository;
import com.finx.reconciliation.repository.ReconciliationItemRepository;
import com.finx.reconciliation.repository.ReconciliationRunRepository;
import com.finx.reconciliation.service.BankStatementEvidenceFingerprint;
import com.finx.reconciliation.service.DeterministicReconciliationMatcher;
import com.finx.security.service.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class DeterministicReconciliationMatcherTest {

    @Mock
    private BankStatementImportRepository importRepository;
    @Mock
    private BankStatementLineRepository lineRepository;
    @Mock
    private PaymentTransactionRepository paymentTransactionRepository;
    @Mock
    private PaymentInstructionRepository paymentInstructionRepository;
    @Mock
    private SettlementInstructionRepository settlementInstructionRepository;
    @Mock
    private ReconciliationRunRepository runRepository;
    @Mock
    private ReconciliationItemRepository itemRepository;

    private DeterministicReconciliationMatcher matcher;

    private UserPrincipal adminUser;
    private UserPrincipal financeUser;
    private UserPrincipal buyerUser;

    private static final String PROVIDER = "MOCK";
    private static final String PROVIDER_ACCOUNT_ID = "MOCK_ESCROW_ACCOUNT";
    private static final String CURRENCY = "INR";

    private Instant windowStart;
    private Instant windowEnd;

    @BeforeEach
    void setUp() {
        matcher = new DeterministicReconciliationMatcher(
                importRepository,
                lineRepository,
                paymentTransactionRepository,
                paymentInstructionRepository,
                settlementInstructionRepository,
                runRepository,
                itemRepository
        );

        adminUser = new UserPrincipal(UUID.randomUUID(), "Admin", "admin@finx.com", "hash", Role.ADMIN, UserStatus.ACTIVE, Collections.emptyList());
        financeUser = new UserPrincipal(UUID.randomUUID(), "Finance", "finance@finx.com", "hash", Role.FINANCE, UserStatus.ACTIVE, Collections.emptyList());
        buyerUser = new UserPrincipal(UUID.randomUUID(), "Buyer", "buyer@finx.com", "hash", Role.BUYER, UserStatus.ACTIVE, Collections.emptyList());

        windowStart = Instant.now().minus(2, ChronoUnit.DAYS);
        windowEnd = Instant.now();

        lenient().when(runRepository.save(any(ReconciliationRun.class))).thenAnswer(invocation -> {
            ReconciliationRun r = invocation.getArgument(0);
            if (r.getId() == null) {
                r.setId(UUID.randomUUID());
            }
            return r;
        });
    }

    private BankStatementLine createLine(UUID importId, String providerLineId, String txnRef, String utr,
                                         BigDecimal amount, String currency, FinancialDirection direction,
                                         BankStatementLineStatus status, Instant txnTime) {
        String fp = BankStatementEvidenceFingerprint.computeLineFingerprint(
                PROVIDER, PROVIDER_ACCOUNT_ID, providerLineId, txnRef, utr, amount, currency,
                direction != null ? direction.name() : null, "SUCCESS",
                status != null ? status.name() : null, txnTime, LocalDate.now()
        );
        BankStatementLine line = new BankStatementLine(importId, PROVIDER, PROVIDER_ACCOUNT_ID, providerLineId,
                txnRef, utr, amount, currency, direction, "SUCCESS", status, txnTime, LocalDate.now(), fp);
        return line;
    }

    private BankStatementImport createImport(List<BankStatementLine> lines, BigDecimal opening, BigDecimal closing) {
        List<String> fingerprints = lines.stream().map(BankStatementLine::getLineFingerprint).toList();
        String sourceHash = BankStatementEvidenceFingerprint.computeSourceHash(
                PROVIDER, PROVIDER_ACCOUNT_ID, CURRENCY, opening, closing, "IMP-REF-1", fingerprints, windowStart, windowEnd
        );

        BankStatementImport imp = new BankStatementImport(
                PROVIDER, PROVIDER_ACCOUNT_ID, windowStart, windowEnd, sourceHash,
                opening, closing, CURRENCY, adminUser.getId()
        );
        imp.setStatus(BankStatementImportStatus.COMPLETED);
        imp.setProviderImportReference("IMP-REF-1");
        imp.setTotalLines(lines.size());
        return imp;
    }

    // ==========================================
    // 1-10: IMPORT INTEGRITY TESTS
    // ==========================================

    @Test
    @DisplayName("1. Incomplete import rejected")
    void testIncompleteImportRejected() {
        BankStatementImport imp = new BankStatementImport(PROVIDER, PROVIDER_ACCOUNT_ID, windowStart, windowEnd,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                BigDecimal.ZERO, BigDecimal.ZERO, CURRENCY, adminUser.getId());
        imp.setStatus(BankStatementImportStatus.PROCESSING);

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));

        assertThatThrownBy(() -> matcher.reconcileImport(imp.getId(), adminUser))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMPLETED");

        verifyNoInteractions(runRepository);
        verifyNoInteractions(itemRepository);
    }

    @Test
    @DisplayName("2. Line count mismatch rejected")
    void testLineCountMismatchRejected() {
        BankStatementLine line = createLine(UUID.randomUUID(), "L1", "REF1", "UTR1", BigDecimal.valueOf(100), CURRENCY,
                FinancialDirection.CREDIT, BankStatementLineStatus.FINAL_SUCCESS, windowStart.plusSeconds(60));
        BankStatementImport imp = createImport(List.of(line), BigDecimal.ZERO, BigDecimal.valueOf(100));
        imp.setTotalLines(2); // Inconsistent totalLines

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));

        assertThatThrownBy(() -> matcher.reconcileImport(imp.getId(), adminUser))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Line count mismatch");

        verifyNoInteractions(runRepository);
    }

    @Test
    @DisplayName("3. Line importId mismatch rejected")
    void testLineImportIdMismatchRejected() {
        UUID otherImportId = UUID.randomUUID();
        BankStatementLine line = createLine(otherImportId, "L1", "REF1", "UTR1", BigDecimal.valueOf(100), CURRENCY,
                FinancialDirection.CREDIT, BankStatementLineStatus.FINAL_SUCCESS, windowStart.plusSeconds(60));
        BankStatementImport imp = createImport(List.of(line), BigDecimal.ZERO, BigDecimal.valueOf(100));

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));

        assertThatThrownBy(() -> matcher.reconcileImport(imp.getId(), adminUser))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("importId mismatch");
    }

    @Test
    @DisplayName("4. Provider mismatch in line rejected")
    void testLineProviderMismatchRejected() {
        UUID importId = UUID.randomUUID();
        BankStatementLine line = createLine(importId, "L1", "REF1", "UTR1", BigDecimal.valueOf(100), CURRENCY,
                FinancialDirection.CREDIT, BankStatementLineStatus.FINAL_SUCCESS, windowStart.plusSeconds(60));
        line.setProvider("OTHER_PROVIDER");
        BankStatementImport imp = createImport(List.of(line), BigDecimal.ZERO, BigDecimal.valueOf(100));
        imp.setId(importId);

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));

        assertThatThrownBy(() -> matcher.reconcileImport(imp.getId(), adminUser))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provider mismatch");
    }

    @Test
    @DisplayName("5. Account mismatch in line rejected")
    void testLineAccountMismatchRejected() {
        UUID importId = UUID.randomUUID();
        BankStatementLine line = createLine(importId, "L1", "REF1", "UTR1", BigDecimal.valueOf(100), CURRENCY,
                FinancialDirection.CREDIT, BankStatementLineStatus.FINAL_SUCCESS, windowStart.plusSeconds(60));
        line.setProviderAccountId("OTHER_ACCOUNT");
        BankStatementImport imp = createImport(List.of(line), BigDecimal.ZERO, BigDecimal.valueOf(100));
        imp.setId(importId);

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));

        assertThatThrownBy(() -> matcher.reconcileImport(imp.getId(), adminUser))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("providerAccountId mismatch");
    }

    @Test
    @DisplayName("6. Currency mismatch between import and line rejected")
    void testLineCurrencyMismatchRejected() {
        UUID importId = UUID.randomUUID();
        BankStatementLine line = createLine(importId, "L1", "REF1", "UTR1", BigDecimal.valueOf(100), "USD",
                FinancialDirection.CREDIT, BankStatementLineStatus.FINAL_SUCCESS, windowStart.plusSeconds(60));
        BankStatementImport imp = createImport(List.of(line), BigDecimal.ZERO, BigDecimal.valueOf(100));
        imp.setId(importId);

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));

        assertThatThrownBy(() -> matcher.reconcileImport(imp.getId(), adminUser))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("currency mismatch");
    }

    @Test
    @DisplayName("7. Line outside import window rejected")
    void testLineOutsideWindowRejected() {
        UUID importId = UUID.randomUUID();
        BankStatementLine line = createLine(importId, "L1", "REF1", "UTR1", BigDecimal.valueOf(100), CURRENCY,
                FinancialDirection.CREDIT, BankStatementLineStatus.FINAL_SUCCESS, windowEnd.plusSeconds(600));
        BankStatementImport imp = createImport(List.of(line), BigDecimal.ZERO, BigDecimal.valueOf(100));
        imp.setId(importId);

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));

        assertThatThrownBy(() -> matcher.reconcileImport(imp.getId(), adminUser))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside import window");
    }

    @Test
    @DisplayName("8. Line fingerprint tamper rejected")
    void testLineFingerprintTamperRejected() {
        UUID importId = UUID.randomUUID();
        BankStatementLine line = createLine(importId, "L1", "REF1", "UTR1", BigDecimal.valueOf(100), CURRENCY,
                FinancialDirection.CREDIT, BankStatementLineStatus.FINAL_SUCCESS, windowStart.plusSeconds(60));
        line.setLineFingerprint("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
        BankStatementImport imp = createImport(List.of(line), BigDecimal.ZERO, BigDecimal.valueOf(100));
        imp.setId(importId);

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));

        assertThatThrownBy(() -> matcher.reconcileImport(imp.getId(), adminUser))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Line fingerprint mismatch");
    }

    @Test
    @DisplayName("9. SourceHash tamper rejected")
    void testSourceHashTamperRejected() {
        UUID importId = UUID.randomUUID();
        BankStatementLine line = createLine(importId, "L1", "REF1", "UTR1", BigDecimal.valueOf(100), CURRENCY,
                FinancialDirection.CREDIT, BankStatementLineStatus.FINAL_SUCCESS, windowStart.plusSeconds(60));
        BankStatementImport imp = createImport(List.of(line), BigDecimal.ZERO, BigDecimal.valueOf(100));
        imp.setId(importId);
        imp.setSourceHash("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));

        assertThatThrownBy(() -> matcher.reconcileImport(imp.getId(), adminUser))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sourceHash mismatch");
    }

    @Test
    @DisplayName("10. Zero run/items on integrity failure and non-admin role rejected")
    void testZeroRunItemsOnFailureAndRoleCheck() {
        UUID importId = UUID.randomUUID();
        assertThatThrownBy(() -> matcher.reconcileImport(importId, buyerUser))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(runRepository);
        verifyNoInteractions(itemRepository);
    }

    // ==========================================
    // 11-24: INBOUND PAYMENT RECONCILIATION TESTS
    // ==========================================

    @Test
    @DisplayName("11. SUCCESS payment + exact UTR + all dimensions valid -> MATCHED")
    void testInboundPaymentUtrMatched() {
        UUID importId = UUID.randomUUID();
        BankStatementLine line = createLine(importId, "L1", "BANK-TXN-1", "UTR-12345", BigDecimal.valueOf(5000), CURRENCY,
                FinancialDirection.CREDIT, BankStatementLineStatus.FINAL_SUCCESS, windowStart.plusSeconds(100));
        BankStatementImport imp = createImport(List.of(line), BigDecimal.ZERO, BigDecimal.valueOf(5000));
        imp.setId(importId);

        UUID instructionId = UUID.randomUUID();
        PaymentInstruction instruction = new PaymentInstruction("INST-1", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), BigDecimal.valueOf(5000), CURRENCY, PROVIDER, "IDEM-1", adminUser.getId());
        instruction.setId(instructionId);
        instruction.setProviderAccountId(PROVIDER_ACCOUNT_ID);
        instruction.setStatus(InstructionStatus.SUCCESS);

        PaymentTransaction transaction = new PaymentTransaction("TXN-1", instructionId, PROVIDER, "PROV-TXN-1",
                BigDecimal.valueOf(5000), CURRENCY, "SUCCESS", "UTR-12345", "{}");

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));
        when(paymentTransactionRepository.findInboundReconciliationCandidates(eq(PROVIDER), eq(PROVIDER_ACCOUNT_ID), eq(windowStart), eq(windowEnd)))
                .thenReturn(List.of(transaction));
        when(paymentInstructionRepository.findById(instructionId)).thenReturn(Optional.of(instruction));
        when(settlementInstructionRepository.findOutboundReconciliationCandidates(any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);

        assertThat(result.clean()).isTrue();
        assertThat(result.run().getMatchedCount()).isEqualTo(1);
        assertThat(result.run().getMismatchCount()).isEqualTo(0);
        assertThat(result.items()).hasSize(1);

        ReconciliationItem item = result.items().get(0);
        assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.MATCHED);
        assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.MATCHED_CONFIRMED);
        assertThat(item.getBankStatementLineId()).isEqualTo(line.getId());
    }

    @Test
    @DisplayName("12. Provider reference match -> MATCHED")
    void testInboundProviderReferenceMatched() {
        UUID importId = UUID.randomUUID();
        BankStatementLine line = createLine(importId, "L1", "PROV-REF-99", null, BigDecimal.valueOf(2500), CURRENCY,
                FinancialDirection.CREDIT, BankStatementLineStatus.FINAL_SUCCESS, windowStart.plusSeconds(100));
        BankStatementImport imp = createImport(List.of(line), BigDecimal.ZERO, BigDecimal.valueOf(2500));
        imp.setId(importId);

        UUID instructionId = UUID.randomUUID();
        PaymentInstruction instruction = new PaymentInstruction("INST-2", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), BigDecimal.valueOf(2500), CURRENCY, PROVIDER, "IDEM-2", adminUser.getId());
        instruction.setId(instructionId);
        instruction.setProviderAccountId(PROVIDER_ACCOUNT_ID);
        instruction.setProviderInstructionId("PROV-REF-99");
        instruction.setStatus(InstructionStatus.SUCCESS);

        PaymentTransaction transaction = new PaymentTransaction("TXN-2", instructionId, PROVIDER, "PROV-REF-99",
                BigDecimal.valueOf(2500), CURRENCY, "SUCCESS", null, "{}");

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));
        when(paymentTransactionRepository.findInboundReconciliationCandidates(any(), any(), any(), any()))
                .thenReturn(List.of(transaction));
        when(paymentInstructionRepository.findById(instructionId)).thenReturn(Optional.of(instruction));
        when(settlementInstructionRepository.findOutboundReconciliationCandidates(any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), financeUser);

        assertThat(result.clean()).isTrue();
        assertThat(result.run().getMatchedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("13. Internal reference fallback -> MATCHED")
    void testInboundFinXReferenceMatched() {
        UUID importId = UUID.randomUUID();
        BankStatementLine line = createLine(importId, "L1", "INST-3", null, BigDecimal.valueOf(1000), CURRENCY,
                FinancialDirection.CREDIT, BankStatementLineStatus.FINAL_SUCCESS, windowStart.plusSeconds(100));
        BankStatementImport imp = createImport(List.of(line), BigDecimal.ZERO, BigDecimal.valueOf(1000));
        imp.setId(importId);

        UUID instructionId = UUID.randomUUID();
        PaymentInstruction instruction = new PaymentInstruction("INST-3", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), BigDecimal.valueOf(1000), CURRENCY, PROVIDER, "IDEM-3", adminUser.getId());
        instruction.setId(instructionId);
        instruction.setProviderAccountId(PROVIDER_ACCOUNT_ID);
        instruction.setStatus(InstructionStatus.SUCCESS);

        PaymentTransaction transaction = new PaymentTransaction("TXN-3", instructionId, PROVIDER, null,
                BigDecimal.valueOf(1000), CURRENCY, "SUCCESS", null, "{}");

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));
        when(paymentTransactionRepository.findInboundReconciliationCandidates(any(), any(), any(), any()))
                .thenReturn(List.of(transaction));
        when(paymentInstructionRepository.findById(instructionId)).thenReturn(Optional.of(instruction));
        when(settlementInstructionRepository.findOutboundReconciliationCandidates(any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);
        assertThat(result.clean()).isTrue();
    }

    @Test
    @DisplayName("14. No reference in bank -> MISSING_EXTERNAL")
    void testInboundMissingExternal() {
        UUID importId = UUID.randomUUID();
        BankStatementImport imp = createImport(Collections.emptyList(), BigDecimal.ZERO, BigDecimal.ZERO);
        imp.setId(importId);

        UUID instructionId = UUID.randomUUID();
        PaymentInstruction instruction = new PaymentInstruction("INST-4", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), BigDecimal.valueOf(1000), CURRENCY, PROVIDER, "IDEM-4", adminUser.getId());
        instruction.setId(instructionId);
        instruction.setProviderAccountId(PROVIDER_ACCOUNT_ID);
        instruction.setStatus(InstructionStatus.SUCCESS);

        PaymentTransaction transaction = new PaymentTransaction("TXN-4", instructionId, PROVIDER, null,
                BigDecimal.valueOf(1000), CURRENCY, "SUCCESS", null, "{}");

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(Collections.emptyList());
        when(paymentTransactionRepository.findInboundReconciliationCandidates(any(), any(), any(), any()))
                .thenReturn(List.of(transaction));
        when(paymentInstructionRepository.findById(instructionId)).thenReturn(Optional.of(instruction));
        when(settlementInstructionRepository.findOutboundReconciliationCandidates(any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);

        assertThat(result.clean()).isFalse();
        assertThat(result.run().getMismatchCount()).isEqualTo(1);
        ReconciliationItem item = result.items().get(0);
        assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.MISSING_EXTERNAL);
        assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.MISSING_EXTERNAL);
    }

    @Test
    @DisplayName("15. NULL provider account -> REQUIRES_REVIEW (INTERNAL_ACCOUNT_SCOPE_UNBOUND)")
    void testInboundNullProviderAccount() {
        UUID importId = UUID.randomUUID();
        BankStatementImport imp = createImport(Collections.emptyList(), BigDecimal.ZERO, BigDecimal.ZERO);
        imp.setId(importId);

        UUID instructionId = UUID.randomUUID();
        PaymentInstruction instruction = new PaymentInstruction("INST-5", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), BigDecimal.valueOf(1000), CURRENCY, PROVIDER, "IDEM-5", adminUser.getId());
        instruction.setId(instructionId);
        instruction.setProviderAccountId(null); // Unbound historical row!
        instruction.setStatus(InstructionStatus.SUCCESS);

        PaymentTransaction transaction = new PaymentTransaction("TXN-5", instructionId, PROVIDER, null,
                BigDecimal.valueOf(1000), CURRENCY, "SUCCESS", "UTR-555", "{}");

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(Collections.emptyList());
        when(paymentTransactionRepository.findInboundReconciliationCandidates(any(), any(), any(), any()))
                .thenReturn(List.of(transaction));
        when(paymentInstructionRepository.findById(instructionId)).thenReturn(Optional.of(instruction));
        when(settlementInstructionRepository.findOutboundReconciliationCandidates(any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);

        assertThat(result.clean()).isFalse();
        ReconciliationItem item = result.items().get(0);
        assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.REQUIRES_REVIEW);
        assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.INTERNAL_ACCOUNT_SCOPE_UNBOUND);
    }

    @Test
    @DisplayName("16. Instruction / transaction lineage mismatch -> REQUIRES_REVIEW")
    void testInboundLineageMismatch() {
        UUID importId = UUID.randomUUID();
        BankStatementImport imp = createImport(Collections.emptyList(), BigDecimal.ZERO, BigDecimal.ZERO);
        imp.setId(importId);

        UUID instructionId = UUID.randomUUID();
        PaymentInstruction instruction = new PaymentInstruction("INST-6", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), BigDecimal.valueOf(2000), CURRENCY, PROVIDER, "IDEM-6", adminUser.getId());
        instruction.setId(instructionId);
        instruction.setProviderAccountId(PROVIDER_ACCOUNT_ID);
        instruction.setStatus(InstructionStatus.SUCCESS);

        // Transaction has mismatched amount with instruction!
        PaymentTransaction transaction = new PaymentTransaction("TXN-6", instructionId, PROVIDER, null,
                BigDecimal.valueOf(1000), CURRENCY, "SUCCESS", "UTR-666", "{}");

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(Collections.emptyList());
        when(paymentTransactionRepository.findInboundReconciliationCandidates(any(), any(), any(), any()))
                .thenReturn(List.of(transaction));
        when(paymentInstructionRepository.findById(instructionId)).thenReturn(Optional.of(instruction));
        when(settlementInstructionRepository.findOutboundReconciliationCandidates(any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);

        assertThat(result.clean()).isFalse();
        ReconciliationItem item = result.items().get(0);
        assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.REQUIRES_REVIEW);
        assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.INTERNAL_LINEAGE_MISMATCH);
    }

    @Test
    @DisplayName("18. DEBIT bank line for inbound transaction -> STATUS_MISMATCH (DIRECTION_MISMATCH)")
    void testInboundWrongDirection() {
        UUID importId = UUID.randomUUID();
        BankStatementLine line = createLine(importId, "L1", "TXN-7", "UTR-7", BigDecimal.valueOf(1000), CURRENCY,
                FinancialDirection.DEBIT, BankStatementLineStatus.FINAL_SUCCESS, windowStart.plusSeconds(100));
        BankStatementImport imp = createImport(List.of(line), BigDecimal.valueOf(1000), BigDecimal.ZERO);
        imp.setId(importId);

        UUID instructionId = UUID.randomUUID();
        PaymentInstruction instruction = new PaymentInstruction("INST-7", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), BigDecimal.valueOf(1000), CURRENCY, PROVIDER, "IDEM-7", adminUser.getId());
        instruction.setId(instructionId);
        instruction.setProviderAccountId(PROVIDER_ACCOUNT_ID);
        instruction.setStatus(InstructionStatus.SUCCESS);

        PaymentTransaction transaction = new PaymentTransaction("TXN-7", instructionId, PROVIDER, null,
                BigDecimal.valueOf(1000), CURRENCY, "SUCCESS", "UTR-7", "{}");

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));
        when(paymentTransactionRepository.findInboundReconciliationCandidates(any(), any(), any(), any()))
                .thenReturn(List.of(transaction));
        when(paymentInstructionRepository.findById(instructionId)).thenReturn(Optional.of(instruction));
        when(settlementInstructionRepository.findOutboundReconciliationCandidates(any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);

        assertThat(result.clean()).isFalse();
        ReconciliationItem item = result.items().get(0);
        assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.STATUS_MISMATCH);
        assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.DIRECTION_MISMATCH);
    }

    @Test
    @DisplayName("20. Amount mismatch -> AMOUNT_MISMATCH")
    void testInboundAmountMismatch() {
        UUID importId = UUID.randomUUID();
        BankStatementLine line = createLine(importId, "L1", "TXN-8", "UTR-8", BigDecimal.valueOf(1000), CURRENCY,
                FinancialDirection.CREDIT, BankStatementLineStatus.FINAL_SUCCESS, windowStart.plusSeconds(100));
        BankStatementImport imp = createImport(List.of(line), BigDecimal.ZERO, BigDecimal.valueOf(1000));
        imp.setId(importId);

        UUID instructionId = UUID.randomUUID();
        PaymentInstruction instruction = new PaymentInstruction("INST-8", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), BigDecimal.valueOf(1500), CURRENCY, PROVIDER, "IDEM-8", adminUser.getId());
        instruction.setId(instructionId);
        instruction.setProviderAccountId(PROVIDER_ACCOUNT_ID);
        instruction.setStatus(InstructionStatus.SUCCESS);

        PaymentTransaction transaction = new PaymentTransaction("TXN-8", instructionId, PROVIDER, null,
                BigDecimal.valueOf(1500), CURRENCY, "SUCCESS", "UTR-8", "{}");

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));
        when(paymentTransactionRepository.findInboundReconciliationCandidates(any(), any(), any(), any()))
                .thenReturn(List.of(transaction));
        when(paymentInstructionRepository.findById(instructionId)).thenReturn(Optional.of(instruction));
        when(settlementInstructionRepository.findOutboundReconciliationCandidates(any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);

        assertThat(result.clean()).isFalse();
        ReconciliationItem item = result.items().get(0);
        assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.AMOUNT_MISMATCH);
        assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.AMOUNT_MISMATCH);
    }

    @Test
    @DisplayName("21-24. Bank finality non-success checks (PENDING/UNKNOWN/FAILED/REVERSED)")
    void testInboundBankFinalityVariations() {
        for (BankStatementLineStatus status : List.of(
                BankStatementLineStatus.PENDING,
                BankStatementLineStatus.UNKNOWN,
                BankStatementLineStatus.FAILED,
                BankStatementLineStatus.REVERSED)) {

            UUID importId = UUID.randomUUID();
            BankStatementLine line = createLine(importId, "L-" + status, "TXN-" + status, "UTR-" + status, BigDecimal.valueOf(1000), CURRENCY,
                    FinancialDirection.CREDIT, status, windowStart.plusSeconds(100));
            BankStatementImport imp = createImport(List.of(line), BigDecimal.ZERO, BigDecimal.valueOf(1000));
            imp.setId(importId);

            UUID instructionId = UUID.randomUUID();
            PaymentInstruction instruction = new PaymentInstruction("INST-" + status, UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(), BigDecimal.valueOf(1000), CURRENCY, PROVIDER, "IDEM-" + status, adminUser.getId());
            instruction.setId(instructionId);
            instruction.setProviderAccountId(PROVIDER_ACCOUNT_ID);
            instruction.setStatus(InstructionStatus.SUCCESS);

            PaymentTransaction transaction = new PaymentTransaction("TXN-" + status, instructionId, PROVIDER, null,
                    BigDecimal.valueOf(1000), CURRENCY, "SUCCESS", "UTR-" + status, "{}");

            when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
            when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));
            when(paymentTransactionRepository.findInboundReconciliationCandidates(any(), any(), any(), any()))
                    .thenReturn(List.of(transaction));
            when(paymentInstructionRepository.findById(instructionId)).thenReturn(Optional.of(instruction));
            when(settlementInstructionRepository.findOutboundReconciliationCandidates(any(), any(), any(), any()))
                    .thenReturn(Collections.emptyList());

            DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);
            assertThat(result.clean()).isFalse();

            ReconciliationItem item = result.items().get(0);
            if (status == BankStatementLineStatus.PENDING) {
                assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.REQUIRES_REVIEW);
                assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.BANK_FINALITY_PENDING);
            } else if (status == BankStatementLineStatus.UNKNOWN) {
                assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.REQUIRES_REVIEW);
                assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.BANK_FINALITY_UNKNOWN);
            } else if (status == BankStatementLineStatus.FAILED) {
                assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.STATUS_MISMATCH);
                assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.BANK_FINALITY_FAILED);
            } else if (status == BankStatementLineStatus.REVERSED) {
                assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.STATUS_MISMATCH);
                assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.BANK_FINALITY_REVERSED);
            }
        }
    }

    // ==========================================
    // 25-33: OUTBOUND SETTLEMENT RECONCILIATION TESTS
    // ==========================================

    @Test
    @DisplayName("25. SETTLED settlement + UTR + valid DEBIT -> MATCHED")
    void testOutboundSettlementMatched() {
        UUID importId = UUID.randomUUID();
        BankStatementLine line = createLine(importId, "L-SETT-1", "SETT-1", "UTR-SETT-1", BigDecimal.valueOf(3000), CURRENCY,
                FinancialDirection.DEBIT, BankStatementLineStatus.FINAL_SUCCESS, windowStart.plusSeconds(100));
        BankStatementImport imp = createImport(List.of(line), BigDecimal.valueOf(3000), BigDecimal.ZERO);
        imp.setId(importId);

        SettlementInstruction settlement = new SettlementInstruction("SETT-1", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), BigDecimal.valueOf(3000), CURRENCY, PROVIDER, "IDEM-SETT-1", adminUser.getId());
        settlement.setProviderAccountId(PROVIDER_ACCOUNT_ID);
        settlement.setStatus(SettlementStatus.SETTLED);
        settlement.setSettledAt(windowStart.plusSeconds(50));
        settlement.setUtrNumber("UTR-SETT-1");

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));
        when(paymentTransactionRepository.findInboundReconciliationCandidates(any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(settlementInstructionRepository.findOutboundReconciliationCandidates(any(), any(), any(), any()))
                .thenReturn(List.of(settlement));

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);

        assertThat(result.clean()).isTrue();
        assertThat(result.run().getMatchedCount()).isEqualTo(1);
        ReconciliationItem item = result.items().get(0);
        assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.MATCHED);
        assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.MATCHED_CONFIRMED);
    }

    @Test
    @DisplayName("28. Settlement missing externally -> MISSING_EXTERNAL")
    void testOutboundMissingExternal() {
        UUID importId = UUID.randomUUID();
        BankStatementImport imp = createImport(Collections.emptyList(), BigDecimal.ZERO, BigDecimal.ZERO);
        imp.setId(importId);

        SettlementInstruction settlement = new SettlementInstruction("SETT-2", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), BigDecimal.valueOf(3000), CURRENCY, PROVIDER, "IDEM-SETT-2", adminUser.getId());
        settlement.setProviderAccountId(PROVIDER_ACCOUNT_ID);
        settlement.setStatus(SettlementStatus.SETTLED);
        settlement.setSettledAt(windowStart.plusSeconds(50));

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(Collections.emptyList());
        when(paymentTransactionRepository.findInboundReconciliationCandidates(any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(settlementInstructionRepository.findOutboundReconciliationCandidates(any(), any(), any(), any()))
                .thenReturn(List.of(settlement));

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);

        assertThat(result.clean()).isFalse();
        ReconciliationItem item = result.items().get(0);
        assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.MISSING_EXTERNAL);
        assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.MISSING_EXTERNAL);
    }

    @Test
    @DisplayName("29. Settlement amount mismatch -> AMOUNT_MISMATCH")
    void testOutboundAmountMismatch() {
        UUID importId = UUID.randomUUID();
        BankStatementLine line = createLine(importId, "L-SETT-3", "SETT-3", "UTR-SETT-3", BigDecimal.valueOf(2500), CURRENCY,
                FinancialDirection.DEBIT, BankStatementLineStatus.FINAL_SUCCESS, windowStart.plusSeconds(100));
        BankStatementImport imp = createImport(List.of(line), BigDecimal.valueOf(2500), BigDecimal.ZERO);
        imp.setId(importId);

        SettlementInstruction settlement = new SettlementInstruction("SETT-3", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), BigDecimal.valueOf(3000), CURRENCY, PROVIDER, "IDEM-SETT-3", adminUser.getId());
        settlement.setProviderAccountId(PROVIDER_ACCOUNT_ID);
        settlement.setStatus(SettlementStatus.SETTLED);
        settlement.setSettledAt(windowStart.plusSeconds(50));
        settlement.setUtrNumber("UTR-SETT-3");

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));
        when(paymentTransactionRepository.findInboundReconciliationCandidates(any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(settlementInstructionRepository.findOutboundReconciliationCandidates(any(), any(), any(), any()))
                .thenReturn(List.of(settlement));

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);

        assertThat(result.clean()).isFalse();
        ReconciliationItem item = result.items().get(0);
        assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.AMOUNT_MISMATCH);
        assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.AMOUNT_MISMATCH);
    }

    @Test
    @DisplayName("33. SETTLED with null settledAt -> REQUIRES_REVIEW (INTERNAL_LINEAGE_MISMATCH)")
    void testOutboundNullSettledAt() {
        UUID importId = UUID.randomUUID();
        BankStatementImport imp = createImport(Collections.emptyList(), BigDecimal.ZERO, BigDecimal.ZERO);
        imp.setId(importId);

        SettlementInstruction settlement = new SettlementInstruction("SETT-4", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), BigDecimal.valueOf(3000), CURRENCY, PROVIDER, "IDEM-SETT-4", adminUser.getId());
        settlement.setProviderAccountId(PROVIDER_ACCOUNT_ID);
        settlement.setStatus(SettlementStatus.SETTLED);
        settlement.setSettledAt(null); // Invalid!

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(Collections.emptyList());
        when(paymentTransactionRepository.findInboundReconciliationCandidates(any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(settlementInstructionRepository.findOutboundReconciliationCandidates(any(), any(), any(), any()))
                .thenReturn(List.of(settlement));

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);

        assertThat(result.clean()).isFalse();
        ReconciliationItem item = result.items().get(0);
        assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.REQUIRES_REVIEW);
        assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.INTERNAL_LINEAGE_MISMATCH);
    }

    // ==========================================
    // 34-37: AMBIGUITY & CONFLICT TESTS
    // ==========================================

    @Test
    @DisplayName("34. Two lines with same strongest reference -> DUPLICATE (REFERENCE_AMBIGUOUS)")
    void testTwoLinesSameReferenceAmbiguous() {
        UUID importId = UUID.randomUUID();
        BankStatementLine line1 = createLine(importId, "L1", "TXN-AMB", "UTR-AMB", BigDecimal.valueOf(1000), CURRENCY,
                FinancialDirection.CREDIT, BankStatementLineStatus.FINAL_SUCCESS, windowStart.plusSeconds(100));
        BankStatementLine line2 = createLine(importId, "L2", "TXN-AMB", "UTR-AMB", BigDecimal.valueOf(1000), CURRENCY,
                FinancialDirection.CREDIT, BankStatementLineStatus.FINAL_SUCCESS, windowStart.plusSeconds(200));
        BankStatementImport imp = createImport(List.of(line1, line2), BigDecimal.ZERO, BigDecimal.valueOf(2000));
        imp.setId(importId);

        UUID instructionId = UUID.randomUUID();
        PaymentInstruction instruction = new PaymentInstruction("INST-AMB", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), BigDecimal.valueOf(1000), CURRENCY, PROVIDER, "IDEM-AMB", adminUser.getId());
        instruction.setId(instructionId);
        instruction.setProviderAccountId(PROVIDER_ACCOUNT_ID);
        instruction.setStatus(InstructionStatus.SUCCESS);

        PaymentTransaction transaction = new PaymentTransaction("TXN-AMB", instructionId, PROVIDER, null,
                BigDecimal.valueOf(1000), CURRENCY, "SUCCESS", "UTR-AMB", "{}");

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line1, line2));
        when(paymentTransactionRepository.findInboundReconciliationCandidates(any(), any(), any(), any()))
                .thenReturn(List.of(transaction));
        when(paymentInstructionRepository.findById(instructionId)).thenReturn(Optional.of(instruction));
        when(settlementInstructionRepository.findOutboundReconciliationCandidates(any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);

        assertThat(result.clean()).isFalse();
        ReconciliationItem ambItem = result.items().stream()
                .filter(i -> i.getReasonCode() == ReconciliationReasonCode.REFERENCE_AMBIGUOUS)
                .findFirst().orElseThrow();
        assertThat(ambItem.getStatus()).isEqualTo(ReconciliationItemStatus.DUPLICATE);
        assertThat(ambItem.getBankStatementLineId()).isNull();

        // The two unconsumed bank lines become unclaimed items!
        long unclaimedCount = result.items().stream()
                .filter(i -> i.getReasonCode() == ReconciliationReasonCode.UNCLAIMED_FINAL_BANK_LINE)
                .count();
        assertThat(unclaimedCount).isEqualTo(2);
    }

    @Test
    @DisplayName("35-37. One line claimed by two internal effects -> both DUPLICATE, no first-row wins")
    void testOneLineClaimedByTwoInternalEffects() {
        UUID importId = UUID.randomUUID();
        BankStatementLine line = createLine(importId, "L1", "SHARED-REF", "SHARED-UTR", BigDecimal.valueOf(1000), CURRENCY,
                FinancialDirection.CREDIT, BankStatementLineStatus.FINAL_SUCCESS, windowStart.plusSeconds(100));
        BankStatementImport imp = createImport(List.of(line), BigDecimal.ZERO, BigDecimal.valueOf(1000));
        imp.setId(importId);

        UUID instId1 = UUID.randomUUID();
        PaymentInstruction inst1 = new PaymentInstruction("INST-C1", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), BigDecimal.valueOf(1000), CURRENCY, PROVIDER, "IDEM-C1", adminUser.getId());
        inst1.setId(instId1);
        inst1.setProviderAccountId(PROVIDER_ACCOUNT_ID);
        inst1.setStatus(InstructionStatus.SUCCESS);

        PaymentTransaction txn1 = new PaymentTransaction("TXN-C1", instId1, PROVIDER, null,
                BigDecimal.valueOf(1000), CURRENCY, "SUCCESS", "SHARED-UTR", "{}");

        UUID instId2 = UUID.randomUUID();
        PaymentInstruction inst2 = new PaymentInstruction("INST-C2", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), BigDecimal.valueOf(1000), CURRENCY, PROVIDER, "IDEM-C2", adminUser.getId());
        inst2.setId(instId2);
        inst2.setProviderAccountId(PROVIDER_ACCOUNT_ID);
        inst2.setStatus(InstructionStatus.SUCCESS);

        PaymentTransaction txn2 = new PaymentTransaction("TXN-C2", instId2, PROVIDER, null,
                BigDecimal.valueOf(1000), CURRENCY, "SUCCESS", "SHARED-UTR", "{}");

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));
        when(paymentTransactionRepository.findInboundReconciliationCandidates(any(), any(), any(), any()))
                .thenReturn(List.of(txn1, txn2));
        when(paymentInstructionRepository.findById(instId1)).thenReturn(Optional.of(inst1));
        when(paymentInstructionRepository.findById(instId2)).thenReturn(Optional.of(inst2));
        when(settlementInstructionRepository.findOutboundReconciliationCandidates(any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);

        assertThat(result.clean()).isFalse();
        List<ReconciliationItem> duplicates = result.items().stream()
                .filter(i -> i.getStatus() == ReconciliationItemStatus.DUPLICATE)
                .toList();
        assertThat(duplicates).hasSize(2);
        assertThat(duplicates.get(0).getBankStatementLineId()).isNull();
        assertThat(duplicates.get(1).getBankStatementLineId()).isNull();

        // Line is left unconsumed and becomes UNCLAIMED_FINAL_BANK_LINE
        boolean unclaimedFound = result.items().stream()
                .anyMatch(i -> i.getReasonCode() == ReconciliationReasonCode.UNCLAIMED_FINAL_BANK_LINE);
        assertThat(unclaimedFound).isTrue();
    }

    // ==========================================
    // 38-42: BANK-ONLY UNCLAIMED LINE TESTS
    // ==========================================

    @Test
    @DisplayName("38. Unmatched FINAL_SUCCESS line -> MISSING_INTERNAL (UNCLAIMED_FINAL_BANK_LINE)")
    void testUnmatchedFinalSuccessBankLine() {
        UUID importId = UUID.randomUUID();
        BankStatementLine line = createLine(importId, "L-UNCLAIMED", "UNCLAIMED-TXN", "UNCLAIMED-UTR", BigDecimal.valueOf(700), CURRENCY,
                FinancialDirection.CREDIT, BankStatementLineStatus.FINAL_SUCCESS, windowStart.plusSeconds(100));
        BankStatementImport imp = createImport(List.of(line), BigDecimal.ZERO, BigDecimal.valueOf(700));
        imp.setId(importId);

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));
        when(paymentTransactionRepository.findInboundReconciliationCandidates(any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(settlementInstructionRepository.findOutboundReconciliationCandidates(any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);

        assertThat(result.clean()).isFalse();
        ReconciliationItem item = result.items().get(0);
        assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.MISSING_INTERNAL);
        assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.UNCLAIMED_FINAL_BANK_LINE);
        assertThat(item.getBankStatementLineId()).isEqualTo(line.getId());
    }

    // ==========================================
    // 43-46: BALANCE CONTROL TESTS
    // ==========================================

    @Test
    @DisplayName("43. Missing balances -> REQUIRES_REVIEW (STATEMENT_BALANCE_MISSING)")
    void testMissingBalances() {
        UUID importId = UUID.randomUUID();
        BankStatementImport imp = createImport(Collections.emptyList(), null, null);
        imp.setId(importId);

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(Collections.emptyList());
        when(paymentTransactionRepository.findInboundReconciliationCandidates(any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(settlementInstructionRepository.findOutboundReconciliationCandidates(any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);

        assertThat(result.clean()).isFalse();
        ReconciliationItem item = result.items().get(0);
        assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.REQUIRES_REVIEW);
        assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.STATEMENT_BALANCE_MISSING);
    }

    @Test
    @DisplayName("45. All-final invalid balance arithmetic -> STATEMENT_BALANCE_MISMATCH")
    void testBalanceMismatch() {
        UUID importId = UUID.randomUUID();
        BankStatementLine line = createLine(importId, "L1", "TXN-1", "UTR-1", BigDecimal.valueOf(100), CURRENCY,
                FinancialDirection.CREDIT, BankStatementLineStatus.FINAL_SUCCESS, windowStart.plusSeconds(100));
        // opening 0, credit 100, closing claims 500!
        BankStatementImport imp = createImport(List.of(line), BigDecimal.ZERO, BigDecimal.valueOf(500));
        imp.setId(importId);

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));
        when(paymentTransactionRepository.findInboundReconciliationCandidates(any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(settlementInstructionRepository.findOutboundReconciliationCandidates(any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);

        assertThat(result.clean()).isFalse();
        boolean balanceMismatchFound = result.items().stream()
                .anyMatch(i -> i.getReasonCode() == ReconciliationReasonCode.STATEMENT_BALANCE_MISMATCH);
        assertThat(balanceMismatchFound).isTrue();
    }

    // ==========================================
    // 53-54: REFERENCE PRIORITY TESTS (Part AU)
    // ==========================================

    @Test
    @DisplayName("53. UTR match wins over weaker reference match")
    void testUtrWinsOverWeakerReference() {
        UUID importId = UUID.randomUUID();
        // Line 1 matches UTR
        BankStatementLine line1 = createLine(importId, "L1", "OTHER-REF", "UTR-PRIMARY", BigDecimal.valueOf(1000), CURRENCY,
                FinancialDirection.CREDIT, BankStatementLineStatus.FINAL_SUCCESS, windowStart.plusSeconds(100));
        // Line 2 matches transactionReference
        BankStatementLine line2 = createLine(importId, "L2", "TXN-WEAK", "UTR-OTHER", BigDecimal.valueOf(1000), CURRENCY,
                FinancialDirection.CREDIT, BankStatementLineStatus.FINAL_SUCCESS, windowStart.plusSeconds(200));

        BankStatementImport imp = createImport(List.of(line1, line2), BigDecimal.ZERO, BigDecimal.valueOf(2000));
        imp.setId(importId);

        UUID instructionId = UUID.randomUUID();
        PaymentInstruction instruction = new PaymentInstruction("INST-PRI", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), BigDecimal.valueOf(1000), CURRENCY, PROVIDER, "IDEM-PRI", adminUser.getId());
        instruction.setId(instructionId);
        instruction.setProviderAccountId(PROVIDER_ACCOUNT_ID);
        instruction.setStatus(InstructionStatus.SUCCESS);

        // Transaction has both UTR-PRIMARY and TXN-WEAK
        PaymentTransaction transaction = new PaymentTransaction("TXN-WEAK", instructionId, PROVIDER, null,
                BigDecimal.valueOf(1000), CURRENCY, "SUCCESS", "UTR-PRIMARY", "{}");

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line1, line2));
        when(paymentTransactionRepository.findInboundReconciliationCandidates(any(), any(), any(), any()))
                .thenReturn(List.of(transaction));
        when(paymentInstructionRepository.findById(instructionId)).thenReturn(Optional.of(instruction));
        when(settlementInstructionRepository.findOutboundReconciliationCandidates(any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);

        ReconciliationItem matchedItem = result.items().stream()
                .filter(i -> i.getStatus() == ReconciliationItemStatus.MATCHED)
                .findFirst().orElseThrow();

        assertThat(matchedItem.getBankStatementLineId()).isEqualTo(line1.getId());
    }
}
