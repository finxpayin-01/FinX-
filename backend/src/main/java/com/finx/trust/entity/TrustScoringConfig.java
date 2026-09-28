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
@Table(name="trust_scoring_config")
public class TrustScoringConfig {
    @Id
    @GeneratedValue(strategy=GenerationType.IDENTITY)
    public Long id;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition="jsonb")
    public TrustRules rules;
    
    public UUID actorId;
    @Column(length=1000)
    public String reason;
    
    public Instant createdAt;
}
