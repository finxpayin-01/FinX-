package com.finx.trust.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import com.finx.trust.model.*;
import com.finx.trust.config.TrustRules;
import com.finx.trust.dto.TrustResult;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.*;

/** Persistence model; never bound directly to incoming HTTP requests. */
@Entity
@Table(name="organizations")
public class TrustOrganization {
    @Id
    public UUID id;
    @Column(nullable=false)
    public String name;
    @Column(nullable=false)
    public Instant createdAt;
    
    public Instant lastCalculatedAt;
    
    public Instant lastManualAt;
    
    public long configVersion;
    
    public boolean dirty;
    
    public Instant retryAfter;
    
    public int failures;
}
