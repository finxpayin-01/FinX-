package com.finx.finance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Safe, read-only projection of financial operations (funding and release).
 * Excludes sensitive banking details, plaintext account numbers, IFSC, and raw provider payloads.
 */
public class FinanceTransactionView {

    private UUID id;
    private String type; // "FUNDING" or "RELEASE"
    private UUID dealId;
    private UUID milestoneId;
    private BigDecimal amount;
    private String currency;
    private String status;
    private String provider;
    private Instant createdAt;
    private Instant settledAt;

    public FinanceTransactionView() {
    }

    public FinanceTransactionView(UUID id, String type, UUID dealId, UUID milestoneId,
                                  BigDecimal amount, String currency, String status,
                                  String provider, Instant createdAt, Instant settledAt) {
        this.id = id;
        this.type = type;
        this.dealId = dealId;
        this.milestoneId = milestoneId;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.provider = provider;
        this.createdAt = createdAt;
        this.settledAt = settledAt;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public UUID getDealId() {
        return dealId;
    }

    public void setDealId(UUID dealId) {
        this.dealId = dealId;
    }

    public UUID getMilestoneId() {
        return milestoneId;
    }

    public void setMilestoneId(UUID milestoneId) {
        this.milestoneId = milestoneId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getSettledAt() {
        return settledAt;
    }

    public void setSettledAt(Instant settledAt) {
        this.settledAt = settledAt;
    }
}
