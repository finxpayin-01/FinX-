package com.finx.invoice.entity;

import jakarta.persistence.*;
import java.util.UUID;

/** Private transactional storage. No public file URL or caller-selected storage key. */
@Entity
@Table(name = "invoice_documents")
public class InvoiceDocument {
    @Id public UUID invoiceId;
    @Column(nullable = false, columnDefinition = "bytea") public byte[] content;
    protected InvoiceDocument() {}
    public InvoiceDocument(UUID invoiceId, byte[] content) { this.invoiceId = invoiceId; this.content = content; }
}
