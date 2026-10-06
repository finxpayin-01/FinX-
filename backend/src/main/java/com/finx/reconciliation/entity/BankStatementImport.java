package com.finx.reconciliation.entity;

import com.finx.reconciliation.enums.BankStatementImportStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents an idempotent, durable bank statement import batch.
 *
 * Note: providerAccountId represents a stable bank/provider account scope identifier or token,
 * NOT a raw plaintext customer/bank account number.
 */
@Entity
@Table(name = "bank_statement_imports")
public class BankStatementImport {

    @Id
    private UUID id;

    @Column(nullable = false, length = 64)
    private String provider;

    @Column(name = "provider_account_id", nullable = false, length = 128)
    private String providerAccountId;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "window_end", nullable = false)
    private Instant windowEnd;

    @Column(name = "source_hash", nullable = false, length = 64)
    private String sourceHash;

    @Column(name = "opening_balance", precision = 19, scale = 4)
    private BigDecimal openingBalance;

    @Column(name = "closing_balance", precision = 19, scale = 4)
    private BigDecimal closingBalance;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "total_lines", nullable = false)
    private int totalLines;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private BankStatementImportStatus status;

    @Column(name = "imported_by")
    private UUID importedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "provider_import_reference", length = 128)
    private String providerImportReference;

    public BankStatementImport() {
        this.id = UUID.randomUUID();
        this.status = BankStatementImportStatus.RECEIVED;
        this.createdAt = Instant.now();
        this.totalLines = 0;
    }

    public BankStatementImport(String provider,
                               String providerAccountId,
                               Instant windowStart,
                               Instant windowEnd,
                               String sourceHash,
                               BigDecimal openingBalance,
                               BigDecimal closingBalance,
                               String currency,
                               UUID importedBy) {
        this();
        this.provider = provider;
        this.providerAccountId = providerAccountId;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.sourceHash = sourceHash;
        this.openingBalance = openingBalance;
        this.closingBalance = closingBalance;
        this.currency = currency;
        this.importedBy = importedBy;
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getProviderAccountId() { return providerAccountId; }
    public void setProviderAccountId(String providerAccountId) { this.providerAccountId = providerAccountId; }

    public Instant getWindowStart() { return windowStart; }
    public void setWindowStart(Instant windowStart) { this.windowStart = windowStart; }

    public Instant getWindowEnd() { return windowEnd; }
    public void setWindowEnd(Instant windowEnd) { this.windowEnd = windowEnd; }

    public String getSourceHash() { return sourceHash; }
    public void setSourceHash(String sourceHash) { this.sourceHash = sourceHash; }

    public BigDecimal getOpeningBalance() { return openingBalance; }
    public void setOpeningBalance(BigDecimal openingBalance) { this.openingBalance = openingBalance; }

    public BigDecimal getClosingBalance() { return closingBalance; }
    public void setClosingBalance(BigDecimal closingBalance) { this.closingBalance = closingBalance; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public int getTotalLines() { return totalLines; }
    public void setTotalLines(int totalLines) { this.totalLines = totalLines; }

    public BankStatementImportStatus getStatus() { return status; }
    public void setStatus(BankStatementImportStatus status) { this.status = status; }

    public UUID getImportedBy() { return importedBy; }
    public void setImportedBy(UUID importedBy) { this.importedBy = importedBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public String getProviderImportReference() { return providerImportReference; }
    public void setProviderImportReference(String providerImportReference) { this.providerImportReference = providerImportReference; }
}
