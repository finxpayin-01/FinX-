package com.finx.outbox.service;

import com.finx.common.time.FinxClock;
import com.finx.outbox.entity.FinancialOutboxMessage;
import com.finx.outbox.model.FinancialOutboxEventType;
import com.finx.outbox.model.FinancialOutboxStatus;
import com.finx.outbox.repository.FinancialOutboxRepository;
import com.finx.release.execution.entity.SettlementExecutionAttempt;
import com.finx.release.execution.model.ProviderSettlementOperationKey;
import com.finx.release.execution.model.SettlementExecutionAttemptStatus;
import com.finx.release.execution.repository.SettlementExecutionAttemptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class FinancialOutboxLeaseServiceTest {

    private FinancialOutboxRepository outboxRepository;
    private SettlementExecutionAttemptRepository attemptRepository;
    private FinxClock clock;
    private FinancialOutboxLeaseService leaseService;

    private static final String WORKER_ID = "worker-node-1";
    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");

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
        clock = mock(FinxClock.class);
        when(clock.now()).thenReturn(NOW);

        leaseService = new FinancialOutboxLeaseService(outboxRepository, attemptRepository, clock);
    }

    @Test
    @DisplayName("claimBatch claims eligible messages, increments attemptCount, sets lockedBy and lockedAt")
    void claimBatchLeasesEligibleMessages() {
        UUID instructionId = UUID.randomUUID();
        FinancialOutboxMessage message = new FinancialOutboxMessage(
                UUID.randomUUID(),
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

        when(outboxRepository.claimEligibleMessages(NOW, 10)).thenReturn(List.of(message));
        when(outboxRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        List<FinancialOutboxMessage> claimed = leaseService.claimBatch(WORKER_ID, 10);

        assertThat(claimed).hasSize(1);
        FinancialOutboxMessage leased = claimed.get(0);
        assertThat(leased.getStatus()).isEqualTo(FinancialOutboxStatus.LEASED);
        assertThat(leased.getLockedBy()).isEqualTo(WORKER_ID);
        assertThat(leased.getLockedAt()).isEqualTo(NOW);
        assertThat(leased.getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("claimBatch with invalid parameters fails closed")
    void claimBatchValidatesParameters() {
        assertThatThrownBy(() -> leaseService.claimBatch(null, 10))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> leaseService.claimBatch("", 10))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> leaseService.claimBatch("a".repeat(129), 10))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> leaseService.claimBatch(WORKER_ID, 0))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> leaseService.claimBatch(WORKER_ID, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("releaseLeaseBeforeDispatchForRetry resets lock fields, sets RETRY, and records error code")
    void releaseLeaseBeforeDispatchForRetrySuccess() {
        UUID messageId = UUID.randomUUID();
        UUID instructionId = UUID.randomUUID();
        FinancialOutboxMessage message = new FinancialOutboxMessage(
                messageId,
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
        message.markLeased(WORKER_ID, NOW);

        when(outboxRepository.findByIdForUpdate(messageId)).thenReturn(Optional.of(message));
        when(attemptRepository.findFirstByOutboxMessageIdOrderByAttemptNumberDesc(messageId)).thenReturn(Optional.empty());
        when(outboxRepository.save(any(FinancialOutboxMessage.class))).thenAnswer(inv -> inv.getArgument(0));

        FinancialOutboxMessage result = leaseService.releaseLeaseBeforeDispatchForRetry(
                messageId,
                WORKER_ID,
                "PRE_DISPATCH_TIMEOUT",
                Duration.ofSeconds(30)
        );

        assertThat(result.getStatus()).isEqualTo(FinancialOutboxStatus.RETRY);
        assertThat(result.getLockedBy()).isNull();
        assertThat(result.getLockedAt()).isNull();
        assertThat(result.getLastErrorCode()).isEqualTo("PRE_DISPATCH_TIMEOUT");
        assertThat(result.getAvailableAt()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    @DisplayName("releaseLeaseBeforeDispatchForRetry succeeds when attempt is PREPARED with no network start")
    void releaseLeaseSucceedsWithPreparedAttempt() {
        UUID messageId = UUID.randomUUID();
        UUID instructionId = UUID.randomUUID();
        FinancialOutboxMessage message = new FinancialOutboxMessage(
                messageId,
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
        message.markLeased(WORKER_ID, NOW);

        SettlementExecutionAttempt preparedAttempt = new SettlementExecutionAttempt(
                UUID.randomUUID(),
                instructionId,
                messageId,
                1,
                instructionId.toString(),
                ProviderSettlementOperationKey.release(instructionId),
                "MOCK",
                "MOCK-ACC-1",
                UUID.randomUUID(),
                new BigDecimal("1000.0000"),
                "INR",
                "c".repeat(64),
                WORKER_ID,
                NOW
        );

        when(outboxRepository.findByIdForUpdate(messageId)).thenReturn(Optional.of(message));
        when(attemptRepository.findFirstByOutboxMessageIdOrderByAttemptNumberDesc(messageId))
                .thenReturn(Optional.of(preparedAttempt));
        when(outboxRepository.save(any(FinancialOutboxMessage.class))).thenAnswer(inv -> inv.getArgument(0));

        FinancialOutboxMessage result = leaseService.releaseLeaseBeforeDispatchForRetry(
                messageId,
                WORKER_ID,
                "WORKER_SHUTDOWN",
                Duration.ofSeconds(10)
        );

        assertThat(result.getStatus()).isEqualTo(FinancialOutboxStatus.RETRY);
    }

    @Test
    @DisplayName("releaseLeaseBeforeDispatchForRetry fails closed if attempt is network-started (SENDING, ACKNOWLEDGED, UNKNOWN)")
    void releaseLeaseFailsIfNetworkStarted() {
        UUID messageId = UUID.randomUUID();
        UUID instructionId = UUID.randomUUID();
        FinancialOutboxMessage message = new FinancialOutboxMessage(
                messageId,
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
        message.markLeased(WORKER_ID, NOW);

        SettlementExecutionAttempt sendingAttempt = new SettlementExecutionAttempt(
                UUID.randomUUID(),
                instructionId,
                messageId,
                1,
                instructionId.toString(),
                ProviderSettlementOperationKey.release(instructionId),
                "MOCK",
                "MOCK-ACC-1",
                UUID.randomUUID(),
                new BigDecimal("1000.0000"),
                "INR",
                "c".repeat(64),
                WORKER_ID,
                NOW
        );
        setField(sendingAttempt, "status", SettlementExecutionAttemptStatus.SENDING);

        when(outboxRepository.findByIdForUpdate(messageId)).thenReturn(Optional.of(message));
        when(attemptRepository.findFirstByOutboxMessageIdOrderByAttemptNumberDesc(messageId))
                .thenReturn(Optional.of(sendingAttempt));

        assertThatThrownBy(() -> leaseService.releaseLeaseBeforeDispatchForRetry(
                messageId,
                WORKER_ID,
                "TIMEOUT",
                Duration.ofSeconds(10)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("network execution");
    }

    @Test
    @DisplayName("recoverExpiredPreDispatchLeases recovers pre-dispatch rows and skips network-started rows")
    void recoverExpiredPreDispatchLeasesRecoversOnlySafeRows() {
        UUID msg1Id = UUID.randomUUID();
        UUID msg2Id = UUID.randomUUID();

        FinancialOutboxMessage msg1 = new FinancialOutboxMessage(
                msg1Id, UUID.randomUUID(), FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED,
                "op-1", 1, FinancialOutboxStatus.PENDING, 0, NOW.minusSeconds(600), NOW.minusSeconds(600), NOW.minusSeconds(600)
        );
        msg1.markLeased("crashed-worker-1", NOW.minusSeconds(600));

        FinancialOutboxMessage msg2 = new FinancialOutboxMessage(
                msg2Id, UUID.randomUUID(), FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED,
                "op-2", 1, FinancialOutboxStatus.PENDING, 0, NOW.minusSeconds(600), NOW.minusSeconds(600), NOW.minusSeconds(600)
        );
        msg2.markLeased("crashed-worker-2", NOW.minusSeconds(600));

        when(outboxRepository.findExpiredLeasedMessages(NOW.minus(Duration.ofMinutes(5)), 10))
                .thenReturn(List.of(msg1, msg2));

        // msg1 has no attempt -> safe to recover
        when(attemptRepository.findFirstByOutboxMessageIdOrderByAttemptNumberDesc(msg1Id))
                .thenReturn(Optional.empty());

        // msg2 has attempt in UNKNOWN -> NOT safe to recover
        SettlementExecutionAttempt unknownAttempt = new SettlementExecutionAttempt(
                UUID.randomUUID(), msg2.getReleaseInstructionId(), msg2Id, 1, "op-2", "FINX-REL-2",
                "MOCK", "ACC", UUID.randomUUID(), new BigDecimal("100.0000"), "INR", "d".repeat(64), "crashed-worker-2", NOW
        );
        setField(unknownAttempt, "status", SettlementExecutionAttemptStatus.UNKNOWN);
        when(attemptRepository.findFirstByOutboxMessageIdOrderByAttemptNumberDesc(msg2Id))
                .thenReturn(Optional.of(unknownAttempt));

        when(outboxRepository.save(any(FinancialOutboxMessage.class))).thenAnswer(inv -> inv.getArgument(0));

        List<FinancialOutboxMessage> recovered = leaseService.recoverExpiredPreDispatchLeases(
                Duration.ofMinutes(5), Duration.ofSeconds(30), 10
        );

        // Only msg1 was recovered
        assertThat(recovered).hasSize(1);
        assertThat(recovered.get(0).getId()).isEqualTo(msg1Id);
        assertThat(recovered.get(0).getStatus()).isEqualTo(FinancialOutboxStatus.RETRY);
        assertThat(recovered.get(0).getLastErrorCode()).isEqualTo("LEASE_EXPIRED_BEFORE_DISPATCH");

        // msg2 remains LEASED and non-redispatchable
        assertThat(msg2.getStatus()).isEqualTo(FinancialOutboxStatus.LEASED);
    }
}
