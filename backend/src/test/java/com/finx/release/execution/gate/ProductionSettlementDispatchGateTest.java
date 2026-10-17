package com.finx.release.execution.gate;

import com.finx.banking.capability.BankingProviderCapabilities;
import com.finx.banking.capability.BankingProviderCapability;
import com.finx.banking.config.BankingPartnerProperties;
import com.finx.banking.contract.UnsupportedBankApiContract;
import com.finx.banking.provider.mock.MockBankingProvider;
import com.finx.banking.provider.partner.PartnerBankingProvider;
import com.finx.banking.readiness.BankingProviderReadinessService;
import com.finx.banking.transport.BankHttpTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionSettlementDispatchGateTest {

    @Test
    @DisplayName("Mock provider is denied production execution")
    void testMockProviderDenied() {
        MockBankingProvider mockProvider = new MockBankingProvider("secret");
        ProductionSettlementDispatchGate gate = new ProductionSettlementDispatchGate(
                true,
                mockProvider,
                null,
                false
        );

        assertThat(gate.isProductionDispatchAllowed()).isFalse();
        assertThatThrownBy(gate::requireProductionDispatchAllowed)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PRODUCTION_SETTLEMENT_DISPATCH_DISABLED");
    }

    @Test
    @DisplayName("Master production flag false denies production dispatch")
    void testMasterFlagFalseDenied() {
        ProductionSettlementDispatchGate gate = new ProductionSettlementDispatchGate(
                false,
                Mockito.mock(PartnerBankingProvider.class),
                new BankingPartnerProperties(),
                false
        );

        assertThat(gate.isProductionDispatchAllowed()).isFalse();
        assertThatThrownBy(gate::requireProductionDispatchAllowed)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Partner in sandbox mode is denied production execution")
    void testPartnerInSandboxDenied() {
        BankingPartnerProperties props = new BankingPartnerProperties();
        props.setSandbox(true);
        props.setProductionExecutionEnabled(false);
        props.getMtls().setEnabled(true);

        PartnerBankingProvider partner = new PartnerBankingProvider(
                props,
                new BankHttpTransport(Mockito.mock(WebClient.class), "PARTNER", "c1"),
                new UnsupportedBankApiContract("PARTNER"),
                BankingProviderCapabilities.of(BankingProviderReadinessService.MINIMUM_PRODUCTION_CAPABILITIES)
        );

        ProductionSettlementDispatchGate gate = new ProductionSettlementDispatchGate(
                true,
                partner,
                props,
                false
        );

        assertThat(gate.isProductionDispatchAllowed()).isFalse();
    }

    @Test
    @DisplayName("Partner with missing mTLS is denied production execution")
    void testPartnerMissingMtlsDenied() {
        BankingPartnerProperties props = new BankingPartnerProperties();
        props.setSandbox(false);
        props.setProductionExecutionEnabled(true);
        props.getMtls().setEnabled(false); // Missing mTLS

        PartnerBankingProvider partner = new PartnerBankingProvider(
                props,
                new BankHttpTransport(Mockito.mock(WebClient.class), "PARTNER", "c1"),
                new UnsupportedBankApiContract("PARTNER"),
                BankingProviderCapabilities.of(BankingProviderReadinessService.MINIMUM_PRODUCTION_CAPABILITIES)
        );

        ProductionSettlementDispatchGate gate = new ProductionSettlementDispatchGate(
                true,
                partner,
                props,
                true
        );

        assertThat(gate.isProductionDispatchAllowed()).isFalse();
    }

    @Test
    @DisplayName("Partner with missing critical capability is denied production execution")
    void testPartnerMissingCapabilityDenied() {
        BankingPartnerProperties props = new BankingPartnerProperties();
        props.setSandbox(false);
        props.setProductionExecutionEnabled(true);
        props.getMtls().setEnabled(true);

        // Missing STATEMENT_PAGING
        PartnerBankingProvider partner = new PartnerBankingProvider(
                props,
                new BankHttpTransport(Mockito.mock(WebClient.class), "PARTNER", "c1"),
                new UnsupportedBankApiContract("PARTNER"),
                BankingProviderCapabilities.of(
                        BankingProviderCapability.SETTLEMENT_INITIATE,
                        BankingProviderCapability.SETTLEMENT_STATUS_QUERY,
                        BankingProviderCapability.SETTLEMENT_OPERATION_LOOKUP,
                        BankingProviderCapability.WEBHOOK_SIGNATURE_VERIFICATION
                )
        );

        ProductionSettlementDispatchGate gate = new ProductionSettlementDispatchGate(
                true,
                partner,
                props,
                true
        );

        assertThat(gate.isProductionDispatchAllowed()).isFalse();
    }

    @Test
    @DisplayName("Even if all technical conditions are satisfied, E10 STILL DENIES production execution due to E12 certification gate absent")
    void testAllTechnicalConditionsMetStillDeniedInE10() {
        BankingPartnerProperties props = new BankingPartnerProperties();
        props.setSandbox(false);
        props.setProductionExecutionEnabled(true);
        props.getMtls().setEnabled(true);

        PartnerBankingProvider partner = new PartnerBankingProvider(
                props,
                new BankHttpTransport(Mockito.mock(WebClient.class), "PARTNER", "c1"),
                new UnsupportedBankApiContract("PARTNER"),
                BankingProviderCapabilities.of(BankingProviderReadinessService.MINIMUM_PRODUCTION_CAPABILITIES)
        );

        // e12Certified is false in Phase E10
        ProductionSettlementDispatchGate gate = new ProductionSettlementDispatchGate(
                true,
                partner,
                props,
                false // strictly false in E10
        );

        assertThat(gate.isProductionDispatchAllowed()).isFalse();
        assertThatThrownBy(gate::requireProductionDispatchAllowed)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Live money movement requires Phase E12 certification");
    }
}
