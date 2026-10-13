package com.finx.release.execution.entity;

import com.finx.release.execution.model.SettlementExecutionAttemptStatus;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable entity tracking settlement execution attempts against banking providers.
 * Strictly identifier-only: NO raw beneficiary account numbers, NO IFSC, NO credentials.
 */
@Entity
@Table(name = "settlement_execution_attempts")
public class SettlementExecutionAttempt {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "release_instruction_id", nullable = false, updatable = false)
    private UUID releaseInstructionId;

    @Column(name = "outbox_message_id", nullable = false, updatable = false)
    private UUID outboxMessageId;

    @Column(name = "attempt_number", nullable = false, updatable = false)
    private Integer attemptNumber;

    @Column(name = "operation_key", nullable = false, length = 128, updatable = false)
    private String operationKey;

    @Column(name = "provider_operation_key", nullable = false, length = 128, updatable = false)
    private String providerOperationKey;

    @Column(name = "provider", nullable = false, length = 50, updatable = false)
    private String provider;

    @Column(name = "provider_account_id", nullable = false, length = 128, updatable = false)
    private String providerAccountId;

    @Column(name = "beneficiary_bank_account_id", nullable = false, updatable = false)
    private UUID beneficiaryBankAccountId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private SettlementExecutionAttemptStatus status;

    @Column(name = "worker_id", nullable = false, length = 128)
    private String workerId;

    @Column(name = "provider_settlement_id", length = 128)
    private String providerSettlementId;

    @Column(name = "provider_status", length = 64)
    private String providerStatus;

    @Column(name = "retryable")
    private Boolean retryable;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "request_started_at")
    private Instant requestStartedAt;

    @Column(name = "response_received_at")
    private Instant responseReceivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected SettlementExecutionAttempt() {
        // For JPA
    }

    /**
     * Factory constructor for creating a PREPARED settlement execution attempt.
     */
    public SettlementExecutionAttempt(
            UUID id,
            UUID releaseInstructionId,
            UUID outboxMessageId,
            Integer attemptNumber,
            String operationKey,
            String providerOperationKey,
            String provider,
            String providerAccountId,
            UUID beneficiaryBankAccountId,
            BigDecimal amount,
            String currency,
            String requestHash,
            String workerId,
            Instant now
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.releaseInstructionId = Objects.requireNonNull(releaseInstructionId, "releaseInstructionId must not be null");
        this.outboxMessageId = Objects.requireNonNull(outboxMessageId, "outboxMessageId must not be null");
        this.attemptNumber = Objects.requireNonNull(attemptNumber, "attemptNumber must not be null");
        this.operationKey = Objects.requireNonNull(operationKey, "operationKey must not be null");
        this.providerOperationKey = Objects.requireNonNull(providerOperationKey, "providerOperationKey must not be null");
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.providerAccountId = Objects.requireNonNull(providerAccountId, "providerAccountId must not be null");
        this.beneficiaryBankAccountId = Objects.requireNonNull(beneficiaryBankAccountId, "beneficiaryBankAccountId must not be null");
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
        this.currency = Objects.requireNonNull(currency, "currency must not be null");
        this.requestHash = Objects.requireNonNull(requestHash, "requestHash must not be null");
        this.workerId = Objects.requireNonNull(workerId, "workerId must not be null");
        Instant timestamp = Objects.requireNonNull(now, "now must not be null");

        if (attemptNumber <= 0) {
            throw new IllegalArgumentException("attemptNumber must be > 0");
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }

        this.status = SettlementExecutionAttemptStatus.PREPARED;
        this.createdAt = timestamp;
        this.updatedAt = timestamp;

        // PREPARED invariant: network and provider fields must remain null
        this.providerSettlementId = null;
        this.providerStatus = null;
        this.retryable = null;
        this.errorCode = null;
        this.requestStartedAt = null;
        this.responseReceivedAt = null;
    }

    public void markSending(Instant startedAt) {
        if (this.status != SettlementExecutionAttemptStatus.PREPARED) {
            throw new IllegalStateException("Cannot mark SENDING from status: " + this.status);
        }
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        this.status = SettlementExecutionAttemptStatus.SENDING;
        this.requestStartedAt = startedAt;
        this.updatedAt = startedAt;
    }

    public void markAcknowledged(String providerSettlementId, String providerStatus, Instant responseReceivedAt) {
        if (this.status != SettlementExecutionAttemptStatus.SENDING) {
            throw new IllegalStateException("Cannot mark ACKNOWLEDGED from status: " + this.status);
        }
        Objects.requireNonNull(providerSettlementId, "providerSettlementId must not be null");
        if (providerSettlementId.isBlank() || providerSettlementId.length() > 128) {
            throw new IllegalArgumentException("providerSettlementId must be non-blank and <= 128 characters");
        }
        Objects.requireNonNull(providerStatus, "providerStatus must not be null");
        if (providerStatus.isBlank() || providerStatus.length() > 64) {
            throw new IllegalArgumentException("providerStatus must be non-blank and <= 64 characters");
        }
        Objects.requireNonNull(responseReceivedAt, "responseReceivedAt must not be null");

        this.status = SettlementExecutionAttemptStatus.ACKNOWLEDGED;
        this.providerSettlementId = providerSettlementId;
        this.providerStatus = providerStatus;
        this.responseReceivedAt = responseReceivedAt;
        this.retryable = false;
        this.errorCode = null;
        this.updatedAt = responseReceivedAt;
    }

    public void markUnknown(String safeErrorCode, Instant observedAt) {
        if (this.status != SettlementExecutionAttemptStatus.SENDING) {
            throw new IllegalStateException("Cannot mark UNKNOWN from status: " + this.status);
        }
        Objects.requireNonNull(observedAt, "observedAt must not be null");
        if (safeErrorCode != null && safeErrorCode.length() > 100) {
            throw new IllegalArgumentException("safeErrorCode length must be <= 100 characters");
        }

        this.status = SettlementExecutionAttemptStatus.UNKNOWN;
        this.responseReceivedAt = observedAt;
        this.retryable = false;
        this.errorCode = safeErrorCode;
        this.updatedAt = observedAt;
    }

    public void markTerminalFailedBeforeDispatch(String safeErrorCode, Instant now) {
        if (this.status != SettlementExecutionAttemptStatus.PREPARED) {
            throw new IllegalStateException("Cannot mark TERMINAL_FAILED from status: " + this.status);
        }
        Objects.requireNonNull(now, "now must not be null");
        if (safeErrorCode != null && safeErrorCode.length() > 100) {
            throw new IllegalArgumentException("safeErrorCode length must be <= 100 characters");
        }

        this.status = SettlementExecutionAttemptStatus.TERMINAL_FAILED;
        this.retryable = false;
        this.errorCode = safeErrorCode;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getReleaseInstructionId() {
        return releaseInstructionId;
    }

    public UUID getOutboxMessageId() {
        return outboxMessageId;
    }

    public Integer getAttemptNumber() {
        return attemptNumber;
    }

    public String getOperationKey() {
        return operationKey;
    }

    public String getProviderOperationKey() {
        return providerOperationKey;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderAccountId() {
        return providerAccountId;
    }

    public UUID getBeneficiaryBankAccountId() {
        return beneficiaryBankAccountId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public SettlementExecutionAttemptStatus getStatus() {
        return status;
    }

    public String getWorkerId() {
        return workerId;
    }

    public String getProviderSettlementId() {
        return providerSettlementId;
    }

    public String getProviderStatus() {
        return providerStatus;
    }

    public Boolean getRetryable() {
        return retryable;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Instant getRequestStartedAt() {
        return requestStartedAt;
    }

    public Instant getResponseReceivedAt() {
        return responseReceivedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
