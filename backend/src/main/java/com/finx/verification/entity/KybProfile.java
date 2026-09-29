package com.finx.verification.entity;

import com.finx.verification.enums.VerificationStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
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

    @Column(name = "trade_name")
    private String tradeName;

    @Column(name = "incorporation_date")
    private LocalDate incorporationDate;

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
    @Column(name = "gst_status", nullable = false)
    private VerificationStatus gstStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "registration_status", nullable = false)
    private VerificationStatus registrationStatus;

    @Column(name = "business_status")
    private String businessStatus;

    @Column(name = "address_status")
    private String addressStatus;

    @Column(name = "name_match_status")
    private String nameMatchStatus;

    @Column(name = "name_match_score")
    private Double nameMatchScore;

    @Column(name = "rejection_reason")
    private String rejectionReason;

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

    public KybProfile() {
        this.id = UUID.randomUUID();
        this.status = VerificationStatus.NOT_STARTED;
        this.panStatus = VerificationStatus.NOT_STARTED;
        this.gstStatus = VerificationStatus.NOT_STARTED;
        this.registrationStatus = VerificationStatus.NOT_STARTED;
        this.nameMatchStatus = "NOT_STARTED";
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

    public String getTradeName() { return tradeName; }
    public void setTradeName(String tradeName) { this.tradeName = tradeName; }

    public LocalDate getIncorporationDate() { return incorporationDate; }
    public void setIncorporationDate(LocalDate incorporationDate) { this.incorporationDate = incorporationDate; }

    public VerificationStatus getStatus() { return status; }
    public void setStatus(VerificationStatus status) { this.status = status; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getProviderReferenceId() { return providerReferenceId; }
    public void setProviderReferenceId(String providerReferenceId) { this.providerReferenceId = providerReferenceId; }

    public VerificationStatus getPanStatus() { return panStatus; }
    public void setPanStatus(VerificationStatus panStatus) { this.panStatus = panStatus; }

    public VerificationStatus getGstStatus() { return gstStatus; }
    public void setGstStatus(VerificationStatus gstStatus) { this.gstStatus = gstStatus; }

    public VerificationStatus getRegistrationStatus() { return registrationStatus; }
    public void setRegistrationStatus(VerificationStatus registrationStatus) { this.registrationStatus = registrationStatus; }

    public String getBusinessStatus() { return businessStatus; }
    public void setBusinessStatus(String businessStatus) { this.businessStatus = businessStatus; }

    public String getAddressStatus() { return addressStatus; }
    public void setAddressStatus(String addressStatus) { this.addressStatus = addressStatus; }

    public String getNameMatchStatus() { return nameMatchStatus; }
    public void setNameMatchStatus(String nameMatchStatus) { this.nameMatchStatus = nameMatchStatus; }

    public Double getNameMatchScore() { return nameMatchScore; }
    public void setNameMatchScore(Double nameMatchScore) { this.nameMatchScore = nameMatchScore; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

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
