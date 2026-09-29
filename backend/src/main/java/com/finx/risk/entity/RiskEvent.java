package com.finx.risk.entity;

import com.finx.risk.dto.RiskDtos.EventType;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="risk_events", uniqueConstraints=@UniqueConstraint(columnNames={"source_key", "subject_id"}))
public class RiskEvent {
    @Id public UUID id = UUID.randomUUID();
    @Column(nullable=false) public UUID subjectId;
    @Column(nullable=false) public String sourceKey;
    public UUID auditLogId;
    public UUID dealId;
    public UUID milestoneId;
    public UUID paymentId;
    public UUID disputeId;
    public UUID attachmentId;
    @Column(columnDefinition="TEXT") public String safeEvidence;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public EventType eventType;
    @Column(nullable=false) public Instant occurredAt = Instant.now();
    public Instant processedAt;
    public Instant retryAt = Instant.now();
    public int attempts;
    public String lastError;
    public boolean deadLetter;
}
