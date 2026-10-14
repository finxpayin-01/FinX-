package com.finx.release.finalizer.gate;

import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.model.ReleaseInstructionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxSettlementFinalizationGateTest {

    @Test
    @DisplayName("Gate blocks finalization when enabled is false")
    void testDisabledThrows() {
        SandboxSettlementFinalizationGate gate = new SandboxSettlementFinalizationGate(false, "mock");
        ReleaseInstruction instruction = createInstruction("MOCK");

        assertThat(gate.isFinalizationAllowed(instruction)).isFalse();
        assertThatThrownBy(() -> gate.assertFinalizationAllowed(instruction))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Sandbox settlement finalization is disabled");
    }

    @Test
    @DisplayName("Gate blocks finalization when banking provider is not mock")
    void testNonMockBankingProviderThrows() {
        SandboxSettlementFinalizationGate gate = new SandboxSettlementFinalizationGate(true, "axis");
        ReleaseInstruction instruction = createInstruction("MOCK");

        assertThat(gate.isFinalizationAllowed(instruction)).isFalse();
        assertThatThrownBy(() -> gate.assertFinalizationAllowed(instruction))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Provider 'axis' is not permitted for sandbox settlement finalization");
    }

    @Test
    @DisplayName("Gate blocks finalization when release instruction provider is not MOCK")
    void testNonMockReleaseProviderThrows() {
        SandboxSettlementFinalizationGate gate = new SandboxSettlementFinalizationGate(true, "mock");
        ReleaseInstruction instruction = createInstruction("AXIS");

        assertThat(gate.isFinalizationAllowed(instruction)).isFalse();
        assertThatThrownBy(() -> gate.assertFinalizationAllowed(instruction))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ReleaseInstruction provider 'AXIS' is not permitted for sandbox settlement finalization");
    }

    @Test
    @DisplayName("Gate blocks finalization when null release instruction or provider")
    void testNullInstructionThrows() {
        SandboxSettlementFinalizationGate gate = new SandboxSettlementFinalizationGate(true, "mock");

        assertThat(gate.isFinalizationAllowed((ReleaseInstruction) null)).isFalse();
        assertThatThrownBy(() -> gate.assertFinalizationAllowed((ReleaseInstruction) null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(gate.isFinalizationAllowed((String) null)).isFalse();
        assertThatThrownBy(() -> gate.assertFinalizationAllowed((String) null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Gate allows finalization when enabled, provider is mock, and release instruction is MOCK")
    void testEnabledAndMockAllowed() {
        SandboxSettlementFinalizationGate gate = new SandboxSettlementFinalizationGate(true, "mock");
        ReleaseInstruction instruction = createInstruction("MOCK");

        assertThat(gate.isFinalizationAllowed(instruction)).isTrue();
        gate.assertFinalizationAllowed(instruction); // should not throw
    }

    @Test
    @DisplayName("Gate handles null configured provider by blocking")
    void testNullConfiguredProviderThrows() {
        SandboxSettlementFinalizationGate gate = new SandboxSettlementFinalizationGate(true, null);
        ReleaseInstruction instruction = createInstruction("MOCK");

        assertThat(gate.isFinalizationAllowed(instruction)).isFalse();
        assertThatThrownBy(() -> gate.assertFinalizationAllowed(instruction))
                .isInstanceOf(IllegalStateException.class);
    }

    private ReleaseInstruction createInstruction(String provider) {
        return new ReleaseInstruction(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "a".repeat(64),
                UUID.randomUUID(),
                "b".repeat(64),
                UUID.randomUUID(),
                provider,
                "ESCROW-POOL-1",
                new BigDecimal("50000.00"),
                "INR",
                "idem-key-1",
                "c".repeat(64),
                UUID.randomUUID()
        );
    }
}
