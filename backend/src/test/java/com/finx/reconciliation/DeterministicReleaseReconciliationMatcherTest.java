package com.finx.reconciliation;

import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
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
import com.finx.reconciliation.enums.ReconciliationEffectType;
import com.finx.reconciliation.enums.ReconciliationEnums.ReconciliationItemStatus;
import com.finx.reconciliation.enums.ReconciliationReasonCode;
import com.finx.reconciliation.repository.BankStatementImportRepository;
import com.finx.reconciliation.repository.BankStatementLineRepository;
import com.finx.reconciliation.repository.ReconciliationItemRepository;
import com.finx.reconciliation.repository.ReconciliationRunRepository;
import com.finx.reconciliation.service.BankStatementEvidenceFingerprint;
import com.finx.reconciliation.service.DeterministicReconciliationMatcher;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.observation.entity.SettlementObservation;
import com.finx.release.observation.model.SettlementObservationFinality;
import com.finx.release.observation.model.SettlementObservationSource;
import com.finx.release.observation.repository.SettlementObservationRepository;
import com.finx.release.observation.service.SettlementObservationEvidenceReader;
import com.finx.release.repository.ReleaseInstructionRepository;
import com.finx.security.service.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class DeterministicReleaseReconciliationMatcherTest {

    @Mock private BankStatementImportRepository importRepository;
    @Mock private BankStatementLineRepository lineRepository;
    @Mock private PaymentTransactionRepository paymentTransactionRepository;
    @Mock private PaymentInstructionRepository paymentInstructionRepository;
    @Mock private SettlementInstructionRepository settlementInstructionRepository;
    @Mock private ReconciliationRunRepository runRepository;
    @Mock private ReconciliationItemRepository itemRepository;
    @Mock private ReleaseInstructionRepository releaseInstructionRepository;
    @Mock private SettlementObservationRepository settlementObservationRepository;
    @Mock private SettlementObservationEvidenceReader settlementObservationEvidenceReader;

    private DeterministicReconciliationMatcher matcher;
    private UserPrincipal adminUser;

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
                releaseInstructionRepository,
                settlementObservationRepository,
                settlementObservationEvidenceReader,
                runRepository,
                itemRepository
        );

        adminUser = new UserPrincipal(UUID.randomUUID(), "Admin", "admin@finx.com", "hash", Role.ADMIN, UserStatus.ACTIVE, Collections.emptyList());

        windowStart = Instant.now().minus(2, ChronoUnit.DAYS);
        windowEnd = Instant.now();

        lenient().when(runRepository.save(any(ReconciliationRun.class))).thenAnswer(invocation -> {
            ReconciliationRun r = invocation.getArgument(0);
            if (r.getId() == null) {
                r.setId(UUID.randomUUID());
            }
            return r;
        });
        lenient().when(itemRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(paymentTransactionRepository.findInboundReconciliationCandidates(any(), any(), any(), any())).thenReturn(Collections.emptyList());
        lenient().when(settlementInstructionRepository.findOutboundReconciliationCandidates(any(), any(), any(), any())).thenReturn(Collections.emptyList());
    }

    private BankStatementLine createDebitLine(UUID importId, String ref, BigDecimal amount, BankStatementLineStatus status, String utr) {
        String lineId = "LINE-" + UUID.randomUUID().toString().substring(0, 8);
        Instant txnTime = windowStart.plusSeconds(3600);
        String fp = BankStatementEvidenceFingerprint.computeLineFingerprint(
                PROVIDER, PROVIDER_ACCOUNT_ID, lineId, ref, utr, amount, CURRENCY,
                FinancialDirection.DEBIT.name(), "SUCCESS",
                status != null ? status.name() : null, txnTime, LocalDate.now()
        );
        return new BankStatementLine(importId, PROVIDER, PROVIDER_ACCOUNT_ID, lineId,
                ref, utr, amount, CURRENCY, FinancialDirection.DEBIT, "SUCCESS", status, txnTime, LocalDate.now(), fp);
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

    private BankStatementLine createCreditLine(UUID importId, String ref, BigDecimal amount, BankStatementLineStatus status, String utr) {
        String lineId = "LINE-" + UUID.randomUUID().toString().substring(0, 8);
        Instant txnTime = windowStart.plusSeconds(3600);
        String fp = BankStatementEvidenceFingerprint.computeLineFingerprint(
                PROVIDER, PROVIDER_ACCOUNT_ID, lineId, ref, utr, amount, CURRENCY,
                FinancialDirection.CREDIT.name(), "SUCCESS",
                status != null ? status.name() : null, txnTime, LocalDate.now()
        );
        return new BankStatementLine(importId, PROVIDER, PROVIDER_ACCOUNT_ID, lineId,
                ref, utr, amount, CURRENCY, FinancialDirection.CREDIT, "SUCCESS", status, txnTime, LocalDate.now(), fp);
    }

    private BankStatementLine createDebitLineWithCurrency(UUID importId, String ref, BigDecimal amount, BankStatementLineStatus status, String utr, String currency) {
        String lineId = "LINE-" + UUID.randomUUID().toString().substring(0, 8);
        Instant txnTime = windowStart.plusSeconds(3600);
        String fp = BankStatementEvidenceFingerprint.computeLineFingerprint(
                PROVIDER, PROVIDER_ACCOUNT_ID, lineId, ref, utr, amount, currency,
                FinancialDirection.DEBIT.name(), "SUCCESS",
                status != null ? status.name() : null, txnTime, LocalDate.now()
        );
        return new BankStatementLine(importId, PROVIDER, PROVIDER_ACCOUNT_ID, lineId,
                ref, utr, amount, currency, FinancialDirection.DEBIT, "SUCCESS", status, txnTime, LocalDate.now(), fp);
    }

    private BankStatementImport createImportWithCurrency(List<BankStatementLine> lines, BigDecimal opening, BigDecimal closing, String currency) {
        List<String> fingerprints = lines.stream().map(BankStatementLine::getLineFingerprint).toList();
        String sourceHash = BankStatementEvidenceFingerprint.computeSourceHash(
                PROVIDER, PROVIDER_ACCOUNT_ID, currency, opening, closing, "IMP-REF-1", fingerprints, windowStart, windowEnd
        );

        BankStatementImport imp = new BankStatementImport(
                PROVIDER, PROVIDER_ACCOUNT_ID, windowStart, windowEnd, sourceHash,
                opening, closing, currency, adminUser.getId()
        );
        imp.setStatus(BankStatementImportStatus.COMPLETED);
        imp.setProviderImportReference("IMP-REF-1");
        imp.setTotalLines(lines.size());
        return imp;
    }

    private ReleaseInstruction createReleaseInstruction(UUID releaseId, BigDecimal amount) {
        ReleaseInstruction ri = new ReleaseInstruction(
                releaseId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "a".repeat(64),
                UUID.randomUUID(),
                "b".repeat(64),
                UUID.randomUUID(),
                PROVIDER,
                PROVIDER_ACCOUNT_ID,
                amount,
                CURRENCY,
                UUID.randomUUID().toString(),
                "c".repeat(64),
                adminUser.getId()
        );
        ReflectionTestUtils.setField(ri, "status", ReleaseInstructionStatus.SETTLED);
        ReflectionTestUtils.setField(ri, "settledAt", windowStart.plusSeconds(1800));
        ReflectionTestUtils.setField(ri, "authorizedAt", windowStart.plusSeconds(1200));
        return ri;
    }

    private SettlementObservation createFinalSuccessObservation(UUID releaseId, String utr, String settleId, BigDecimal amount) {
        return SettlementObservation.builder()
                .id(UUID.randomUUID())
                .releaseInstructionId(releaseId)
                .executionAttemptId(UUID.randomUUID())
                .provider(PROVIDER)
                .source(SettlementObservationSource.WEBHOOK)
                .sourceReference("EVT-1")
                .providerOperationKey("FINX-REL-" + releaseId)
                .providerSettlementId(settleId)
                .providerAccountId(PROVIDER_ACCOUNT_ID)
                .providerStatus("SETTLED")
                .finality(SettlementObservationFinality.FINAL_SUCCESS)
                .reasonCode("SETTLEMENT_FINAL_CONFIRMED")
                .utrNumber(utr)
                .evidenceHash("a".repeat(64))
                .observedAmount(amount)
                .observedCurrency(CURRENCY)
                .observedDirection("DEBIT")
                .observedAt(windowStart.plusSeconds(3600))
                .createdAt(windowStart.plusSeconds(3600))
                .build();
    }

    @Test
    @DisplayName("1, 2, 3, 4: Exact ReleaseInstruction debit match -> MATCHED, effectType RELEASE_INSTRUCTION, effectId release.id, UTR match")
    void exactReleaseInstructionDebitMatchWithUtr() {
        UUID releaseId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("70.0000");
        String utr = "UTR-REL-123456";

        UUID importId = UUID.randomUUID();
        BankStatementLine line = createDebitLine(importId, "TXN-001", amount, BankStatementLineStatus.FINAL_SUCCESS, utr);
        BankStatementImport imp = createImport(List.of(line), new BigDecimal("100.0000"), new BigDecimal("30.0000"));
        ReflectionTestUtils.setField(line, "importId", imp.getId());

        ReleaseInstruction ri = createReleaseInstruction(releaseId, amount);
        SettlementObservation obs = createFinalSuccessObservation(releaseId, utr, "SETTLE-001", amount);
        ReflectionTestUtils.setField(ri, "finalSettlementObservationId", obs.getId());

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));
        when(releaseInstructionRepository.findOutboundReconciliationCandidates(eq(PROVIDER), eq(PROVIDER_ACCOUNT_ID), any(), any()))
                .thenReturn(List.of(ri));
        when(settlementObservationRepository.findById(obs.getId())).thenReturn(Optional.of(obs));
        when(settlementObservationEvidenceReader.getLatestAuthoritativeFinalObservation(releaseId))
                .thenReturn(new SettlementObservationEvidenceReader.AuthoritativeObservationResult(
                        SettlementObservationEvidenceReader.AuthoritativeStatus.FINAL_SUCCESS, obs, "Clean"
                ));

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);

        assertThat(result.clean()).isTrue();
        assertThat(result.run().getMatchedCount()).isEqualTo(1);
        assertThat(result.run().getMismatchCount()).isEqualTo(0);

        ArgumentCaptor<List<ReconciliationItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(captor.capture());
        List<ReconciliationItem> saved = captor.getValue();
        assertThat(saved).hasSize(1);
        ReconciliationItem item = saved.get(0);
        assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.MATCHED);
        assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.MATCHED_CONFIRMED);
        assertThat(item.getInternalEffectType()).isEqualTo(ReconciliationEffectType.RELEASE_INSTRUCTION);
        assertThat(item.getInternalEffectId()).isEqualTo(releaseId);
        assertThat(item.getBankStatementLineId()).isEqualTo(line.getId());
        assertThat(item.getFinxTransactionReference()).isEqualTo("FINX-REL-" + releaseId);
        assertThat(item.getBankTransactionReference()).isEqualTo("TXN-001");
        assertThat(item.getDirection()).isEqualTo(FinancialDirection.DEBIT);
    }

    @Test
    @DisplayName("5. Provider settlement ID fallback match")
    void providerSettlementIdFallbackMatch() {
        UUID releaseId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("50.0000");
        String settleId = "SETTLE-FALLBACK-001";

        UUID importId = UUID.randomUUID();
        BankStatementLine line = createDebitLine(importId, settleId, amount, BankStatementLineStatus.FINAL_SUCCESS, null);
        BankStatementImport imp = createImport(List.of(line), new BigDecimal("100.0000"), new BigDecimal("50.0000"));
        ReflectionTestUtils.setField(line, "importId", imp.getId());

        ReleaseInstruction ri = createReleaseInstruction(releaseId, amount);
        SettlementObservation obs = createFinalSuccessObservation(releaseId, "UTR-UNRECORDED-ON-LINE", settleId, amount);
        ReflectionTestUtils.setField(ri, "finalSettlementObservationId", obs.getId());

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));
        when(releaseInstructionRepository.findOutboundReconciliationCandidates(eq(PROVIDER), eq(PROVIDER_ACCOUNT_ID), any(), any()))
                .thenReturn(List.of(ri));
        when(settlementObservationRepository.findById(obs.getId())).thenReturn(Optional.of(obs));
        when(settlementObservationEvidenceReader.getLatestAuthoritativeFinalObservation(releaseId))
                .thenReturn(new SettlementObservationEvidenceReader.AuthoritativeObservationResult(
                        SettlementObservationEvidenceReader.AuthoritativeStatus.FINAL_SUCCESS, obs, "Clean"
                ));

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);

        // Policy for absent line UTR when observation has UTR: REQUIRES_REVIEW / UTR_MISMATCH
        assertThat(result.clean()).isFalse();
        ArgumentCaptor<List<ReconciliationItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(captor.capture());
        ReconciliationItem item = captor.getValue().get(0);
        assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.REQUIRES_REVIEW);
        assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.UTR_MISMATCH);
    }

    @Test
    @DisplayName("6. Provider operation key fallback match with matching UTR")
    void providerOperationKeyFallbackMatch() {
        UUID releaseId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("40.0000");
        String opKey = "FINX-REL-" + releaseId;
        String utr = "UTR-99999";

        UUID importId = UUID.randomUUID();
        BankStatementLine line = createDebitLine(importId, opKey, amount, BankStatementLineStatus.FINAL_SUCCESS, utr);
        BankStatementImport imp = createImport(List.of(line), new BigDecimal("100.0000"), new BigDecimal("60.0000"));
        ReflectionTestUtils.setField(line, "importId", imp.getId());

        ReleaseInstruction ri = createReleaseInstruction(releaseId, amount);
        SettlementObservation obs = createFinalSuccessObservation(releaseId, utr, "SETTLE-OTHER", amount);
        ReflectionTestUtils.setField(ri, "finalSettlementObservationId", obs.getId());

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));
        when(releaseInstructionRepository.findOutboundReconciliationCandidates(eq(PROVIDER), eq(PROVIDER_ACCOUNT_ID), any(), any()))
                .thenReturn(List.of(ri));
        when(settlementObservationRepository.findById(obs.getId())).thenReturn(Optional.of(obs));
        when(settlementObservationEvidenceReader.getLatestAuthoritativeFinalObservation(releaseId))
                .thenReturn(new SettlementObservationEvidenceReader.AuthoritativeObservationResult(
                        SettlementObservationEvidenceReader.AuthoritativeStatus.FINAL_SUCCESS, obs, "Clean"
                ));

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);

        assertThat(result.clean()).isTrue();
        assertThat(result.run().getMatchedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("7. Missing external -> MISSING_EXTERNAL")
    void missingExternalReleaseDebit() {
        UUID releaseId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("40.0000");

        BankStatementImport imp = createImport(Collections.emptyList(), new BigDecimal("100.0000"), new BigDecimal("100.0000"));

        ReleaseInstruction ri = createReleaseInstruction(releaseId, amount);
        SettlementObservation obs = createFinalSuccessObservation(releaseId, "UTR-111", "SETTLE-111", amount);
        ReflectionTestUtils.setField(ri, "finalSettlementObservationId", obs.getId());

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(Collections.emptyList());
        when(releaseInstructionRepository.findOutboundReconciliationCandidates(eq(PROVIDER), eq(PROVIDER_ACCOUNT_ID), any(), any()))
                .thenReturn(List.of(ri));
        when(settlementObservationRepository.findById(obs.getId())).thenReturn(Optional.of(obs));
        when(settlementObservationEvidenceReader.getLatestAuthoritativeFinalObservation(releaseId))
                .thenReturn(new SettlementObservationEvidenceReader.AuthoritativeObservationResult(
                        SettlementObservationEvidenceReader.AuthoritativeStatus.FINAL_SUCCESS, obs, "Clean"
                ));

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);

        assertThat(result.clean()).isFalse();
        ArgumentCaptor<List<ReconciliationItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(captor.capture());
        ReconciliationItem item = captor.getValue().get(0);
        assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.MISSING_EXTERNAL);
        assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.MISSING_EXTERNAL);
        assertThat(item.getInternalEffectType()).isEqualTo(ReconciliationEffectType.RELEASE_INSTRUCTION);
    }

    @Test
    @DisplayName("8. Amount mismatch -> AMOUNT_MISMATCH")
    void amountMismatch() {
        UUID releaseId = UUID.randomUUID();
        BigDecimal releaseAmount = new BigDecimal("50.0000");
        BigDecimal bankAmount = new BigDecimal("49.0000");
        String utr = "UTR-AMT-DIFF";

        UUID importId = UUID.randomUUID();
        BankStatementLine line = createDebitLine(importId, "TXN-1", bankAmount, BankStatementLineStatus.FINAL_SUCCESS, utr);
        BankStatementImport imp = createImport(List.of(line), new BigDecimal("100.0000"), new BigDecimal("51.0000"));
        ReflectionTestUtils.setField(line, "importId", imp.getId());

        ReleaseInstruction ri = createReleaseInstruction(releaseId, releaseAmount);
        SettlementObservation obs = createFinalSuccessObservation(releaseId, utr, "SETTLE-1", releaseAmount);
        ReflectionTestUtils.setField(ri, "finalSettlementObservationId", obs.getId());

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));
        when(releaseInstructionRepository.findOutboundReconciliationCandidates(eq(PROVIDER), eq(PROVIDER_ACCOUNT_ID), any(), any()))
                .thenReturn(List.of(ri));
        when(settlementObservationRepository.findById(obs.getId())).thenReturn(Optional.of(obs));
        when(settlementObservationEvidenceReader.getLatestAuthoritativeFinalObservation(releaseId))
                .thenReturn(new SettlementObservationEvidenceReader.AuthoritativeObservationResult(
                        SettlementObservationEvidenceReader.AuthoritativeStatus.FINAL_SUCCESS, obs, "Clean"
                ));

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);

        assertThat(result.clean()).isFalse();
        ArgumentCaptor<List<ReconciliationItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(captor.capture());
        ReconciliationItem item = captor.getValue().get(0);
        assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.AMOUNT_MISMATCH);
        assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.AMOUNT_MISMATCH);
    }

    @Test
    @DisplayName("9. Currency mismatch -> CURRENCY_MISMATCH")
    void currencyMismatch() {
        UUID releaseId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("50.0000");
        String utr = "UTR-CURR-DIFF";

        UUID importId = UUID.randomUUID();
        BankStatementLine line = createDebitLineWithCurrency(importId, "TXN-1", amount, BankStatementLineStatus.FINAL_SUCCESS, utr, "USD");
        BankStatementImport imp = createImportWithCurrency(List.of(line), new BigDecimal("100.0000"), new BigDecimal("50.0000"), "USD");
        ReflectionTestUtils.setField(line, "importId", imp.getId());

        ReleaseInstruction ri = createReleaseInstruction(releaseId, amount);
        SettlementObservation obs = createFinalSuccessObservation(releaseId, utr, "SETTLE-1", amount);
        ReflectionTestUtils.setField(ri, "finalSettlementObservationId", obs.getId());

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));
        when(releaseInstructionRepository.findOutboundReconciliationCandidates(eq(PROVIDER), eq(PROVIDER_ACCOUNT_ID), any(), any()))
                .thenReturn(List.of(ri));
        when(settlementObservationRepository.findById(obs.getId())).thenReturn(Optional.of(obs));
        when(settlementObservationEvidenceReader.getLatestAuthoritativeFinalObservation(releaseId))
                .thenReturn(new SettlementObservationEvidenceReader.AuthoritativeObservationResult(
                        SettlementObservationEvidenceReader.AuthoritativeStatus.FINAL_SUCCESS, obs, "Clean"
                ));

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);

        assertThat(result.clean()).isFalse();
        ArgumentCaptor<List<ReconciliationItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(captor.capture());
        ReconciliationItem item = captor.getValue().get(0);
        assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.STATUS_MISMATCH);
        assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.CURRENCY_MISMATCH);
    }

    @Test
    @DisplayName("10. Direction mismatch -> DIRECTION_MISMATCH")
    void directionMismatch() {
        UUID releaseId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("50.0000");
        String utr = "UTR-DIR-DIFF";

        UUID importId = UUID.randomUUID();
        BankStatementLine line = createCreditLine(importId, "TXN-1", amount, BankStatementLineStatus.FINAL_SUCCESS, utr);
        BankStatementImport imp = createImport(List.of(line), new BigDecimal("100.0000"), new BigDecimal("150.0000"));
        ReflectionTestUtils.setField(line, "importId", imp.getId());

        ReleaseInstruction ri = createReleaseInstruction(releaseId, amount);
        SettlementObservation obs = createFinalSuccessObservation(releaseId, utr, "SETTLE-1", amount);
        ReflectionTestUtils.setField(ri, "finalSettlementObservationId", obs.getId());

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));
        when(releaseInstructionRepository.findOutboundReconciliationCandidates(eq(PROVIDER), eq(PROVIDER_ACCOUNT_ID), any(), any()))
                .thenReturn(List.of(ri));
        when(settlementObservationRepository.findById(obs.getId())).thenReturn(Optional.of(obs));
        when(settlementObservationEvidenceReader.getLatestAuthoritativeFinalObservation(releaseId))
                .thenReturn(new SettlementObservationEvidenceReader.AuthoritativeObservationResult(
                        SettlementObservationEvidenceReader.AuthoritativeStatus.FINAL_SUCCESS, obs, "Clean"
                ));

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);

        assertThat(result.clean()).isFalse();
        ArgumentCaptor<List<ReconciliationItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(captor.capture());
        ReconciliationItem item = captor.getValue().get(0);
        assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.STATUS_MISMATCH);
        assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.DIRECTION_MISMATCH);
    }

    @Test
    @DisplayName("11 & 12. Provider and Provider account mismatch handled by candidate query boundary")
    void providerAndAccountMismatchHandled() {
        BankStatementImport imp = createImport(Collections.emptyList(), new BigDecimal("100.00"), new BigDecimal("100.00"));
        when(importRepository.findById(any())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(any())).thenReturn(Collections.emptyList());
        when(releaseInstructionRepository.findOutboundReconciliationCandidates(eq(PROVIDER), eq(PROVIDER_ACCOUNT_ID), any(), any()))
                .thenReturn(Collections.emptyList());

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);
        assertThat(result.run().getTotalInternalTransactions()).isEqualTo(0);
    }

    @Test
    @DisplayName("13. Bank status FAILED -> STATUS_MISMATCH BANK_FINALITY_FAILED")
    void bankStatusFailed() {
        UUID releaseId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("50.0000");
        String utr = "UTR-FAIL";

        UUID importId = UUID.randomUUID();
        BankStatementLine line = createDebitLine(importId, "TXN-FAIL", amount, BankStatementLineStatus.FAILED, utr);
        BankStatementImport imp = createImport(List.of(line), new BigDecimal("100.0000"), new BigDecimal("100.0000"));
        ReflectionTestUtils.setField(line, "importId", imp.getId());

        ReleaseInstruction ri = createReleaseInstruction(releaseId, amount);
        SettlementObservation obs = createFinalSuccessObservation(releaseId, utr, "SETTLE-1", amount);
        ReflectionTestUtils.setField(ri, "finalSettlementObservationId", obs.getId());

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));
        when(releaseInstructionRepository.findOutboundReconciliationCandidates(eq(PROVIDER), eq(PROVIDER_ACCOUNT_ID), any(), any()))
                .thenReturn(List.of(ri));
        when(settlementObservationRepository.findById(obs.getId())).thenReturn(Optional.of(obs));
        when(settlementObservationEvidenceReader.getLatestAuthoritativeFinalObservation(releaseId))
                .thenReturn(new SettlementObservationEvidenceReader.AuthoritativeObservationResult(
                        SettlementObservationEvidenceReader.AuthoritativeStatus.FINAL_SUCCESS, obs, "Clean"
                ));

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);

        assertThat(result.clean()).isFalse();
        ArgumentCaptor<List<ReconciliationItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(captor.capture());
        ReconciliationItem item = captor.getValue().get(0);
        assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.STATUS_MISMATCH);
        assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.BANK_FINALITY_FAILED);
    }

    @Test
    @DisplayName("14. Bank status REVERSED -> STATUS_MISMATCH BANK_FINALITY_REVERSED")
    void bankStatusReversed() {
        UUID releaseId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("50.0000");
        String utr = "UTR-REV";

        UUID importId = UUID.randomUUID();
        BankStatementLine line = createDebitLine(importId, "TXN-REV", amount, BankStatementLineStatus.REVERSED, utr);
        BankStatementImport imp = createImport(List.of(line), new BigDecimal("100.0000"), new BigDecimal("100.0000"));
        ReflectionTestUtils.setField(line, "importId", imp.getId());

        ReleaseInstruction ri = createReleaseInstruction(releaseId, amount);
        SettlementObservation obs = createFinalSuccessObservation(releaseId, utr, "SETTLE-1", amount);
        ReflectionTestUtils.setField(ri, "finalSettlementObservationId", obs.getId());

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));
        when(releaseInstructionRepository.findOutboundReconciliationCandidates(eq(PROVIDER), eq(PROVIDER_ACCOUNT_ID), any(), any()))
                .thenReturn(List.of(ri));
        when(settlementObservationRepository.findById(obs.getId())).thenReturn(Optional.of(obs));
        when(settlementObservationEvidenceReader.getLatestAuthoritativeFinalObservation(releaseId))
                .thenReturn(new SettlementObservationEvidenceReader.AuthoritativeObservationResult(
                        SettlementObservationEvidenceReader.AuthoritativeStatus.FINAL_SUCCESS, obs, "Clean"
                ));

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);

        assertThat(result.clean()).isFalse();
        ArgumentCaptor<List<ReconciliationItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(captor.capture());
        ReconciliationItem item = captor.getValue().get(0);
        assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.STATUS_MISMATCH);
        assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.BANK_FINALITY_REVERSED);
    }

    @Test
    @DisplayName("15. Bank status PENDING -> REQUIRES_REVIEW BANK_FINALITY_PENDING")
    void bankStatusPending() {
        UUID releaseId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("50.0000");
        String utr = "UTR-PEND";

        UUID importId = UUID.randomUUID();
        BankStatementLine line = createDebitLine(importId, "TXN-PEND", amount, BankStatementLineStatus.PENDING, utr);
        BankStatementImport imp = createImport(List.of(line), new BigDecimal("100.0000"), new BigDecimal("100.0000"));
        ReflectionTestUtils.setField(line, "importId", imp.getId());

        ReleaseInstruction ri = createReleaseInstruction(releaseId, amount);
        SettlementObservation obs = createFinalSuccessObservation(releaseId, utr, "SETTLE-1", amount);
        ReflectionTestUtils.setField(ri, "finalSettlementObservationId", obs.getId());

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));
        when(releaseInstructionRepository.findOutboundReconciliationCandidates(eq(PROVIDER), eq(PROVIDER_ACCOUNT_ID), any(), any()))
                .thenReturn(List.of(ri));
        when(settlementObservationRepository.findById(obs.getId())).thenReturn(Optional.of(obs));
        when(settlementObservationEvidenceReader.getLatestAuthoritativeFinalObservation(releaseId))
                .thenReturn(new SettlementObservationEvidenceReader.AuthoritativeObservationResult(
                        SettlementObservationEvidenceReader.AuthoritativeStatus.FINAL_SUCCESS, obs, "Clean"
                ));

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);

        assertThat(result.clean()).isFalse();
        ArgumentCaptor<List<ReconciliationItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(captor.capture());
        ReconciliationItem item = captor.getValue().get(0);
        assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.REQUIRES_REVIEW);
        assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.BANK_FINALITY_PENDING);
    }

    @Test
    @DisplayName("16. Bank status UNKNOWN -> REQUIRES_REVIEW BANK_FINALITY_UNKNOWN")
    void bankStatusUnknown() {
        UUID releaseId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("50.0000");
        String utr = "UTR-UNK";

        UUID importId = UUID.randomUUID();
        BankStatementLine line = createDebitLine(importId, "TXN-UNK", amount, BankStatementLineStatus.UNKNOWN, utr);
        BankStatementImport imp = createImport(List.of(line), new BigDecimal("100.0000"), new BigDecimal("100.0000"));
        ReflectionTestUtils.setField(line, "importId", imp.getId());

        ReleaseInstruction ri = createReleaseInstruction(releaseId, amount);
        SettlementObservation obs = createFinalSuccessObservation(releaseId, utr, "SETTLE-1", amount);
        ReflectionTestUtils.setField(ri, "finalSettlementObservationId", obs.getId());

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));
        when(releaseInstructionRepository.findOutboundReconciliationCandidates(eq(PROVIDER), eq(PROVIDER_ACCOUNT_ID), any(), any()))
                .thenReturn(List.of(ri));
        when(settlementObservationRepository.findById(obs.getId())).thenReturn(Optional.of(obs));
        when(settlementObservationEvidenceReader.getLatestAuthoritativeFinalObservation(releaseId))
                .thenReturn(new SettlementObservationEvidenceReader.AuthoritativeObservationResult(
                        SettlementObservationEvidenceReader.AuthoritativeStatus.FINAL_SUCCESS, obs, "Clean"
                ));

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);

        assertThat(result.clean()).isFalse();
        ArgumentCaptor<List<ReconciliationItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(captor.capture());
        ReconciliationItem item = captor.getValue().get(0);
        assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.REQUIRES_REVIEW);
        assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.BANK_FINALITY_UNKNOWN);
    }

    @Test
    @DisplayName("17. Duplicate UTR lines -> DUPLICATE / REFERENCE_AMBIGUOUS")
    void duplicateUtrLinesAmbiguous() {
        UUID releaseId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("50.0000");
        String utr = "UTR-DUP-LINES";

        UUID importId = UUID.randomUUID();
        BankStatementLine line1 = createDebitLine(importId, "TXN-1", amount, BankStatementLineStatus.FINAL_SUCCESS, utr);
        BankStatementLine line2 = createDebitLine(importId, "TXN-2", amount, BankStatementLineStatus.FINAL_SUCCESS, utr);
        BankStatementImport imp = createImport(List.of(line1, line2), new BigDecimal("100.0000"), new BigDecimal("0.0000"));
        ReflectionTestUtils.setField(line1, "importId", imp.getId());
        ReflectionTestUtils.setField(line2, "importId", imp.getId());

        ReleaseInstruction ri = createReleaseInstruction(releaseId, amount);
        SettlementObservation obs = createFinalSuccessObservation(releaseId, utr, "SETTLE-1", amount);
        ReflectionTestUtils.setField(ri, "finalSettlementObservationId", obs.getId());

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line1, line2));
        when(releaseInstructionRepository.findOutboundReconciliationCandidates(eq(PROVIDER), eq(PROVIDER_ACCOUNT_ID), any(), any()))
                .thenReturn(List.of(ri));
        when(settlementObservationRepository.findById(obs.getId())).thenReturn(Optional.of(obs));
        when(settlementObservationEvidenceReader.getLatestAuthoritativeFinalObservation(releaseId))
                .thenReturn(new SettlementObservationEvidenceReader.AuthoritativeObservationResult(
                        SettlementObservationEvidenceReader.AuthoritativeStatus.FINAL_SUCCESS, obs, "Clean"
                ));

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);

        assertThat(result.clean()).isFalse();
        ArgumentCaptor<List<ReconciliationItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(captor.capture());
        ReconciliationItem item = captor.getValue().stream()
                .filter(i -> i.getInternalEffectType() == ReconciliationEffectType.RELEASE_INSTRUCTION)
                .findFirst().orElseThrow();
        assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.DUPLICATE);
        assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.REFERENCE_AMBIGUOUS);
    }

    @Test
    @DisplayName("18. Same bank line claimed twice -> duplicate conflict")
    void sameBankLineClaimedTwiceAmbiguous() {
        UUID releaseId1 = UUID.randomUUID();
        UUID releaseId2 = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("50.0000");
        String utr = "UTR-SHARED-LINE";

        UUID importId = UUID.randomUUID();
        BankStatementLine line = createDebitLine(importId, "TXN-1", amount, BankStatementLineStatus.FINAL_SUCCESS, utr);
        BankStatementImport imp = createImport(List.of(line), new BigDecimal("100.0000"), new BigDecimal("50.0000"));
        ReflectionTestUtils.setField(line, "importId", imp.getId());

        ReleaseInstruction ri1 = createReleaseInstruction(releaseId1, amount);
        SettlementObservation obs1 = createFinalSuccessObservation(releaseId1, utr, "SETTLE-1", amount);
        ReflectionTestUtils.setField(ri1, "finalSettlementObservationId", obs1.getId());

        ReleaseInstruction ri2 = createReleaseInstruction(releaseId2, amount);
        SettlementObservation obs2 = createFinalSuccessObservation(releaseId2, utr, "SETTLE-2", amount);
        ReflectionTestUtils.setField(ri2, "finalSettlementObservationId", obs2.getId());

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));
        when(releaseInstructionRepository.findOutboundReconciliationCandidates(eq(PROVIDER), eq(PROVIDER_ACCOUNT_ID), any(), any()))
                .thenReturn(List.of(ri1, ri2));
        when(settlementObservationRepository.findById(obs1.getId())).thenReturn(Optional.of(obs1));
        when(settlementObservationRepository.findById(obs2.getId())).thenReturn(Optional.of(obs2));
        when(settlementObservationEvidenceReader.getLatestAuthoritativeFinalObservation(releaseId1))
                .thenReturn(new SettlementObservationEvidenceReader.AuthoritativeObservationResult(
                        SettlementObservationEvidenceReader.AuthoritativeStatus.FINAL_SUCCESS, obs1, "Clean"
                ));
        when(settlementObservationEvidenceReader.getLatestAuthoritativeFinalObservation(releaseId2))
                .thenReturn(new SettlementObservationEvidenceReader.AuthoritativeObservationResult(
                        SettlementObservationEvidenceReader.AuthoritativeStatus.FINAL_SUCCESS, obs2, "Clean"
                ));

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);

        assertThat(result.clean()).isFalse();
        ArgumentCaptor<List<ReconciliationItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(captor.capture());
        List<ReconciliationItem> items = captor.getValue();
        List<ReconciliationItem> releaseItems = items.stream()
                .filter(i -> i.getInternalEffectType() == ReconciliationEffectType.RELEASE_INSTRUCTION)
                .toList();
        assertThat(releaseItems).hasSize(2).allMatch(i -> i.getStatus() == ReconciliationItemStatus.DUPLICATE);
    }

    @Test
    @DisplayName("19. Final observation missing -> internal lineage mismatch")
    void finalObservationMissingLineageMismatch() {
        UUID releaseId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("50.0000");

        UUID importId = UUID.randomUUID();
        BankStatementLine line = createDebitLine(importId, "TXN-1", amount, BankStatementLineStatus.FINAL_SUCCESS, "UTR-1");
        BankStatementImport imp = createImport(List.of(line), new BigDecimal("100.0000"), new BigDecimal("50.0000"));
        ReflectionTestUtils.setField(line, "importId", imp.getId());

        ReleaseInstruction ri = createReleaseInstruction(releaseId, amount);
        ReflectionTestUtils.setField(ri, "finalSettlementObservationId", null);

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));
        when(releaseInstructionRepository.findOutboundReconciliationCandidates(eq(PROVIDER), eq(PROVIDER_ACCOUNT_ID), any(), any()))
                .thenReturn(List.of(ri));

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);

        assertThat(result.clean()).isFalse();
        ArgumentCaptor<List<ReconciliationItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(captor.capture());
        ReconciliationItem item = captor.getValue().stream()
                .filter(i -> i.getInternalEffectType() == ReconciliationEffectType.RELEASE_INSTRUCTION)
                .findFirst().orElseThrow();
        assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.REQUIRES_REVIEW);
        assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.INTERNAL_LINEAGE_MISMATCH);
    }

    @Test
    @DisplayName("20. Wrong final observation release binding -> lineage mismatch")
    void wrongObservationReleaseBindingMismatch() {
        UUID releaseId = UUID.randomUUID();
        UUID otherReleaseId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("50.0000");

        UUID importId = UUID.randomUUID();
        BankStatementLine line = createDebitLine(importId, "TXN-1", amount, BankStatementLineStatus.FINAL_SUCCESS, "UTR-1");
        BankStatementImport imp = createImport(List.of(line), new BigDecimal("100.0000"), new BigDecimal("50.0000"));
        ReflectionTestUtils.setField(line, "importId", imp.getId());

        ReleaseInstruction ri = createReleaseInstruction(releaseId, amount);
        SettlementObservation obs = createFinalSuccessObservation(otherReleaseId, "UTR-1", "SETTLE-1", amount);
        ReflectionTestUtils.setField(ri, "finalSettlementObservationId", obs.getId());

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));
        when(releaseInstructionRepository.findOutboundReconciliationCandidates(eq(PROVIDER), eq(PROVIDER_ACCOUNT_ID), any(), any()))
                .thenReturn(List.of(ri));
        when(settlementObservationRepository.findById(obs.getId())).thenReturn(Optional.of(obs));

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);

        assertThat(result.clean()).isFalse();
        ArgumentCaptor<List<ReconciliationItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(captor.capture());
        ReconciliationItem item = captor.getValue().stream()
                .filter(i -> i.getInternalEffectType() == ReconciliationEffectType.RELEASE_INSTRUCTION)
                .findFirst().orElseThrow();
        assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.REQUIRES_REVIEW);
        assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.INTERNAL_LINEAGE_MISMATCH);
    }

    @Test
    @DisplayName("21. Observation not FINAL_SUCCESS -> lineage mismatch")
    void observationNotFinalSuccess() {
        UUID releaseId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("50.0000");

        UUID importId = UUID.randomUUID();
        BankStatementLine line = createDebitLine(importId, "TXN-1", amount, BankStatementLineStatus.FINAL_SUCCESS, "UTR-1");
        BankStatementImport imp = createImport(List.of(line), new BigDecimal("100.0000"), new BigDecimal("50.0000"));
        ReflectionTestUtils.setField(line, "importId", imp.getId());

        ReleaseInstruction ri = createReleaseInstruction(releaseId, amount);
        SettlementObservation obs = SettlementObservation.builder()
                .id(UUID.randomUUID())
                .releaseInstructionId(releaseId)
                .executionAttemptId(UUID.randomUUID())
                .provider(PROVIDER)
                .source(SettlementObservationSource.WEBHOOK)
                .sourceReference("EVT-1")
                .providerOperationKey("FINX-REL-" + releaseId)
                .providerSettlementId("SETTLE-1")
                .providerAccountId(PROVIDER_ACCOUNT_ID)
                .providerStatus("PENDING")
                .finality(SettlementObservationFinality.PENDING) // NOT FINAL_SUCCESS
                .reasonCode("PENDING")
                .utrNumber("UTR-1")
                .evidenceHash("a".repeat(64))
                .observedAmount(amount)
                .observedCurrency(CURRENCY)
                .observedDirection("DEBIT")
                .observedAt(Instant.now())
                .createdAt(Instant.now())
                .build();
        ReflectionTestUtils.setField(ri, "finalSettlementObservationId", obs.getId());

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));
        when(releaseInstructionRepository.findOutboundReconciliationCandidates(eq(PROVIDER), eq(PROVIDER_ACCOUNT_ID), any(), any()))
                .thenReturn(List.of(ri));
        when(settlementObservationRepository.findById(obs.getId())).thenReturn(Optional.of(obs));

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);

        assertThat(result.clean()).isFalse();
        ArgumentCaptor<List<ReconciliationItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(captor.capture());
        ReconciliationItem item = captor.getValue().stream()
                .filter(i -> i.getInternalEffectType() == ReconciliationEffectType.RELEASE_INSTRUCTION)
                .findFirst().orElseThrow();
        assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.REQUIRES_REVIEW);
        assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.INTERNAL_LINEAGE_MISMATCH);
    }

    @Test
    @DisplayName("22. Observation provider mismatch -> lineage mismatch")
    void observationProviderMismatch() {
        UUID releaseId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("50.0000");

        UUID importId = UUID.randomUUID();
        BankStatementLine line = createDebitLine(importId, "TXN-1", amount, BankStatementLineStatus.FINAL_SUCCESS, "UTR-1");
        BankStatementImport imp = createImport(List.of(line), new BigDecimal("100.0000"), new BigDecimal("50.0000"));
        ReflectionTestUtils.setField(line, "importId", imp.getId());

        ReleaseInstruction ri = createReleaseInstruction(releaseId, amount);
        SettlementObservation obs = createFinalSuccessObservation(releaseId, "UTR-1", "SETTLE-1", amount);
        ReflectionTestUtils.setField(obs, "provider", "OTHER_PROVIDER");
        ReflectionTestUtils.setField(ri, "finalSettlementObservationId", obs.getId());

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));
        when(releaseInstructionRepository.findOutboundReconciliationCandidates(eq(PROVIDER), eq(PROVIDER_ACCOUNT_ID), any(), any()))
                .thenReturn(List.of(ri));
        when(settlementObservationRepository.findById(obs.getId())).thenReturn(Optional.of(obs));

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);

        assertThat(result.clean()).isFalse();
        ArgumentCaptor<List<ReconciliationItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(captor.capture());
        ReconciliationItem item = captor.getValue().stream()
                .filter(i -> i.getInternalEffectType() == ReconciliationEffectType.RELEASE_INSTRUCTION)
                .findFirst().orElseThrow();
        assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.REQUIRES_REVIEW);
        assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.INTERNAL_LINEAGE_MISMATCH);
    }

    @Test
    @DisplayName("23. Operation key mismatch -> lineage mismatch")
    void operationKeyMismatch() {
        UUID releaseId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("50.0000");

        UUID importId = UUID.randomUUID();
        BankStatementLine line = createDebitLine(importId, "TXN-1", amount, BankStatementLineStatus.FINAL_SUCCESS, "UTR-1");
        BankStatementImport imp = createImport(List.of(line), new BigDecimal("100.0000"), new BigDecimal("50.0000"));
        ReflectionTestUtils.setField(line, "importId", imp.getId());

        ReleaseInstruction ri = createReleaseInstruction(releaseId, amount);
        SettlementObservation obs = createFinalSuccessObservation(releaseId, "UTR-1", "SETTLE-1", amount);
        ReflectionTestUtils.setField(obs, "providerOperationKey", "WRONG-KEY");
        ReflectionTestUtils.setField(ri, "finalSettlementObservationId", obs.getId());

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));
        when(releaseInstructionRepository.findOutboundReconciliationCandidates(eq(PROVIDER), eq(PROVIDER_ACCOUNT_ID), any(), any()))
                .thenReturn(List.of(ri));
        when(settlementObservationRepository.findById(obs.getId())).thenReturn(Optional.of(obs));

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);

        assertThat(result.clean()).isFalse();
        ArgumentCaptor<List<ReconciliationItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(captor.capture());
        ReconciliationItem item = captor.getValue().stream()
                .filter(i -> i.getInternalEffectType() == ReconciliationEffectType.RELEASE_INSTRUCTION)
                .findFirst().orElseThrow();
        assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.REQUIRES_REVIEW);
        assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.INTERNAL_LINEAGE_MISMATCH);
    }

    @Test
    @DisplayName("24. UTR mismatch -> REQUIRES_REVIEW UTR_MISMATCH")
    void utrMismatchPolicy() {
        UUID releaseId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("50.0000");
        String opKey = "FINX-REL-" + releaseId;

        UUID importId = UUID.randomUUID();
        BankStatementLine line = createDebitLine(importId, opKey, amount, BankStatementLineStatus.FINAL_SUCCESS, "UTR-BANK-DIFFERENT");
        BankStatementImport imp = createImport(List.of(line), new BigDecimal("100.0000"), new BigDecimal("50.0000"));
        ReflectionTestUtils.setField(line, "importId", imp.getId());

        ReleaseInstruction ri = createReleaseInstruction(releaseId, amount);
        SettlementObservation obs = createFinalSuccessObservation(releaseId, "UTR-OBS-DIFFERENT", "SETTLE-1", amount);
        ReflectionTestUtils.setField(ri, "finalSettlementObservationId", obs.getId());

        when(importRepository.findById(imp.getId())).thenReturn(Optional.of(imp));
        when(lineRepository.findByImportIdOrderByTransactionTimeAscIdAsc(imp.getId())).thenReturn(List.of(line));
        when(releaseInstructionRepository.findOutboundReconciliationCandidates(eq(PROVIDER), eq(PROVIDER_ACCOUNT_ID), any(), any()))
                .thenReturn(List.of(ri));
        when(settlementObservationRepository.findById(obs.getId())).thenReturn(Optional.of(obs));
        when(settlementObservationEvidenceReader.getLatestAuthoritativeFinalObservation(releaseId))
                .thenReturn(new SettlementObservationEvidenceReader.AuthoritativeObservationResult(
                        SettlementObservationEvidenceReader.AuthoritativeStatus.FINAL_SUCCESS, obs, "Clean"
                ));

        DeterministicReconciliationResult result = matcher.reconcileImport(imp.getId(), adminUser);

        assertThat(result.clean()).isFalse();
        ArgumentCaptor<List<ReconciliationItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(captor.capture());
        ReconciliationItem item = captor.getValue().stream()
                .filter(i -> i.getInternalEffectType() == ReconciliationEffectType.RELEASE_INSTRUCTION)
                .findFirst().orElseThrow();
        assertThat(item.getStatus()).isEqualTo(ReconciliationItemStatus.REQUIRES_REVIEW);
        assertThat(item.getReasonCode()).isEqualTo(ReconciliationReasonCode.UTR_MISMATCH);
    }
}
