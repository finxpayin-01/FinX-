package com.finx.risk.entity;

import com.finx.risk.dto.RiskDtos.Level;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="risk_alerts")
public class RiskAlert {
    @Id public UUID id = UUID.randomUUID();
    @Column(nullable=false) public UUID subjectId;
    @Column(nullable=false) public UUID assessmentId;
    @Enumerated(EnumType.STRING) public Level severity;
    @Column(nullable=false, columnDefinition="TEXT") public String reason;
    public Instant createdAt = Instant.now();
    public Instant clearedAt;
}
