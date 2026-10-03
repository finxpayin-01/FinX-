package com.finx.paymentorchestration.entity;

import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.InstructionStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_instructions")
public class PaymentInstruction {

    @Id
    private UUID id;

    @Column(name = "instruction_reference", nullable = false, unique = true)
    private String instructionReference;

    @Column(name = "deal_id", nullable = false)
    private UUID dealId;

    @Column(name = "milestone_id")
    private UUID milestoneId;

    @Column(name = "buyer_organization_id")
    private UUID buyerOrganizationId;

    @Column(name = "seller_organization_id")
    private UUID sellerOrganizationId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    private String provider;

    @Column(name = "provider_instruction_id")
    private String providerInstructionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InstructionStatus status;

    @Column(name = "virtual_account_number")
    private String virtualAccountNumber;

    @Column(name = "virtual_ifsc")
    private String virtualIfsc;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "request_hash", length = 64)
    private String requestHash;

    @Column(name = "initiated_by")
    private UUID initiatedBy;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public PaymentInstruction() {
        this.id = UUID.randomUUID();
        this.status = InstructionStatus.CREATED;
        this.currency = "INR";
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public PaymentInstruction(String instructionReference, UUID dealId, UUID milestoneId,
                              UUID buyerOrganizationId, UUID sellerOrganizationId, BigDecimal amount,
                              String currency, String provider, String idempotencyKey, UUID initiatedBy) {
        this();
        this.instructionReference = instructionReference;
        this.dealId = dealId;
        this.milestoneId = milestoneId;
        this.buyerOrganizationId = buyerOrganizationId;
        this.sellerOrganizationId = sellerOrganizationId;
        this.amount = amount != null ? amount.setScale(4) : BigDecimal.ZERO.setScale(4);
        if (currency != null) this.currency = currency;
        this.provider = provider;
        this.idempotencyKey = idempotencyKey;
        this.initiatedBy = initiatedBy;
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getInstructionReference() { return instructionReference; }
    public void setInstructionReference(String instructionReference) { this.instructionReference = instructionReference; }
    public UUID getDealId() { return dealId; }
    public void setDealId(UUID dealId) { this.dealId = dealId; }
    public UUID getMilestoneId() { return milestoneId; }
    public void setMilestoneId(UUID milestoneId) { this.milestoneId = milestoneId; }
    public UUID getBuyerOrganizationId() { return buyerOrganizationId; }
    public void setBuyerOrganizationId(UUID buyerOrganizationId) { this.buyerOrganizationId = buyerOrganizationId; }
    public UUID getSellerOrganizationId() { return sellerOrganizationId; }
    public void setSellerOrganizationId(UUID sellerOrganizationId) { this.sellerOrganizationId = sellerOrganizationId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount != null ? amount.setScale(4) : BigDecimal.ZERO.setScale(4); }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getProviderInstructionId() { return providerInstructionId; }
    public void setProviderInstructionId(String providerInstructionId) { this.providerInstructionId = providerInstructionId; }
    public InstructionStatus getStatus() { return status; }
    public void setStatus(InstructionStatus status) { this.status = status; }
    public String getVirtualAccountNumber() { return virtualAccountNumber; }
    public void setVirtualAccountNumber(String virtualAccountNumber) { this.virtualAccountNumber = virtualAccountNumber; }
    public String getVirtualIfsc() { return virtualIfsc; }
    public void setVirtualIfsc(String virtualIfsc) { this.virtualIfsc = virtualIfsc; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String requestHash) { this.requestHash = requestHash; }
    public UUID getInitiatedBy() { return initiatedBy; }
    public void setInitiatedBy(UUID initiatedBy) { this.initiatedBy = initiatedBy; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(Instant confirmedAt) { this.confirmedAt = confirmedAt; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
