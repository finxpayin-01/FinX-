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
@Table(name="trust_signal")
public class TrustSignal {
    @Id
    public UUID id;
    
    public UUID organizationId;
    
    public UUID calculationId;
    @Enumerated(EnumType.STRING)
    public TrustRole role;
    
    public String signalType;
    
    public String category;
    
    public double impact;
    
    public int severity;
    @Column(length=1000)
    public String description;
    
    public UUID sourceEventId;
    
    public Instant occurredAt;
    
    public Instant expiresAt;
}
