package com.finx.paymentorchestration.enums;

public class PaymentOrchestrationEnums {

    public enum InstructionStatus {
        CREATED,
        SUBMITTED,
        PROCESSING,
        SUCCESS,
        FAILED,
        CANCELLED,
        REVERSED
    }

    public enum SettlementStatus {
        PENDING,
        PROCESSING,
        SETTLED,
        FAILED,
        REVERSED
    }

    public enum RefundStatus {
        REQUESTED,
        PROCESSING,
        COMPLETED,
        REJECTED
    }
}
