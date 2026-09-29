package com.finx.verification.entity;

import com.finx.verification.enums.ConsentType;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "verification_consents")
public class VerificationConsent {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_type", nullable = false)
    private ConsentType verificationType;

    @Column(name = "consent_version", nullable = false)
    private String consentVersion;

    @Column(name = "purpose", nullable = false)
    private String purpose;

    @Column(name = "consented_at", nullable = false)
    private Instant consentedAt;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "user_agent")
    private String userAgent;

    public VerificationConsent() {
        this.id = UUID.randomUUID();
        this.consentedAt = Instant.now();
        this.consentVersion = "1.0";
    }

    public VerificationConsent(UUID userId, UUID organizationId, ConsentType verificationType, String consentVersion, String purpose, String ipAddress, String userAgent) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.organizationId = organizationId;
        this.verificationType = verificationType;
        this.consentVersion = consentVersion != null ? consentVersion : "1.0";
        this.purpose = purpose;
        this.consentedAt = Instant.now();
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }

    public ConsentType getVerificationType() { return verificationType; }
    public void setVerificationType(ConsentType verificationType) { this.verificationType = verificationType; }

    public String getConsentVersion() { return consentVersion; }
    public void setConsentVersion(String consentVersion) { this.consentVersion = consentVersion; }

    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }

    public Instant getConsentedAt() { return consentedAt; }
    public void setConsentedAt(Instant consentedAt) { this.consentedAt = consentedAt; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
}
