package com.finx.paymentorchestration.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_transactions")
public class PaymentTransaction {

    @Id
    private UUID id;

    @Column(name = "transaction_reference", nullable = false, unique = true)
    private String transactionReference;

    @Column(name = "payment_instruction_id", nullable = false)
    private UUID paymentInstructionId;

    @Column(name = "provider_reference")
    private String providerReference;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private String provider = "BANK";

    @Column(name = "utr_number")
    private String utrNumber;

    @Column(name = "raw_provider_response", columnDefinition = "TEXT")
    private String rawProviderResponse;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public PaymentTransaction() {
        this.id = UUID.randomUUID();
        this.currency = "INR";
        this.provider = "BANK";
        this.createdAt = Instant.now();
    }

    public PaymentTransaction(String transactionReference, UUID paymentInstructionId, String providerReference,
                              BigDecimal amount, String currency, String status, String utrNumber, String rawProviderResponse) {
        this(transactionReference, paymentInstructionId, "BANK", providerReference, amount, currency, status, utrNumber, rawProviderResponse);
    }

    public PaymentTransaction(String transactionReference, UUID paymentInstructionId, String provider, String providerReference,
                              BigDecimal amount, String currency, String status, String utrNumber, String rawProviderResponse) {
        this();
        this.transactionReference = transactionReference;
        this.paymentInstructionId = paymentInstructionId;
        this.provider = provider != null ? provider : "BANK";
        this.providerReference = providerReference;
        this.amount = amount != null ? amount.setScale(4) : BigDecimal.ZERO.setScale(4);
        if (currency != null) this.currency = currency;
        this.status = status;
        this.utrNumber = utrNumber;
        this.rawProviderResponse = rawProviderResponse;
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTransactionReference() { return transactionReference; }
    public void setTransactionReference(String transactionReference) { this.transactionReference = transactionReference; }
    public UUID getPaymentInstructionId() { return paymentInstructionId; }
    public void setPaymentInstructionId(UUID paymentInstructionId) { this.paymentInstructionId = paymentInstructionId; }
    public String getProviderReference() { return providerReference; }
    public void setProviderReference(String providerReference) { this.providerReference = providerReference; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount != null ? amount.setScale(4) : BigDecimal.ZERO.setScale(4); }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getUtrNumber() { return utrNumber; }
    public void setUtrNumber(String utrNumber) { this.utrNumber = utrNumber; }
    public String getRawProviderResponse() { return rawProviderResponse; }
    public void setRawProviderResponse(String rawProviderResponse) { this.rawProviderResponse = rawProviderResponse; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
