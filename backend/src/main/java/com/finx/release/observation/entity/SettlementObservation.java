package com.finx.release.observation.entity;

import com.finx.release.observation.model.SettlementObservationFinality;
import com.finx.release.observation.model.SettlementObservationSource;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable trusted settlement observation representing authoritative external provider evidence.
 * All columns are non-updatable; once persisted, an observation is durable external truth.
 */
@Entity
@Table(name = "settlement_observations")
@Immutable
public class SettlementObservation {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "release_instruction_id", nullable = false, updatable = false)
    private UUID releaseInstructionId;

    @Column(name = "execution_attempt_id", nullable = false, updatable = false)
    private UUID executionAttemptId;

    @Column(name = "webhook_event_id", updatable = false)
    private UUID webhookEventId;

    @Column(name = "provider", nullable = false, length = 50, updatable = false)
    private String provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 32, updatable = false)
    private SettlementObservationSource source;

    @Column(name = "source_reference", nullable = false, length = 255, updatable = false)
    private String sourceReference;

    @Column(name = "provider_operation_key", nullable = false, length = 128, updatable = false)
    private String providerOperationKey;

    @Column(name = "provider_settlement_id", length = 128, updatable = false)
    private String providerSettlementId;

    @Column(name = "provider_account_id", length = 128, updatable = false)
    private String providerAccountId;

    @Column(name = "provider_status", nullable = false, length = 64, updatable = false)
    private String providerStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "finality", nullable = false, length = 32, updatable = false)
    private SettlementObservationFinality finality;

    @Column(name = "reason_code", nullable = false, length = 100, updatable = false)
    private String reasonCode;

    @Column(name = "utr_number", length = 128, updatable = false)
    private String utrNumber;

    @Column(name = "observed_amount", precision = 19, scale = 4, updatable = false)
    private BigDecimal observedAmount;

    @Column(name = "observed_currency", length = 3, updatable = false)
    private String observedCurrency;

    @Column(name = "observed_direction", length = 10, updatable = false)
    private String observedDirection;

    @Column(name = "provider_observed_at", updatable = false)
    private Instant providerObservedAt;

    @Column(name = "evidence_hash", nullable = false, length = 64, updatable = false)
    private String evidenceHash;

    @Column(name = "observed_at", nullable = false, updatable = false)
    private Instant observedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // Protected default constructor for JPA
    protected SettlementObservation() {}

    private SettlementObservation(Builder builder) {
        this.id = builder.id != null ? builder.id : UUID.randomUUID();
        this.releaseInstructionId = Objects.requireNonNull(builder.releaseInstructionId, "releaseInstructionId must not be null");
        this.executionAttemptId = Objects.requireNonNull(builder.executionAttemptId, "executionAttemptId must not be null");
        this.webhookEventId = builder.webhookEventId;
        this.provider = Objects.requireNonNull(builder.provider, "provider must not be null");
        this.source = Objects.requireNonNull(builder.source, "source must not be null");
        this.sourceReference = Objects.requireNonNull(builder.sourceReference, "sourceReference must not be null");
        this.providerOperationKey = Objects.requireNonNull(builder.providerOperationKey, "providerOperationKey must not be null");
        this.providerSettlementId = builder.providerSettlementId;
        this.providerAccountId = builder.providerAccountId;
        this.providerStatus = Objects.requireNonNull(builder.providerStatus, "providerStatus must not be null");
        this.finality = Objects.requireNonNull(builder.finality, "finality must not be null");
        this.reasonCode = Objects.requireNonNull(builder.reasonCode, "reasonCode must not be null");
        this.utrNumber = builder.utrNumber;
        this.observedAmount = builder.observedAmount;
        this.observedCurrency = builder.observedCurrency;
        this.observedDirection = builder.observedDirection;
        this.providerObservedAt = builder.providerObservedAt;
        this.evidenceHash = Objects.requireNonNull(builder.evidenceHash, "evidenceHash must not be null");
        this.observedAt = Objects.requireNonNull(builder.observedAt, "observedAt must not be null");
        this.createdAt = builder.createdAt != null ? builder.createdAt : Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getReleaseInstructionId() { return releaseInstructionId; }
    public UUID getExecutionAttemptId() { return executionAttemptId; }
    public UUID getWebhookEventId() { return webhookEventId; }
    public String getProvider() { return provider; }
    public SettlementObservationSource getSource() { return source; }
    public String getSourceReference() { return sourceReference; }
    public String getProviderOperationKey() { return providerOperationKey; }
    public String getProviderSettlementId() { return providerSettlementId; }
    public String getProviderAccountId() { return providerAccountId; }
    public String getProviderStatus() { return providerStatus; }
    public SettlementObservationFinality getFinality() { return finality; }
    public String getReasonCode() { return reasonCode; }
    public String getUtrNumber() { return utrNumber; }
    public BigDecimal getObservedAmount() { return observedAmount; }
    public String getObservedCurrency() { return observedCurrency; }
    public String getObservedDirection() { return observedDirection; }
    public Instant getProviderObservedAt() { return providerObservedAt; }
    public String getEvidenceHash() { return evidenceHash; }
    public Instant getObservedAt() { return observedAt; }
    public Instant getCreatedAt() { return createdAt; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private UUID id;
        private UUID releaseInstructionId;
        private UUID executionAttemptId;
        private UUID webhookEventId;
        private String provider;
        private SettlementObservationSource source;
        private String sourceReference;
        private String providerOperationKey;
        private String providerSettlementId;
        private String providerAccountId;
        private String providerStatus;
        private SettlementObservationFinality finality;
        private String reasonCode;
        private String utrNumber;
        private BigDecimal observedAmount;
        private String observedCurrency;
        private String observedDirection;
        private Instant providerObservedAt;
        private String evidenceHash;
        private Instant observedAt;
        private Instant createdAt;

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder releaseInstructionId(UUID releaseInstructionId) { this.releaseInstructionId = releaseInstructionId; return this; }
        public Builder executionAttemptId(UUID executionAttemptId) { this.executionAttemptId = executionAttemptId; return this; }
        public Builder webhookEventId(UUID webhookEventId) { this.webhookEventId = webhookEventId; return this; }
        public Builder provider(String provider) { this.provider = provider; return this; }
        public Builder source(SettlementObservationSource source) { this.source = source; return this; }
        public Builder sourceReference(String sourceReference) { this.sourceReference = sourceReference; return this; }
        public Builder providerOperationKey(String providerOperationKey) { this.providerOperationKey = providerOperationKey; return this; }
        public Builder providerSettlementId(String providerSettlementId) { this.providerSettlementId = providerSettlementId; return this; }
        public Builder providerAccountId(String providerAccountId) { this.providerAccountId = providerAccountId; return this; }
        public Builder providerStatus(String providerStatus) { this.providerStatus = providerStatus; return this; }
        public Builder finality(SettlementObservationFinality finality) { this.finality = finality; return this; }
        public Builder reasonCode(String reasonCode) { this.reasonCode = reasonCode; return this; }
        public Builder utrNumber(String utrNumber) { this.utrNumber = utrNumber; return this; }
        public Builder observedAmount(BigDecimal observedAmount) { this.observedAmount = observedAmount; return this; }
        public Builder observedCurrency(String observedCurrency) { this.observedCurrency = observedCurrency; return this; }
        public Builder observedDirection(String observedDirection) { this.observedDirection = observedDirection; return this; }
        public Builder providerObservedAt(Instant providerObservedAt) { this.providerObservedAt = providerObservedAt; return this; }
        public Builder evidenceHash(String evidenceHash) { this.evidenceHash = evidenceHash; return this; }
        public Builder observedAt(Instant observedAt) { this.observedAt = observedAt; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }

        public SettlementObservation build() {
            return new SettlementObservation(this);
        }
    }
}
