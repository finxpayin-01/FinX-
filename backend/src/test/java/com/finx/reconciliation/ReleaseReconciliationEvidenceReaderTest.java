package com.finx.reconciliation;

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
import com.finx.reconciliation.enums.ReconciliationReasonCode;
import com.finx.reconciliation.dto.ReleaseReconciliationResult;
import com.finx.reconciliation.model.ReleaseReconciliationStatus;
import com.finx.reconciliation.repository.BankStatementImportRepository;
import com.finx.reconciliation.repository.BankStatementLineRepository;
import com.finx.reconciliation.repository.ReconciliationItemRepository;
import com.finx.reconciliation.repository.ReconciliationRunRepository;
import com.finx.reconciliation.service.DeterministicReconciliationMatcher;
import com.finx.reconciliation.service.ReleaseReconciliationEvidenceReader;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.observation.entity.SettlementObservation;
import com.finx.release.observation.model.SettlementObservationFinality;
import com.finx.release.observation.model.SettlementObservationSource;
import com.finx.release.observation.repository.SettlementObservationRepository;
import com.finx.release.observation.service.SettlementObservationEvidenceReader;
import com.finx.release.repository.ReleaseInstructionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class ReleaseReconciliationEvidenceReaderTest {

    @Mock private ReconciliationItemRepository itemRepository;
    @Mock private ReconciliationRunRepository runRepository;
    @Mock private BankStatementImportRepository importRepository;
    @Mock private BankStatementLineRepository lineRepository;
    @Mock private ReleaseInstructionRepository releaseInstructionRepository;
    @Mock private SettlementObservationRepository settlementObservationRepository;
    @Mock private SettlementObservationEvidenceReader settlementObservationEvidenceReader;

    private ReleaseReconciliationEvidenceReader reader;

    private static final String PROVIDER = "MOCK";
    private static final String PROVIDER_ACCOUNT_ID = "MOCK_ACC_1";
    private static final String CURRENCY = "INR";
    private static final String SOURCE_HASH = "h".repeat(64);

    @BeforeEach
    void setUp() {
        reader = new ReleaseReconciliationEvidenceReader(
                releaseInstructionRepository,
                settlementObservationRepository,
                settlementObservationEvidenceReader,
                itemRepository,
                runRepository,
                importRepository,
                lineRepository
        );
    }

    private ReleaseInstruction createRelease(UUID releaseId, BigDecimal amount) {
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
                UUID.randomUUID()
        );
        ReflectionTestUtils.setField(ri, "status", ReleaseInstructionStatus.SETTLED);
        ReflectionTestUtils.setField(ri, "settledAt", Instant.now().minus(2, ChronoUnit.HOURS));
        ReflectionTestUtils.setField(ri, "authorizedAt", Instant.now().minus(3, ChronoUnit.HOURS));
        return ri;
    }

    private SettlementObservation createObservation(UUID releaseId, String utr, BigDecimal amount) {
        return SettlementObservation.builder()
                .id(UUID.randomUUID())
                .releaseInstructionId(releaseId)
                .executionAttemptId(UUID.randomUUID())
                .provider(PROVIDER)
                .source(SettlementObservationSource.WEBHOOK)
                .sourceReference("EVT-1")
                .providerOperationKey("FINX-REL-" + releaseId)
                .providerSettlementId("SETTLE-1")
                .providerAccountId(PROVIDER_ACCOUNT_ID)
                .providerStatus("SETTLED")
                .finality(SettlementObservationFinality.FINAL_SUCCESS)
                .reasonCode("SETTLEMENT_FINAL_CONFIRMED")
                .utrNumber(utr)
                .evidenceHash("e".repeat(64))
                .observedAmount(amount)
                .observedCurrency(CURRENCY)
                .observedDirection("DEBIT")
                .observedAt(Instant.now())
                .createdAt(Instant.now())
                .build();
    }

    private BankStatementImport createImport(UUID importId) {
        BankStatementImport imp = new BankStatementImport(
                PROVIDER,
                PROVIDER_ACCOUNT_ID,
                Instant.now().minus(2, ChronoUnit.DAYS),
                Instant.now(),
                SOURCE_HASH,
                new BigDecimal("100.00"),
                new BigDecimal("30.00"),
                CURRENCY,
                UUID.randomUUID()
        );
        ReflectionTestUtils.setField(imp, "id", importId);
        imp.setStatus(BankStatementImportStatus.COMPLETED);
        return imp;
    }

    private ReconciliationRun createRun(UUID runId, UUID importId, String version, int mismatchCount, Instant endTime) {
        ReconciliationRun run = new ReconciliationRun();
        run.setId(runId);
        run.setRunReference("RUN-" + UUID.randomUUID().toString().substring(0, 8));
        run.setBankStatementImportId(importId);
        run.setStatementSourceHash(SOURCE_HASH);
        run.setMatcherVersion(version);
        run.setStatus(ReconciliationRunStatus.COMPLETED);
        run.setProvider(PROVIDER);
        run.setProviderAccountId(PROVIDER_ACCOUNT_ID);
        run.setCurrency(CURRENCY);
        run.setMismatchCount(mismatchCount);
        run.setMatchedCount(1);
        run.setStartTime(endTime.minus(10, ChronoUnit.MINUTES));
        run.setEndTime(endTime);
        run.setCreatedAt(endTime);
        return run;
    }

    private BankStatementLine createLine(UUID lineId, BankStatementImport imp, BigDecimal amount, String utr, BankStatementLineStatus status) {
        BankStatementLine line = new BankStatementLine(
                imp.getId(), PROVIDER, PROVIDER_ACCOUNT_ID, "LINE-1", "TXN-1", utr,
                amount, CURRENCY, FinancialDirection.DEBIT, "SUCCESS", status, Instant.now(), LocalDate.now(), "fp"
        );
        ReflectionTestUtils.setField(line, "id", lineId);
        return line;
    }

    private ReconciliationItem createItem(UUID itemId, UUID runId, UUID releaseId, UUID lineId, ReconciliationItemStatus status, ReconciliationReasonCode reason, BigDecimal amount) {
        ReconciliationItem item = new ReconciliationItem();
        item.setId(itemId);
        item.setReconciliationRunId(runId);
        item.setBankStatementLineId(lineId);
        item.setInternalEffectType(ReconciliationEffectType.RELEASE_INSTRUCTION);
        item.setInternalEffectId(releaseId);
        item.setDirection(FinancialDirection.DEBIT);
        item.setFinxAmount(amount);
        item.setBankAmount(amount);
        item.setCurrency(CURRENCY);
        item.setStatus(status);
        item.setReasonCode(reason);
        item.setFinxTransactionReference("FINX-REL-" + releaseId);
        item.setBankTransactionReference("TXN-1");
        item.setBankStatus("SUCCESS");
        return item;
    }

    @Test
    @DisplayName("1. NO_EVIDENCE when release does not exist or has no reconciliation item")
    void noEvidenceWhenNoReleaseOrItem() {
        UUID releaseId = UUID.randomUUID();
        when(releaseInstructionRepository.findById(releaseId)).thenReturn(Optional.empty());

        ReleaseReconciliationResult result = reader.getReleaseReconciliationEvidence(releaseId);
        assertThat(result.getStatus()).isEqualTo(ReleaseReconciliationStatus.NO_EVIDENCE);

        // With release present but no reconciliation items
        ReleaseInstruction ri = createRelease(releaseId, new BigDecimal("70.00"));
        when(releaseInstructionRepository.findById(releaseId)).thenReturn(Optional.of(ri));
        when(itemRepository.findByInternalEffectTypeAndInternalEffectId(ReconciliationEffectType.RELEASE_INSTRUCTION, releaseId))
                .thenReturn(Collections.emptyList());

        ReleaseReconciliationResult result2 = reader.getReleaseReconciliationEvidence(releaseId);
        assertThat(result2.getStatus()).isEqualTo(ReleaseReconciliationStatus.NO_EVIDENCE);
    }

    @Test
    @DisplayName("2. CONFIRMED for clean latest V2 run")
    void confirmedForCleanLatestV2Run() {
        UUID releaseId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("70.00");
        String utr = "UTR-CLEAN-01";
        UUID importId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        Instant now = Instant.now();

        ReleaseInstruction ri = createRelease(releaseId, amount);
        SettlementObservation obs = createObservation(releaseId, utr, amount);
        ReflectionTestUtils.setField(ri, "finalSettlementObservationId", obs.getId());

        BankStatementImport imp = createImport(importId);
        ReconciliationRun run = createRun(runId, importId, DeterministicReconciliationMatcher.MATCHER_VERSION_V2, 0, now);
        BankStatementLine line = createLine(lineId, imp, amount, utr, BankStatementLineStatus.FINAL_SUCCESS);
        ReconciliationItem item = createItem(itemId, runId, releaseId, lineId, ReconciliationItemStatus.MATCHED, ReconciliationReasonCode.MATCHED_CONFIRMED, amount);

        when(releaseInstructionRepository.findById(releaseId)).thenReturn(Optional.of(ri));
        when(itemRepository.findByInternalEffectTypeAndInternalEffectId(ReconciliationEffectType.RELEASE_INSTRUCTION, releaseId))
                .thenReturn(List.of(item));
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(importRepository.findById(importId)).thenReturn(Optional.of(imp));
        when(itemRepository.findByReconciliationRunId(runId)).thenReturn(List.of(item));
        when(lineRepository.findById(lineId)).thenReturn(Optional.of(line));
        when(settlementObservationRepository.findById(obs.getId())).thenReturn(Optional.of(obs));
        when(settlementObservationEvidenceReader.getLatestAuthoritativeFinalObservation(releaseId))
                .thenReturn(new SettlementObservationEvidenceReader.AuthoritativeObservationResult(
                        SettlementObservationEvidenceReader.AuthoritativeStatus.FINAL_SUCCESS, obs, "Clean"
                ));

        ReleaseReconciliationResult result = reader.getReleaseReconciliationEvidence(releaseId);
        assertThat(result.getStatus()).isEqualTo(ReleaseReconciliationStatus.CONFIRMED);
        assertThat(result.getReconciliationRunId()).isEqualTo(runId);
        assertThat(result.getReconciliationItemId()).isEqualTo(itemId);
        assertThat(result.getBankStatementLineId()).isEqualTo(lineId);
        assertThat(result.getBankStatementImportId()).isEqualTo(importId);
    }

    @Test
    @DisplayName("3. Latest run rule: Old clean V2 + newer mismatch V2 -> not CONFIRMED")
    void latestRunRuleOverOldCleanRun() {
        UUID releaseId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("70.00");
        String utr = "UTR-CLEAN-01";
        UUID importId = UUID.randomUUID();
        UUID oldRunId = UUID.randomUUID();
        UUID newRunId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        Instant tOld = Instant.now().minus(2, ChronoUnit.HOURS);
        Instant tNew = Instant.now().minus(10, ChronoUnit.MINUTES);

        ReleaseInstruction ri = createRelease(releaseId, amount);
        SettlementObservation obs = createObservation(releaseId, utr, amount);
        ReflectionTestUtils.setField(ri, "finalSettlementObservationId", obs.getId());

        BankStatementImport imp = createImport(importId);
        ReconciliationRun oldRun = createRun(oldRunId, importId, DeterministicReconciliationMatcher.MATCHER_VERSION_V2, 0, tOld);
        ReconciliationRun newRun = createRun(newRunId, importId, DeterministicReconciliationMatcher.MATCHER_VERSION_V2, 1, tNew);

        ReconciliationItem oldItem = createItem(UUID.randomUUID(), oldRunId, releaseId, lineId, ReconciliationItemStatus.MATCHED, ReconciliationReasonCode.MATCHED_CONFIRMED, amount);
        ReconciliationItem newItem = createItem(UUID.randomUUID(), newRunId, releaseId, lineId, ReconciliationItemStatus.AMOUNT_MISMATCH, ReconciliationReasonCode.AMOUNT_MISMATCH, amount);

        when(releaseInstructionRepository.findById(releaseId)).thenReturn(Optional.of(ri));
        when(itemRepository.findByInternalEffectTypeAndInternalEffectId(ReconciliationEffectType.RELEASE_INSTRUCTION, releaseId))
                .thenReturn(List.of(oldItem, newItem));
        when(runRepository.findById(oldRunId)).thenReturn(Optional.of(oldRun));
        when(runRepository.findById(newRunId)).thenReturn(Optional.of(newRun));

        ReleaseReconciliationResult result = reader.getReleaseReconciliationEvidence(releaseId);
        // Latest run had mismatch -> must NOT fall back to old clean run
        assertThat(result.getStatus()).isNotEqualTo(ReleaseReconciliationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("4. V1 match -> not CONFIRMED (V2 required)")
    void v1MatchNotConfirmed() {
        UUID releaseId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("70.00");
        UUID importId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        ReleaseInstruction ri = createRelease(releaseId, amount);
        BankStatementImport imp = createImport(importId);
        ReconciliationRun runV1 = createRun(runId, importId, DeterministicReconciliationMatcher.MATCHER_VERSION_V1, 0, Instant.now());
        ReconciliationItem item = createItem(UUID.randomUUID(), runId, releaseId, UUID.randomUUID(), ReconciliationItemStatus.MATCHED, ReconciliationReasonCode.MATCHED_CONFIRMED, amount);

        when(releaseInstructionRepository.findById(releaseId)).thenReturn(Optional.of(ri));
        when(itemRepository.findByInternalEffectTypeAndInternalEffectId(ReconciliationEffectType.RELEASE_INSTRUCTION, releaseId))
                .thenReturn(List.of(item));
        when(runRepository.findById(runId)).thenReturn(Optional.of(runV1));

        ReleaseReconciliationResult result = reader.getReleaseReconciliationEvidence(releaseId);
        assertThat(result.getStatus()).isEqualTo(ReleaseReconciliationStatus.NO_EVIDENCE);
    }

    @Test
    @DisplayName("5. Source hash mismatch -> not CONFIRMED")
    void sourceHashMismatchNotConfirmed() {
        UUID releaseId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("70.00");
        UUID importId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        ReleaseInstruction ri = createRelease(releaseId, amount);
        BankStatementImport imp = createImport(importId);
        ReflectionTestUtils.setField(imp, "sourceHash", "different-hash".repeat(4));
        ReconciliationRun run = createRun(runId, importId, DeterministicReconciliationMatcher.MATCHER_VERSION_V2, 0, Instant.now());
        ReconciliationItem item = createItem(UUID.randomUUID(), runId, releaseId, UUID.randomUUID(), ReconciliationItemStatus.MATCHED, ReconciliationReasonCode.MATCHED_CONFIRMED, amount);

        when(releaseInstructionRepository.findById(releaseId)).thenReturn(Optional.of(ri));
        when(itemRepository.findByInternalEffectTypeAndInternalEffectId(ReconciliationEffectType.RELEASE_INSTRUCTION, releaseId))
                .thenReturn(List.of(item));
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(importRepository.findById(importId)).thenReturn(Optional.of(imp));

        ReleaseReconciliationResult result = reader.getReleaseReconciliationEvidence(releaseId);
        assertThat(result.getStatus()).isNotEqualTo(ReleaseReconciliationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("6. Run mismatchCount > 0 -> not CONFIRMED")
    void runMismatchCountNotConfirmed() {
        UUID releaseId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("70.00");
        UUID importId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        ReleaseInstruction ri = createRelease(releaseId, amount);
        BankStatementImport imp = createImport(importId);
        ReconciliationRun run = createRun(runId, importId, DeterministicReconciliationMatcher.MATCHER_VERSION_V2, 1, Instant.now()); // mismatchCount = 1
        ReconciliationItem item = createItem(UUID.randomUUID(), runId, releaseId, UUID.randomUUID(), ReconciliationItemStatus.MATCHED, ReconciliationReasonCode.MATCHED_CONFIRMED, amount);

        when(releaseInstructionRepository.findById(releaseId)).thenReturn(Optional.of(ri));
        when(itemRepository.findByInternalEffectTypeAndInternalEffectId(ReconciliationEffectType.RELEASE_INSTRUCTION, releaseId))
                .thenReturn(List.of(item));
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(importRepository.findById(importId)).thenReturn(Optional.of(imp));

        ReleaseReconciliationResult result = reader.getReleaseReconciliationEvidence(releaseId);
        assertThat(result.getStatus()).isNotEqualTo(ReleaseReconciliationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("7. Another run item non-MATCHED -> not CONFIRMED")
    void anotherRunItemNonMatchedNotConfirmed() {
        UUID releaseId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("70.00");
        UUID importId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        ReleaseInstruction ri = createRelease(releaseId, amount);
        BankStatementImport imp = createImport(importId);
        ReconciliationRun run = createRun(runId, importId, DeterministicReconciliationMatcher.MATCHER_VERSION_V2, 0, Instant.now());
        ReconciliationItem item1 = createItem(UUID.randomUUID(), runId, releaseId, UUID.randomUUID(), ReconciliationItemStatus.MATCHED, ReconciliationReasonCode.MATCHED_CONFIRMED, amount);
        ReconciliationItem item2 = createItem(UUID.randomUUID(), runId, UUID.randomUUID(), UUID.randomUUID(), ReconciliationItemStatus.REQUIRES_REVIEW, ReconciliationReasonCode.BANK_FINALITY_PENDING, amount);

        when(releaseInstructionRepository.findById(releaseId)).thenReturn(Optional.of(ri));
        when(itemRepository.findByInternalEffectTypeAndInternalEffectId(ReconciliationEffectType.RELEASE_INSTRUCTION, releaseId))
                .thenReturn(List.of(item1));
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(importRepository.findById(importId)).thenReturn(Optional.of(imp));
        when(itemRepository.findByReconciliationRunId(runId)).thenReturn(List.of(item1, item2));

        ReleaseReconciliationResult result = reader.getReleaseReconciliationEvidence(releaseId);
        assertThat(result.getStatus()).isNotEqualTo(ReleaseReconciliationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("8. Bank line PENDING -> not CONFIRMED (REVIEW)")
    void bankLinePendingNotConfirmed() {
        UUID releaseId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("70.00");
        String utr = "UTR-1";
        UUID importId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();

        ReleaseInstruction ri = createRelease(releaseId, amount);
        SettlementObservation obs = createObservation(releaseId, utr, amount);
        ReflectionTestUtils.setField(ri, "finalSettlementObservationId", obs.getId());

        BankStatementImport imp = createImport(importId);
        ReconciliationRun run = createRun(runId, importId, DeterministicReconciliationMatcher.MATCHER_VERSION_V2, 0, Instant.now());
        BankStatementLine line = createLine(lineId, imp, amount, utr, BankStatementLineStatus.PENDING); // PENDING line
        ReconciliationItem item = createItem(UUID.randomUUID(), runId, releaseId, lineId, ReconciliationItemStatus.MATCHED, ReconciliationReasonCode.MATCHED_CONFIRMED, amount);

        when(releaseInstructionRepository.findById(releaseId)).thenReturn(Optional.of(ri));
        when(itemRepository.findByInternalEffectTypeAndInternalEffectId(ReconciliationEffectType.RELEASE_INSTRUCTION, releaseId))
                .thenReturn(List.of(item));
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(importRepository.findById(importId)).thenReturn(Optional.of(imp));
        when(itemRepository.findByReconciliationRunId(runId)).thenReturn(List.of(item));
        when(lineRepository.findById(lineId)).thenReturn(Optional.of(line));

        ReleaseReconciliationResult result = reader.getReleaseReconciliationEvidence(releaseId);
        assertThat(result.getStatus()).isEqualTo(ReleaseReconciliationStatus.REVIEW);
    }

    @Test
    @DisplayName("9. Bank line REVERSED -> REVERSAL_RECOVERY_REQUIRED")
    void bankLineReversedReversalRecoveryRequired() {
        UUID releaseId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("70.00");
        String utr = "UTR-1";
        UUID importId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();

        ReleaseInstruction ri = createRelease(releaseId, amount);
        SettlementObservation obs = createObservation(releaseId, utr, amount);
        ReflectionTestUtils.setField(ri, "finalSettlementObservationId", obs.getId());

        BankStatementImport imp = createImport(importId);
        ReconciliationRun run = createRun(runId, importId, DeterministicReconciliationMatcher.MATCHER_VERSION_V2, 0, Instant.now());
        BankStatementLine line = createLine(lineId, imp, amount, utr, BankStatementLineStatus.REVERSED); // REVERSED line
        ReconciliationItem item = createItem(UUID.randomUUID(), runId, releaseId, lineId, ReconciliationItemStatus.MATCHED, ReconciliationReasonCode.MATCHED_CONFIRMED, amount);

        when(releaseInstructionRepository.findById(releaseId)).thenReturn(Optional.of(ri));
        when(itemRepository.findByInternalEffectTypeAndInternalEffectId(ReconciliationEffectType.RELEASE_INSTRUCTION, releaseId))
                .thenReturn(List.of(item));
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(importRepository.findById(importId)).thenReturn(Optional.of(imp));
        when(itemRepository.findByReconciliationRunId(runId)).thenReturn(List.of(item));
        when(lineRepository.findById(lineId)).thenReturn(Optional.of(line));

        ReleaseReconciliationResult result = reader.getReleaseReconciliationEvidence(releaseId);
        assertThat(result.getStatus()).isEqualTo(ReleaseReconciliationStatus.REVERSAL_RECOVERY_REQUIRED);
    }

    @Test
    @DisplayName("10. E6 observation CONFLICT or REVERSED -> REVERSAL_RECOVERY_REQUIRED")
    void e6ObservationConflictReversalRecoveryRequired() {
        UUID releaseId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("70.00");
        String utr = "UTR-1";
        UUID importId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();

        ReleaseInstruction ri = createRelease(releaseId, amount);
        SettlementObservation obs = createObservation(releaseId, utr, amount);
        ReflectionTestUtils.setField(ri, "finalSettlementObservationId", obs.getId());

        BankStatementImport imp = createImport(importId);
        ReconciliationRun run = createRun(runId, importId, DeterministicReconciliationMatcher.MATCHER_VERSION_V2, 0, Instant.now());
        BankStatementLine line = createLine(lineId, imp, amount, utr, BankStatementLineStatus.FINAL_SUCCESS);
        ReconciliationItem item = createItem(UUID.randomUUID(), runId, releaseId, lineId, ReconciliationItemStatus.MATCHED, ReconciliationReasonCode.MATCHED_CONFIRMED, amount);

        SettlementObservation conflictObs = SettlementObservation.builder()
                .id(UUID.randomUUID())
                .releaseInstructionId(releaseId)
                .executionAttemptId(UUID.randomUUID())
                .provider(PROVIDER)
                .source(SettlementObservationSource.WEBHOOK)
                .sourceReference("EVT-CONFLICT")
                .providerOperationKey("FINX-REL-" + releaseId)
                .providerStatus("CONFLICT")
                .finality(SettlementObservationFinality.REVERSED)
                .reasonCode("CONFLICT")
                .evidenceHash("c".repeat(64))
                .observedAt(Instant.now())
                .build();

        when(releaseInstructionRepository.findById(releaseId)).thenReturn(Optional.of(ri));
        when(itemRepository.findByInternalEffectTypeAndInternalEffectId(ReconciliationEffectType.RELEASE_INSTRUCTION, releaseId))
                .thenReturn(List.of(item));
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(importRepository.findById(importId)).thenReturn(Optional.of(imp));
        when(itemRepository.findByReconciliationRunId(runId)).thenReturn(List.of(item));
        when(lineRepository.findById(lineId)).thenReturn(Optional.of(line));
        when(settlementObservationEvidenceReader.getLatestAuthoritativeFinalObservation(releaseId))
                .thenReturn(new SettlementObservationEvidenceReader.AuthoritativeObservationResult(
                        SettlementObservationEvidenceReader.AuthoritativeStatus.CONFLICT, conflictObs, "Conflict"
                ));

        ReleaseReconciliationResult result = reader.getReleaseReconciliationEvidence(releaseId);
        assertThat(result.getStatus()).isEqualTo(ReleaseReconciliationStatus.REVERSAL_RECOVERY_REQUIRED);
    }

    @Test
    @DisplayName("11. Dimension mismatches (UTR) -> BLOCKED")
    void dimensionMismatchesBlocked() {
        UUID releaseId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("70.00");
        String utr = "UTR-1";
        UUID importId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();

        ReleaseInstruction ri = createRelease(releaseId, amount);
        SettlementObservation obs = createObservation(releaseId, utr, amount);
        ReflectionTestUtils.setField(ri, "finalSettlementObservationId", obs.getId());

        BankStatementImport imp = createImport(importId);
        ReconciliationRun run = createRun(runId, importId, DeterministicReconciliationMatcher.MATCHER_VERSION_V2, 0, Instant.now());
        // Line UTR does not match observation UTR
        BankStatementLine line = createLine(lineId, imp, amount, "UTR-WRONG", BankStatementLineStatus.FINAL_SUCCESS);
        ReconciliationItem item = createItem(UUID.randomUUID(), runId, releaseId, lineId, ReconciliationItemStatus.MATCHED, ReconciliationReasonCode.MATCHED_CONFIRMED, amount);

        when(releaseInstructionRepository.findById(releaseId)).thenReturn(Optional.of(ri));
        when(itemRepository.findByInternalEffectTypeAndInternalEffectId(ReconciliationEffectType.RELEASE_INSTRUCTION, releaseId))
                .thenReturn(List.of(item));
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(importRepository.findById(importId)).thenReturn(Optional.of(imp));
        when(itemRepository.findByReconciliationRunId(runId)).thenReturn(List.of(item));
        when(lineRepository.findById(lineId)).thenReturn(Optional.of(line));
        when(settlementObservationRepository.findById(obs.getId())).thenReturn(Optional.of(obs));
        when(settlementObservationEvidenceReader.getLatestAuthoritativeFinalObservation(releaseId))
                .thenReturn(new SettlementObservationEvidenceReader.AuthoritativeObservationResult(
                        SettlementObservationEvidenceReader.AuthoritativeStatus.FINAL_SUCCESS, obs, "Clean"
                ));

        ReleaseReconciliationResult result = reader.getReleaseReconciliationEvidence(releaseId);
        assertThat(result.getStatus()).isEqualTo(ReleaseReconciliationStatus.BLOCKED);
    }

    @Test
    @DisplayName("12. Reader does not invoke bank or write to database")
    void readerIsStrictlyReadOnly() {
        UUID releaseId = UUID.randomUUID();
        when(releaseInstructionRepository.findById(releaseId)).thenReturn(Optional.empty());

        reader.getReleaseReconciliationEvidence(releaseId);

        verifyNoInteractions(runRepository);
        verify(releaseInstructionRepository, never()).save(any());
        verify(settlementObservationRepository, never()).save(any());
    }
}
