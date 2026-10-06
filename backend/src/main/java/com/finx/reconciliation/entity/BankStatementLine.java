package com.finx.reconciliation.entity;

import com.finx.reconciliation.enums.BankStatementLineStatus;
import com.finx.reconciliation.enums.FinancialDirection;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Represents immutable, durable bank statement line evidence.
 *
 * Note: providerAccountId represents a stable bank/provider account scope identifier or token,
 * NOT a raw plaintext customer/bank account number.
 * No raw bank statement payloads or plaintext counterparty account numbers are persisted.
 */
@Entity
@Immutable
@Table(name = "bank_statement_lines")
public class BankStatementLine {

    @Id
    private UUID id;

    @Column(name = "import_id", nullable = false)
    private UUID importId;

    @Column(nullable = false, length = 64)
    private String provider;

    @Column(name = "provider_account_id", nullable = false, length = 128)
    private String providerAccountId;

    @Column(name = "provider_line_id", length = 128)
    private String providerLineId;

    @Column(name = "transaction_reference", length = 128)
    private String transactionReference;

    @Column(name = "utr_number", length = 128)
    private String utrNumber;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FinancialDirection direction;

    @Column(name = "provider_status", length = 64)
    private String providerStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "normalized_status", nullable = false, length = 32)
    private BankStatementLineStatus normalizedStatus;

    @Column(name = "transaction_time", nullable = false)
    private Instant transactionTime;

    @Column(name = "value_date")
    private LocalDate valueDate;

    @Column(name = "line_fingerprint", nullable = false, length = 64)
    private String lineFingerprint;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public BankStatementLine() {
        this.id = UUID.randomUUID();
        this.normalizedStatus = BankStatementLineStatus.UNKNOWN;
        this.createdAt = Instant.now();
    }

    public BankStatementLine(UUID importId,
                             String provider,
                             String providerAccountId,
                             String providerLineId,
                             String transactionReference,
                             String utrNumber,
                             BigDecimal amount,
                             String currency,
                             FinancialDirection direction,
                             String providerStatus,
                             BankStatementLineStatus normalizedStatus,
                             Instant transactionTime,
                             LocalDate valueDate,
                             String lineFingerprint) {
        this();
        this.importId = importId;
        this.provider = provider;
        this.providerAccountId = providerAccountId;
        this.providerLineId = providerLineId;
        this.transactionReference = transactionReference;
        this.utrNumber = utrNumber;
        this.amount = amount;
        this.currency = currency;
        this.direction = direction;
        this.providerStatus = providerStatus;
        this.normalizedStatus = normalizedStatus != null ? normalizedStatus : BankStatementLineStatus.UNKNOWN;
        this.transactionTime = transactionTime;
        this.valueDate = valueDate;
        this.lineFingerprint = lineFingerprint;
    }

    // Getters and Setters (Setters for JPA hydration/initialization only; entity is @Immutable)
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getImportId() { return importId; }
    public void setImportId(UUID importId) { this.importId = importId; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getProviderAccountId() { return providerAccountId; }
    public void setProviderAccountId(String providerAccountId) { this.providerAccountId = providerAccountId; }

    public String getProviderLineId() { return providerLineId; }
    public void setProviderLineId(String providerLineId) { this.providerLineId = providerLineId; }

    public String getTransactionReference() { return transactionReference; }
    public void setTransactionReference(String transactionReference) { this.transactionReference = transactionReference; }

    public String getUtrNumber() { return utrNumber; }
    public void setUtrNumber(String utrNumber) { this.utrNumber = utrNumber; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public FinancialDirection getDirection() { return direction; }
    public void setDirection(FinancialDirection direction) { this.direction = direction; }

    public String getProviderStatus() { return providerStatus; }
    public void setProviderStatus(String providerStatus) { this.providerStatus = providerStatus; }

    public BankStatementLineStatus getNormalizedStatus() { return normalizedStatus; }
    public void setNormalizedStatus(BankStatementLineStatus normalizedStatus) { this.normalizedStatus = normalizedStatus; }

    public Instant getTransactionTime() { return transactionTime; }
    public void setTransactionTime(Instant transactionTime) { this.transactionTime = transactionTime; }

    public LocalDate getValueDate() { return valueDate; }
    public void setValueDate(LocalDate valueDate) { this.valueDate = valueDate; }

    public String getLineFingerprint() { return lineFingerprint; }
    public void setLineFingerprint(String lineFingerprint) { this.lineFingerprint = lineFingerprint; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
