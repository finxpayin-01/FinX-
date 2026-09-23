package com.finx.ledger.dto;

import com.finx.ledger.enums.LedgerEnums.EntryType;
import com.finx.ledger.enums.LedgerEnums.LedgerTransactionType;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public class LedgerDtos {

    public record JournalLineItem(
            String accountNumber,
            EntryType entryType,
            BigDecimal amount,
            String description
    ) {}

    public record PostJournalEntryRequest(
            LedgerTransactionType transactionType,
            UUID referenceId,
            UUID dealId,
            UUID milestoneId,
            String currency,
            String narration,
            UUID postedBy,
            List<JournalLineItem> lines
    ) {}

    public record JournalEntryResponse(
            UUID id,
            String entryReference,
            LedgerTransactionType transactionType,
            UUID referenceId,
            UUID dealId,
            UUID milestoneId,
            BigDecimal totalDebit,
            BigDecimal totalCredit,
            String currency,
            String narration,
            String postedAt
    ) {}
}
