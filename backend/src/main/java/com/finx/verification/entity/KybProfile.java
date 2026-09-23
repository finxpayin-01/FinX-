package com.finx.verification.entity;

import com.finx.verification.enums.VerificationStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "kyb_profiles")
public class KybProfile {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, unique = true)
    private UUID organizationId;

    @Column(name = "business_pan_masked")
    private String businessPanMasked;

    @Column(name = "gstin")
    private String gstin;

    @Column(name = "cin_or_llpin")
    private String cinOrLlpin;

    @Column(name = "udyam_number")
    private String udyamNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VerificationStatus status;

    @Column(name = "verification_reference")
    private String verificationReference;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public KybProfile() {
        this.id = UUID.randomUUID();
        this.status = VerificationStatus.NOT_STARTED;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public KybProfile(UUID organizationId, String businessPanMasked, String gstin, String cinOrLlpin, String udyamNumber) {
        this();
        this.organizationId = organizationId;
        this.businessPanMasked = businessPanMasked;
        this.gstin = gstin;
        this.cinOrLlpin = cinOrLlpin;
        this.udyamNumber = udyamNumber;
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }
    public String getBusinessPanMasked() { return businessPanMasked; }
    public void setBusinessPanMasked(String businessPanMasked) { this.businessPanMasked = businessPanMasked; }
    public String getGstin() { return gstin; }
    public void setGstin(String gstin) { this.gstin = gstin; }
    public String getCinOrLlpin() { return cinOrLlpin; }
    public void setCinOrLlpin(String cinOrLlpin) { this.cinOrLlpin = cinOrLlpin; }
    public String getUdyamNumber() { return udyamNumber; }
    public void setUdyamNumber(String udyamNumber) { this.udyamNumber = udyamNumber; }
    public VerificationStatus getStatus() { return status; }
    public void setStatus(VerificationStatus status) { this.status = status; }
    public String getVerificationReference() { return verificationReference; }
    public void setVerificationReference(String verificationReference) { this.verificationReference = verificationReference; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Instant verifiedAt) { this.verifiedAt = verifiedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
