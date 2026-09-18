package com.finx.payment.service.onramp;

public enum SettlementStatus {
    VERIFIED,
    PENDING,
    REJECTED,
    RPC_UNAVAILABLE,
    MANUAL_REVIEW
}
