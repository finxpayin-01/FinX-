package com.finx.banking.capability;

/**
 * Enumeration of distinct financial operations and capabilities supported by banking providers.
 * Providers explicitly declare their capabilities; callers must not infer capability support
 * by invoking methods and catching exceptions.
 */
public enum BankingProviderCapability {
    PAYMENT_INSTRUCTION_CREATE,
    PAYMENT_STATUS_QUERY,
    ACCOUNT_DETAILS_QUERY,
    BENEFICIARY_VERIFICATION,
    SETTLEMENT_INITIATE,
    SETTLEMENT_STATUS_QUERY,
    SETTLEMENT_OPERATION_LOOKUP,
    TRANSACTION_FEED,
    STATEMENT_PAGING,
    WEBHOOK_SIGNATURE_VERIFICATION
}
