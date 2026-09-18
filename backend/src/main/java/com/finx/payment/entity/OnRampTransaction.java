package com.finx.payment.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "onramp_transactions", indexes = {
        @Index(name = "idx_onramp_buyer_id", columnList = "buyer_id"),
        @Index(name = "idx_onramp_project_id", columnList = "project_id"),
        @Index(name = "idx_onramp_milestone_id", columnList = "milestone_id"),
        @Index(name = "idx_onramp_provider_order_id", columnList = "provider_order_id"),
        @Index(name = "idx_onramp_status", columnList = "status")
})
public class OnRampTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "buyer_id", nullable = false)
    private UUID buyerId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "milestone_id", nullable = false)
    private UUID milestoneId;

    @Column(name = "provider", nullable = false, length = 100)
    private String provider;

    @Column(name = "provider_order_id")
    private String providerOrderId;

    @Column(name = "provider_transaction_id")
    private String providerTransactionId;

    @Column(name = "fiat_currency", nullable = false, length = 10)
    private String fiatCurrency = "INR";

    @Column(name = "fiat_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal fiatAmount;

    @Column(name = "crypto_asset", nullable = false, length = 20)
    private String cryptoAsset = "USDC";

    @Column(name = "expected_crypto_amount", precision = 15, scale = 6)
    private BigDecimal expectedCryptoAmount;

    @Column(name = "actual_crypto_amount", precision = 15, scale = 6)
    private BigDecimal actualCryptoAmount;

    @Column(name = "network", length = 50)
    private String network;

    @Column(name = "destination_wallet")
    private String destinationWallet;

    @Column(name = "quote_id")
    private String quoteId;

    @Column(name = "quote_expires_at")
    private Instant quoteExpiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private OnRampStatus status = OnRampStatus.CREATED;

    @Column(name = "blockchain_tx_hash")
    private String blockchainTxHash;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "idempotency_key", unique = true)
    private String idempotencyKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    // Default constructor
    public OnRampTransaction() {}

    // Getters and Setters ...
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getBuyerId() { return buyerId; }
    public void setBuyerId(UUID buyerId) { this.buyerId = buyerId; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public UUID getMilestoneId() { return milestoneId; }
    public void setMilestoneId(UUID milestoneId) { this.milestoneId = milestoneId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getProviderOrderId() { return providerOrderId; }
    public void setProviderOrderId(String providerOrderId) { this.providerOrderId = providerOrderId; }
    public String getProviderTransactionId() { return providerTransactionId; }
    public void setProviderTransactionId(String providerTransactionId) { this.providerTransactionId = providerTransactionId; }
    public String getFiatCurrency() { return fiatCurrency; }
    public void setFiatCurrency(String fiatCurrency) { this.fiatCurrency = fiatCurrency; }
    public BigDecimal getFiatAmount() { return fiatAmount; }
    public void setFiatAmount(BigDecimal fiatAmount) { this.fiatAmount = fiatAmount; }
    public String getCryptoAsset() { return cryptoAsset; }
    public void setCryptoAsset(String cryptoAsset) { this.cryptoAsset = cryptoAsset; }
    public BigDecimal getExpectedCryptoAmount() { return expectedCryptoAmount; }
    public void setExpectedCryptoAmount(BigDecimal expectedCryptoAmount) { this.expectedCryptoAmount = expectedCryptoAmount; }
    public BigDecimal getActualCryptoAmount() { return actualCryptoAmount; }
    public void setActualCryptoAmount(BigDecimal actualCryptoAmount) { this.actualCryptoAmount = actualCryptoAmount; }
    public String getNetwork() { return network; }
    public void setNetwork(String network) { this.network = network; }
    public String getDestinationWallet() { return destinationWallet; }
    public void setDestinationWallet(String destinationWallet) { this.destinationWallet = destinationWallet; }
    public String getQuoteId() { return quoteId; }
    public void setQuoteId(String quoteId) { this.quoteId = quoteId; }
    public Instant getQuoteExpiresAt() { return quoteExpiresAt; }
    public void setQuoteExpiresAt(Instant quoteExpiresAt) { this.quoteExpiresAt = quoteExpiresAt; }
    public OnRampStatus getStatus() { return status; }
    public void setStatus(OnRampStatus status) { this.status = status; }
    public String getBlockchainTxHash() { return blockchainTxHash; }
    public void setBlockchainTxHash(String blockchainTxHash) { this.blockchainTxHash = blockchainTxHash; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OnRampTransaction that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
