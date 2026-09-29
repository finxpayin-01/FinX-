package com.finx.invoice.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "invoice_extracted_fields", indexes = {
        @Index(name = "idx_extracted_fields_invoice_id", columnList = "invoice_id")
})
public class InvoiceExtractedField {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @Column(name = "field_name", nullable = false, length = 100)
    private String fieldName;

    @Column(name = "raw_value", columnDefinition = "TEXT")
    private String rawValue;

    @Column(name = "normalized_value", columnDefinition = "TEXT")
    private String normalizedValue;

    @Column(name = "confidence", nullable = false, precision = 5, scale = 4)
    private BigDecimal confidence = new BigDecimal("1.0000");

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public InvoiceExtractedField() {
    }

    public InvoiceExtractedField(Invoice invoice, String fieldName, String rawValue, String normalizedValue, BigDecimal confidence) {
        this.invoice = invoice;
        this.fieldName = fieldName;
        this.rawValue = rawValue;
        this.normalizedValue = normalizedValue;
        this.confidence = confidence != null ? confidence : new BigDecimal("1.0000");
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

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getRawValue() {
        return rawValue;
    }

    public void setRawValue(String rawValue) {
        this.rawValue = rawValue;
    }

    public String getNormalizedValue() {
        return normalizedValue;
    }

    public void setNormalizedValue(String normalizedValue) {
        this.normalizedValue = normalizedValue;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public void setConfidence(BigDecimal confidence) {
        this.confidence = confidence;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
