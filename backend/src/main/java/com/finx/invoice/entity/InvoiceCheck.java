package com.finx.invoice.entity;

import com.finx.invoice.enums.CheckStatus;
import com.finx.invoice.enums.InvoiceCheckType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "invoice_checks", indexes = {
        @Index(name = "idx_invoice_checks_invoice_id", columnList = "invoice_id"),
        @Index(name = "idx_invoice_checks_type", columnList = "check_type")
})
public class InvoiceCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @Enumerated(EnumType.STRING)
    @Column(name = "check_type", nullable = false, length = 50)
    private InvoiceCheckType checkType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CheckStatus status;

    @Column(name = "reason_code", nullable = false, length = 100)
    private String reasonCode;

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "weight", nullable = false)
    private int weight;

    @Column(name = "score_earned", nullable = false)
    private int scoreEarned;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public InvoiceCheck() {
    }

    public InvoiceCheck(Invoice invoice, InvoiceCheckType checkType, CheckStatus status,
                        String reasonCode, String message, int weight, int scoreEarned, String details) {
        this.invoice = invoice;
        this.checkType = checkType;
        this.status = status;
        this.reasonCode = reasonCode;
        this.message = message;
        this.weight = weight;
        this.scoreEarned = scoreEarned;
        this.details = details;
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

    public InvoiceCheckType getCheckType() {
        return checkType;
    }

    public void setCheckType(InvoiceCheckType checkType) {
        this.checkType = checkType;
    }

    public CheckStatus getStatus() {
        return status;
    }

    public void setStatus(CheckStatus status) {
        this.status = status;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public int getWeight() {
        return weight;
    }

    public void setWeight(int weight) {
        this.weight = weight;
    }

    public int getScoreEarned() {
        return scoreEarned;
    }

    public void setScoreEarned(int scoreEarned) {
        this.scoreEarned = scoreEarned;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
