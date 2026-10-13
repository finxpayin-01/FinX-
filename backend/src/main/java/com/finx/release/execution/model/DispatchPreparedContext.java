package com.finx.release.execution.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable in-memory context prepared by TX-D1 containing all authoritative
 * parameters required for external provider dispatch.
 * Ephemeral encrypted account number is decrypted only in memory immediately before network dispatch.
 * Strictly identifier-only: NO raw account number, NO credentials.
 */
public record DispatchPreparedContext(
        UUID outboxMessageId,
        UUID executionAttemptId,
        UUID releaseInstructionId,
        UUID dealId,
        UUID milestoneId,
        BigDecimal amount,
        String currency,
        String provider,
        String providerAccountId,
        String providerOperationKey,
        UUID beneficiaryBankAccountId,
        String beneficiaryName,
        String beneficiaryIfsc,
        String encryptedAccountNumber
) {
    public DispatchPreparedContext {
        Objects.requireNonNull(outboxMessageId, "outboxMessageId must not be null");
        Objects.requireNonNull(executionAttemptId, "executionAttemptId must not be null");
        Objects.requireNonNull(releaseInstructionId, "releaseInstructionId must not be null");
        Objects.requireNonNull(dealId, "dealId must not be null");
        Objects.requireNonNull(milestoneId, "milestoneId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(providerAccountId, "providerAccountId must not be null");
        Objects.requireNonNull(providerOperationKey, "providerOperationKey must not be null");
        Objects.requireNonNull(beneficiaryBankAccountId, "beneficiaryBankAccountId must not be null");
        Objects.requireNonNull(beneficiaryName, "beneficiaryName must not be null");
        Objects.requireNonNull(beneficiaryIfsc, "beneficiaryIfsc must not be null");
        Objects.requireNonNull(encryptedAccountNumber, "encryptedAccountNumber must not be null");
    }
}
