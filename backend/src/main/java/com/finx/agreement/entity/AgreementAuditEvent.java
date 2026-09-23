package com.finx.agreement.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agreement_audit_events")
public class AgreementAuditEvent {

    @Id
    private UUID id;

    @Column(name = "agreement_id", nullable = false)
    private UUID agreementId;

    @Column(nullable = false)
    private String action;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(nullable = false, updatable = false)
    private Instant timestamp;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    public AgreementAuditEvent() {
        this.id = UUID.randomUUID();
        this.timestamp = Instant.now();
    }

    public AgreementAuditEvent(UUID agreementId, String action, UUID actorId, String ipAddress, String userAgent, String metadataJson) {
        this();
        this.agreementId = agreementId;
        this.action = action;
        this.actorId = actorId;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.metadataJson = metadataJson;
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getAgreementId() { return agreementId; }
    public void setAgreementId(UUID agreementId) { this.agreementId = agreementId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public UUID getActorId() { return actorId; }
    public void setActorId(UUID actorId) { this.actorId = actorId; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
}
