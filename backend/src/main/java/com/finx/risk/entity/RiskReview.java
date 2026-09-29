package com.finx.risk.entity;

import com.finx.risk.dto.RiskDtos.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="risk_reviews")
public class RiskReview {
    @Id public UUID id = UUID.randomUUID();
    @Column(nullable=false) public UUID subjectId;
    @Column(nullable=false, unique=true) public UUID assessmentId;
    @Column(nullable=false) public UUID alertId;
    @Enumerated(EnumType.STRING) public ReviewStatus status = ReviewStatus.OPEN;
    @Enumerated(EnumType.STRING) public Action action = Action.MANUAL_REVIEW;
    public UUID reviewerId;
    public Instant reviewedAt;
    public Instant restrictionUntil;
    @Column(columnDefinition="TEXT") public String reason;
    @Column(columnDefinition="TEXT") public String notes;
    @Column(columnDefinition="TEXT") public String evidenceReferences = "[]";
    public Instant createdAt = Instant.now();
    @Version public long version;
}
