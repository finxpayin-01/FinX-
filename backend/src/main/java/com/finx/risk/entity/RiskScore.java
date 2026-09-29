package com.finx.risk.entity;

import com.finx.risk.dto.RiskDtos.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name="risk_scores", uniqueConstraints=@UniqueConstraint(columnNames={"subject_key"}))
public class RiskScore {
    @Id public UUID id = UUID.randomUUID();
    @Column(nullable=false, length=150) public String subjectKey;
    @Column(nullable=false) public UUID userId;
    public UUID organizationId;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public UserType userType;
    public UUID assessmentId;
    public int score;
    @Enumerated(EnumType.STRING) public Level riskLevel = Level.LOW;
    public String verificationStatus = "NOT_STARTED";
    @Column(precision=19, scale=4) public BigDecimal transactionVolume = BigDecimal.ZERO;
    public String currency = "INR";
    public Instant calculatedAt;
    public Instant nextRefreshAt = Instant.now();
    @Version public long version;
}
