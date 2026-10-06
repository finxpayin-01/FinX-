package com.finx.paymentorchestration.entity;

import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.SettlementStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "settlement_instructions")
public class SettlementInstruction {

    @Id
    private UUID id;

    @Column(name = "settlement_reference", nullable = false, unique = true)
    private String settlementReference;

    @Column(name = "deal_id", nullable = false)
    private UUID dealId;

    @Column(name = "milestone_id", nullable = false)
    private UUID milestoneId;

    @Column(name = "seller_organization_id", nullable = false)
    private UUID sellerOrganizationId;

    @Column(name = "beneficiary_bank_account_id", nullable = false)
    private UUID beneficiaryBankAccountId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    private String provider;

    @Column(name = "provider_settlement_id")
    private String providerSettlementId;

    @Column(name = "utr_number")
    private String utrNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SettlementStatus status;

    @Column(name = "authorized_by")
    private UUID authorizedBy;

    @Column(name = "authorized_at")
    private Instant authorizedAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "request_hash", length = 64)
    private String requestHash;

    @Column(name = "provider_account_id", length = 128)
    private String providerAccountId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public SettlementInstruction() {
        this.id = UUID.randomUUID();
        this.status = SettlementStatus.PENDING;
        this.currency = "INR";
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public SettlementInstruction(String settlementReference, UUID dealId, UUID milestoneId,
                                 UUID sellerOrganizationId, UUID beneficiaryBankAccountId, BigDecimal amount,
                                 String currency, String provider, String idempotencyKey, UUID authorizedBy) {
        this();
        this.settlementReference = settlementReference;
        this.dealId = dealId;
        this.milestoneId = milestoneId;
        this.sellerOrganizationId = sellerOrganizationId;
        this.beneficiaryBankAccountId = beneficiaryBankAccountId;
        this.amount = amount != null ? amount.setScale(4) : BigDecimal.ZERO.setScale(4);
        if (currency != null) this.currency = currency;
        this.provider = provider;
        this.idempotencyKey = idempotencyKey;
        this.authorizedBy = authorizedBy;
        this.authorizedAt = Instant.now();
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getSettlementReference() { return settlementReference; }
    public void setSettlementReference(String settlementReference) { this.settlementReference = settlementReference; }
    public UUID getDealId() { return dealId; }
    public void setDealId(UUID dealId) { this.dealId = dealId; }
    public UUID getMilestoneId() { return milestoneId; }
    public void setMilestoneId(UUID milestoneId) { this.milestoneId = milestoneId; }
    public UUID getSellerOrganizationId() { return sellerOrganizationId; }
    public void setSellerOrganizationId(UUID sellerOrganizationId) { this.sellerOrganizationId = sellerOrganizationId; }
    public UUID getBeneficiaryBankAccountId() { return beneficiaryBankAccountId; }
    public void setBeneficiaryBankAccountId(UUID beneficiaryBankAccountId) { this.beneficiaryBankAccountId = beneficiaryBankAccountId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount != null ? amount.setScale(4) : BigDecimal.ZERO.setScale(4); }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getProviderSettlementId() { return providerSettlementId; }
    public void setProviderSettlementId(String providerSettlementId) { this.providerSettlementId = providerSettlementId; }
    public String getUtrNumber() { return utrNumber; }
    public void setUtrNumber(String utrNumber) { this.utrNumber = utrNumber; }
    public SettlementStatus getStatus() { return status; }
    public void setStatus(SettlementStatus status) { this.status = status; }
    public UUID getAuthorizedBy() { return authorizedBy; }
    public void setAuthorizedBy(UUID authorizedBy) { this.authorizedBy = authorizedBy; }
    public Instant getAuthorizedAt() { return authorizedAt; }
    public void setAuthorizedAt(Instant authorizedAt) { this.authorizedAt = authorizedAt; }
    public Instant getSettledAt() { return settledAt; }
    public void setSettledAt(Instant settledAt) { this.settledAt = settledAt; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String requestHash) { this.requestHash = requestHash; }
    public String getProviderAccountId() { return providerAccountId; }
    public void setProviderAccountId(String providerAccountId) { this.providerAccountId = providerAccountId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
