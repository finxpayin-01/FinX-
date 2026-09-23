package com.finx.verification.entity;

import com.finx.verification.enums.VerificationStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "kyc_profiles")
public class KycProfile {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "pan_number_masked")
    private String panNumberMasked;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VerificationStatus status;

    @Column(name = "id_document_type")
    private String idDocumentType;

    @Column(name = "id_document_ref")
    private String idDocumentRef;

    @Column(name = "verification_reference")
    private String verificationReference;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public KycProfile() {
        this.id = UUID.randomUUID();
        this.status = VerificationStatus.NOT_STARTED;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public KycProfile(UUID userId, String fullName, String panNumberMasked) {
        this();
        this.userId = userId;
        this.fullName = fullName;
        this.panNumberMasked = panNumberMasked;
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getPanNumberMasked() { return panNumberMasked; }
    public void setPanNumberMasked(String panNumberMasked) { this.panNumberMasked = panNumberMasked; }
    public VerificationStatus getStatus() { return status; }
    public void setStatus(VerificationStatus status) { this.status = status; }
    public String getIdDocumentType() { return idDocumentType; }
    public void setIdDocumentType(String idDocumentType) { this.idDocumentType = idDocumentType; }
    public String getIdDocumentRef() { return idDocumentRef; }
    public void setIdDocumentRef(String idDocumentRef) { this.idDocumentRef = idDocumentRef; }
    public String getVerificationReference() { return verificationReference; }
    public void setVerificationReference(String verificationReference) { this.verificationReference = verificationReference; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Instant verifiedAt) { this.verifiedAt = verifiedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
