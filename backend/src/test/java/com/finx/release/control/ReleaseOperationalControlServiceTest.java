package com.finx.release.control;

import com.finx.audit.service.AuditService;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.outbox.entity.FinancialOutboxMessage;
import com.finx.outbox.model.FinancialOutboxEventType;
import com.finx.outbox.model.FinancialOutboxStatus;
import com.finx.outbox.repository.FinancialOutboxRepository;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.entity.ReleaseReservation;
import com.finx.release.execution.entity.SettlementExecutionAttempt;
import com.finx.release.execution.model.SettlementExecutionAttemptStatus;
import com.finx.release.execution.repository.SettlementExecutionAttemptRepository;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.model.ReleaseReservationStatus;
import com.finx.release.repository.ReleaseInstructionRepository;
import com.finx.release.repository.ReleaseReservationRepository;
import com.finx.security.service.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReleaseOperationalControlServiceTest {

    @Mock
    private FinancialOutboxRepository outboxRepository;
    @Mock
    private SettlementExecutionAttemptRepository attemptRepository;
    @Mock
    private ReleaseInstructionRepository instructionRepository;
    @Mock
    private ReleaseReservationRepository reservationRepository;
    @Mock
    private AuditService auditService;

    @InjectMocks
    private ReleaseOperationalControlService controlService;

    private UserPrincipal adminPrincipal;
    private UserPrincipal buyerPrincipal;
    private UUID releaseId;
    private ReleaseInstruction instruction;
    private FinancialOutboxMessage outbox;
    private ReleaseReservation reservation;

    @BeforeEach
    void setUp() {
        UUID adminId = UUID.randomUUID();
        adminPrincipal = new UserPrincipal(adminId, "Admin", "admin@finx.com", "pass", Role.ADMIN, UserStatus.ACTIVE, Collections.emptyList());

        UUID buyerId = UUID.randomUUID();
        buyerPrincipal = new UserPrincipal(buyerId, "Buyer", "buyer@finx.com", "pass", Role.BUYER, UserStatus.ACTIVE, Collections.emptyList());

        releaseId = UUID.randomUUID();

        instruction = new ReleaseInstruction(
                releaseId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "fprint",
                UUID.randomUUID(),
                "chash",
                UUID.randomUUID(),
                "MOCK",
                "acc-123",
                BigDecimal.valueOf(1000),
                "INR",
                "idemp-1",
                "req-hash-1",
                adminId
        );
        instruction.markAuthorized(adminId, Instant.now());
        instruction.markReserved();
        instruction.markQueued();

        outbox = FinancialOutboxMessage.createReleaseDispatch(instruction, Instant.now());

        reservation = new ReleaseReservation(
                UUID.randomUUID(),
                releaseId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigDecimal.valueOf(1000),
                "INR"
        );
    }

    @Test
    @DisplayName("Non-admin/finance role is rejected with AccessDeniedException")
    void nonAdminRoleRejected() {
        assertThatThrownBy(() -> controlService.holdPreDispatchRelease(releaseId, buyerPrincipal, "HOLD"))
                .isInstanceOf(AccessDeniedException.class);

        assertThatThrownBy(() -> controlService.resumeHeldRelease(releaseId, buyerPrincipal, "RESUME"))
                .isInstanceOf(AccessDeniedException.class);

        assertThatThrownBy(() -> controlService.cancelPreDispatchRelease(releaseId, buyerPrincipal, "CANCEL"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("Pre-dispatch hold successfully moves QUEUED to MANUAL_REVIEW and outbox to HELD")
    void holdPreDispatchReleaseSuccess() {
        when(outboxRepository.findByReleaseInstructionIdForUpdate(releaseId)).thenReturn(Optional.of(outbox));
        when(attemptRepository.findFirstByReleaseInstructionIdOrderByAttemptNumberDesc(releaseId)).thenReturn(Optional.empty());
        when(instructionRepository.findByIdForUpdate(releaseId)).thenReturn(Optional.of(instruction));
        when(reservationRepository.findByReleaseInstructionIdForUpdate(releaseId)).thenReturn(Optional.of(reservation));

        controlService.holdPreDispatchRelease(releaseId, adminPrincipal, "INVESTIGATION");

        assertThat(instruction.getStatus()).isEqualTo(ReleaseInstructionStatus.MANUAL_REVIEW);
        assertThat(outbox.getStatus()).isEqualTo(FinancialOutboxStatus.HELD);
        assertThat(reservation.getStatus()).isEqualTo(ReleaseReservationStatus.ACTIVE);

        verify(auditService).logEvent(eq(adminPrincipal.getId()), eq("RELEASE_ADMIN_HOLD"), eq("RELEASE_INSTRUCTION"), eq(releaseId.toString()), any());
    }

    @Test
    @DisplayName("Resume successfully moves MANUAL_REVIEW to QUEUED and outbox to PENDING")
    void resumeHeldReleaseSuccess() {
        // Prepare in held state
        instruction.markPreDispatchManualReview();
        outbox.markAdministrativelyHeld(Instant.now(), "HOLD");

        when(outboxRepository.findByReleaseInstructionIdForUpdate(releaseId)).thenReturn(Optional.of(outbox));
        when(attemptRepository.findFirstByReleaseInstructionIdOrderByAttemptNumberDesc(releaseId)).thenReturn(Optional.empty());
        when(instructionRepository.findByIdForUpdate(releaseId)).thenReturn(Optional.of(instruction));
        when(reservationRepository.findByReleaseInstructionIdForUpdate(releaseId)).thenReturn(Optional.of(reservation));

        controlService.resumeHeldRelease(releaseId, adminPrincipal, "RESOLVED");

        assertThat(instruction.getStatus()).isEqualTo(ReleaseInstructionStatus.QUEUED);
        assertThat(outbox.getStatus()).isEqualTo(FinancialOutboxStatus.PENDING);
        assertThat(reservation.getStatus()).isEqualTo(ReleaseReservationStatus.ACTIVE);

        verify(auditService).logEvent(eq(adminPrincipal.getId()), eq("RELEASE_ADMIN_RESUME"), eq("RELEASE_INSTRUCTION"), eq(releaseId.toString()), any());
    }

    @Test
    @DisplayName("Cancel successfully marks outbox DEAD_LETTER, reservation RELEASED, instruction CANCELLED")
    void cancelPreDispatchReleaseSuccess() {
        when(outboxRepository.findByReleaseInstructionId(releaseId)).thenReturn(Optional.of(outbox));
        when(outboxRepository.findByReleaseInstructionIdForUpdate(releaseId)).thenReturn(Optional.of(outbox));
        when(attemptRepository.findFirstByReleaseInstructionIdOrderByAttemptNumberDesc(releaseId)).thenReturn(Optional.empty());
        when(instructionRepository.findByIdForUpdate(releaseId)).thenReturn(Optional.of(instruction));
        when(reservationRepository.findByReleaseInstructionIdForUpdate(releaseId)).thenReturn(Optional.of(reservation));

        controlService.cancelPreDispatchRelease(releaseId, adminPrincipal, "DUPLICATE");

        assertThat(instruction.getStatus()).isEqualTo(ReleaseInstructionStatus.CANCELLED);
        assertThat(outbox.getStatus()).isEqualTo(FinancialOutboxStatus.DEAD_LETTER);
        assertThat(reservation.getStatus()).isEqualTo(ReleaseReservationStatus.RELEASED);

        verify(auditService).logEvent(eq(adminPrincipal.getId()), eq("RELEASE_ADMIN_CANCELLED"), eq("RELEASE_INSTRUCTION"), eq(releaseId.toString()), any());
    }

    @Test
    @DisplayName("HOLD is BLOCKED when network request has already started")
    void holdBlockedWhenNetworkStarted() {
        SettlementExecutionAttempt attempt = new SettlementExecutionAttempt(
                UUID.randomUUID(), releaseId, outbox.getId(), 1, "op-key-1", "prov-op-1", "MOCK", "acc-1",
                UUID.randomUUID(), BigDecimal.valueOf(1000), "INR", "req-hash", "worker-1", Instant.now()
        );
        attempt.markSending(Instant.now());

        when(outboxRepository.findByReleaseInstructionIdForUpdate(releaseId)).thenReturn(Optional.of(outbox));
        when(attemptRepository.findFirstByReleaseInstructionIdOrderByAttemptNumberDesc(releaseId)).thenReturn(Optional.of(attempt));
        when(attemptRepository.findByIdForUpdate(attempt.getId())).thenReturn(Optional.of(attempt));

        assertThatThrownBy(() -> controlService.holdPreDispatchRelease(releaseId, adminPrincipal, "HOLD"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CRITICAL_SAFETY_VIOLATION");
    }

    @Test
    @DisplayName("CANCEL is BLOCKED when network request has entered SENDING or UNKNOWN status")
    void cancelBlockedWhenNetworkStarted() {
        SettlementExecutionAttempt attempt = new SettlementExecutionAttempt(
                UUID.randomUUID(), releaseId, outbox.getId(), 1, "op-key-1", "prov-op-1", "MOCK", "acc-1",
                UUID.randomUUID(), BigDecimal.valueOf(1000), "INR", "req-hash", "worker-1", Instant.now()
        );
        attempt.markSending(Instant.now());
        attempt.markUnknown("TIMEOUT", Instant.now());

        when(outboxRepository.findByReleaseInstructionId(releaseId)).thenReturn(Optional.of(outbox));
        when(outboxRepository.findByReleaseInstructionIdForUpdate(releaseId)).thenReturn(Optional.of(outbox));
        when(attemptRepository.findFirstByReleaseInstructionIdOrderByAttemptNumberDesc(releaseId)).thenReturn(Optional.of(attempt));
        when(attemptRepository.findByIdForUpdate(attempt.getId())).thenReturn(Optional.of(attempt));

        assertThatThrownBy(() -> controlService.cancelPreDispatchRelease(releaseId, adminPrincipal, "CANCEL"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CRITICAL_SAFETY_VIOLATION");
    }
}
