package com.finx.banking.dto;

import java.math.BigDecimal;
import java.util.List;

public record BankStatementPage(
        String providerAccountId,
        String providerImportReference,
        String currency,
        BigDecimal openingBalance,
        BigDecimal closingBalance,
        List<BankStatementEntry> entries,
        String nextCursor
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String providerAccountId;
        private String providerImportReference;
        private String currency;
        private BigDecimal openingBalance;
        private BigDecimal closingBalance;
        private List<BankStatementEntry> entries;
        private String nextCursor;

        public Builder providerAccountId(String providerAccountId) {
            this.providerAccountId = providerAccountId;
            return this;
        }

        public Builder providerImportReference(String providerImportReference) {
            this.providerImportReference = providerImportReference;
            return this;
        }

        public Builder currency(String currency) {
            this.currency = currency;
            return this;
        }

        public Builder openingBalance(BigDecimal openingBalance) {
            this.openingBalance = openingBalance;
            return this;
        }

        public Builder closingBalance(BigDecimal closingBalance) {
            this.closingBalance = closingBalance;
            return this;
        }

        public Builder entries(List<BankStatementEntry> entries) {
            this.entries = entries;
            return this;
        }

        public Builder nextCursor(String nextCursor) {
            this.nextCursor = nextCursor;
            return this;
        }

        public BankStatementPage build() {
            return new BankStatementPage(providerAccountId, providerImportReference, currency,
                    openingBalance, closingBalance, entries != null ? List.copyOf(entries) : List.of(), nextCursor);
        }
    }

    public String getProviderAccountId() { return providerAccountId(); }
    public String getProviderImportReference() { return providerImportReference(); }
    public String getCurrency() { return currency(); }
    public BigDecimal getOpeningBalance() { return openingBalance(); }
    public BigDecimal getClosingBalance() { return closingBalance(); }
    public List<BankStatementEntry> getEntries() { return entries(); }
    public String getNextCursor() { return nextCursor(); }
}
