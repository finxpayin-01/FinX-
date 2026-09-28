package com.finx.trust.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.finx.trust.model.*;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.*;

/** Persistence model; never bound directly to incoming HTTP requests. */
@Entity
@Table(name="trust_events")
public class TrustEvent {
    @Id
    public UUID id;
    @Column(nullable=false, updatable=false)
    public UUID organizationId;
    
    public UUID userId;
    
    public UUID dealId;
    
    public UUID milestoneId;
    
    public UUID counterpartyId;
    @Enumerated(EnumType.STRING)
    public TrustRole role;
    @Enumerated(EnumType.STRING)
    public TrustEventType eventType;
    @Column(precision=19,scale=4)
    public BigDecimal eventValue;
    
    public String currency;
    
    public int severity;
    @Column(nullable=false,updatable=false)
    public Instant occurredAt;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition="jsonb")
    public Map<String, Object> metadata;
    
    public String source;
    @Column(nullable=false,unique=true,length=300,updatable=false)
    public String sourceKey;
    
    public boolean processed;
    @Column(nullable=false,updatable=false)
    public Instant createdAt;
}
