package com.finx.trust.entity;

import jakarta.persistence.*;
import java.util.UUID;

/** Persistence model; never bound directly to incoming HTTP requests. */
@Entity
@Table(name="organization_members")
public class TrustMembership {
    @Id
    public UUID userId;
    @Column(nullable=false)
    public UUID organizationId;
}
