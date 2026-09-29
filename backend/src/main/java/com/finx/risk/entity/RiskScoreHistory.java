package com.finx.risk.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import java.time.Instant;
import java.util.UUID;

@Entity @Immutable
@Table(name="risk_score_history")
public class RiskScoreHistory {
    @Id public UUID id = UUID.randomUUID();
    @Column(nullable=false, updatable=false) public UUID subjectId;
    @Column(nullable=false, updatable=false, unique=true) public UUID assessmentId;
    @Column(nullable=false, updatable=false, columnDefinition="TEXT") public String snapshot;
    @Column(nullable=false, updatable=false) public Instant calculatedAt;
}
