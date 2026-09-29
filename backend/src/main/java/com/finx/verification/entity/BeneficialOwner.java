package com.finx.verification.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "beneficial_owners")
public class BeneficialOwner {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "person_name", nullable = false)
    private String personName;

    @Column(name = "ownership_percentage", nullable = false, precision = 5, scale = 2)
    private BigDecimal ownershipPercentage;

    @Column(name = "relationship_type", nullable = false)
    private String relationshipType;

    @Column(name = "pan_masked")
    private String panMasked;

    @Column(name = "verification_status", nullable = false)
    private String verificationStatus;

    @Column(name = "provider_reference")
    private String providerReference;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public BeneficialOwner() {
        this.id = UUID.randomUUID();
        this.verificationStatus = "PENDING";
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public BeneficialOwner(UUID organizationId, String personName, BigDecimal ownershipPercentage, String relationshipType, String panMasked) {
        this();
        this.organizationId = organizationId;
        this.personName = personName;
        this.ownershipPercentage = ownershipPercentage;
        this.relationshipType = relationshipType;
        this.panMasked = panMasked;
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

    public String getPersonName() { return personName; }
    public void setPersonName(String personName) { this.personName = personName; }

    public BigDecimal getOwnershipPercentage() { return ownershipPercentage; }
    public void setOwnershipPercentage(BigDecimal ownershipPercentage) { this.ownershipPercentage = ownershipPercentage; }

    public String getRelationshipType() { return relationshipType; }
    public void setRelationshipType(String relationshipType) { this.relationshipType = relationshipType; }

    public String getPanMasked() { return panMasked; }
    public void setPanMasked(String panMasked) { this.panMasked = panMasked; }

    public String getVerificationStatus() { return verificationStatus; }
    public void setVerificationStatus(String verificationStatus) { this.verificationStatus = verificationStatus; }

    public String getProviderReference() { return providerReference; }
    public void setProviderReference(String providerReference) { this.providerReference = providerReference; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
