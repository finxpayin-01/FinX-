package com.finx.escrow.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "escrow_ledger", uniqueConstraints = {
        @UniqueConstraint(name = "uk_escrow_ledger_payment_id", columnNames = "payment_id"),
        @UniqueConstraint(name = "uk_escrow_ledger_payment_instruction_id", columnNames = "payment_instruction_id"),
        @UniqueConstraint(name = "uk_escrow_ledger_milestone_type", columnNames = {"milestone_id", "transaction_type"})
}, indexes = {
        @Index(name = "idx_escrow_ledger_account_id", columnList = "escrow_account_id"),
        @Index(name = "idx_escrow_ledger_payment_instruction_id", columnList = "payment_instruction_id"),
        @Index(name = "idx_escrow_ledger_milestone_id", columnList = "milestone_id"),
        @Index(name = "idx_escrow_ledger_type", columnList = "transaction_type"),
        @Index(name = "idx_escrow_ledger_created_at", columnList = "created_at")
})
public class EscrowLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "escrow_account_id", nullable = false)
    private UUID escrowAccountId;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "payment_instruction_id")
    private UUID paymentInstructionId;

    @Column(name = "milestone_id")
    private UUID milestoneId;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 50)
    private TransactionType transactionType;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 15, scale = 2)
    private BigDecimal balanceAfter;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public EscrowLedger() {
    }

    public EscrowLedger(UUID escrowAccountId, UUID paymentId, UUID milestoneId, TransactionType transactionType, BigDecimal amount, BigDecimal balanceAfter, String description) {
        this(escrowAccountId, paymentId, null, milestoneId, transactionType, amount, balanceAfter, description);
    }

    public EscrowLedger(UUID escrowAccountId, UUID paymentId, UUID paymentInstructionId, UUID milestoneId, TransactionType transactionType, BigDecimal amount, BigDecimal balanceAfter, String description) {
        this.escrowAccountId = escrowAccountId;
        this.paymentId = paymentId;
        this.paymentInstructionId = paymentInstructionId;
        this.milestoneId = milestoneId;
        this.transactionType = transactionType;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.description = description;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getEscrowAccountId() {
        return escrowAccountId;
    }

    public void setEscrowAccountId(UUID escrowAccountId) {
        this.escrowAccountId = escrowAccountId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(UUID paymentId) {
        this.paymentId = paymentId;
    }

    public UUID getPaymentInstructionId() {
        return paymentInstructionId;
    }

    public void setPaymentInstructionId(UUID paymentInstructionId) {
        this.paymentInstructionId = paymentInstructionId;
    }

    public UUID getMilestoneId() {
        return milestoneId;
    }

    public void setMilestoneId(UUID milestoneId) {
        this.milestoneId = milestoneId;
    }

    public TransactionType getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(TransactionType transactionType) {
        this.transactionType = transactionType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public void setBalanceAfter(BigDecimal balanceAfter) {
        this.balanceAfter = balanceAfter;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EscrowLedger that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
