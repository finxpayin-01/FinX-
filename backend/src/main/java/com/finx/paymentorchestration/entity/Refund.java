package com.finx.paymentorchestration.entity;

import com.finx.paymentorchestration.enums.PaymentOrchestrationEnums.RefundStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refunds")
public class Refund {

    @Id
    private UUID id;

    @Column(name = "refund_reference", nullable = false, unique = true)
    private String refundReference;

    @Column(name = "deal_id", nullable = false)
    private UUID dealId;

    @Column(name = "milestone_id")
    private UUID milestoneId;

    @Column(name = "payment_instruction_id")
    private UUID paymentInstructionId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RefundStatus status;

    @Column(name = "authorized_by")
    private UUID authorizedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Refund() {
        this.id = UUID.randomUUID();
        this.status = RefundStatus.REQUESTED;
        this.currency = "INR";
        this.createdAt = Instant.now();
    }

    public Refund(String refundReference, UUID dealId, UUID milestoneId, UUID paymentInstructionId,
                  BigDecimal amount, String currency, String reason, UUID authorizedBy) {
        this();
        this.refundReference = refundReference;
        this.dealId = dealId;
        this.milestoneId = milestoneId;
        this.paymentInstructionId = paymentInstructionId;
        this.amount = amount != null ? amount.setScale(4) : BigDecimal.ZERO.setScale(4);
        if (currency != null) this.currency = currency;
        this.reason = reason;
        this.authorizedBy = authorizedBy;
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getRefundReference() { return refundReference; }
    public void setRefundReference(String refundReference) { this.refundReference = refundReference; }
    public UUID getDealId() { return dealId; }
    public void setDealId(UUID dealId) { this.dealId = dealId; }
    public UUID getMilestoneId() { return milestoneId; }
    public void setMilestoneId(UUID milestoneId) { this.milestoneId = milestoneId; }
    public UUID getPaymentInstructionId() { return paymentInstructionId; }
    public void setPaymentInstructionId(UUID paymentInstructionId) { this.paymentInstructionId = paymentInstructionId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount != null ? amount.setScale(4) : BigDecimal.ZERO.setScale(4); }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public RefundStatus getStatus() { return status; }
    public void setStatus(RefundStatus status) { this.status = status; }
    public UUID getAuthorizedBy() { return authorizedBy; }
    public void setAuthorizedBy(UUID authorizedBy) { this.authorizedBy = authorizedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
