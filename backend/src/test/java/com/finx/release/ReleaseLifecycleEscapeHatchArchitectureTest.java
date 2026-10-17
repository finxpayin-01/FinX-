package com.finx.release;

import com.finx.outbox.entity.FinancialOutboxMessage;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.entity.ReleaseReservation;
import com.finx.release.execution.entity.SettlementExecutionAttempt;
import com.finx.release.model.ReleaseInstructionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Architectural tests verifying that no lifecycle escape hatches or arbitrary status
 * mutation methods exist on release, outbox, and attempt entities.
 */
class ReleaseLifecycleEscapeHatchArchitectureTest {

    @Test
    @DisplayName("ReleaseInstruction has no public or protected arbitrary status mutation methods")
    void testReleaseInstruction_noArbitraryStatusMutation() {
        List<Method> methods = Arrays.stream(ReleaseInstruction.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()) || Modifier.isProtected(m.getModifiers()))
                .toList();

        for (Method m : methods) {
            String name = m.getName();
            assertThat(name)
                    .as("ReleaseInstruction must not expose updateStatus or setStatus")
                    .isNotEqualTo("updateStatus")
                    .isNotEqualTo("setStatus");

            if (name.equals("markSettled")) {
                // Legacy markSettled(Instant) must be absent
                assertThat(m.getParameterCount())
                        .as("Legacy markSettled(Instant) must not exist on ReleaseInstruction")
                        .isNotEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("ReleaseInstruction markSettledFromObservation strictly requires observation, finalizedBy, and settledAt")
    void testReleaseInstruction_markSettledRequiresObservationIdentity() {
        ReleaseInstruction instruction = createQueuedInstruction();
        instruction.markDispatching();
        instruction.markPendingSettlement();

        UUID obsId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Instant now = Instant.now();

        assertThatThrownBy(() -> instruction.markSettledFromObservation(null, actorId, now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("settlementObservationId must not be null");

        assertThatThrownBy(() -> instruction.markSettledFromObservation(obsId, null, now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finalizedBy must not be null");

        assertThatThrownBy(() -> instruction.markSettledFromObservation(obsId, actorId, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("settledAt must not be null");
    }

    @Test
    @DisplayName("ReleaseInstruction rejects direct CREATED -> SETTLED transition")
    void testReleaseInstruction_rejectsDirectCreatedToSettled() {
        ReleaseInstruction instruction = createInstruction();
        assertThat(instruction.getStatus()).isEqualTo(ReleaseInstructionStatus.CREATED);

        assertThatThrownBy(() -> instruction.markSettledFromObservation(UUID.randomUUID(), UUID.randomUUID(), Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot mark settled from status CREATED");
    }

    @Test
    @DisplayName("ReleaseInstruction rejects direct QUEUED -> SETTLED transition")
    void testReleaseInstruction_rejectsDirectQueuedToSettled() {
        ReleaseInstruction instruction = createQueuedInstruction();
        assertThat(instruction.getStatus()).isEqualTo(ReleaseInstructionStatus.QUEUED);

        assertThatThrownBy(() -> instruction.markSettledFromObservation(UUID.randomUUID(), UUID.randomUUID(), Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot mark settled from status QUEUED");
    }

    @Test
    @DisplayName("ReleaseReservation has no public or protected arbitrary status mutation methods")
    void testReleaseReservation_noArbitraryStatusMutation() {
        List<Method> methods = Arrays.stream(ReleaseReservation.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()) || Modifier.isProtected(m.getModifiers()))
                .toList();

        for (Method m : methods) {
            String name = m.getName();
            assertThat(name)
                    .as("ReleaseReservation must not expose updateStatus or setStatus")
                    .isNotEqualTo("updateStatus")
                    .isNotEqualTo("setStatus");
        }
    }

    @Test
    @DisplayName("FinancialOutboxMessage has no public or protected arbitrary status mutation methods")
    void testFinancialOutboxMessage_noArbitraryStatusMutation() {
        List<Method> methods = Arrays.stream(FinancialOutboxMessage.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()) || Modifier.isProtected(m.getModifiers()))
                .toList();

        for (Method m : methods) {
            String name = m.getName();
            assertThat(name)
                    .as("FinancialOutboxMessage must not expose updateStatus or setStatus")
                    .isNotEqualTo("updateStatus")
                    .isNotEqualTo("setStatus");
        }
    }

    @Test
    @DisplayName("SettlementExecutionAttempt has no public or protected arbitrary status mutation methods")
    void testSettlementExecutionAttempt_noArbitraryStatusMutation() {
        List<Method> methods = Arrays.stream(SettlementExecutionAttempt.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()) || Modifier.isProtected(m.getModifiers()))
                .toList();

        for (Method m : methods) {
            String name = m.getName();
            assertThat(name)
                    .as("SettlementExecutionAttempt must not expose updateStatus or setStatus")
                    .isNotEqualTo("updateStatus")
                    .isNotEqualTo("setStatus");
        }
    }

    private ReleaseInstruction createInstruction() {
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
                "AXIS",
                "ESCROW-POOL-1",
                new BigDecimal("50000.00"),
                "INR",
                "idem-key-arch",
                "c".repeat(64),
                UUID.randomUUID()
        );
    }

    private ReleaseInstruction createQueuedInstruction() {
        ReleaseInstruction instruction = createInstruction();
        instruction.markAuthorized(UUID.randomUUID(), Instant.now());
        instruction.markReserved();
        instruction.markQueued();
        return instruction;
    }
}
