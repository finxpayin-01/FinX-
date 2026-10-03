package com.finx.escrow.dto.response;

import com.finx.escrow.entity.EscrowLedger;
import com.finx.escrow.entity.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class EscrowLedgerResponse {

    private UUID id;
    private UUID escrowAccountId;
    private UUID paymentId;
    private UUID paymentInstructionId;
    private UUID milestoneId;
    private TransactionType transactionType;
    private BigDecimal amount;
    private BigDecimal balanceAfter;
    private String description;
    private Instant createdAt;

    public EscrowLedgerResponse() {
    }

    public static EscrowLedgerResponse fromEntity(EscrowLedger ledger) {
        EscrowLedgerResponse response = new EscrowLedgerResponse();
        response.setId(ledger.getId());
        response.setEscrowAccountId(ledger.getEscrowAccountId());
        response.setPaymentId(ledger.getPaymentId());
        response.setPaymentInstructionId(ledger.getPaymentInstructionId());
        response.setMilestoneId(ledger.getMilestoneId());
        response.setTransactionType(ledger.getTransactionType());
        response.setAmount(ledger.getAmount());
        response.setBalanceAfter(ledger.getBalanceAfter());
        response.setDescription(ledger.getDescription());
        response.setCreatedAt(ledger.getCreatedAt());
        return response;
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
}
