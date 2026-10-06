package com.finx.banking.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public class BankingDtos {

    public record PaymentInstructionRequest(
            UUID dealId,
            UUID milestoneId,
            String instructionReference,
            BigDecimal amount,
            String currency,
            String buyerLegalName,
            String sellerLegalName
    ) {}

    public record PaymentInstructionResult(
            String providerInstructionId,
            String virtualAccountNumber,
            String ifscCode,
            String status,
            String paymentUrl,
            String rawResponse,
            String providerAccountId
    ) {
        public PaymentInstructionResult(
                String providerInstructionId,
                String virtualAccountNumber,
                String ifscCode,
                String status,
                String paymentUrl,
                String rawResponse
        ) {
            this(providerInstructionId, virtualAccountNumber, ifscCode, status, paymentUrl, rawResponse, null);
        }
    }

    public record PaymentStatusResult(
            String providerInstructionId,
            String status,
            String utrNumber,
            BigDecimal amount,
            Instant confirmedAt,
            String rawResponse
    ) {}

    public record AccountDetailsResult(
            String accountNumber,
            String ifscCode,
            String accountHolderName,
            BigDecimal balance,
            String status
    ) {}

    public record BeneficiaryVerificationRequest(
            String accountHolderName,
            String accountNumber,
            String ifscCode,
            String entityType,
            String panOrGstin
    ) {}

    public record BeneficiaryVerificationResult(
            boolean verified,
            String registeredName,
            String verificationReference,
            String message,
            String rawResponse
    ) {}

    public record SettlementRequest(
            String settlementReference,
            UUID dealId,
            UUID milestoneId,
            BigDecimal amount,
            String currency,
            String beneficiaryName,
            String beneficiaryAccountNumber,
            String beneficiaryIfsc,
            String idempotencyKey
    ) {}

    public record SettlementResult(
            String providerSettlementId,
            String status,
            String utrNumber,
            Instant initiatedAt,
            String rawResponse,
            String providerAccountId
    ) {
        public SettlementResult(
                String providerSettlementId,
                String status,
                String utrNumber,
                Instant initiatedAt,
                String rawResponse
        ) {
            this(providerSettlementId, status, utrNumber, initiatedAt, rawResponse, null);
        }
    }

    public record SettlementStatusResult(
            String providerSettlementId,
            String status,
            String utrNumber,
            Instant settledAt,
            String failureReason
    ) {}

    public record BankTransactionRecord(
            String transactionReference,
            String utrNumber,
            String virtualAccountNumber,
            BigDecimal amount,
            String currency,
            String type, // CREDIT or DEBIT
            Instant timestamp,
            String counterpartyAccount,
            String description
    ) {}
}
