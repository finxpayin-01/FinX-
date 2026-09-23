package com.finx.risk.entity;

import com.finx.risk.enums.RiskEnums.RiskLevel;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "risk_signals")
public class RiskSignal {

    @Id
    private UUID id;

    @Column(name = "risk_assessment_id", nullable = false)
    private UUID riskAssessmentId;

    @Column(name = "signal_type", nullable = false)
    private String signalType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RiskLevel severity;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public RiskSignal() {
        this.id = UUID.randomUUID();
        this.severity = RiskLevel.LOW;
        this.createdAt = Instant.now();
    }

    public RiskSignal(UUID riskAssessmentId, String signalType, RiskLevel severity, String details) {
        this();
        this.riskAssessmentId = riskAssessmentId;
        this.signalType = signalType;
        this.severity = severity;
        this.details = details;
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getRiskAssessmentId() { return riskAssessmentId; }
    public void setRiskAssessmentId(UUID riskAssessmentId) { this.riskAssessmentId = riskAssessmentId; }
    public String getSignalType() { return signalType; }
    public void setSignalType(String signalType) { this.signalType = signalType; }
    public RiskLevel getSeverity() { return severity; }
    public void setSeverity(RiskLevel severity) { this.severity = severity; }
    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
