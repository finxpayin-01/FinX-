package com.finx.reconciliation;

import com.finx.banking.dto.BankStatementDirection;
import com.finx.banking.dto.BankStatementEntry;
import com.finx.banking.dto.BankStatementFinality;
import com.finx.reconciliation.service.BankStatementEvidenceFingerprint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BankStatementEvidenceFingerprintTest {

    @Test
    @DisplayName("1. Same line produces exact same fingerprint")
    void testSameLineSameFingerprint() {
        Instant time = Instant.parse("2026-10-06T10:00:00Z");
        LocalDate valueDate = LocalDate.parse("2026-10-06");

        BankStatementEntry entry1 = BankStatementEntry.builder()
                .providerLineId("LINE-001")
                .transactionReference("TXN-REF-1")
                .utrNumber("UTR-001")
                .amount(new BigDecimal("1000.00"))
                .currency("INR")
                .direction(BankStatementDirection.CREDIT)
                .providerStatus("SETTLED")
                .finality(BankStatementFinality.FINAL_SUCCESS)
                .transactionTime(time)
                .valueDate(valueDate)
                .build();

        BankStatementEntry entry2 = BankStatementEntry.builder()
                .providerLineId("LINE-001")
                .transactionReference("TXN-REF-1")
                .utrNumber("UTR-001")
                .amount(new BigDecimal("1000.00"))
                .currency("INR")
                .direction(BankStatementDirection.CREDIT)
                .providerStatus("SETTLED")
                .finality(BankStatementFinality.FINAL_SUCCESS)
                .transactionTime(time)
                .valueDate(valueDate)
                .build();

        String fp1 = BankStatementEvidenceFingerprint.computeLineFingerprint("MOCK", "ACC_001", entry1);
        String fp2 = BankStatementEvidenceFingerprint.computeLineFingerprint("MOCK", "ACC_001", entry2);

        assertThat(fp1).isEqualTo(fp2);
    }

    @Test
    @DisplayName("2. Field change causes fingerprint to change")
    void testFieldChangeChangesFingerprint() {
        Instant time = Instant.parse("2026-10-06T10:00:00Z");
        BankStatementEntry base = BankStatementEntry.builder()
                .providerLineId("LINE-001")
                .transactionReference("TXN-REF-1")
                .utrNumber("UTR-001")
                .amount(new BigDecimal("1000.00"))
                .currency("INR")
                .direction(BankStatementDirection.CREDIT)
                .providerStatus("SETTLED")
                .finality(BankStatementFinality.FINAL_SUCCESS)
                .transactionTime(time)
                .build();

        String baseFp = BankStatementEvidenceFingerprint.computeLineFingerprint("MOCK", "ACC_001", base);

        // Different amount
        BankStatementEntry diffAmount = BankStatementEntry.builder()
                .providerLineId("LINE-001")
                .transactionReference("TXN-REF-1")
                .utrNumber("UTR-001")
                .amount(new BigDecimal("2000.00"))
                .currency("INR")
                .direction(BankStatementDirection.CREDIT)
                .providerStatus("SETTLED")
                .finality(BankStatementFinality.FINAL_SUCCESS)
                .transactionTime(time)
                .build();
        assertThat(BankStatementEvidenceFingerprint.computeLineFingerprint("MOCK", "ACC_001", diffAmount))
                .isNotEqualTo(baseFp);

        // Different direction
        BankStatementEntry diffDir = BankStatementEntry.builder()
                .providerLineId("LINE-001")
                .transactionReference("TXN-REF-1")
                .utrNumber("UTR-001")
                .amount(new BigDecimal("1000.00"))
                .currency("INR")
                .direction(BankStatementDirection.DEBIT)
                .providerStatus("SETTLED")
                .finality(BankStatementFinality.FINAL_SUCCESS)
                .transactionTime(time)
                .build();
        assertThat(BankStatementEvidenceFingerprint.computeLineFingerprint("MOCK", "ACC_001", diffDir))
                .isNotEqualTo(baseFp);

        // Different finality
        BankStatementEntry diffFinality = BankStatementEntry.builder()
                .providerLineId("LINE-001")
                .transactionReference("TXN-REF-1")
                .utrNumber("UTR-001")
                .amount(new BigDecimal("1000.00"))
                .currency("INR")
                .direction(BankStatementDirection.CREDIT)
                .providerStatus("SETTLED")
                .finality(BankStatementFinality.PENDING)
                .transactionTime(time)
                .build();
        assertThat(BankStatementEvidenceFingerprint.computeLineFingerprint("MOCK", "ACC_001", diffFinality))
                .isNotEqualTo(baseFp);
    }

    @Test
    @DisplayName("3. BigDecimal 100.00 and 100.0000 canonicalize consistently")
    void testBigDecimalCanonicalization() {
        Instant time = Instant.parse("2026-10-06T10:00:00Z");
        BankStatementEntry e1 = BankStatementEntry.builder()
                .providerLineId("LINE-001")
                .transactionReference("TXN-1")
                .amount(new BigDecimal("100.00"))
                .currency("INR")
                .direction(BankStatementDirection.CREDIT)
                .finality(BankStatementFinality.FINAL_SUCCESS)
                .transactionTime(time)
                .build();

        BankStatementEntry e2 = BankStatementEntry.builder()
                .providerLineId("LINE-001")
                .transactionReference("TXN-1")
                .amount(new BigDecimal("100.0000"))
                .currency("INR")
                .direction(BankStatementDirection.CREDIT)
                .finality(BankStatementFinality.FINAL_SUCCESS)
                .transactionTime(time)
                .build();

        String fp1 = BankStatementEvidenceFingerprint.computeLineFingerprint("MOCK", "ACC_001", e1);
        String fp2 = BankStatementEvidenceFingerprint.computeLineFingerprint("MOCK", "ACC_001", e2);

        assertThat(fp1).isEqualTo(fp2);
    }

    @Test
    @DisplayName("4, 5. Fingerprint and SourceHash are 64 lowercase hex characters")
    void testFormatIs64LowercaseHex() {
        Instant time = Instant.parse("2026-10-06T10:00:00Z");
        BankStatementEntry entry = BankStatementEntry.builder()
                .providerLineId("LINE-001")
                .transactionReference("TXN-1")
                .amount(new BigDecimal("100"))
                .currency("INR")
                .direction(BankStatementDirection.CREDIT)
                .finality(BankStatementFinality.FINAL_SUCCESS)
                .transactionTime(time)
                .build();

        String fp = BankStatementEvidenceFingerprint.computeLineFingerprint("MOCK", "ACC_001", entry);
        assertThat(fp).matches("^[a-f0-9]{64}$");

        String sourceHash = BankStatementEvidenceFingerprint.computeSourceHash(
                "MOCK", "ACC_001", "INR", BigDecimal.ZERO, BigDecimal.ZERO, "IMP-001",
                List.of(fp), time.minus(1, ChronoUnit.DAYS), time);
        assertThat(sourceHash).matches("^[a-f0-9]{64}$");
    }

    @Test
    @DisplayName("6, 7, 8. Page segmentation, page size, and line order do not affect source hash")
    void testSourceHashIndependenceFromSegmentationAndOrder() {
        Instant wStart = Instant.parse("2026-10-01T00:00:00Z");
        Instant wEnd = Instant.parse("2026-10-07T00:00:00Z");

        String fpA = BankStatementEvidenceFingerprint.computeLineFingerprint(
                "MOCK", "ACC_001", "LINE-A", "TXN-A", "UTR-A", new BigDecimal("100"),
                "INR", "CREDIT", "SETTLED", "FINAL_SUCCESS", wStart.plus(1, ChronoUnit.HOURS), null);

        String fpB = BankStatementEvidenceFingerprint.computeLineFingerprint(
                "MOCK", "ACC_001", "LINE-B", "TXN-B", "UTR-B", new BigDecimal("200"),
                "INR", "CREDIT", "SETTLED", "FINAL_SUCCESS", wStart.plus(2, ChronoUnit.HOURS), null);

        String fpC = BankStatementEvidenceFingerprint.computeLineFingerprint(
                "MOCK", "ACC_001", "LINE-C", "TXN-C", "UTR-C", new BigDecimal("300"),
                "INR", "CREDIT", "SETTLED", "FINAL_SUCCESS", wStart.plus(3, ChronoUnit.HOURS), null);

        List<String> listOrder1 = List.of(fpA, fpB, fpC);
        List<String> listOrder2 = List.of(fpC, fpA, fpB);
        List<String> listOrder3 = List.of(fpB, fpC, fpA);

        String hash1 = BankStatementEvidenceFingerprint.computeSourceHash(
                "MOCK", "ACC_001", "INR", new BigDecimal("1000"), new BigDecimal("1600"), "REF-1", listOrder1, wStart, wEnd);
        String hash2 = BankStatementEvidenceFingerprint.computeSourceHash(
                "MOCK", "ACC_001", "INR", new BigDecimal("1000"), new BigDecimal("1600"), "REF-1", listOrder2, wStart, wEnd);
        String hash3 = BankStatementEvidenceFingerprint.computeSourceHash(
                "MOCK", "ACC_001", "INR", new BigDecimal("1000"), new BigDecimal("1600"), "REF-1", listOrder3, wStart, wEnd);

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash2).isEqualTo(hash3);
    }

    @Test
    @DisplayName("9, 10, 11. Provider, account, and line content change source hash")
    void testSourceHashSensitivity() {
        Instant wStart = Instant.parse("2026-10-01T00:00:00Z");
        Instant wEnd = Instant.parse("2026-10-07T00:00:00Z");
        String fp = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

        String baseHash = BankStatementEvidenceFingerprint.computeSourceHash(
                "MOCK", "ACC_001", "INR", BigDecimal.ZERO, BigDecimal.ZERO, "REF-1", List.of(fp), wStart, wEnd);

        // Different account
        String diffAccount = BankStatementEvidenceFingerprint.computeSourceHash(
                "MOCK", "ACC_002", "INR", BigDecimal.ZERO, BigDecimal.ZERO, "REF-1", List.of(fp), wStart, wEnd);
        assertThat(diffAccount).isNotEqualTo(baseHash);

        // Different provider
        String diffProvider = BankStatementEvidenceFingerprint.computeSourceHash(
                "OTHER_BANK", "ACC_001", "INR", BigDecimal.ZERO, BigDecimal.ZERO, "REF-1", List.of(fp), wStart, wEnd);
        assertThat(diffProvider).isNotEqualTo(baseHash);

        // Different line content
        String diffFp = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";
        String diffLine = BankStatementEvidenceFingerprint.computeSourceHash(
                "MOCK", "ACC_001", "INR", BigDecimal.ZERO, BigDecimal.ZERO, "REF-1", List.of(diffFp), wStart, wEnd);
        assertThat(diffLine).isNotEqualTo(baseHash);
    }

    @Test
    @DisplayName("12. Empty different windows produce different source hashes")
    void testEmptyDifferentWindowsProduceDifferentHashes() {
        Instant win1Start = Instant.parse("2026-10-01T00:00:00Z");
        Instant win1End = Instant.parse("2026-10-01T23:59:59Z");

        Instant win2Start = Instant.parse("2026-10-02T00:00:00Z");
        Instant win2End = Instant.parse("2026-10-02T23:59:59Z");

        String hashOct1 = BankStatementEvidenceFingerprint.computeSourceHash(
                "MOCK", "ACC_001", "INR", BigDecimal.ZERO, BigDecimal.ZERO, "REF-1", Collections.emptyList(), win1Start, win1End);

        String hashOct2 = BankStatementEvidenceFingerprint.computeSourceHash(
                "MOCK", "ACC_001", "INR", BigDecimal.ZERO, BigDecimal.ZERO, "REF-1", Collections.emptyList(), win2Start, win2End);

        assertThat(hashOct1).isNotEqualTo(hashOct2);
    }
}
