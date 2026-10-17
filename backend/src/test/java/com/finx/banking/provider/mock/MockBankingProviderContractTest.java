package com.finx.banking.provider.mock;

import com.finx.banking.capability.BankingProviderCapability;
import com.finx.banking.dto.BankingDtos.*;
import com.finx.banking.provider.BankingProvider;
import com.finx.banking.provider.BankingProviderContractTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MockBankingProviderContractTest extends BankingProviderContractTest {

    private final MockBankingProvider provider = new MockBankingProvider("test-secret-key-32-chars-length");

    @Override
    protected BankingProvider createProvider() {
        return provider;
    }

    @Test
    @DisplayName("MockBankingProvider declares all 10 capabilities")
    void testMockDeclaresAllCapabilities() {
        for (BankingProviderCapability cap : BankingProviderCapability.values()) {
            assertThat(provider.supports(cap))
                    .as("Mock should support " + cap)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("Settlement lookup for unknown operation key returns notFound")
    void testUnknownOperationKeyLookup() {
        SettlementLookupResult result = provider.lookupSettlementByOperationKey("FINX-REL-UNKNOWN-ID");
        assertThat(result.supported()).isTrue();
        assertThat(result.found()).isFalse();
        assertThat(result.settlement()).isNull();
    }

    @Test
    @DisplayName("Settlement status query for unknown settlement ID returns NOT_FOUND")
    void testUnknownSettlementStatus() {
        SettlementStatusResult result = provider.getSettlementStatus("MOCK-SETTL-NONEXISTENT");
        assertThat(result.status()).isEqualTo("NOT_FOUND");
        assertThat(result.utrNumber()).isNull();
    }
}
