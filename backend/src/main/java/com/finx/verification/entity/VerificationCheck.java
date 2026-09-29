package com.finx.verification.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "verification_checks")
public class VerificationCheck {

    @Id
    private UUID id;

    @Column(name = "entity_type", nullable = false)
    private String entityType; // KYC, KYB, BANK_ACCOUNT

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "check_type", nullable = false)
    private String checkType; // PAN, GSTIN, CIN, LLPIN, UDYAM, BANK, IDENTITY

    @Column(name = "status", nullable = false)
    private String status; // VERIFIED, FAILED, REVIEW, NOT_SUPPORTED

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "provider_reference")
    private String providerReference;

    @Column(name = "raw_code")
    private String rawCode;

    @Column(name = "message")
    private String message;

    @Column(name = "name_match_status")
    private String nameMatchStatus;

    @Column(name = "name_match_score")
    private Double nameMatchScore;

    @Column(name = "checked_at", nullable = false, updatable = false)
    private Instant checkedAt;

    public VerificationCheck() {
        this.id = UUID.randomUUID();
        this.checkedAt = Instant.now();
    }

    public VerificationCheck(String entityType, UUID entityId, String checkType, String status,
                             String provider, String providerReference, String rawCode, String message,
                             String nameMatchStatus, Double nameMatchScore) {
        this.id = UUID.randomUUID();
        this.entityType = entityType;
        this.entityId = entityId;
        this.checkType = checkType;
        this.status = status;
        this.provider = provider;
        this.providerReference = providerReference;
        this.rawCode = rawCode;
        this.message = message;
        this.nameMatchStatus = nameMatchStatus;
        this.nameMatchScore = nameMatchScore;
        this.checkedAt = Instant.now();
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }

    public UUID getEntityId() { return entityId; }
    public void setEntityId(UUID entityId) { this.entityId = entityId; }

    public String getCheckType() { return checkType; }
    public void setCheckType(String checkType) { this.checkType = checkType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getProviderReference() { return providerReference; }
    public void setProviderReference(String providerReference) { this.providerReference = providerReference; }

    public String getRawCode() { return rawCode; }
    public void setRawCode(String rawCode) { this.rawCode = rawCode; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getNameMatchStatus() { return nameMatchStatus; }
    public void setNameMatchStatus(String nameMatchStatus) { this.nameMatchStatus = nameMatchStatus; }

    public Double getNameMatchScore() { return nameMatchScore; }
    public void setNameMatchScore(Double nameMatchScore) { this.nameMatchScore = nameMatchScore; }

    public Instant getCheckedAt() { return checkedAt; }
    public void setCheckedAt(Instant checkedAt) { this.checkedAt = checkedAt; }
}
