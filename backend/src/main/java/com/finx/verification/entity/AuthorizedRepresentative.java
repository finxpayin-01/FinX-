package com.finx.verification.entity;

import com.finx.verification.enums.AuthorityRelationshipType;
import com.finx.verification.enums.AuthorityStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "authorized_representatives", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"organization_id", "user_id", "relationship_type"})
})
public class AuthorizedRepresentative {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "relationship_type", nullable = false)
    private AuthorityRelationshipType relationshipType;

    @Enumerated(EnumType.STRING)
    @Column(name = "authority_status", nullable = false)
    private AuthorityStatus authorityStatus;

    @Column(name = "authorization_document_ref")
    private String authorizationDocumentRef;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "verified_by")
    private UUID verifiedBy;

    @Column(name = "notes")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public AuthorizedRepresentative() {
        this.id = UUID.randomUUID();
        this.authorityStatus = AuthorityStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public AuthorizedRepresentative(UUID organizationId, UUID userId, AuthorityRelationshipType relationshipType) {
        this();
        this.organizationId = organizationId;
        this.userId = userId;
        this.relationshipType = relationshipType;
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

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public AuthorityRelationshipType getRelationshipType() { return relationshipType; }
    public void setRelationshipType(AuthorityRelationshipType relationshipType) { this.relationshipType = relationshipType; }

    public AuthorityStatus getAuthorityStatus() { return authorityStatus; }
    public void setAuthorityStatus(AuthorityStatus authorityStatus) { this.authorityStatus = authorityStatus; }

    public String getAuthorizationDocumentRef() { return authorizationDocumentRef; }
    public void setAuthorizationDocumentRef(String authorizationDocumentRef) { this.authorizationDocumentRef = authorizationDocumentRef; }

    public Instant getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Instant verifiedAt) { this.verifiedAt = verifiedAt; }

    public UUID getVerifiedBy() { return verifiedBy; }
    public void setVerifiedBy(UUID verifiedBy) { this.verifiedBy = verifiedBy; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
