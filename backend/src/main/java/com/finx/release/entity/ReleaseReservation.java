package com.finx.release.entity;

import com.finx.release.model.ReleaseReservationStatus;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable reservation claiming a portion of escrow funds without deducting balance.
 * Prevents double-spending and race conditions across concurrent release authorizations.
 */
@Entity
@Table(name = "release_reservations")
public class ReleaseReservation {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "release_instruction_id", nullable = false, updatable = false)
    private UUID releaseInstructionId;

    @Column(name = "escrow_account_id", nullable = false, updatable = false)
    private UUID escrowAccountId;

    @Column(name = "milestone_id", nullable = false, updatable = false)
    private UUID milestoneId;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReleaseReservationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected ReleaseReservation() {
        // JPA requirement
    }

    public ReleaseReservation(
            UUID id,
            UUID releaseInstructionId,
            UUID escrowAccountId,
            UUID milestoneId,
            BigDecimal amount,
            String currency
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.releaseInstructionId = Objects.requireNonNull(releaseInstructionId, "releaseInstructionId must not be null");
        this.escrowAccountId = Objects.requireNonNull(escrowAccountId, "escrowAccountId must not be null");
        this.milestoneId = Objects.requireNonNull(milestoneId, "milestoneId must not be null");
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
        this.currency = Objects.requireNonNull(currency, "currency must not be null");

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Reservation amount must be strictly positive: " + amount);
        }

        this.status = ReleaseReservationStatus.ACTIVE;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    // Controlled mutation methods for lifecycle
    public void markConsumed(Instant consumedAt) {
        if (this.status != ReleaseReservationStatus.ACTIVE) {
            throw new IllegalStateException("Only ACTIVE reservations can be consumed. Current: " + this.status);
        }
        this.status = ReleaseReservationStatus.CONSUMED;
        this.consumedAt = Objects.requireNonNull(consumedAt, "consumedAt must not be null");
        this.updatedAt = Instant.now();
    }

    public void markReleased(Instant releasedAt) {
        if (this.status != ReleaseReservationStatus.ACTIVE) {
            throw new IllegalStateException("Only ACTIVE reservations can be released. Current: " + this.status);
        }
        this.status = ReleaseReservationStatus.RELEASED;
        this.releasedAt = Objects.requireNonNull(releasedAt, "releasedAt must not be null");
        this.updatedAt = Instant.now();
    }

    // Getters
    public UUID getId() { return id; }
    public UUID getReleaseInstructionId() { return releaseInstructionId; }
    public UUID getEscrowAccountId() { return escrowAccountId; }
    public UUID getMilestoneId() { return milestoneId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public ReleaseReservationStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getConsumedAt() { return consumedAt; }
    public Instant getReleasedAt() { return releasedAt; }
    public Long getVersion() { return version; }
}
