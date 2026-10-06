package com.finx.banking.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record BankStatementEntry(
        String providerLineId,
        String transactionReference,
        String utrNumber,
        BigDecimal amount,
        String currency,
        BankStatementDirection direction,
        String providerStatus,
        BankStatementFinality finality,
        Instant transactionTime,
        LocalDate valueDate
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String providerLineId;
        private String transactionReference;
        private String utrNumber;
        private BigDecimal amount;
        private String currency;
        private BankStatementDirection direction;
        private String providerStatus;
        private BankStatementFinality finality;
        private Instant transactionTime;
        private LocalDate valueDate;

        public Builder providerLineId(String providerLineId) {
            this.providerLineId = providerLineId;
            return this;
        }

        public Builder transactionReference(String transactionReference) {
            this.transactionReference = transactionReference;
            return this;
        }

        public Builder utrNumber(String utrNumber) {
            this.utrNumber = utrNumber;
            return this;
        }

        public Builder amount(BigDecimal amount) {
            this.amount = amount;
            return this;
        }

        public Builder currency(String currency) {
            this.currency = currency;
            return this;
        }

        public Builder direction(BankStatementDirection direction) {
            this.direction = direction;
            return this;
        }

        public Builder providerStatus(String providerStatus) {
            this.providerStatus = providerStatus;
            return this;
        }

        public Builder finality(BankStatementFinality finality) {
            this.finality = finality;
            return this;
        }

        public Builder transactionTime(Instant transactionTime) {
            this.transactionTime = transactionTime;
            return this;
        }

        public Builder valueDate(LocalDate valueDate) {
            this.valueDate = valueDate;
            return this;
        }

        public BankStatementEntry build() {
            return new BankStatementEntry(providerLineId, transactionReference, utrNumber, amount,
                    currency, direction, providerStatus, finality, transactionTime, valueDate);
        }
    }

    public String getProviderLineId() { return providerLineId(); }
    public String getTransactionReference() { return transactionReference(); }
    public String getUtrNumber() { return utrNumber(); }
    public BigDecimal getAmount() { return amount(); }
    public String getCurrency() { return currency(); }
    public BankStatementDirection getDirection() { return direction(); }
    public String getProviderStatus() { return providerStatus(); }
    public BankStatementFinality getFinality() { return finality(); }
    public Instant getTransactionTime() { return transactionTime(); }
    public LocalDate getValueDate() { return valueDate(); }
}
