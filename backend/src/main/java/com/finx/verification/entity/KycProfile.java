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

    @Column(name = "provider")
    private String provider;

    @Column(name = "provider_reference_id")
    private String providerReferenceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "pan_status", nullable = false)
    private VerificationStatus panStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "identity_status", nullable = false)
    private VerificationStatus identityStatus;

    @Column(name = "name_match_status")
    private String nameMatchStatus;

    @Column(name = "name_match_score")
    private Double nameMatchScore;

    @Column(name = "date_of_birth_match_status")
    private String dateOfBirthMatchStatus;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "id_document_type")
    private String idDocumentType;

    @Column(name = "id_document_ref")
    private String idDocumentRef;

    @Column(name = "verification_reference")
    private String verificationReference;

    @Column(name = "verification_started_at")
    private Instant verificationStartedAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public KycProfile() {
        this.id = UUID.randomUUID();
        this.status = VerificationStatus.NOT_STARTED;
        this.panStatus = VerificationStatus.NOT_STARTED;
        this.identityStatus = VerificationStatus.NOT_STARTED;
        this.nameMatchStatus = "NOT_STARTED";
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

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getProviderReferenceId() { return providerReferenceId; }
    public void setProviderReferenceId(String providerReferenceId) { this.providerReferenceId = providerReferenceId; }

    public VerificationStatus getPanStatus() { return panStatus; }
    public void setPanStatus(VerificationStatus panStatus) { this.panStatus = panStatus; }

    public VerificationStatus getIdentityStatus() { return identityStatus; }
    public void setIdentityStatus(VerificationStatus identityStatus) { this.identityStatus = identityStatus; }

    public String getNameMatchStatus() { return nameMatchStatus; }
    public void setNameMatchStatus(String nameMatchStatus) { this.nameMatchStatus = nameMatchStatus; }

    public Double getNameMatchScore() { return nameMatchScore; }
    public void setNameMatchScore(Double nameMatchScore) { this.nameMatchScore = nameMatchScore; }

    public String getDateOfBirthMatchStatus() { return dateOfBirthMatchStatus; }
    public void setDateOfBirthMatchStatus(String dateOfBirthMatchStatus) { this.dateOfBirthMatchStatus = dateOfBirthMatchStatus; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public String getIdDocumentType() { return idDocumentType; }
    public void setIdDocumentType(String idDocumentType) { this.idDocumentType = idDocumentType; }

    public String getIdDocumentRef() { return idDocumentRef; }
    public void setIdDocumentRef(String idDocumentRef) { this.idDocumentRef = idDocumentRef; }

    public String getVerificationReference() { return verificationReference; }
    public void setVerificationReference(String verificationReference) { this.verificationReference = verificationReference; }

    public Instant getVerificationStartedAt() { return verificationStartedAt; }
    public void setVerificationStartedAt(Instant verificationStartedAt) { this.verificationStartedAt = verificationStartedAt; }

    public Instant getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Instant verifiedAt) { this.verifiedAt = verifiedAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
