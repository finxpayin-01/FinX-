package com.finx.banking.provider.mock;

import com.finx.banking.dto.BankingDtos.SettlementRequest;
import com.finx.banking.dto.BankingDtos.SettlementResult;
import com.finx.exception.DuplicateResourceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockBankSettlementIdempotencyTest {

    private MockBankingProvider provider;

    @BeforeEach
    void setUp() {
        provider = new MockBankingProvider("mock-secret-key-32chars-for-test");
    }

    @Test
    @DisplayName("Same idempotencyKey + same request returns identical provider settlement identity and exactly 1 simulated debit")
    void testIdempotentReplayReturnsSameResultAndSingleDebit() {
        String idempotencyKey = "FINX-REL-" + UUID.randomUUID();
        UUID dealId = UUID.randomUUID();
        UUID milestoneId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("50000.00");

        SettlementRequest req1 = new SettlementRequest(
                idempotencyKey,
                dealId,
                milestoneId,
                amount,
                "INR",
                "Acme Corp",
                "123456789012",
                "HDFC0001234",
                idempotencyKey
        );

        SettlementResult result1 = provider.initiateSettlement(req1);

        assertThat(result1.providerSettlementId()).isNotNull();
        assertThat(result1.utrNumber()).isNotNull();
        assertThat(result1.status()).isEqualTo("SETTLED");
        assertThat(provider.fetchTransactions(LocalDate.now(), LocalDate.now())).hasSize(1);

        // Replay identical request
        SettlementRequest req2 = new SettlementRequest(
                idempotencyKey,
                dealId,
                milestoneId,
                amount,
                "INR",
                "Acme Corp",
                "123456789012",
                "HDFC0001234",
                idempotencyKey
        );

        SettlementResult result2 = provider.initiateSettlement(req2);

        // Must return identical identities
        assertThat(result2.providerSettlementId()).isEqualTo(result1.providerSettlementId());
        assertThat(result2.utrNumber()).isEqualTo(result1.utrNumber());
        assertThat(result2.status()).isEqualTo(result1.status());

        // Simulated bank feed MUST contain exactly ONE debit, not two
        assertThat(provider.fetchTransactions(LocalDate.now(), LocalDate.now())).hasSize(1);
    }

    @Test
    @DisplayName("Same idempotencyKey + conflicting amount throws DuplicateResourceException")
    void testSameKeyConflictingAmountThrows() {
        String idempotencyKey = "FINX-REL-" + UUID.randomUUID();
        UUID dealId = UUID.randomUUID();
        UUID milestoneId = UUID.randomUUID();

        SettlementRequest req1 = new SettlementRequest(
                idempotencyKey,
                dealId,
                milestoneId,
                new BigDecimal("50000.00"),
                "INR",
                "Acme Corp",
                "123456789012",
                "HDFC0001234",
                idempotencyKey
        );
        provider.initiateSettlement(req1);

        // Conflicting amount
        SettlementRequest req2 = new SettlementRequest(
                idempotencyKey,
                dealId,
                milestoneId,
                new BigDecimal("60000.00"),
                "INR",
                "Acme Corp",
                "123456789012",
                "HDFC0001234",
                idempotencyKey
        );

        assertThatThrownBy(() -> provider.initiateSettlement(req2))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("Conflicting mock settlement request");

        // Debits remains 1
        assertThat(provider.fetchTransactions(LocalDate.now(), LocalDate.now())).hasSize(1);
    }

    @Test
    @DisplayName("Same idempotencyKey + conflicting beneficiary account throws DuplicateResourceException")
    void testSameKeyConflictingBeneficiaryThrows() {
        String idempotencyKey = "FINX-REL-" + UUID.randomUUID();
        UUID dealId = UUID.randomUUID();
        UUID milestoneId = UUID.randomUUID();

        SettlementRequest req1 = new SettlementRequest(
                idempotencyKey,
                dealId,
                milestoneId,
                new BigDecimal("50000.00"),
                "INR",
                "Acme Corp",
                "123456789012",
                "HDFC0001234",
                idempotencyKey
        );
        provider.initiateSettlement(req1);

        // Conflicting beneficiary account
        SettlementRequest req2 = new SettlementRequest(
                idempotencyKey,
                dealId,
                milestoneId,
                new BigDecimal("50000.00"),
                "INR",
                "Acme Corp",
                "999999999999",
                "HDFC0001234",
                idempotencyKey
        );

        assertThatThrownBy(() -> provider.initiateSettlement(req2))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("Conflicting mock settlement request");
    }

    @Test
    @DisplayName("Different idempotencyKeys return distinct provider settlement identities and records separate debits")
    void testDifferentKeysReturnDistinctIdentities() {
        UUID dealId = UUID.randomUUID();
        UUID milestoneId = UUID.randomUUID();

        SettlementRequest req1 = new SettlementRequest(
                "REF-1",
                dealId,
                milestoneId,
                new BigDecimal("50000.00"),
                "INR",
                "Acme Corp",
                "123456789012",
                "HDFC0001234",
                "KEY-1"
        );
        SettlementRequest req2 = new SettlementRequest(
                "REF-2",
                dealId,
                milestoneId,
                new BigDecimal("50000.00"),
                "INR",
                "Acme Corp",
                "123456789012",
                "HDFC0001234",
                "KEY-2"
        );

        SettlementResult res1 = provider.initiateSettlement(req1);
        SettlementResult res2 = provider.initiateSettlement(req2);

        assertThat(res1.providerSettlementId()).isNotEqualTo(res2.providerSettlementId());
        assertThat(provider.fetchTransactions(LocalDate.now(), LocalDate.now())).hasSize(2);
    }
}
