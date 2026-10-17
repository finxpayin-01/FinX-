package com.finx.banking.provider.partner;

import com.finx.banking.capability.BankingCapabilityNotConfiguredException;
import com.finx.banking.capability.BankingCapabilityNotSupportedException;
import com.finx.banking.capability.BankingProviderCapabilities;
import com.finx.banking.capability.BankingProviderCapability;
import com.finx.banking.config.BankingPartnerProperties;
import com.finx.banking.contract.UnsupportedBankApiContract;
import com.finx.banking.dto.BankingDtos.*;
import com.finx.banking.provider.BankingProvider;
import com.finx.banking.provider.BankingProviderContractTest;
import com.finx.banking.transport.BankHttpTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PartnerBankingProviderFailClosedContractTest extends BankingProviderContractTest {

    private final BankingPartnerProperties properties = new BankingPartnerProperties();
    private final BankHttpTransport transport = new BankHttpTransport(
            Mockito.mock(WebClient.class),
            "TEST_PARTNER",
            "client-id-123"
    );
    private final UnsupportedBankApiContract contract = new UnsupportedBankApiContract("TEST_PARTNER");

    {
        properties.setProviderName("TEST_PARTNER");
    }

    @Override
    protected BankingProvider createProvider() {
        return new PartnerBankingProvider(properties, transport, contract, BankingProviderCapabilities.empty());
    }

    @Test
    @DisplayName("Unsupported capability throws BankingCapabilityNotSupportedException")
    void testUnsupportedCapabilityThrows() {
        BankingProvider provider = createProvider();
        assertThat(provider.supports(BankingProviderCapability.SETTLEMENT_INITIATE)).isFalse();

        SettlementRequest req = new SettlementRequest(
                "SETTL-REF-1",
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigDecimal.valueOf(1000),
                "INR",
                "Vendor",
                "1234567890",
                "HDFC0000060",
                "FINX-REL-" + UUID.randomUUID()
        );

        assertThatThrownBy(() -> provider.initiateSettlement(req))
                .isInstanceOf(BankingCapabilityNotSupportedException.class)
                .hasMessageContaining("CAPABILITY_NOT_SUPPORTED");
    }

    @Test
    @DisplayName("Supported capability backed by UnsupportedBankApiContract throws BankingCapabilityNotConfiguredException")
    void testSupportedCapabilityWithUnsupportedContractThrows() {
        BankingProvider provider = new PartnerBankingProvider(
                properties,
                transport,
                contract,
                BankingProviderCapabilities.of(BankingProviderCapability.SETTLEMENT_INITIATE)
        );

        SettlementRequest req = new SettlementRequest(
                "SETTL-REF-1",
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigDecimal.valueOf(1000),
                "INR",
                "Vendor",
                "1234567890",
                "HDFC0000060",
                "FINX-REL-" + UUID.randomUUID()
        );

        assertThatThrownBy(() -> provider.initiateSettlement(req))
                .isInstanceOf(BankingCapabilityNotConfiguredException.class)
                .hasMessageContaining("OFFICIAL_BANK_API_CONTRACT_NOT_PRESENT");
    }

    @Test
    @DisplayName("Settlement initiation requires FINX-REL- stable operation idempotency key prefix")
    void testRequiresStableOperationKeyPrefix() {
        BankingProvider provider = new PartnerBankingProvider(
                properties,
                transport,
                contract,
                BankingProviderCapabilities.of(BankingProviderCapability.SETTLEMENT_INITIATE)
        );

        SettlementRequest req = new SettlementRequest(
                "SETTL-REF-1",
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigDecimal.valueOf(1000),
                "INR",
                "Vendor",
                "1234567890",
                "HDFC0000060",
                "RANDOM-KEY-1234" // Invalid: missing FINX-REL- prefix
        );

        assertThatThrownBy(() -> provider.initiateSettlement(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("STABLE_OPERATION_ID_REQUIRED");
    }
}
