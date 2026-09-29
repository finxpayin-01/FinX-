package com.finx.risk.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import java.util.UUID;

@Entity @Immutable
@Table(name="risk_factors")
public class RiskFactor {
    @Id public UUID id = UUID.randomUUID();
    @Column(nullable=false, updatable=false) public UUID assessmentId;
    @Column(nullable=false, updatable=false) public String code;
    @Column(nullable=false, updatable=false, columnDefinition="TEXT") public String evidence;
}
