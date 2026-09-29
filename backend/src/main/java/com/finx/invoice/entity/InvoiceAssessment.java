package com.finx.invoice.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import java.time.Instant;
import java.util.UUID;

/** Append-only automated evidence; human decisions never modify this record. */
@Entity @Immutable
@Table(name = "invoice_assessments")
public class InvoiceAssessment {
    @Id public UUID id = UUID.randomUUID();
    @Column(nullable=false, updatable=false) public UUID invoiceId;
    @Column(nullable=false, updatable=false) public String fingerprint;
    @Column(nullable=false, updatable=false, columnDefinition="text") public String resultJson;
    @Column(nullable=false, updatable=false, columnDefinition="text") public String policyJson;
    @Column(nullable=false, updatable=false) public Instant createdAt = Instant.now();
}
