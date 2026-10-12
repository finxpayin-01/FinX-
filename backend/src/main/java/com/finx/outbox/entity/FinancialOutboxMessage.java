package com.finx.outbox.entity;

import com.finx.outbox.model.FinancialOperationKey;
import com.finx.outbox.model.FinancialOutboxEventType;
import com.finx.outbox.model.FinancialOutboxStatus;
import com.finx.release.entity.ReleaseInstruction;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable command queue message in the FinX financial outbox.
 * Maps to table 'financial_outbox' (V31).
 * <p>
 * Strictly identifier-only: worker E4/E5 reloads the canonical ReleaseInstruction
 * entity from PostgreSQL rather than consuming a deserialized bank payload snapshot.
 * NO bank credentials, NO account numbers, NO IFSC, NO secrets.
 */
@Entity
@Table(name = "financial_outbox")
public class FinancialOutboxMessage {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "release_instruction_id", nullable = false, updatable = false)
    private UUID releaseInstructionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 64)
    private FinancialOutboxEventType eventType;

    @Column(name = "operation_key", nullable = false, updatable = false, length = 128)
    private String operationKey;

    @Column(name = "payload_version", nullable = false, updatable = false)
    private Integer payloadVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private FinancialOutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "locked_by", length = 128)
    private String lockedBy;

    @Column(name = "last_error_code", length = 100)
    private String lastErrorCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "dead_lettered_at")
    private Instant deadLetteredAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected FinancialOutboxMessage() {
        // JPA requirement
    }

    public FinancialOutboxMessage(
            UUID id,
            UUID releaseInstructionId,
            FinancialOutboxEventType eventType,
            String operationKey,
            int payloadVersion,
            FinancialOutboxStatus status,
            int attemptCount,
            Instant availableAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.releaseInstructionId = Objects.requireNonNull(releaseInstructionId, "releaseInstructionId must not be null");
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        this.operationKey = Objects.requireNonNull(operationKey, "operationKey must not be null");
        if (operationKey.isBlank()) {
            throw new IllegalArgumentException("operationKey must not be blank");
        }
        if (payloadVersion <= 0) {
            throw new IllegalArgumentException("payloadVersion must be strictly positive: " + payloadVersion);
        }
        this.payloadVersion = payloadVersion;
        this.status = Objects.requireNonNull(status, "status must not be null");
        if (attemptCount < 0) {
            throw new IllegalArgumentException("attemptCount must not be negative: " + attemptCount);
        }
        this.attemptCount = attemptCount;
        this.availableAt = Objects.requireNonNull(availableAt, "availableAt must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    /**
     * Factory method creating a new PENDING release dispatch outbox message.
     */
    public static FinancialOutboxMessage createReleaseDispatch(ReleaseInstruction instruction, Instant now) {
        Objects.requireNonNull(instruction, "instruction must not be null");
        Objects.requireNonNull(instruction.getId(), "instruction.id must not be null");
        Objects.requireNonNull(now, "now must not be null");

        String opKey = FinancialOperationKey.releaseDispatch(instruction.getId());
        return new FinancialOutboxMessage(
                UUID.randomUUID(),
                instruction.getId(),
                FinancialOutboxEventType.RELEASE_DISPATCH_REQUESTED,
                opKey,
                1,
                FinancialOutboxStatus.PENDING,
                0,
                now,
                now,
                now
        );
    }

    // Getters
    public UUID getId() { return id; }
    public UUID getReleaseInstructionId() { return releaseInstructionId; }
    public FinancialOutboxEventType getEventType() { return eventType; }
    public String getOperationKey() { return operationKey; }
    public Integer getPayloadVersion() { return payloadVersion; }
    public FinancialOutboxStatus getStatus() { return status; }
    public Integer getAttemptCount() { return attemptCount; }
    public Instant getAvailableAt() { return availableAt; }
    public Instant getLockedAt() { return lockedAt; }
    public String getLockedBy() { return lockedBy; }
    public String getLastErrorCode() { return lastErrorCode; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getProcessedAt() { return processedAt; }
    public Instant getDeadLetteredAt() { return deadLetteredAt; }
    public Long getVersion() { return version; }
}
