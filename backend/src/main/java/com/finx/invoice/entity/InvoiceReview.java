package com.finx.invoice.entity;

import com.finx.invoice.enums.InvoiceReviewStatus;
import com.finx.invoice.enums.ReviewDecision;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "invoice_reviews", indexes = {
        @Index(name = "idx_invoice_reviews_invoice_id", columnList = "invoice_id"),
        @Index(name = "idx_invoice_reviews_status", columnList = "status")
})
public class InvoiceReview {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @Column(name = "reviewer_id")
    private UUID reviewerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private InvoiceReviewStatus status = InvoiceReviewStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", length = 50)
    private ReviewDecision decision;

    @Column(name = "decision_reason", length = 100)
    private String decisionReason;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "evidence_references", columnDefinition = "TEXT")
    private String evidenceReferences;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name="assessment_id")
    private UUID assessmentId;
    public UUID getAssessmentId() { return assessmentId; }
    public void setAssessmentId(UUID value) { assessmentId = value; }

    public InvoiceReview() {
    }

    public InvoiceReview(Invoice invoice, String notes) {
        this.invoice = invoice;
        this.notes = notes;
        this.status = InvoiceReviewStatus.OPEN;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Invoice getInvoice() {
        return invoice;
    }

    public void setInvoice(Invoice invoice) {
        this.invoice = invoice;
    }

    public UUID getReviewerId() {
        return reviewerId;
    }

    public void setReviewerId(UUID reviewerId) {
        this.reviewerId = reviewerId;
    }

    public InvoiceReviewStatus getStatus() {
        return status;
    }

    public void setStatus(InvoiceReviewStatus status) {
        this.status = status;
    }

    public ReviewDecision getDecision() {
        return decision;
    }

    public void setDecision(ReviewDecision decision) {
        this.decision = decision;
    }

    public String getDecisionReason() {
        return decisionReason;
    }

    public void setDecisionReason(String decisionReason) {
        this.decisionReason = decisionReason;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getEvidenceReferences() {
        return evidenceReferences;
    }

    public void setEvidenceReferences(String evidenceReferences) {
        this.evidenceReferences = evidenceReferences;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
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
}
