package com.finx.reconciliation.entity;

import com.finx.reconciliation.enums.ReconciliationEnums.ReconciliationRunStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_runs")
public class ReconciliationRun {

    @Id
    private UUID id;

    @Column(name = "run_reference", nullable = false, unique = true)
    private String runReference;

    @Column(nullable = false)
    private String provider;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time")
    private Instant endTime;

    @Column(name = "total_internal_transactions", nullable = false)
    private int totalInternalTransactions;

    @Column(name = "total_bank_transactions", nullable = false)
    private int totalBankTransactions;

    @Column(name = "matched_count", nullable = false)
    private int matchedCount;

    @Column(name = "mismatch_count", nullable = false)
    private int mismatchCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReconciliationRunStatus status;

    @Column(name = "triggered_by")
    private UUID triggeredBy;

    @Column(name = "summary_notes", columnDefinition = "TEXT")
    private String summaryNotes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // V26 Additive Durable Reconciliation Fields (Nullable for backward compatibility)
    @Column(name = "provider_account_id", length = 128)
    private String providerAccountId;

    @Column(name = "window_start")
    private Instant windowStart;

    @Column(name = "window_end")
    private Instant windowEnd;

    @Column(name = "bank_statement_import_id")
    private UUID bankStatementImportId;

    @Column(name = "opening_balance", precision = 19, scale = 4)
    private BigDecimal openingBalance;

    @Column(name = "closing_balance", precision = 19, scale = 4)
    private BigDecimal closingBalance;

    @Column(name = "currency", length = 3)
    private String currency;

    // V28 Additive Deterministic Reconciliation Fields (Nullable for backward compatibility)
    @Column(name = "matcher_version", length = 64)
    private String matcherVersion;

    @Column(name = "statement_source_hash", length = 64)
    private String statementSourceHash;

    public ReconciliationRun() {
        this.id = UUID.randomUUID();
        this.status = ReconciliationRunStatus.IN_PROGRESS;
        this.startTime = Instant.now();
        this.createdAt = Instant.now();
    }

    public ReconciliationRun(String runReference, String provider, UUID triggeredBy) {
        this();
        this.runReference = runReference;
        this.provider = provider;
        this.triggeredBy = triggeredBy;
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getRunReference() { return runReference; }
    public void setRunReference(String runReference) { this.runReference = runReference; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public Instant getStartTime() { return startTime; }
    public void setStartTime(Instant startTime) { this.startTime = startTime; }
    public Instant getEndTime() { return endTime; }
    public void setEndTime(Instant endTime) { this.endTime = endTime; }
    public int getTotalInternalTransactions() { return totalInternalTransactions; }
    public void setTotalInternalTransactions(int totalInternalTransactions) { this.totalInternalTransactions = totalInternalTransactions; }
    public int getTotalBankTransactions() { return totalBankTransactions; }
    public void setTotalBankTransactions(int totalBankTransactions) { this.totalBankTransactions = totalBankTransactions; }
    public int getMatchedCount() { return matchedCount; }
    public void setMatchedCount(int matchedCount) { this.matchedCount = matchedCount; }
    public int getMismatchCount() { return mismatchCount; }
    public void setMismatchCount(int mismatchCount) { this.mismatchCount = mismatchCount; }
    public ReconciliationRunStatus getStatus() { return status; }
    public void setStatus(ReconciliationRunStatus status) { this.status = status; }
    public UUID getTriggeredBy() { return triggeredBy; }
    public void setTriggeredBy(UUID triggeredBy) { this.triggeredBy = triggeredBy; }
    public String getSummaryNotes() { return summaryNotes; }
    public void setSummaryNotes(String summaryNotes) { this.summaryNotes = summaryNotes; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getProviderAccountId() { return providerAccountId; }
    public void setProviderAccountId(String providerAccountId) { this.providerAccountId = providerAccountId; }
    public Instant getWindowStart() { return windowStart; }
    public void setWindowStart(Instant windowStart) { this.windowStart = windowStart; }
    public Instant getWindowEnd() { return windowEnd; }
    public void setWindowEnd(Instant windowEnd) { this.windowEnd = windowEnd; }
    public UUID getBankStatementImportId() { return bankStatementImportId; }
    public void setBankStatementImportId(UUID bankStatementImportId) { this.bankStatementImportId = bankStatementImportId; }
    public BigDecimal getOpeningBalance() { return openingBalance; }
    public void setOpeningBalance(BigDecimal openingBalance) { this.openingBalance = openingBalance; }
    public BigDecimal getClosingBalance() { return closingBalance; }
    public void setClosingBalance(BigDecimal closingBalance) { this.closingBalance = closingBalance; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getMatcherVersion() { return matcherVersion; }
    public void setMatcherVersion(String matcherVersion) { this.matcherVersion = matcherVersion; }
    public String getStatementSourceHash() { return statementSourceHash; }
    public void setStatementSourceHash(String statementSourceHash) { this.statementSourceHash = statementSourceHash; }
}
