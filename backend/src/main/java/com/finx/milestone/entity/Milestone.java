package com.finx.milestone.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "milestones", indexes = {
        @Index(name = "idx_milestones_deal_id", columnList = "deal_id"),
        @Index(name = "idx_milestones_status", columnList = "status"),
        @Index(name = "idx_milestones_sequence", columnList = "deal_id, sequence")
})
public class Milestone {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "deal_id", nullable = false)
    private UUID dealId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "sequence", nullable = false)
    private int sequence = 1;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 10)
    private String currency = "INR";

    @Column(name = "due_date")
    private Instant dueDate;

    @Column(name = "submission_deadline")
    private Instant submissionDeadline;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private MilestoneStatus status = MilestoneStatus.PENDING;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private List<Deliverable> deliverables = new ArrayList<>();

    @Column(name="invoice_required", nullable=false)
    private boolean invoiceRequired;
    public boolean isInvoiceRequired() { return invoiceRequired; }
    public void setInvoiceRequired(boolean value) { invoiceRequired=value; }

    public Milestone() {
    }

    public Milestone(UUID dealId, String title, String description, int sequence, BigDecimal amount, String currency, Instant dueDate) {
        this(dealId, title, description, sequence, amount, currency, dueDate, dueDate);
    }

    public Milestone(UUID dealId, String title, String description, int sequence, BigDecimal amount, String currency, Instant dueDate, Instant submissionDeadline) {
        this.dealId = dealId;
        this.title = title;
        this.description = description;
        this.sequence = sequence;
        this.amount = amount;
        this.currency = (currency != null && !currency.isBlank()) ? currency.toUpperCase() : "INR";
        this.dueDate = dueDate;
        this.submissionDeadline = (submissionDeadline != null) ? submissionDeadline : dueDate;
        this.status = MilestoneStatus.PENDING;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getDealId() {
        return dealId;
    }

    public void setDealId(UUID dealId) {
        this.dealId = dealId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getSequence() {
        return sequence;
    }

    public void setSequence(int sequence) {
        this.sequence = sequence;
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

    public Instant getDueDate() {
        return dueDate;
    }

    public void setDueDate(Instant dueDate) {
        this.dueDate = dueDate;
    }

    public Instant getSubmissionDeadline() {
        return submissionDeadline != null ? submissionDeadline : dueDate;
    }

    public void setSubmissionDeadline(Instant submissionDeadline) {
        this.submissionDeadline = submissionDeadline;
    }

    public MilestoneStatus getStatus() {
        return status;
    }

    public void setStatus(MilestoneStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<Deliverable> getDeliverables() {
        return deliverables;
    }

    public void setDeliverables(List<Deliverable> deliverables) {
        this.deliverables = deliverables;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Milestone milestone)) return false;
        return Objects.equals(id, milestone.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
