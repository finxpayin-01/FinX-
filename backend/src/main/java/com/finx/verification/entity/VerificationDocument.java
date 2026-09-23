package com.finx.verification.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "verification_documents")
public class VerificationDocument {

    @Id
    private UUID id;

    @Column(name = "entity_type", nullable = false)
    private String entityType; // KYC or KYB

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "document_type", nullable = false)
    private String documentType; // PAN, GST_CERTIFICATE, CIN_CERTIFICATE, BANK_STATEMENT

    @Column(name = "file_reference", nullable = false)
    private String fileReference;

    @Column(name = "file_hash", nullable = false)
    private String fileHash;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public VerificationDocument() {
        this.id = UUID.randomUUID();
        this.status = "PENDING";
        this.createdAt = Instant.now();
    }

    public VerificationDocument(String entityType, UUID entityId, String documentType, String fileReference, String fileHash) {
        this();
        this.entityType = entityType;
        this.entityId = entityId;
        this.documentType = documentType;
        this.fileReference = fileReference;
        this.fileHash = fileHash;
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }
    public UUID getEntityId() { return entityId; }
    public void setEntityId(UUID entityId) { this.entityId = entityId; }
    public String getDocumentType() { return documentType; }
    public void setDocumentType(String documentType) { this.documentType = documentType; }
    public String getFileReference() { return fileReference; }
    public void setFileReference(String fileReference) { this.fileReference = fileReference; }
    public String getFileHash() { return fileHash; }
    public void setFileHash(String fileHash) { this.fileHash = fileHash; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
