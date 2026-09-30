package com.finx.payment.entity;

import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "webhook_events", uniqueConstraints = {
    @UniqueConstraint(name = "uq_provider_event", columnNames = {"provider", "provider_event_id"})
})
public class WebhookEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private String provider;
    @Column(name = "provider_event_id")
    private String providerEventId;
    @Column(name = "event_type")
    private String eventType;
    @Column(name = "payload_hash")
    private String payloadHash;
    @Column(name = "processing_status")
    private String processingStatus = "RECEIVED";
    @CreationTimestamp
    @Column(name = "received_at", updatable = false)
    private Instant receivedAt;
    @Column(name = "processed_at")
    private Instant processedAt;
    
    // Getters / Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getProviderEventId() { return providerEventId; }
    public void setProviderEventId(String providerEventId) { this.providerEventId = providerEventId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }
    public String getProcessingStatus() { return processingStatus; }
    public void setProcessingStatus(String processingStatus) { this.processingStatus = processingStatus; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
}
