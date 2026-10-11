package com.finx.release.entity;

import com.finx.release.model.ReleaseInstructionStatus;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable execution intent bound to one certified EngineDecision(RELEASE, ALLOW).
 * Represents authorized milestone payout intent prior to bank dispatch and trusted finality.
 */
@Entity
@Table(name = "release_instructions")
public class ReleaseInstruction {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "deal_id", nullable = false, updatable = false)
    private UUID dealId;

    @Column(name = "milestone_id", nullable = false, updatable = false)
    private UUID milestoneId;

    @Column(name = "release_decision_id", nullable = false, updatable = false)
    private UUID releaseDecisionId;

    @Column(name = "release_decision_fingerprint", nullable = false, updatable = false, length = 64)
    private String releaseDecisionFingerprint;

    @Column(name = "policy_version_id", nullable = false, updatable = false)
    private UUID policyVersionId;

    @Column(name = "policy_content_hash", nullable = false, updatable = false, length = 64)
    private String policyContentHash;

    @Column(name = "beneficiary_bank_account_id", nullable = false, updatable = false)
    private UUID beneficiaryBankAccountId;

    @Column(name = "provider", nullable = false, updatable = false, length = 50)
    private String provider;

    @Column(name = "provider_account_id", nullable = false, updatable = false, length = 128)
    private String providerAccountId;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private ReleaseInstructionStatus status;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "authorized_by")
    private UUID authorizedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "authorized_at")
    private Instant authorizedAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected ReleaseInstruction() {
        // JPA requirement
    }

    public ReleaseInstruction(
            UUID id,
            UUID organizationId,
            UUID dealId,
            UUID milestoneId,
            UUID releaseDecisionId,
            String releaseDecisionFingerprint,
            UUID policyVersionId,
            String policyContentHash,
            UUID beneficiaryBankAccountId,
            String provider,
            String providerAccountId,
            BigDecimal amount,
            String currency,
            String idempotencyKey,
            String requestHash,
            UUID createdBy
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
        this.dealId = Objects.requireNonNull(dealId, "dealId must not be null");
        this.milestoneId = Objects.requireNonNull(milestoneId, "milestoneId must not be null");
        this.releaseDecisionId = Objects.requireNonNull(releaseDecisionId, "releaseDecisionId must not be null");
        this.releaseDecisionFingerprint = Objects.requireNonNull(releaseDecisionFingerprint, "releaseDecisionFingerprint must not be null");
        this.policyVersionId = Objects.requireNonNull(policyVersionId, "policyVersionId must not be null");
        this.policyContentHash = Objects.requireNonNull(policyContentHash, "policyContentHash must not be null");
        this.beneficiaryBankAccountId = Objects.requireNonNull(beneficiaryBankAccountId, "beneficiaryBankAccountId must not be null");
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.providerAccountId = Objects.requireNonNull(providerAccountId, "providerAccountId must not be null");
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
        this.currency = Objects.requireNonNull(currency, "currency must not be null");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        this.requestHash = Objects.requireNonNull(requestHash, "requestHash must not be null");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy must not be null");

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Release amount must be strictly positive: " + amount);
        }

        this.status = ReleaseInstructionStatus.CREATED;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    // Controlled mutation methods for lifecycle (used during transitions)
    public void markAuthorized(UUID authorizedBy, Instant authorizedAt) {
        if (this.status != ReleaseInstructionStatus.CREATED) {
            throw new IllegalStateException("Cannot authorize ReleaseInstruction in status " + this.status);
        }
        this.authorizedBy = Objects.requireNonNull(authorizedBy, "authorizedBy must not be null");
        this.authorizedAt = Objects.requireNonNull(authorizedAt, "authorizedAt must not be null");
        this.status = ReleaseInstructionStatus.AUTHORIZED;
        this.updatedAt = Instant.now();
    }

    public void markReserved() {
        if (this.status != ReleaseInstructionStatus.AUTHORIZED) {
            throw new IllegalStateException("Cannot mark reserved on ReleaseInstruction in status " + this.status);
        }
        this.status = ReleaseInstructionStatus.RESERVED;
        this.updatedAt = Instant.now();
    }

    public void markCancelled() {
        if (this.status == ReleaseInstructionStatus.SETTLED || this.status == ReleaseInstructionStatus.CANCELLED) {
            throw new IllegalStateException("Cannot cancel instruction in " + this.status + " status");
        }
        this.status = ReleaseInstructionStatus.CANCELLED;
        this.updatedAt = Instant.now();
    }

    @Deprecated
    public void updateStatus(ReleaseInstructionStatus newStatus) {
        this.status = Objects.requireNonNull(newStatus, "status must not be null");
        this.updatedAt = Instant.now();
    }

    public void markSettled(Instant settledAt) {
        this.status = ReleaseInstructionStatus.SETTLED;
        this.settledAt = Objects.requireNonNull(settledAt, "settledAt must not be null");
        this.updatedAt = Instant.now();
    }

    // Getters
    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getDealId() { return dealId; }
    public UUID getMilestoneId() { return milestoneId; }
    public UUID getReleaseDecisionId() { return releaseDecisionId; }
    public String getReleaseDecisionFingerprint() { return releaseDecisionFingerprint; }
    public UUID getPolicyVersionId() { return policyVersionId; }
    public String getPolicyContentHash() { return policyContentHash; }
    public UUID getBeneficiaryBankAccountId() { return beneficiaryBankAccountId; }
    public String getProvider() { return provider; }
    public String getProviderAccountId() { return providerAccountId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public ReleaseInstructionStatus getStatus() { return status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public UUID getCreatedBy() { return createdBy; }
    public UUID getAuthorizedBy() { return authorizedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getAuthorizedAt() { return authorizedAt; }
    public Instant getSettledAt() { return settledAt; }
    public Long getVersion() { return version; }
}
