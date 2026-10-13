package com.finx.banking.provider.mock;

import com.finx.banking.dto.BankingDtos.SettlementLookupResult;
import com.finx.banking.dto.BankingDtos.SettlementRequest;
import com.finx.banking.dto.BankingDtos.SettlementResult;
import com.finx.banking.dto.BankingDtos.SettlementStatusResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MockBankSettlementStatusSafetyTest {

    private MockBankingProvider provider;

    @BeforeEach
    void setUp() {
        provider = new MockBankingProvider("test-secret-key-32-chars-long");
    }

    @Test
    @DisplayName("Unknown providerSettlementId returns NOT_FOUND with null UTR - NEVER fabricated SETTLED")
    void unknownSettlementId_returnsNotFound_neverFabricated() {
        String unknownId = "MOCK-SETTL-UNKNOWN-" + UUID.randomUUID();

        SettlementStatusResult result = provider.getSettlementStatus(unknownId);

        assertThat(result.providerSettlementId()).isEqualTo(unknownId);
        assertThat(result.status()).isEqualTo("NOT_FOUND");
        assertThat(result.utrNumber()).isNull();
        assertThat(result.settledAt()).isNull();
        assertThat(result.failureReason()).isEqualTo("SETTLEMENT_NOT_FOUND");
    }

    @Test
    @DisplayName("Known providerSettlementId returns real settlement")
    void knownSettlementId_returnsRealSettlement() {
        SettlementRequest request = new SettlementRequest(
                "SETTL-REF-001",
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("50000.00"),
                "INR",
                "Beneficiary Corp",
                "9876543210",
                "HDFC0001234",
                "FINX-REL-" + UUID.randomUUID()
        );

        SettlementResult initiated = provider.initiateSettlement(request);
        assertThat(initiated.providerSettlementId()).isNotNull();

        SettlementStatusResult status = provider.getSettlementStatus(initiated.providerSettlementId());

        assertThat(status.providerSettlementId()).isEqualTo(initiated.providerSettlementId());
        assertThat(status.status()).isEqualTo("SETTLED");
        assertThat(status.utrNumber()).isEqualTo(initiated.utrNumber());
        assertThat(status.settledAt()).isEqualTo(initiated.initiatedAt());
        assertThat(status.failureReason()).isNull();
    }

    @Test
    @DisplayName("Known operation key returns supported=true, found=true with actual settlement")
    void knownOperationKey_lookupSupportedAndFound() {
        String operationKey = "FINX-REL-" + UUID.randomUUID();
        SettlementRequest request = new SettlementRequest(
                "SETTL-REF-002",
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("75000.00"),
                "INR",
                "Beneficiary Corp",
                "9876543210",
                "HDFC0001234",
                operationKey
        );

        SettlementResult initiated = provider.initiateSettlement(request);

        SettlementLookupResult lookup = provider.lookupSettlementByOperationKey(operationKey);

        assertThat(lookup.supported()).isTrue();
        assertThat(lookup.found()).isTrue();
        assertThat(lookup.settlement()).isNotNull();
        assertThat(lookup.settlement().providerSettlementId()).isEqualTo(initiated.providerSettlementId());
        assertThat(lookup.settlement().utrNumber()).isEqualTo(initiated.utrNumber());
        assertThat(lookup.settlement().status()).isEqualTo("SETTLED");
    }

    @Test
    @DisplayName("Unknown operation key returns supported=true, found=false, settlement=null")
    void unknownOperationKey_lookupSupportedAndNotFound() {
        String unknownOpKey = "FINX-REL-" + UUID.randomUUID();

        SettlementLookupResult lookup = provider.lookupSettlementByOperationKey(unknownOpKey);

        assertThat(lookup.supported()).isTrue();
        assertThat(lookup.found()).isFalse();
        assertThat(lookup.settlement()).isNull();
    }

    @Test
    @DisplayName("Operation-key lookup is deterministic on repeated calls")
    void operationKeyLookup_isDeterministic() {
        String operationKey = "FINX-REL-" + UUID.randomUUID();
        SettlementRequest request = new SettlementRequest(
                "SETTL-REF-003",
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("25000.00"),
                "INR",
                "Beneficiary Corp",
                "9876543210",
                "HDFC0001234",
                operationKey
        );

        provider.initiateSettlement(request);

        SettlementLookupResult lookup1 = provider.lookupSettlementByOperationKey(operationKey);
        SettlementLookupResult lookup2 = provider.lookupSettlementByOperationKey(operationKey);

        assertThat(lookup1.found()).isTrue();
        assertThat(lookup2.found()).isTrue();
        assertThat(lookup1.settlement().providerSettlementId()).isEqualTo(lookup2.settlement().providerSettlementId());
        assertThat(lookup1.settlement().utrNumber()).isEqualTo(lookup2.settlement().utrNumber());
    }
}
