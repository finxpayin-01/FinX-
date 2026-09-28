package com.finx.trust.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="trust_audit_receipts")
public class TrustAuditReceipt {
    @Id public UUID auditId;
    public Instant capturedAt;
}
