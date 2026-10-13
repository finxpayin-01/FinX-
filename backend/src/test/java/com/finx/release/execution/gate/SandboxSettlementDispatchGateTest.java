package com.finx.release.execution.gate;

import com.finx.banking.provider.BankingProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

class SandboxSettlementDispatchGateTest {

    @Test
    @DisplayName("1. Property disabled -> reject")
    void testPropertyDisabledRejects() {
        BankingProvider mockProvider = Mockito.mock(BankingProvider.class);
        when(mockProvider.getProviderName()).thenReturn("MOCK");

        SandboxSettlementDispatchGate gate = new SandboxSettlementDispatchGate(false, "mock", mockProvider);

        assertThat(gate.isDispatchAllowed()).isFalse();
        assertThatThrownBy(gate::requireDispatchAllowed)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SANDBOX_SETTLEMENT_DISPATCH_DISABLED");
    }

    @Test
    @DisplayName("2. Property missing (default false) -> reject")
    void testPropertyMissingRejects() {
        BankingProvider mockProvider = Mockito.mock(BankingProvider.class);
        when(mockProvider.getProviderName()).thenReturn("MOCK");

        SandboxSettlementDispatchGate gate = new SandboxSettlementDispatchGate(false, "mock", mockProvider);

        assertThat(gate.isDispatchAllowed()).isFalse();
        assertThatThrownBy(gate::requireDispatchAllowed)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("3. Provider config missing/empty -> reject")
    void testProviderConfigMissingRejects() {
        BankingProvider mockProvider = Mockito.mock(BankingProvider.class);
        when(mockProvider.getProviderName()).thenReturn("MOCK");

        SandboxSettlementDispatchGate gateBlank = new SandboxSettlementDispatchGate(true, "  ", mockProvider);
        assertThat(gateBlank.isDispatchAllowed()).isFalse();
        assertThatThrownBy(gateBlank::requireDispatchAllowed)
                .isInstanceOf(IllegalStateException.class);

        SandboxSettlementDispatchGate gateNull = new SandboxSettlementDispatchGate(true, null, mockProvider);
        assertThat(gateNull.isDispatchAllowed()).isFalse();
        assertThatThrownBy(gateNull::requireDispatchAllowed)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("4. Provider config real (e.g. axis) -> reject")
    void testProviderConfigRealRejects() {
        BankingProvider mockProvider = Mockito.mock(BankingProvider.class);
        when(mockProvider.getProviderName()).thenReturn("MOCK");

        SandboxSettlementDispatchGate gate = new SandboxSettlementDispatchGate(true, "axis", mockProvider);

        assertThat(gate.isDispatchAllowed()).isFalse();
        assertThatThrownBy(gate::requireDispatchAllowed)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("5. Configured mock + actual real provider -> reject")
    void testConfiguredMockActualRealProviderRejects() {
        BankingProvider realProvider = Mockito.mock(BankingProvider.class);
        when(realProvider.getProviderName()).thenReturn("AXIS");

        SandboxSettlementDispatchGate gate = new SandboxSettlementDispatchGate(true, "mock", realProvider);

        assertThat(gate.isDispatchAllowed()).isFalse();
        assertThatThrownBy(gate::requireDispatchAllowed)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("6. Configured mock + actual MOCK + enabled -> allow")
    void testConfiguredMockActualMockEnabledAllows() {
        BankingProvider mockProvider = Mockito.mock(BankingProvider.class);
        when(mockProvider.getProviderName()).thenReturn("MOCK");

        SandboxSettlementDispatchGate gate = new SandboxSettlementDispatchGate(true, "mock", mockProvider);

        assertThat(gate.isDispatchAllowed()).isTrue();
        // Does not throw
        gate.requireDispatchAllowed();
    }
}
