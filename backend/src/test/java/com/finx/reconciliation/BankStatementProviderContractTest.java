package com.finx.reconciliation;

import com.finx.banking.dto.*;
import com.finx.banking.dto.BankingDtos.BankTransactionRecord;
import com.finx.banking.provider.mock.MockBankingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BankStatementProviderContractTest {

    private MockBankingProvider provider;

    @BeforeEach
    void setUp() {
        provider = new MockBankingProvider("test-secret-key-32-chars-length!!");
        provider.clearDurableStatementEntries();
    }

    @Test
    @DisplayName("1. Provider account scope preserved")
    void testProviderAccountScopePreserved() {
        BankStatementQuery query = BankStatementQuery.builder()
                .providerAccountId(MockBankingProvider.MOCK_ESCROW_ACCOUNT)
                .windowStart(Instant.now().minus(7, ChronoUnit.DAYS))
                .windowEnd(Instant.now())
                .pageSize(10)
                .build();

        BankStatementPage page = provider.fetchStatementPage(query);
        assertThat(page.getProviderAccountId()).isEqualTo(MockBankingProvider.MOCK_ESCROW_ACCOUNT);
    }

    @Test
    @DisplayName("2. No raw customer account in providerAccountId")
    void testNoRawCustomerAccountInProviderAccountId() {
        assertThat(MockBankingProvider.MOCK_ESCROW_ACCOUNT).doesNotContain("1234567890", "9876543210");
        assertThat(MockBankingProvider.MOCK_ESCROW_ACCOUNT).isEqualTo("MOCK_ESCROW_ACCOUNT");
    }

    @Test
    @DisplayName("3. Deterministic page ordering (transactionTime asc, providerLineId asc)")
    void testDeterministicPageOrdering() {
        Instant now = Instant.now();
        BankStatementEntry entry1 = BankStatementEntry.builder()
                .providerLineId("LINE-003")
                .transactionReference("TXN-3")
                .amount(BigDecimal.valueOf(300))
                .currency("INR")
                .direction(BankStatementDirection.CREDIT)
                .finality(BankStatementFinality.FINAL_SUCCESS)
                .transactionTime(now.minus(1, ChronoUnit.HOURS))
                .build();
        BankStatementEntry entry2 = BankStatementEntry.builder()
                .providerLineId("LINE-001")
                .transactionReference("TXN-1")
                .amount(BigDecimal.valueOf(100))
                .currency("INR")
                .direction(BankStatementDirection.CREDIT)
                .finality(BankStatementFinality.FINAL_SUCCESS)
                .transactionTime(now.minus(2, ChronoUnit.HOURS))
                .build();
        BankStatementEntry entry3 = BankStatementEntry.builder()
                .providerLineId("LINE-002")
                .transactionReference("TXN-2")
                .amount(BigDecimal.valueOf(200))
                .currency("INR")
                .direction(BankStatementDirection.CREDIT)
                .finality(BankStatementFinality.FINAL_SUCCESS)
                .transactionTime(now.minus(2, ChronoUnit.HOURS))
                .build();

        provider.addDurableStatementEntry(entry1);
        provider.addDurableStatementEntry(entry2);
        provider.addDurableStatementEntry(entry3);

        BankStatementQuery query = BankStatementQuery.builder()
                .providerAccountId(MockBankingProvider.MOCK_ESCROW_ACCOUNT)
                .windowStart(now.minus(5, ChronoUnit.HOURS))
                .windowEnd(now)
                .pageSize(10)
                .build();

        BankStatementPage page = provider.fetchStatementPage(query);
        assertThat(page.getEntries()).hasSize(3);
        assertThat(page.getEntries().get(0).getProviderLineId()).isEqualTo("LINE-001");
        assertThat(page.getEntries().get(1).getProviderLineId()).isEqualTo("LINE-002");
        assertThat(page.getEntries().get(2).getProviderLineId()).isEqualTo("LINE-003");
    }

    @Test
    @DisplayName("4, 5, 6, 7. Pagination: pageSize honored, cursor advances, no duplicate lines, final page cursor null")
    void testPaginationFlow() {
        Instant now = Instant.now();
        for (int i = 1; i <= 5; i++) {
            provider.addDurableStatementEntry(BankStatementEntry.builder()
                    .providerLineId(String.format("LINE-%03d", i))
                    .transactionReference(String.format("TXN-%03d", i))
                    .amount(BigDecimal.valueOf(i * 100))
                    .currency("INR")
                    .direction(BankStatementDirection.CREDIT)
                    .finality(BankStatementFinality.FINAL_SUCCESS)
                    .transactionTime(now.plus(i, ChronoUnit.MINUTES))
                    .build());
        }

        // Page 1: size 2
        BankStatementQuery q1 = BankStatementQuery.builder()
                .providerAccountId(MockBankingProvider.MOCK_ESCROW_ACCOUNT)
                .windowStart(now)
                .windowEnd(now.plus(10, ChronoUnit.MINUTES))
                .pageSize(2)
                .build();

        BankStatementPage p1 = provider.fetchStatementPage(q1);
        assertThat(p1.getEntries()).hasSize(2);
        assertThat(p1.getNextCursor()).isEqualTo("2");
        assertThat(p1.getEntries().get(0).getProviderLineId()).isEqualTo("LINE-001");
        assertThat(p1.getEntries().get(1).getProviderLineId()).isEqualTo("LINE-002");

        // Page 2: size 2, cursor "2"
        BankStatementQuery q2 = BankStatementQuery.builder()
                .providerAccountId(MockBankingProvider.MOCK_ESCROW_ACCOUNT)
                .windowStart(now)
                .windowEnd(now.plus(10, ChronoUnit.MINUTES))
                .pageSize(2)
                .cursor("2")
                .build();

        BankStatementPage p2 = provider.fetchStatementPage(q2);
        assertThat(p2.getEntries()).hasSize(2);
        assertThat(p2.getNextCursor()).isEqualTo("4");
        assertThat(p2.getEntries().get(0).getProviderLineId()).isEqualTo("LINE-003");
        assertThat(p2.getEntries().get(1).getProviderLineId()).isEqualTo("LINE-004");

        // Page 3: size 2, cursor "4"
        BankStatementQuery q3 = BankStatementQuery.builder()
                .providerAccountId(MockBankingProvider.MOCK_ESCROW_ACCOUNT)
                .windowStart(now)
                .windowEnd(now.plus(10, ChronoUnit.MINUTES))
                .pageSize(2)
                .cursor("4")
                .build();

        BankStatementPage p3 = provider.fetchStatementPage(q3);
        assertThat(p3.getEntries()).hasSize(1);
        assertThat(p3.getNextCursor()).isNull();
        assertThat(p3.getEntries().get(0).getProviderLineId()).isEqualTo("LINE-005");
    }

    @Test
    @DisplayName("8. Window filtering works")
    void testWindowFiltering() {
        Instant now = Instant.now();
        provider.addDurableStatementEntry(BankStatementEntry.builder()
                .providerLineId("LINE-OLD")
                .transactionReference("TXN-OLD")
                .amount(BigDecimal.valueOf(100))
                .currency("INR")
                .direction(BankStatementDirection.CREDIT)
                .finality(BankStatementFinality.FINAL_SUCCESS)
                .transactionTime(now.minus(10, ChronoUnit.DAYS))
                .build());

        provider.addDurableStatementEntry(BankStatementEntry.builder()
                .providerLineId("LINE-RECENT")
                .transactionReference("TXN-RECENT")
                .amount(BigDecimal.valueOf(200))
                .currency("INR")
                .direction(BankStatementDirection.CREDIT)
                .finality(BankStatementFinality.FINAL_SUCCESS)
                .transactionTime(now.minus(1, ChronoUnit.DAYS))
                .build());

        BankStatementQuery query = BankStatementQuery.builder()
                .providerAccountId(MockBankingProvider.MOCK_ESCROW_ACCOUNT)
                .windowStart(now.minus(2, ChronoUnit.DAYS))
                .windowEnd(now)
                .pageSize(10)
                .build();

        BankStatementPage page = provider.fetchStatementPage(query);
        assertThat(page.getEntries()).hasSize(1);
        assertThat(page.getEntries().get(0).getProviderLineId()).isEqualTo("LINE-RECENT");
    }

    @Test
    @DisplayName("9. Invalid query parameters rejected fail-closed")
    void testInvalidQueryRejected() {
        Instant now = Instant.now();
        // Null query
        assertThatThrownBy(() -> provider.fetchStatementPage(null))
                .isInstanceOf(IllegalArgumentException.class);

        // Blank account
        assertThatThrownBy(() -> provider.fetchStatementPage(BankStatementQuery.builder()
                .providerAccountId(" ")
                .windowStart(now.minus(1, ChronoUnit.DAYS))
                .windowEnd(now)
                .pageSize(10)
                .build()))
                .isInstanceOf(IllegalArgumentException.class);

        // Window end before window start
        assertThatThrownBy(() -> provider.fetchStatementPage(BankStatementQuery.builder()
                .providerAccountId(MockBankingProvider.MOCK_ESCROW_ACCOUNT)
                .windowStart(now)
                .windowEnd(now.minus(1, ChronoUnit.DAYS))
                .pageSize(10)
                .build()))
                .isInstanceOf(IllegalArgumentException.class);

        // Page size <= 0
        assertThatThrownBy(() -> provider.fetchStatementPage(BankStatementQuery.builder()
                .providerAccountId(MockBankingProvider.MOCK_ESCROW_ACCOUNT)
                .windowStart(now.minus(1, ChronoUnit.DAYS))
                .windowEnd(now)
                .pageSize(0)
                .build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("10-17. Field semantics: providerLineId, time, valueDate, finality, directions")
    void testFieldSemantics() {
        Instant now = Instant.now();
        LocalDate valueDate = LocalDate.now();

        BankStatementEntry settledCredit = BankStatementEntry.builder()
                .providerLineId("LINE-CREDIT-1")
                .transactionReference("TXN-CR-1")
                .utrNumber("UTR-CR-1")
                .amount(BigDecimal.valueOf(5000))
                .currency("INR")
                .direction(BankStatementDirection.CREDIT)
                .providerStatus("SETTLED")
                .finality(BankStatementFinality.FINAL_SUCCESS)
                .transactionTime(now)
                .valueDate(valueDate)
                .build();

        BankStatementEntry unknownDebit = BankStatementEntry.builder()
                .providerLineId("LINE-DEBIT-1")
                .transactionReference("TXN-DR-1")
                .utrNumber("UTR-DR-1")
                .amount(BigDecimal.valueOf(2500))
                .currency("INR")
                .direction(BankStatementDirection.DEBIT)
                .providerStatus("IN_PROGRESS")
                .finality(BankStatementFinality.UNKNOWN)
                .transactionTime(now)
                .valueDate(valueDate)
                .build();

        provider.addDurableStatementEntry(settledCredit);
        provider.addDurableStatementEntry(unknownDebit);

        BankStatementQuery query = BankStatementQuery.builder()
                .providerAccountId(MockBankingProvider.MOCK_ESCROW_ACCOUNT)
                .windowStart(now.minus(1, ChronoUnit.HOURS))
                .windowEnd(now.plus(1, ChronoUnit.HOURS))
                .pageSize(10)
                .build();

        BankStatementPage page = provider.fetchStatementPage(query);
        assertThat(page.getEntries()).hasSize(2);

        BankStatementEntry cr = page.getEntries().get(0);
        assertThat(cr.getDirection()).isEqualTo(BankStatementDirection.CREDIT);
        assertThat(cr.getFinality()).isEqualTo(BankStatementFinality.FINAL_SUCCESS);
        assertThat(cr.getValueDate()).isEqualTo(valueDate);

        BankStatementEntry dr = page.getEntries().get(1);
        assertThat(dr.getDirection()).isEqualTo(BankStatementDirection.DEBIT);
        assertThat(dr.getFinality()).isEqualTo(BankStatementFinality.UNKNOWN);
    }

    @Test
    @DisplayName("18. Legacy fetchTransactions still works unchanged")
    void testLegacyFetchTransactionsUnchanged() {
        LocalDate today = LocalDate.now();
        provider.recordSimulatedInboundCredit("TXN-LEG-1", "UTR-LEG-1", "VA-100", BigDecimal.valueOf(500), "INR");

        List<BankTransactionRecord> legacyRecords = provider.fetchTransactions(today.minusDays(1), today.plusDays(1));
        assertThat(legacyRecords).isNotEmpty();
        assertThat(legacyRecords.get(0).transactionReference()).isEqualTo("TXN-LEG-1");
    }
}
