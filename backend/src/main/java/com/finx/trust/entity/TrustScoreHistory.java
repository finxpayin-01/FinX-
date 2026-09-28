package com.finx.trust.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.finx.trust.model.*;
import com.finx.trust.config.TrustRules;
import com.finx.trust.dto.TrustResult;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.*;

/** Persistence model; never bound directly to incoming HTTP requests. */
@Entity
@Table(name="trust_score_history")
public class TrustScoreHistory {
    @Id
    public UUID id;
    
    public UUID organizationId;
    @Enumerated(EnumType.STRING)
    public TrustRole role;
    
    public Double previousScore;
    
    public double newScore;
    
    public String previousLevel;
    
    public String newLevel;
    @Column(length=1000)
    public String reason;
    
    public UUID triggerEventId;
    
    public UUID actorId;
    
    public long configVersion;
    
    public Instant calculatedAt;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition="jsonb")
    public TrustResult snapshot;
}
