package com.finx.risk.entity;

import com.finx.risk.enums.RiskEnums.RiskEntityType;
import com.finx.risk.enums.RiskEnums.RiskLevel;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "risk_assessments")
public class RiskAssessment {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false)
    private RiskEntityType entityType;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false)
    private RiskLevel riskLevel;

    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    @Column(name = "factors_summary", columnDefinition = "TEXT")
    private String factorsSummary;

    @Column(name = "is_cleared", nullable = false)
    private boolean isCleared;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "riskAssessmentId", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RiskSignal> signals = new ArrayList<>();

    public RiskAssessment() {
        this.id = UUID.randomUUID();
        this.riskLevel = RiskLevel.LOW;
        this.riskScore = 0;
        this.isCleared = true;
        this.createdAt = Instant.now();
    }

    public RiskAssessment(RiskEntityType entityType, UUID entityId, RiskLevel riskLevel, int riskScore, String factorsSummary, boolean isCleared) {
        this();
        this.entityType = entityType;
        this.entityId = entityId;
        this.riskLevel = riskLevel;
        this.riskScore = riskScore;
        this.factorsSummary = factorsSummary;
        this.isCleared = isCleared;
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public RiskEntityType getEntityType() { return entityType; }
    public void setEntityType(RiskEntityType entityType) { this.entityType = entityType; }
    public UUID getEntityId() { return entityId; }
    public void setEntityId(UUID entityId) { this.entityId = entityId; }
    public RiskLevel getRiskLevel() { return riskLevel; }
    public void setRiskLevel(RiskLevel riskLevel) { this.riskLevel = riskLevel; }
    public int getRiskScore() { return riskScore; }
    public void setRiskScore(int riskScore) { this.riskScore = riskScore; }
    public String getFactorsSummary() { return factorsSummary; }
    public void setFactorsSummary(String factorsSummary) { this.factorsSummary = factorsSummary; }
    public boolean isCleared() { return isCleared; }
    public void setCleared(boolean cleared) { isCleared = cleared; }
    public UUID getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(UUID reviewedBy) { this.reviewedBy = reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public List<RiskSignal> getSignals() { return signals; }
    public void setSignals(List<RiskSignal> signals) { this.signals = signals; }
}
