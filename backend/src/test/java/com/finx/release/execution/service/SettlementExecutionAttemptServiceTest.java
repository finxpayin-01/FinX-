package com.finx.release.execution.service;

import com.finx.common.time.FinxClock;
import com.finx.outbox.entity.FinancialOutboxMessage;
import com.finx.outbox.model.FinancialOperationKey;
import com.finx.outbox.model.FinancialOutboxEventType;
import com.finx.outbox.model.FinancialOutboxStatus;
import com.finx.outbox.repository.FinancialOutboxRepository;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.entity.ReleaseReservation;
import com.finx.release.execution.entity.SettlementExecutionAttempt;
import com.finx.release.execution.model.ProviderSettlementOperationKey;
import com.finx.release.execution.model.SettlementExecutionAttemptStatus;
import com.finx.release.execution.repository.SettlementExecutionAttemptRepository;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.model.ReleaseReservationStatus;
import com.finx.release.repository.ReleaseInstructionRepository;
import com.finx.release.repository.ReleaseReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SettlementExecutionAttemptServiceTest {

    private FinancialOutboxRepository outboxRepository;
    private SettlementExecutionAttemptRepository attemptRepository;
    private ReleaseInstructionRepository instructionRepository;
    private ReleaseReservationRepository reservationRepository;
    private FinxClock clock;
    private SettlementExecutionAttemptService service;

    private static final String WORKER_ID = "worker-node-1";
    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");

    private UUID instructionId;
    private UUID outboxId;
    private ReleaseInstruction instruction;
    private ReleaseReservation reservation;
    private FinancialOutboxMessage leasedOutbox;

    private void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void setUp() {
        outboxRepository = mock(FinancialOutboxRepository.class);
        attemptRepository = mock(SettlementExecutionAttemptRepository.class);
        instructionRepository = mock(ReleaseInstructionRepository.class);
        reservationRepository = mock(ReleaseReservationRepository.class);
        clock = mock(FinxClock.class);
        when(clock.now()).thenReturn(NOW);

        service = new SettlementExecutionAttemptService(
                outboxRepository,
                attemptRepository,
                instructionRepository,
                reservationRepository,
                clock
        );

        instructionId = UUID.randomUUID();
        outboxId = UUID.randomUUID();

        instruction = new ReleaseInstruction(
                instructionId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "dummy-fp",
                UUID.randomUUID(),
                "dummy-hash",
                UUID.randomUUID(),
                "MOCK",
                "MOCK-ACC-1",
                new BigDecimal("50000.0000"),
                "INR",
                "idem-" + UUID.randomUUID(),
                "a".repeat(64),
                UUID.randomUUID()
        );
        instruction.markAuthorized(UUID.randomUUID(), NOW);
        instruction.markReserved();
        instruction.markQueued();

        reservation = new ReleaseReservation(
                UUID.randomUUID(),
                instructionId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("50000.0000"),
                "INR"
        );

        leasedOutbox = new FinancialOutboxMessage(
                outboxId,
                instructionId,
                FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED,
                instructionId.toString(),
                1,
                FinancialOutboxStatus.PENDING,
                0,
                NOW,
                NOW,
                NOW
        );
        leasedOutbox.markLeased(WORKER_ID, NOW);

        when(outboxRepository.findByIdForUpdate(outboxId)).thenReturn(Optional.of(leasedOutbox));
        when(instructionRepository.findById(instructionId)).thenReturn(Optional.of(instruction));
        when(reservationRepository.findByReleaseInstructionId(instructionId)).thenReturn(Optional.of(reservation));
        when(attemptRepository.maxAttemptNumberForOutbox(outboxId)).thenReturn(0);
        when(attemptRepository.save(any(SettlementExecutionAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("1 & 12. LEASED matching outbox creates PREPARED attempt with attemptNumber=1")
    void leasedMatchingOutboxCreatesPreparedAttempt() {
        SettlementExecutionAttempt attempt = service.prepareAttempt(outboxId, WORKER_ID);

        assertThat(attempt).isNotNull();
        assertThat(attempt.getStatus()).isEqualTo(SettlementExecutionAttemptStatus.PREPARED);
        assertThat(attempt.getAttemptNumber()).isEqualTo(1);
        assertThat(attempt.getReleaseInstructionId()).isEqualTo(instructionId);
        assertThat(attempt.getOutboxMessageId()).isEqualTo(outboxId);
        assertThat(attempt.getWorkerId()).isEqualTo(WORKER_ID);
        assertThat(attempt.getProviderOperationKey()).isEqualTo("FINX-REL-" + instructionId);
        assertThat(attempt.getAmount()).isEqualByComparingTo(new BigDecimal("50000.0000"));
        assertThat(attempt.getCurrency()).isEqualTo("INR");

        // Verify save was called
        verify(attemptRepository).save(any(SettlementExecutionAttempt.class));
    }

    @Test
    @DisplayName("2. Instruction must be QUEUED; CREATED or other status fails closed")
    void instructionMustBeQueued() {
        setField(instruction, "status", ReleaseInstructionStatus.CREATED);

        assertThatThrownBy(() -> service.prepareAttempt(outboxId, WORKER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QUEUED");
    }

    @Test
    @DisplayName("3. ACTIVE reservation required; missing reservation fails closed")
    void activeReservationRequired() {
        when(reservationRepository.findByReleaseInstructionId(instructionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.prepareAttempt(outboxId, WORKER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACTIVE reservation required");
    }

    @Test
    @DisplayName("4. Reservation amount must match instruction amount")
    void reservationAmountMustMatch() {
        setField(reservation, "amount", new BigDecimal("49999.0000"));

        assertThatThrownBy(() -> service.prepareAttempt(outboxId, WORKER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Reservation amount mismatch");
    }

    @Test
    @DisplayName("5. Reservation currency must match instruction currency")
    void reservationCurrencyMustMatch() {
        setField(reservation, "currency", "USD");

        assertThatThrownBy(() -> service.prepareAttempt(outboxId, WORKER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Reservation currency mismatch");
    }

    @Test
    @DisplayName("6. Outbox operation key must match release instruction ID")
    void outboxOperationKeyMustMatchReleaseId() {
        setField(leasedOutbox, "operationKey", UUID.randomUUID().toString());

        assertThatThrownBy(() -> service.prepareAttempt(outboxId, WORKER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Outbox operation key mismatch");
    }

    @Test
    @DisplayName("7. Wrong worker is rejected")
    void wrongWorkerRejected() {
        assertThatThrownBy(() -> service.prepareAttempt(outboxId, "other-worker-node"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("leased by worker-node-1");
    }

    @Test
    @DisplayName("8, 9, 10, 11. Non-LEASED outbox status (PENDING, RETRY, PROCESSED, DEAD_LETTER) rejected")
    void nonLeasedStatusRejected() {
        setField(leasedOutbox, "status", FinancialOutboxStatus.PENDING);
        assertThatThrownBy(() -> service.prepareAttempt(outboxId, WORKER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LEASED");

        setField(leasedOutbox, "status", FinancialOutboxStatus.RETRY);
        assertThatThrownBy(() -> service.prepareAttempt(outboxId, WORKER_ID))
                .isInstanceOf(IllegalStateException.class);

        setField(leasedOutbox, "status", FinancialOutboxStatus.PROCESSED);
        assertThatThrownBy(() -> service.prepareAttempt(outboxId, WORKER_ID))
                .isInstanceOf(IllegalStateException.class);

        setField(leasedOutbox, "status", FinancialOutboxStatus.DEAD_LETTER);
        assertThatThrownBy(() -> service.prepareAttempt(outboxId, WORKER_ID))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("13. Same PREPARED replay returns existing attempt idempotently without creating attempt 2")
    void preparedReplayReturnsExistingAttempt() {
        SettlementExecutionAttempt existingAttempt = new SettlementExecutionAttempt(
                UUID.randomUUID(),
                instructionId,
                outboxId,
                1,
                instructionId.toString(),
                ProviderSettlementOperationKey.release(instructionId),
                instruction.getProvider(),
                instruction.getProviderAccountId(),
                instruction.getBeneficiaryBankAccountId(),
                instruction.getAmount(),
                instruction.getCurrency(),
                instruction.getRequestHash(),
                WORKER_ID,
                NOW
        );

        when(attemptRepository.findFirstByOutboxMessageIdOrderByAttemptNumberDesc(outboxId))
                .thenReturn(Optional.of(existingAttempt));

        SettlementExecutionAttempt result = service.prepareAttempt(outboxId, WORKER_ID);

        assertThat(result.getId()).isEqualTo(existingAttempt.getId());
        verify(attemptRepository, never()).save(any(SettlementExecutionAttempt.class));
    }

    @Test
    @DisplayName("14. Snapshot conflict fails closed")
    void snapshotConflictFailsClosed() {
        SettlementExecutionAttempt conflictingAttempt = new SettlementExecutionAttempt(
                UUID.randomUUID(),
                instructionId,
                outboxId,
                1,
                instructionId.toString(),
                ProviderSettlementOperationKey.release(instructionId),
                instruction.getProvider(),
                instruction.getProviderAccountId(),
                instruction.getBeneficiaryBankAccountId(),
                new BigDecimal("99999.0000"), // Mismatched amount
                instruction.getCurrency(),
                instruction.getRequestHash(),
                WORKER_ID,
                NOW
        );

        when(attemptRepository.findFirstByOutboxMessageIdOrderByAttemptNumberDesc(outboxId))
                .thenReturn(Optional.of(conflictingAttempt));

        assertThatThrownBy(() -> service.prepareAttempt(outboxId, WORKER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EXECUTION_ATTEMPT_SNAPSHOT_CONFLICT");
    }

    @Test
    @DisplayName("15. Provider operation key is stable: FINX-REL-{id}")
    void providerOperationKeyIsStable() {
        SettlementExecutionAttempt attempt = service.prepareAttempt(outboxId, WORKER_ID);
        assertThat(attempt.getProviderOperationKey()).isEqualTo("FINX-REL-" + instructionId);
    }

    @Test
    @DisplayName("16, 17, 18. Latest attempt in SENDING, ACKNOWLEDGED, or UNKNOWN requires outcome recovery")
    void nonPreparedAttemptRequiresRecovery() {
        SettlementExecutionAttempt sendingAttempt = new SettlementExecutionAttempt(
                UUID.randomUUID(),
                instructionId,
                outboxId,
                1,
                instructionId.toString(),
                ProviderSettlementOperationKey.release(instructionId),
                instruction.getProvider(),
                instruction.getProviderAccountId(),
                instruction.getBeneficiaryBankAccountId(),
                instruction.getAmount(),
                instruction.getCurrency(),
                instruction.getRequestHash(),
                WORKER_ID,
                NOW
        );
        setField(sendingAttempt, "status", SettlementExecutionAttemptStatus.SENDING);

        when(attemptRepository.findFirstByOutboxMessageIdOrderByAttemptNumberDesc(outboxId))
                .thenReturn(Optional.of(sendingAttempt));

        assertThatThrownBy(() -> service.prepareAttempt(outboxId, WORKER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EXECUTION_OUTCOME_RECOVERY_REQUIRED");

        setField(sendingAttempt, "status", SettlementExecutionAttemptStatus.ACKNOWLEDGED);
        assertThatThrownBy(() -> service.prepareAttempt(outboxId, WORKER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EXECUTION_OUTCOME_RECOVERY_REQUIRED");

        setField(sendingAttempt, "status", SettlementExecutionAttemptStatus.UNKNOWN);
        assertThatThrownBy(() -> service.prepareAttempt(outboxId, WORKER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EXECUTION_OUTCOME_RECOVERY_REQUIRED");
    }

    @Test
    @DisplayName("19, 20, 21. No bank call, instruction remains QUEUED, reservation remains ACTIVE")
    void noBankCallAndNoStatusMutation() {
        service.prepareAttempt(outboxId, WORKER_ID);

        assertThat(instruction.getStatus()).isEqualTo(ReleaseInstructionStatus.QUEUED);
        assertThat(reservation.getStatus()).isEqualTo(ReleaseReservationStatus.ACTIVE);
    }
}
