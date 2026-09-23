package com.finx.paymentorchestration.dto;

import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.*;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public class PaymentOrchestrationDtos {

    public record CreateInstructionRequest(
            @NotNull UUID dealId,
            UUID milestoneId,
            String idempotencyKey
    ) {}

    public record ConfirmPaymentRequest(
            String utrNumber,
            String providerReference
    ) {}

    public record PayoutAuthorizationRequest(
            @NotNull UUID milestoneId,
            String comment,
            String idempotencyKey
    ) {}

    public record PaymentInstructionResponse(
            UUID id,
            String instructionReference,
            UUID dealId,
            UUID milestoneId,
            BigDecimal amount,
            String currency,
            String provider,
            String providerInstructionId,
            InstructionStatus status,
            String virtualAccountNumber,
            String virtualIfsc,
            String confirmedAt,
            String createdAt
    ) {}

    public record SettlementResponse(
            UUID id,
            String settlementReference,
            UUID dealId,
            UUID milestoneId,
            BigDecimal amount,
            String currency,
            String provider,
            String utrNumber,
            SettlementStatus status,
            String authorizedAt,
            String settledAt,
            String failureReason
    ) {}
}
