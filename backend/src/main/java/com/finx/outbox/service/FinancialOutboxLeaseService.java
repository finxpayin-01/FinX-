package com.finx.outbox.service;

import com.finx.common.time.FinxClock;
import com.finx.outbox.entity.FinancialOutboxMessage;
import com.finx.outbox.model.FinancialOutboxStatus;
import com.finx.outbox.repository.FinancialOutboxRepository;
import com.finx.release.execution.entity.SettlementExecutionAttempt;
import com.finx.release.execution.model.SettlementExecutionAttemptStatus;
import com.finx.release.execution.repository.SettlementExecutionAttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Service managing outbox worker leasing, pre-dispatch lease release, and pre-dispatch lease recovery.
 * Uses PostgreSQL SELECT ... FOR UPDATE SKIP LOCKED to ensure non-overlapping multi-node claims.
 * Zero banking provider dependency.
 */
@Service
public class FinancialOutboxLeaseService {

    private static final Logger log = LoggerFactory.getLogger(FinancialOutboxLeaseService.class);

    private final FinancialOutboxRepository outboxRepository;
    private final SettlementExecutionAttemptRepository attemptRepository;
    private final FinxClock clock;

    public FinancialOutboxLeaseService(
            FinancialOutboxRepository outboxRepository,
            SettlementExecutionAttemptRepository attemptRepository,
            FinxClock clock
    ) {
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository must not be null");
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Claims a batch of available outbox messages for a worker using SELECT FOR UPDATE SKIP LOCKED.
     * Transaction duration is kept strictly minimal.
     */
    @Transactional
    public List<FinancialOutboxMessage> claimBatch(String workerId, int limit) {
        validateWorkerId(workerId);
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100, received: " + limit);
        }

        Instant now = clock.now();
        List<FinancialOutboxMessage> eligible = outboxRepository.claimEligibleMessages(now, limit);
        if (eligible.isEmpty()) {
            return Collections.emptyList();
        }

        List<FinancialOutboxMessage> leased = new ArrayList<>(eligible.size());
        for (FinancialOutboxMessage msg : eligible) {
            msg.markLeased(workerId, now);
            leased.add(msg);
        }

        return outboxRepository.saveAll(leased);
    }

    /**
     * Safely releases a lease before network dispatch and returns the message to RETRY status.
     * Fails closed if a network request has already started.
     */
    @Transactional
    public FinancialOutboxMessage releaseLeaseBeforeDispatchForRetry(
            UUID outboxMessageId,
            String workerId,
            String safeErrorCode,
            Duration retryDelay
    ) {
        Objects.requireNonNull(outboxMessageId, "outboxMessageId must not be null");
        validateWorkerId(workerId);
        Objects.requireNonNull(safeErrorCode, "safeErrorCode must not be null");
        Objects.requireNonNull(retryDelay, "retryDelay must not be null");

        Instant now = clock.now();
        FinancialOutboxMessage message = outboxRepository.findByIdForUpdate(outboxMessageId)
                .orElseThrow(() -> new IllegalArgumentException("Outbox message not found: " + outboxMessageId));

        if (message.getStatus() != FinancialOutboxStatus.LEASED) {
            throw new IllegalStateException("Outbox message is not in LEASED status: " + message.getStatus());
        }

        if (!workerId.equals(message.getLockedBy())) {
            throw new IllegalStateException("Outbox message is leased by worker " + message.getLockedBy()
                    + ", but release requested by " + workerId);
        }

        // Verify that no network request has started for this outbox message
        Optional<SettlementExecutionAttempt> latestAttempt =
                attemptRepository.findFirstByOutboxMessageIdOrderByAttemptNumberDesc(outboxMessageId);

        if (latestAttempt.isPresent()) {
            SettlementExecutionAttempt attempt = latestAttempt.get();
            if (isNetworkStarted(attempt)) {
                throw new IllegalStateException("Cannot release lease for retry when attempt has started network execution: "
                        + attempt.getStatus());
            }
        }

        Instant nextAvailableAt = now.plus(retryDelay);
        message.releaseLeaseForRetry(nextAvailableAt, safeErrorCode, now);
        return outboxRepository.save(message);
    }

    /**
     * Recovers expired pre-dispatch leases where no network execution has begun.
     * Outbox rows whose attempts are network-started are left non-redispatchable.
     */
    @Transactional
    public List<FinancialOutboxMessage> recoverExpiredPreDispatchLeases(
            Duration leaseTimeout,
            Duration retryDelay,
            int limit
    ) {
        Objects.requireNonNull(leaseTimeout, "leaseTimeout must not be null");
        Objects.requireNonNull(retryDelay, "retryDelay must not be null");
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100, received: " + limit);
        }

        Instant now = clock.now();
        Instant expiredThreshold = now.minus(leaseTimeout);
        List<FinancialOutboxMessage> expired = outboxRepository.findExpiredLeasedMessages(expiredThreshold, limit);

        List<FinancialOutboxMessage> recovered = new ArrayList<>();
        for (FinancialOutboxMessage msg : expired) {
            Optional<SettlementExecutionAttempt> latestAttempt =
                    attemptRepository.findFirstByOutboxMessageIdOrderByAttemptNumberDesc(msg.getId());

            if (latestAttempt.isEmpty() || !isNetworkStarted(latestAttempt.get())) {
                msg.releaseLeaseForRetry(now.plus(retryDelay), "LEASE_EXPIRED_BEFORE_DISPATCH", now);
                recovered.add(outboxRepository.save(msg));
            } else {
                log.warn("RECOVERY_REQUIRED: Outbox message {} has expired lease but attempt {} is in network state {}",
                        msg.getId(), latestAttempt.get().getId(), latestAttempt.get().getStatus());
            }
        }

        return recovered;
    }

    private boolean isNetworkStarted(SettlementExecutionAttempt attempt) {
        SettlementExecutionAttemptStatus st = attempt.getStatus();
        return st == SettlementExecutionAttemptStatus.SENDING
                || st == SettlementExecutionAttemptStatus.ACKNOWLEDGED
                || st == SettlementExecutionAttemptStatus.UNKNOWN
                || attempt.getRequestStartedAt() != null;
    }

    private void validateWorkerId(String workerId) {
        Objects.requireNonNull(workerId, "workerId must not be null");
        if (workerId.trim().isEmpty() || workerId.length() > 128) {
            throw new IllegalArgumentException("workerId must be non-blank and <= 128 characters: " + workerId);
        }
    }
}
