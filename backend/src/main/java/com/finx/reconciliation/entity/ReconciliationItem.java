package com.finx.reconciliation.entity;

import com.finx.reconciliation.enums.ReconciliationEnums.ReconciliationItemStatus;
import com.finx.reconciliation.enums.ReconciliationEnums.ResolutionStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_items")
public class ReconciliationItem {

    @Id
    private UUID id;

    @Column(name = "reconciliation_run_id", nullable = false)
    private UUID reconciliationRunId;

    @Column(name = "finx_transaction_reference")
    private String finxTransactionReference;

    @Column(name = "bank_transaction_reference")
    private String bankTransactionReference;

    @Column(name = "finx_amount", precision = 19, scale = 4)
    private BigDecimal finxAmount;

    @Column(name = "bank_amount", precision = 19, scale = 4)
    private BigDecimal bankAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReconciliationItemStatus status;

    @Column(name = "discrepancy_details", columnDefinition = "TEXT")
    private String discrepancyDetails;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_status", nullable = false)
    private ResolutionStatus resolutionStatus;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public ReconciliationItem() {
        this.id = UUID.randomUUID();
        this.resolutionStatus = ResolutionStatus.UNRESOLVED;
        this.createdAt = Instant.now();
    }

    public ReconciliationItem(UUID reconciliationRunId, String finxTransactionReference, String bankTransactionReference,
                              BigDecimal finxAmount, BigDecimal bankAmount, ReconciliationItemStatus status, String discrepancyDetails) {
        this();
        this.reconciliationRunId = reconciliationRunId;
        this.finxTransactionReference = finxTransactionReference;
        this.bankTransactionReference = bankTransactionReference;
        this.finxAmount = finxAmount;
        this.bankAmount = bankAmount;
        this.status = status;
        this.discrepancyDetails = discrepancyDetails;
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getReconciliationRunId() { return reconciliationRunId; }
    public void setReconciliationRunId(UUID reconciliationRunId) { this.reconciliationRunId = reconciliationRunId; }
    public String getFinxTransactionReference() { return finxTransactionReference; }
    public void setFinxTransactionReference(String finxTransactionReference) { this.finxTransactionReference = finxTransactionReference; }
    public String getBankTransactionReference() { return bankTransactionReference; }
    public void setBankTransactionReference(String bankTransactionReference) { this.bankTransactionReference = bankTransactionReference; }
    public BigDecimal getFinxAmount() { return finxAmount; }
    public void setFinxAmount(BigDecimal finxAmount) { this.finxAmount = finxAmount; }
    public BigDecimal getBankAmount() { return bankAmount; }
    public void setBankAmount(BigDecimal bankAmount) { this.bankAmount = bankAmount; }
    public ReconciliationItemStatus getStatus() { return status; }
    public void setStatus(ReconciliationItemStatus status) { this.status = status; }
    public String getDiscrepancyDetails() { return discrepancyDetails; }
    public void setDiscrepancyDetails(String discrepancyDetails) { this.discrepancyDetails = discrepancyDetails; }
    public ResolutionStatus getResolutionStatus() { return resolutionStatus; }
    public void setResolutionStatus(ResolutionStatus resolutionStatus) { this.resolutionStatus = resolutionStatus; }
    public UUID getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(UUID resolvedBy) { this.resolvedBy = resolvedBy; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
