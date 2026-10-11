package com.finx.release;

import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.entity.ReleaseReservation;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.model.ReleaseReservationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReleaseInstructionLifecycleTest {

    @Test
    @DisplayName("markAuthorized transitions CREATED -> AUTHORIZED")
    void testMarkAuthorizedFromCreated() {
        UUID actorId = UUID.randomUUID();
        Instant now = Instant.now();
        ReleaseInstruction instruction = createInstruction(ReleaseInstructionStatus.CREATED);

        instruction.markAuthorized(actorId, now);

        assertThat(instruction.getStatus()).isEqualTo(ReleaseInstructionStatus.AUTHORIZED);
        assertThat(instruction.getAuthorizedBy()).isEqualTo(actorId);
        assertThat(instruction.getAuthorizedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("markAuthorized rejects non-CREATED status")
    void testMarkAuthorizedFromNonCreatedThrows() {
        UUID actorId = UUID.randomUUID();
        Instant now = Instant.now();
        ReleaseInstruction instruction = createInstruction(ReleaseInstructionStatus.CREATED);
        instruction.markAuthorized(actorId, now);

        // Attempt second markAuthorized from AUTHORIZED
        assertThatThrownBy(() -> instruction.markAuthorized(actorId, now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot authorize ReleaseInstruction in status AUTHORIZED");
    }

    @Test
    @DisplayName("markReserved transitions AUTHORIZED -> RESERVED")
    void testMarkReservedFromAuthorized() {
        UUID actorId = UUID.randomUUID();
        Instant now = Instant.now();
        ReleaseInstruction instruction = createInstruction(ReleaseInstructionStatus.CREATED);
        instruction.markAuthorized(actorId, now);

        instruction.markReserved();

        assertThat(instruction.getStatus()).isEqualTo(ReleaseInstructionStatus.RESERVED);
    }

    @Test
    @DisplayName("markReserved rejects non-AUTHORIZED status (e.g., CREATED)")
    void testMarkReservedFromCreatedThrows() {
        ReleaseInstruction instruction = createInstruction(ReleaseInstructionStatus.CREATED);

        assertThatThrownBy(instruction::markReserved)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot mark reserved on ReleaseInstruction in status CREATED");
    }

    @Test
    @DisplayName("markCancelled transitions CREATED -> CANCELLED")
    void testMarkCancelledFromCreated() {
        ReleaseInstruction instruction = createInstruction(ReleaseInstructionStatus.CREATED);

        instruction.markCancelled();

        assertThat(instruction.getStatus()).isEqualTo(ReleaseInstructionStatus.CANCELLED);
    }

    @Test
    @DisplayName("markCancelled rejects terminal/settled status")
    void testMarkCancelledFromSettledThrows() throws Exception {
        ReleaseInstruction instruction = createInstruction(ReleaseInstructionStatus.CREATED);
        java.lang.reflect.Field statusField = ReleaseInstruction.class.getDeclaredField("status");
        statusField.setAccessible(true);
        statusField.set(instruction, ReleaseInstructionStatus.SETTLED);

        assertThatThrownBy(instruction::markCancelled)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot cancel instruction in SETTLED status");
    }

    @Test
    @DisplayName("Reservation ACTIVE remains ACTIVE in E2 (lifecycle bounds)")
    void testReservationRemainsActive() {
        ReleaseReservation reservation = new ReleaseReservation(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("50000.00"),
                "INR"
        );

        assertThat(reservation.getStatus()).isEqualTo(ReleaseReservationStatus.ACTIVE);
        assertThat(reservation.getConsumedAt()).isNull();
        assertThat(reservation.getReleasedAt()).isNull();
    }

    @Test
    @DisplayName("Reservation markConsumed transitions ACTIVE -> CONSUMED")
    void testReservationMarkConsumed() {
        ReleaseReservation reservation = new ReleaseReservation(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("50000.00"),
                "INR"
        );
        Instant now = Instant.now();

        reservation.markConsumed(now);

        assertThat(reservation.getStatus()).isEqualTo(ReleaseReservationStatus.CONSUMED);
        assertThat(reservation.getConsumedAt()).isEqualTo(now);

        // Cannot consume again
        assertThatThrownBy(() -> reservation.markConsumed(now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only ACTIVE reservations can be consumed");
    }

    @Test
    @DisplayName("Reservation markReleased transitions ACTIVE -> RELEASED")
    void testReservationMarkReleased() {
        ReleaseReservation reservation = new ReleaseReservation(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("50000.00"),
                "INR"
        );
        Instant now = Instant.now();

        reservation.markReleased(now);

        assertThat(reservation.getStatus()).isEqualTo(ReleaseReservationStatus.RELEASED);
        assertThat(reservation.getReleasedAt()).isEqualTo(now);

        // Cannot release again
        assertThatThrownBy(() -> reservation.markReleased(now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only ACTIVE reservations can be released");
    }

    private ReleaseInstruction createInstruction(ReleaseInstructionStatus initialStatus) {
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
                "idem-key-1",
                "c".repeat(64),
                UUID.randomUUID()
        );
    }
}
