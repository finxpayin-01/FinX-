package com.finx.trust.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Persistence model; never bound directly to incoming HTTP requests. */
@Entity
@Table(name="trust_manual_review")
public class TrustManualReview {
    @Id
    public UUID id;
    
    public UUID organizationId;
    
    public UUID adminId;
    @Column(length=1000)
    public String reason;
    
    public String status;
    
    public UUID resolvedBy;
    @Column(length=1000)
    public String resolution;
    
    public Instant createdAt;
    
    public Instant resolvedAt;
}
