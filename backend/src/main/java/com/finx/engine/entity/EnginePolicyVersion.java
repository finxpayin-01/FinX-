package com.finx.engine.entity;

import com.finx.engine.model.EngineType;
import com.finx.engine.model.PolicyScope;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable policy version archive and identity for engine evaluations.
 * Append-only: updates and deletes are prohibited.
 */
@Entity
@Immutable
@Table(name = "engine_policy_versions")
public class EnginePolicyVersion {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", updatable = false)
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, updatable = false, length = 30)
    private PolicyScope scope;

    @Enumerated(EnumType.STRING)
    @Column(name = "engine_type", nullable = false, updatable = false, length = 50)
    private EngineType engineType;

    @Column(name = "policy_key", nullable = false, updatable = false, length = 100)
    private String policyKey;

    @Column(name = "version", nullable = false, updatable = false, length = 50)
    private String version;

    @Column(name = "content_hash", nullable = false, updatable = false, length = 64)
    private String contentHash;

    @Column(name = "schema_version", nullable = false, updatable = false, length = 20)
    private String schemaVersion = "1.0";

    @Column(name = "description", updatable = false, length = 500)
    private String description;

    @Column(name = "policy_content", updatable = false, columnDefinition = "TEXT")
    private String policyContent;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "activated_at", updatable = false)
    private Instant activatedAt;

    public EnginePolicyVersion() {
        this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
        this.schemaVersion = "1.0";
    }

    public EnginePolicyVersion(UUID organizationId, PolicyScope scope, EngineType engineType,
                               String policyKey, String version, String contentHash,
                               String schemaVersion, String description, String policyContent,
                               UUID createdBy, Instant activatedAt) {
        this();
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        if (scope == PolicyScope.PLATFORM && organizationId != null) {
            throw new IllegalArgumentException("Platform-scoped policy must have null organizationId");
        }
        if (scope == PolicyScope.ORGANIZATION && organizationId == null) {
            throw new IllegalArgumentException("Organization-scoped policy must have non-null organizationId");
        }
        if (engineType == null) {
            throw new IllegalArgumentException("engineType must not be null");
        }
        if (policyKey == null || policyKey.isBlank()) {
            throw new IllegalArgumentException("policyKey must not be null or blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be null or blank");
        }
        if (contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("contentHash must not be null or blank");
        }

        this.organizationId = organizationId;
        this.scope = scope;
        this.engineType = engineType;
        this.policyKey = policyKey.trim();
        this.version = version.trim();
        this.contentHash = contentHash.trim();
        this.schemaVersion = (schemaVersion != null && !schemaVersion.isBlank()) ? schemaVersion.trim() : "1.0";
        this.description = description;
        this.policyContent = policyContent;
        this.createdBy = createdBy;
        this.activatedAt = activatedAt;
    }

    public static EnginePolicyVersion platform(EngineType engineType, String policyKey, String version, String contentHash) {
        return new EnginePolicyVersion(null, PolicyScope.PLATFORM, engineType, policyKey, version, contentHash, "1.0", null, null, null, Instant.now());
    }

    public static EnginePolicyVersion organization(UUID organizationId, EngineType engineType, String policyKey, String version, String contentHash) {
        return new EnginePolicyVersion(organizationId, PolicyScope.ORGANIZATION, engineType, policyKey, version, contentHash, "1.0", null, null, null, Instant.now());
    }

    // Getters
    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public PolicyScope getScope() { return scope; }
    public EngineType getEngineType() { return engineType; }
    public String getPolicyKey() { return policyKey; }
    public String getVersion() { return version; }
    public String getContentHash() { return contentHash; }
    public String getSchemaVersion() { return schemaVersion; }
    public String getDescription() { return description; }
    public String getPolicyContent() { return policyContent; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getActivatedAt() { return activatedAt; }
}
