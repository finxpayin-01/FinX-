package com.finx.verification.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "verification_events")
public class VerificationEvent {

    @Id
    private UUID id;

    @Column(name = "subject_type", nullable = false)
    private String subjectType; // USER, ORGANIZATION, BANK_ACCOUNT, REPRESENTATIVE

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(name = "verification_type", nullable = false)
    private String verificationType; // PAN, GST, CIN, BANK, IDENTITY, KYB_OVERALL, KYC_OVERALL

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "provider_reference")
    private String providerReference;

    @Column(name = "old_status")
    private String oldStatus;

    @Column(name = "new_status", nullable = false)
    private String newStatus;

    @Column(name = "reason")
    private String reason;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "safe_metadata_json")
    private String safeMetadataJson;

    @Column(name = "timestamp", nullable = false, updatable = false)
    private Instant timestamp;

    public VerificationEvent() {
        this.id = UUID.randomUUID();
        this.timestamp = Instant.now();
    }

    public VerificationEvent(String subjectType, UUID subjectId, String verificationType, String provider,
                             String providerReference, String oldStatus, String newStatus, String reason,
                             UUID actorId, String safeMetadataJson) {
        this.id = UUID.randomUUID();
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.verificationType = verificationType;
        this.provider = provider;
        this.providerReference = providerReference;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
        this.reason = reason;
        this.actorId = actorId;
        this.safeMetadataJson = safeMetadataJson;
        this.timestamp = Instant.now();
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getSubjectType() { return subjectType; }
    public void setSubjectType(String subjectType) { this.subjectType = subjectType; }

    public UUID getSubjectId() { return subjectId; }
    public void setSubjectId(UUID subjectId) { this.subjectId = subjectId; }

    public String getVerificationType() { return verificationType; }
    public void setVerificationType(String verificationType) { this.verificationType = verificationType; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getProviderReference() { return providerReference; }
    public void setProviderReference(String providerReference) { this.providerReference = providerReference; }

    public String getOldStatus() { return oldStatus; }
    public void setOldStatus(String oldStatus) { this.oldStatus = oldStatus; }

    public String getNewStatus() { return newStatus; }
    public void setNewStatus(String newStatus) { this.newStatus = newStatus; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public UUID getActorId() { return actorId; }
    public void setActorId(UUID actorId) { this.actorId = actorId; }

    public String getSafeMetadataJson() { return safeMetadataJson; }
    public void setSafeMetadataJson(String safeMetadataJson) { this.safeMetadataJson = safeMetadataJson; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
}
