package com.finx.risk.entity;

import com.finx.risk.dto.RiskDtos.UserType;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="risk_rules")
public class RiskRule {
    @Id public UUID id;
    @Enumerated(EnumType.STRING) @Column(nullable=false, unique=true) public UserType userType;
    @Column(nullable=false, columnDefinition="TEXT") public String configuration;
    @Version public long version = 1;
    public Instant updatedAt = Instant.now();
    public UUID updatedBy;
}
