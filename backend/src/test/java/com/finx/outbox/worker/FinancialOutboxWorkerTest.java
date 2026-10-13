package com.finx.outbox.worker;

import com.finx.common.time.FinxClock;
import com.finx.outbox.entity.FinancialOutboxMessage;
import com.finx.outbox.model.FinancialOutboxEventType;
import com.finx.outbox.model.FinancialOutboxStatus;
import com.finx.outbox.repository.FinancialOutboxRepository;
import com.finx.outbox.service.FinancialOutboxLeaseService;
import com.finx.release.entity.ReleaseInstruction;
import com.finx.release.entity.ReleaseReservation;
import com.finx.release.execution.entity.SettlementExecutionAttempt;
import com.finx.release.execution.model.PreparedSettlementWorkItem;
import com.finx.release.execution.model.ProviderSettlementOperationKey;
import com.finx.release.execution.model.SettlementExecutionAttemptStatus;
import com.finx.release.execution.repository.SettlementExecutionAttemptRepository;
import com.finx.release.execution.service.SettlementExecutionAttemptService;
import com.finx.release.model.ReleaseInstructionStatus;
import com.finx.release.model.ReleaseReservationStatus;
import com.finx.release.repository.ReleaseInstructionRepository;
import com.finx.release.repository.ReleaseReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FinancialOutboxWorkerTest {

    private FinancialOutboxRepository outboxRepository;
    private SettlementExecutionAttemptRepository attemptRepository;
    private ReleaseInstructionRepository instructionRepository;
    private ReleaseReservationRepository reservationRepository;
    private FinxClock clock;

    private FinancialOutboxLeaseService leaseService;
    private SettlementExecutionAttemptService attemptService;
    private FinancialOutboxWorker worker;

    private static final String WORKER_A = "worker-node-A";
    private static final String WORKER_B = "worker-node-B";
    private static final Instant T0 = Instant.parse("2026-10-13T12:00:00Z");

    @BeforeEach
    void setUp() {
        outboxRepository = mock(FinancialOutboxRepository.class);
        attemptRepository = mock(SettlementExecutionAttemptRepository.class);
        instructionRepository = mock(ReleaseInstructionRepository.class);
        reservationRepository = mock(ReleaseReservationRepository.class);
        clock = mock(FinxClock.class);
        when(clock.now()).thenReturn(T0);

        when(outboxRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        leaseService = new FinancialOutboxLeaseService(outboxRepository, attemptRepository, clock);
        attemptService = new SettlementExecutionAttemptService(
                outboxRepository, attemptRepository, instructionRepository, reservationRepository, clock
        );
        worker = new FinancialOutboxWorker(leaseService, attemptService);
    }

    @Test
    @DisplayName("prepareNextBatch leases rows, prepares attempts, and returns identifier-only work items")
    void prepareNextBatchReturnsSafeWorkItems() {
        UUID instructionId = UUID.randomUUID();
        UUID outboxId = UUID.randomUUID();

        FinancialOutboxMessage outbox = new FinancialOutboxMessage(
                outboxId, instructionId, FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED,
                instructionId.toString(), 1, FinancialOutboxStatus.PENDING, 0, T0, T0, T0
        );

        ReleaseInstruction instruction = new ReleaseInstruction(
                instructionId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "fp", UUID.randomUUID(), "hash", UUID.randomUUID(), "MOCK", "MOCK-ACC",
                new BigDecimal("5000.0000"), "INR", "idem", "a".repeat(64), UUID.randomUUID()
        );
        instruction.markAuthorized(UUID.randomUUID(), T0);
        instruction.markReserved();
        instruction.markQueued();

        ReleaseReservation reservation = new ReleaseReservation(
                UUID.randomUUID(), instructionId, UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("5000.0000"), "INR"
        );

        when(outboxRepository.claimEligibleMessages(T0, 5)).thenReturn(List.of(outbox));
        when(outboxRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(outboxRepository.findByIdForUpdate(outboxId)).thenReturn(Optional.of(outbox));
        when(instructionRepository.findById(instructionId)).thenReturn(Optional.of(instruction));
        when(reservationRepository.findByReleaseInstructionId(instructionId)).thenReturn(Optional.of(reservation));
        when(attemptRepository.maxAttemptNumberForOutbox(outboxId)).thenReturn(0);
        when(attemptRepository.save(any(SettlementExecutionAttempt.class))).thenAnswer(inv -> inv.getArgument(0));

        List<PreparedSettlementWorkItem> items = worker.prepareNextBatch(WORKER_A, 5);

        assertThat(items).hasSize(1);
        PreparedSettlementWorkItem item = items.get(0);
        assertThat(item.outboxMessageId()).isEqualTo(outboxId);
        assertThat(item.releaseInstructionId()).isEqualTo(instructionId);
        assertThat(item.operationKey()).isEqualTo(instructionId.toString());
        assertThat(item.providerOperationKey()).isEqualTo("FINX-REL-" + instructionId);
        assertThat(item.workerId()).isEqualTo(WORKER_A);

        // Invariant: ReleaseInstruction is still QUEUED, reservation is ACTIVE
        assertThat(instruction.getStatus()).isEqualTo(ReleaseInstructionStatus.QUEUED);
        assertThat(reservation.getStatus()).isEqualTo(ReleaseReservationStatus.ACTIVE);
        assertThat(outbox.getStatus()).isEqualTo(FinancialOutboxStatus.LEASED);
    }

    @Test
    @DisplayName("Part AU — Multi-node crash test: Worker A crashes after PREPARED, lease expires, Worker B reclaims and reuses existing attempt")
    void multiNodeCrashRecoveryTest() {
        UUID instructionId = UUID.randomUUID();
        UUID outboxId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();

        FinancialOutboxMessage outbox = new FinancialOutboxMessage(
                outboxId, instructionId, FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED,
                instructionId.toString(), 1, FinancialOutboxStatus.PENDING, 0, T0, T0, T0
        );

        ReleaseInstruction instruction = new ReleaseInstruction(
                instructionId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "fp", UUID.randomUUID(), "hash", UUID.randomUUID(), "MOCK", "MOCK-ACC",
                new BigDecimal("5000.0000"), "INR", "idem", "a".repeat(64), UUID.randomUUID()
        );
        instruction.markAuthorized(UUID.randomUUID(), T0);
        instruction.markReserved();
        instruction.markQueued();

        ReleaseReservation reservation = new ReleaseReservation(
                UUID.randomUUID(), instructionId, UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("5000.0000"), "INR"
        );

        // 1. Worker A leases and prepares attempt
        outbox.markLeased(WORKER_A, T0);

        SettlementExecutionAttempt preparedAttempt = new SettlementExecutionAttempt(
                attemptId, instructionId, outboxId, 1, instructionId.toString(),
                ProviderSettlementOperationKey.release(instructionId), "MOCK", "MOCK-ACC",
                instruction.getBeneficiaryBankAccountId(), new BigDecimal("5000.0000"), "INR",
                "a".repeat(64), WORKER_A, T0
        );

        // 2. Worker A crashes. Time advances beyond lease timeout (e.g. +10 mins)
        Instant T1 = T0.plus(Duration.ofMinutes(10));
        when(clock.now()).thenReturn(T1);

        when(outboxRepository.findExpiredLeasedMessages(eq(T1.minus(Duration.ofMinutes(5))), anyInt()))
                .thenReturn(List.of(outbox));
        when(attemptRepository.findFirstByOutboxMessageIdOrderByAttemptNumberDesc(outboxId))
                .thenReturn(Optional.of(preparedAttempt));
        when(outboxRepository.save(any(FinancialOutboxMessage.class))).thenAnswer(inv -> inv.getArgument(0));

        // 3. Expired lease is recovered to RETRY
        List<FinancialOutboxMessage> recovered = leaseService.recoverExpiredPreDispatchLeases(
                Duration.ofMinutes(5), Duration.ofSeconds(0), 10
        );
        assertThat(recovered).hasSize(1);
        assertThat(outbox.getStatus()).isEqualTo(FinancialOutboxStatus.RETRY);

        // 4. Worker B claims the row
        when(outboxRepository.claimEligibleMessages(T1, 5)).thenReturn(List.of(outbox));
        when(outboxRepository.findByIdForUpdate(outboxId)).thenReturn(Optional.of(outbox));
        when(instructionRepository.findById(instructionId)).thenReturn(Optional.of(instruction));
        when(reservationRepository.findByReleaseInstructionId(instructionId)).thenReturn(Optional.of(reservation));

        List<PreparedSettlementWorkItem> itemsB = worker.prepareNextBatch(WORKER_B, 5);

        assertThat(itemsB).hasSize(1);
        PreparedSettlementWorkItem itemB = itemsB.get(0);

        // Must reuse the exact same executionAttemptId and same providerOperationKey
        assertThat(itemB.executionAttemptId()).isEqualTo(attemptId);
        assertThat(itemB.providerOperationKey()).isEqualTo("FINX-REL-" + instructionId);
        assertThat(itemB.workerId()).isEqualTo(WORKER_B);

        // Zero duplicate attempt created
        verify(attemptRepository, never()).save(argThat(a -> !a.getId().equals(attemptId)));
    }
}
