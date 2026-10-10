package com.finx.approval.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable durable evidence of a milestone approval event.
 * Represents post-approval persisted truth with cryptographic fingerprint binding.
 */
@Entity
@Immutable
@Table(name = "milestone_approval_bindings", indexes = {
        @Index(name = "idx_mab_org_approved_at", columnList = "organization_id, approved_at DESC"),
        @Index(name = "idx_mab_deal_approved_at", columnList = "deal_id, approved_at DESC"),
        @Index(name = "idx_mab_milestone_id", columnList = "milestone_id"),
        @Index(name = "idx_mab_approved_by", columnList = "approved_by, approved_at DESC")
})
public class MilestoneApprovalBinding {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "deal_id", nullable = false, updatable = false)
    private UUID dealId;

    @Column(name = "milestone_id", nullable = false, updatable = false, unique = true)
    private UUID milestoneId;

    @Column(name = "approved_by", nullable = false, updatable = false)
    private UUID approvedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_authority", nullable = false, updatable = false, length = 30)
    private MilestoneApprovalAuthority approvalAuthority;

    @Column(name = "milestone_fingerprint", nullable = false, updatable = false, length = 64)
    private String milestoneFingerprint;

    @Column(name = "deliverables_fingerprint", nullable = false, updatable = false, length = 64)
    private String deliverablesFingerprint;

    @Column(name = "approval_fingerprint", nullable = false, updatable = false, length = 64)
    private String approvalFingerprint;

    @Column(name = "project_manager_id", updatable = false)
    private UUID projectManagerId;

    @Column(name = "approved_at", nullable = false, updatable = false)
    private Instant approvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MilestoneApprovalBinding() {
        // Required by JPA
    }

    public MilestoneApprovalBinding(
            UUID id,
            UUID organizationId,
            UUID dealId,
            UUID milestoneId,
            UUID approvedBy,
            MilestoneApprovalAuthority approvalAuthority,
            String milestoneFingerprint,
            String deliverablesFingerprint,
            String approvalFingerprint,
            UUID projectManagerId,
            Instant approvedAt,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
        this.dealId = Objects.requireNonNull(dealId, "dealId must not be null");
        this.milestoneId = Objects.requireNonNull(milestoneId, "milestoneId must not be null");
        this.approvedBy = Objects.requireNonNull(approvedBy, "approvedBy must not be null");
        this.approvalAuthority = Objects.requireNonNull(approvalAuthority, "approvalAuthority must not be null");
        this.milestoneFingerprint = Objects.requireNonNull(milestoneFingerprint, "milestoneFingerprint must not be null");
        this.deliverablesFingerprint = Objects.requireNonNull(deliverablesFingerprint, "deliverablesFingerprint must not be null");
        this.approvalFingerprint = Objects.requireNonNull(approvalFingerprint, "approvalFingerprint must not be null");
        this.projectManagerId = projectManagerId;
        this.approvedAt = Objects.requireNonNull(approvedAt, "approvedAt must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getDealId() { return dealId; }
    public UUID getMilestoneId() { return milestoneId; }
    public UUID getApprovedBy() { return approvedBy; }
    public MilestoneApprovalAuthority getApprovalAuthority() { return approvalAuthority; }
    public String getMilestoneFingerprint() { return milestoneFingerprint; }
    public String getDeliverablesFingerprint() { return deliverablesFingerprint; }
    public String getApprovalFingerprint() { return approvalFingerprint; }
    public UUID getProjectManagerId() { return projectManagerId; }
    public Instant getApprovedAt() { return approvedAt; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MilestoneApprovalBinding that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
