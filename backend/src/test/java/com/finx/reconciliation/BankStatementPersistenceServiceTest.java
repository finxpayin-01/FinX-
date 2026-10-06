package com.finx.reconciliation;

import com.finx.banking.dto.BankStatementDirection;
import com.finx.banking.dto.BankStatementEntry;
import com.finx.banking.dto.BankStatementFinality;
import com.finx.reconciliation.entity.BankStatementImport;
import com.finx.reconciliation.entity.BankStatementLine;
import com.finx.reconciliation.enums.BankStatementImportStatus;
import com.finx.reconciliation.enums.BankStatementLineStatus;
import com.finx.reconciliation.enums.FinancialDirection;
import com.finx.reconciliation.repository.BankStatementImportRepository;
import com.finx.reconciliation.repository.BankStatementLineRepository;
import com.finx.reconciliation.service.BankStatementPersistenceService;
import com.finx.reconciliation.service.BankStatementSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BankStatementPersistenceServiceTest {

    private BankStatementImportRepository importRepository;
    private BankStatementLineRepository lineRepository;
    private BankStatementPersistenceService persistenceService;

    @BeforeEach
    void setUp() {
        importRepository = mock(BankStatementImportRepository.class);
        lineRepository = mock(BankStatementLineRepository.class);
        persistenceService = new BankStatementPersistenceService(importRepository, lineRepository);
    }

    private BankStatementSnapshot sampleSnapshot(String providerAccountId, String sourceHash, int lineCount) {
        UUID userId = UUID.randomUUID();
        List<BankStatementEntry> entries = List.of(
                BankStatementEntry.builder()
                        .providerLineId("LINE-001")
                        .transactionReference("TXN-001")
                        .utrNumber("UTR-001")
                        .amount(new BigDecimal("1000.00"))
                        .currency("INR")
                        .direction(BankStatementDirection.CREDIT)
                        .providerStatus("SETTLED")
                        .finality(BankStatementFinality.FINAL_SUCCESS)
                        .transactionTime(Instant.parse("2026-10-02T12:00:00Z"))
                        .valueDate(LocalDate.parse("2026-10-02"))
                        .build(),
                BankStatementEntry.builder()
                        .providerLineId("LINE-002")
                        .transactionReference("TXN-002")
                        .utrNumber("UTR-002")
                        .amount(new BigDecimal("500.00"))
                        .currency("INR")
                        .direction(BankStatementDirection.DEBIT)
                        .providerStatus("PENDING")
                        .finality(BankStatementFinality.PENDING)
                        .transactionTime(Instant.parse("2026-10-02T13:00:00Z"))
                        .valueDate(LocalDate.parse("2026-10-02"))
                        .build()
        );

        return new BankStatementSnapshot(
                "MOCK",
                providerAccountId,
                Instant.parse("2026-10-01T00:00:00Z"),
                Instant.parse("2026-10-07T00:00:00Z"),
                sourceHash,
                new BigDecimal("50000.00"),
                new BigDecimal("50500.00"),
                "INR",
                "MOCK-IMP-REF-1",
                entries.subList(0, Math.min(lineCount, entries.size())),
                userId
        );
    }

    @Test
    @DisplayName("1-8. Persists import, all lines, totalLines, COMPLETED status, completedAt, sourceHash, fingerprints, importedBy")
    void testPersistNewImportAndLines() {
        String sourceHash = "hash1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef";
        BankStatementSnapshot snapshot = sampleSnapshot("ACC_001", sourceHash, 2);

        when(importRepository.findByProviderAndProviderAccountIdAndSourceHash("MOCK", "ACC_001", sourceHash))
                .thenReturn(Optional.empty());

        when(importRepository.save(any(BankStatementImport.class))).thenAnswer(inv -> inv.getArgument(0));

        BankStatementImport result = persistenceService.persistStatementImport(snapshot);

        assertThat(result).isNotNull();
        assertThat(result.getProvider()).isEqualTo("MOCK");
        assertThat(result.getProviderAccountId()).isEqualTo("ACC_001");
        assertThat(result.getSourceHash()).isEqualTo(sourceHash);
        assertThat(result.getTotalLines()).isEqualTo(2);
        assertThat(result.getStatus()).isEqualTo(BankStatementImportStatus.COMPLETED);
        assertThat(result.getCompletedAt()).isNotNull();
        assertThat(result.getImportedBy()).isEqualTo(snapshot.importedBy());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BankStatementLine>> linesCaptor = ArgumentCaptor.forClass(List.class);
        verify(lineRepository).saveAll(linesCaptor.capture());

        List<BankStatementLine> savedLines = linesCaptor.getValue();
        assertThat(savedLines).hasSize(2);

        BankStatementLine line1 = savedLines.get(0);
        assertThat(line1.getProviderLineId()).isEqualTo("LINE-001");
        assertThat(line1.getDirection()).isEqualTo(FinancialDirection.CREDIT);
        assertThat(line1.getNormalizedStatus()).isEqualTo(BankStatementLineStatus.FINAL_SUCCESS);
        assertThat(line1.getLineFingerprint()).matches("^[a-f0-9]{64}$");

        BankStatementLine line2 = savedLines.get(1);
        assertThat(line2.getProviderLineId()).isEqualTo("LINE-002");
        assertThat(line2.getDirection()).isEqualTo(FinancialDirection.DEBIT);
        assertThat(line2.getNormalizedStatus()).isEqualTo(BankStatementLineStatus.PENDING);
    }

    @Test
    @DisplayName("9, 10. Exact replay returns existing import and creates zero new lines")
    void testExactReplayIdempotency() {
        String sourceHash = "hash1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef";
        BankStatementSnapshot snapshot = sampleSnapshot("ACC_001", sourceHash, 2);

        BankStatementImport existing = new BankStatementImport();
        existing.setStatus(BankStatementImportStatus.COMPLETED);
        existing.setSourceHash(sourceHash);
        existing.setTotalLines(2);

        when(importRepository.findByProviderAndProviderAccountIdAndSourceHash("MOCK", "ACC_001", sourceHash))
                .thenReturn(Optional.of(existing));

        BankStatementImport result = persistenceService.persistStatementImport(snapshot);

        assertThat(result).isSameAs(existing);
        verify(importRepository, never()).save(any());
        verify(lineRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("11. Same hash different account is independent")
    void testSameHashDifferentAccountIsIndependent() {
        String sourceHash = "hash1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef";
        BankStatementSnapshot snapAcc2 = sampleSnapshot("ACC_002", sourceHash, 1);

        when(importRepository.findByProviderAndProviderAccountIdAndSourceHash("MOCK", "ACC_002", sourceHash))
                .thenReturn(Optional.empty());
        when(importRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BankStatementImport result = persistenceService.persistStatementImport(snapAcc2);
        assertThat(result.getProviderAccountId()).isEqualTo("ACC_002");
        verify(importRepository).save(any());
    }

    @Test
    @DisplayName("12. Non-COMPLETED duplicate import fails closed")
    void testNonCompletedDuplicateFailsClosed() {
        String sourceHash = "hash1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef";
        BankStatementSnapshot snapshot = sampleSnapshot("ACC_001", sourceHash, 2);

        BankStatementImport existing = new BankStatementImport();
        existing.setStatus(BankStatementImportStatus.PROCESSING); // Incomplete/processing

        when(importRepository.findByProviderAndProviderAccountIdAndSourceHash("MOCK", "ACC_001", sourceHash))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> persistenceService.persistStatementImport(snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-completed state: PROCESSING");

        verify(importRepository, never()).save(any());
        verify(lineRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("13. DB conflict or line exception propagates safely")
    void testDbConflictRollback() {
        String sourceHash = "hash1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef";
        BankStatementSnapshot snapshot = sampleSnapshot("ACC_001", sourceHash, 2);

        when(importRepository.findByProviderAndProviderAccountIdAndSourceHash("MOCK", "ACC_001", sourceHash))
                .thenReturn(Optional.empty());
        when(importRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(lineRepository.saveAll(any())).thenThrow(new RuntimeException("DB Unique constraint violation on line_fingerprint"));

        assertThatThrownBy(() -> persistenceService.persistStatementImport(snapshot))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB Unique constraint violation");
    }
}
