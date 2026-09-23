package com.finx.org.entity;

import com.finx.org.enums.OrgEnums.EntityType;
import com.finx.org.enums.OrgEnums.OrgVerificationStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organizations")
public class Organization {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "legal_name", nullable = false)
    private String legalName;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false)
    private EntityType entityType;

    @Column(name = "registration_number")
    private String registrationNumber;

    @Column(name = "tax_id")
    private String taxId;

    @Column(name = "gstin")
    private String gstin;

    @Column(name = "registered_address")
    private String registeredAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false)
    private OrgVerificationStatus verificationStatus;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Organization() {
        this.id = UUID.randomUUID();
        this.verificationStatus = OrgVerificationStatus.NOT_STARTED;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public Organization(String name, String legalName, EntityType entityType, String registrationNumber,
                        String taxId, String gstin, String registeredAddress, UUID createdBy) {
        this();
        this.name = name;
        this.legalName = legalName;
        this.entityType = entityType;
        this.registrationNumber = registrationNumber;
        this.taxId = taxId;
        this.gstin = gstin;
        this.registeredAddress = registeredAddress;
        this.createdBy = createdBy;
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getLegalName() { return legalName; }
    public void setLegalName(String legalName) { this.legalName = legalName; }
    public EntityType getEntityType() { return entityType; }
    public void setEntityType(EntityType entityType) { this.entityType = entityType; }
    public String getRegistrationNumber() { return registrationNumber; }
    public void setRegistrationNumber(String registrationNumber) { this.registrationNumber = registrationNumber; }
    public String getTaxId() { return taxId; }
    public void setTaxId(String taxId) { this.taxId = taxId; }
    public String getGstin() { return gstin; }
    public void setGstin(String gstin) { this.gstin = gstin; }
    public String getRegisteredAddress() { return registeredAddress; }
    public void setRegisteredAddress(String registeredAddress) { this.registeredAddress = registeredAddress; }
    public OrgVerificationStatus getVerificationStatus() { return verificationStatus; }
    public void setVerificationStatus(OrgVerificationStatus verificationStatus) { this.verificationStatus = verificationStatus; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
