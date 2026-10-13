package com.finx.release.observation.service;

import com.finx.release.observation.model.SettlementObservationSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Command containing provider observation evidence for durable persistence.
 */
public record RecordObservationCommand(
        UUID releaseInstructionId,
        UUID executionAttemptId,
        UUID webhookEventId,
        SettlementObservationSource source,
        String sourceReference,
        String provider,
        String providerOperationKey,
        String providerSettlementId,
        String providerAccountId,
        String rawProviderStatus,
        String utrNumber,
        BigDecimal observedAmount,
        String observedCurrency,
        String observedDirection,
        Instant providerObservedAt,
        Instant observedAt
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private UUID releaseInstructionId;
        private UUID executionAttemptId;
        private UUID webhookEventId;
        private SettlementObservationSource source;
        private String sourceReference;
        private String provider;
        private String providerOperationKey;
        private String providerSettlementId;
        private String providerAccountId;
        private String rawProviderStatus;
        private String utrNumber;
        private BigDecimal observedAmount;
        private String observedCurrency;
        private String observedDirection;
        private Instant providerObservedAt;
        private Instant observedAt;

        public Builder releaseInstructionId(UUID releaseInstructionId) { this.releaseInstructionId = releaseInstructionId; return this; }
        public Builder executionAttemptId(UUID executionAttemptId) { this.executionAttemptId = executionAttemptId; return this; }
        public Builder webhookEventId(UUID webhookEventId) { this.webhookEventId = webhookEventId; return this; }
        public Builder source(SettlementObservationSource source) { this.source = source; return this; }
        public Builder sourceReference(String sourceReference) { this.sourceReference = sourceReference; return this; }
        public Builder provider(String provider) { this.provider = provider; return this; }
        public Builder providerOperationKey(String providerOperationKey) { this.providerOperationKey = providerOperationKey; return this; }
        public Builder providerSettlementId(String providerSettlementId) { this.providerSettlementId = providerSettlementId; return this; }
        public Builder providerAccountId(String providerAccountId) { this.providerAccountId = providerAccountId; return this; }
        public Builder rawProviderStatus(String rawProviderStatus) { this.rawProviderStatus = rawProviderStatus; return this; }
        public Builder utrNumber(String utrNumber) { this.utrNumber = utrNumber; return this; }
        public Builder observedAmount(BigDecimal observedAmount) { this.observedAmount = observedAmount; return this; }
        public Builder observedCurrency(String observedCurrency) { this.observedCurrency = observedCurrency; return this; }
        public Builder observedDirection(String observedDirection) { this.observedDirection = observedDirection; return this; }
        public Builder providerObservedAt(Instant providerObservedAt) { this.providerObservedAt = providerObservedAt; return this; }
        public Builder observedAt(Instant observedAt) { this.observedAt = observedAt; return this; }

        public RecordObservationCommand build() {
            return new RecordObservationCommand(
                    releaseInstructionId,
                    executionAttemptId,
                    webhookEventId,
                    source,
                    sourceReference,
                    provider,
                    providerOperationKey,
                    providerSettlementId,
                    providerAccountId,
                    rawProviderStatus,
                    utrNumber,
                    observedAmount,
                    observedCurrency,
                    observedDirection,
                    providerObservedAt,
                    observedAt
            );
        }
    }
}
