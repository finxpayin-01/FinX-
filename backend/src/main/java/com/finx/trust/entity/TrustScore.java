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
@Table(name="trust_scores", uniqueConstraints=@UniqueConstraint(columnNames={"organization_id","role"}))
public class TrustScore {
    @Id
    public UUID id;
    @Column(nullable=false)
    public UUID organizationId;
    @Enumerated(EnumType.STRING)
    public TrustRole role;
    
    public double overallScore;
    
    public double confidenceScore;
    @Enumerated(EnumType.STRING)
    public TrustLevel trustLevel;
    
    public long configVersion;
    
    public String algorithmVersion;
    
    public long dataPoints;
    
    public Instant lastCalculatedAt;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition="jsonb")
    public TrustResult result;
}
