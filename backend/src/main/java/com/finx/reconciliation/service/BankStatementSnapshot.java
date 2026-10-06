package com.finx.reconciliation.service;

import com.finx.banking.dto.BankStatementEntry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BankStatementSnapshot(
        String provider,
        String providerAccountId,
        Instant windowStart,
        Instant windowEnd,
        String sourceHash,
        BigDecimal openingBalance,
        BigDecimal closingBalance,
        String currency,
        String providerImportReference,
        List<BankStatementEntry> entries,
        UUID importedBy
) {
}
