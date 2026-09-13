package com.finx.escrow.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "escrow_accounts", indexes = {
        @Index(name = "idx_escrow_accounts_deal_id", columnList = "deal_id"),
        @Index(name = "idx_escrow_accounts_status", columnList = "status")
})
public class EscrowAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "deal_id", nullable = false, unique = true)
    private UUID dealId;

    @Column(name = "balance", nullable = false, precision = 15, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(name = "total_funded", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalFunded = BigDecimal.ZERO;

    @Column(name = "total_released", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalReleased = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false, length = 10)
    private String currency = "INR";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private EscrowAccountStatus status = EscrowAccountStatus.ACTIVE;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public EscrowAccount() {
    }

    public EscrowAccount(UUID dealId, BigDecimal balance, String currency) {
        this.dealId = dealId;
        this.balance = balance != null ? balance : BigDecimal.ZERO;
        this.totalFunded = balance != null ? balance : BigDecimal.ZERO;
        this.totalReleased = BigDecimal.ZERO;
        this.currency = currency != null ? currency.toUpperCase() : "INR";
        this.status = EscrowAccountStatus.ACTIVE;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getDealId() {
        return dealId;
    }

    public void setDealId(UUID dealId) {
        this.dealId = dealId;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public BigDecimal getTotalFunded() {
        return totalFunded != null ? totalFunded : BigDecimal.ZERO;
    }

    public void setTotalFunded(BigDecimal totalFunded) {
        this.totalFunded = totalFunded;
    }

    public BigDecimal getTotalReleased() {
        return totalReleased != null ? totalReleased : BigDecimal.ZERO;
    }

    public void setTotalReleased(BigDecimal totalReleased) {
        this.totalReleased = totalReleased;
    }

    public BigDecimal getAvailableBalance() {
        return balance != null ? balance : BigDecimal.ZERO;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public EscrowAccountStatus getStatus() {
        return status;
    }

    public void setStatus(EscrowAccountStatus status) {
        this.status = status;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EscrowAccount that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
