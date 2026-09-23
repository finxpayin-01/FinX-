package com.finx.ledger.enums;

public class LedgerEnums {

    public enum AccountType {
        ASSET,
        LIABILITY,
        EQUITY,
        REVENUE,
        EXPENSE
    }

    public enum EntryType {
        DEBIT,
        CREDIT
    }

    public enum LedgerTransactionType {
        ESCROW_DEPOSIT,
        MILESTONE_SETTLEMENT,
        REFUND,
        FEE_DEDUCTION,
        ADJUSTMENT
    }
}
