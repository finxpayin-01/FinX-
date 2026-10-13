package com.finx.outbox.worker;

import com.finx.outbox.entity.FinancialOutboxMessage;
import com.finx.outbox.service.FinancialOutboxLeaseService;
import com.finx.release.execution.entity.SettlementExecutionAttempt;
import com.finx.release.execution.model.PreparedSettlementWorkItem;
import com.finx.release.execution.service.SettlementExecutionAttemptService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Worker framework component responsible for leasing outbox batches and preparing execution attempts.
 * ZERO background scheduling, zero bank provider dependency, zero money movement.
 * E4 ends strictly at PREPARED work-item stage; actual dispatch occurs in E5.
 */
@Component
public class FinancialOutboxWorker {

    private final FinancialOutboxLeaseService leaseService;
    private final SettlementExecutionAttemptService attemptService;

    public FinancialOutboxWorker(
            FinancialOutboxLeaseService leaseService,
            SettlementExecutionAttemptService attemptService
    ) {
        this.leaseService = Objects.requireNonNull(leaseService, "leaseService must not be null");
        this.attemptService = Objects.requireNonNull(attemptService, "attemptService must not be null");
    }

    /**
     * Leases the next batch of eligible outbox messages and prepares durable PREPARED execution attempts.
     * Returns immutable safe work items containing identifiers only.
     */
    public List<PreparedSettlementWorkItem> prepareNextBatch(String workerId, int batchSize) {
        Objects.requireNonNull(workerId, "workerId must not be null");
        if (workerId.trim().isEmpty() || workerId.length() > 128) {
            throw new IllegalArgumentException("workerId must be non-blank and <= 128 characters: " + workerId);
        }
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("batchSize must be between 1 and 100: " + batchSize);
        }

        List<FinancialOutboxMessage> leasedMessages = leaseService.claimBatch(workerId, batchSize);
        if (leasedMessages.isEmpty()) {
            return Collections.emptyList();
        }

        List<PreparedSettlementWorkItem> workItems = new ArrayList<>(leasedMessages.size());
        for (FinancialOutboxMessage message : leasedMessages) {
            SettlementExecutionAttempt attempt = attemptService.prepareAttempt(message.getId(), workerId);
            workItems.add(new PreparedSettlementWorkItem(
                    message.getId(),
                    message.getReleaseInstructionId(),
                    attempt.getId(),
                    attempt.getOperationKey(),
                    attempt.getProviderOperationKey(),
                    workerId
            ));
        }

        return Collections.unmodifiableList(workItems);
    }
}
