package com.finx.agreement.entity;

import com.finx.agreement.enums.AgreementStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "deal_agreements", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"deal_id", "version"})
})
public class DealAgreement {

    @Id
    private UUID id;

    @Column(name = "deal_id", nullable = false)
    private UUID dealId;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false)
    private String title;

    @Column(name = "terms_content", nullable = false, columnDefinition = "TEXT")
    private String termsContent;

    @Column(name = "document_sha256", nullable = false, length = 64)
    private String documentSha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AgreementStatus status;

    @Column(name = "buyer_accepted_at")
    private Instant buyerAcceptedAt;

    @Column(name = "buyer_accepted_by")
    private UUID buyerAcceptedBy;

    @Column(name = "buyer_signer_ip")
    private String buyerSignerIp;

    @Column(name = "seller_accepted_at")
    private Instant sellerAcceptedAt;

    @Column(name = "seller_accepted_by")
    private UUID sellerAcceptedBy;

    @Column(name = "seller_signer_ip")
    private String sellerSignerIp;

    @Column(name = "effective_date")
    private Instant effectiveDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public DealAgreement() {
        this.id = UUID.randomUUID();
        this.version = 1;
        this.status = AgreementStatus.DRAFT;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public DealAgreement(UUID dealId, int version, String title, String termsContent, String documentSha256) {
        this();
        this.dealId = dealId;
        this.version = version;
        this.title = title;
        this.termsContent = termsContent;
        this.documentSha256 = documentSha256;
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getDealId() { return dealId; }
    public void setDealId(UUID dealId) { this.dealId = dealId; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getTermsContent() { return termsContent; }
    public void setTermsContent(String termsContent) { this.termsContent = termsContent; }
    public String getDocumentSha256() { return documentSha256; }
    public void setDocumentSha256(String documentSha256) { this.documentSha256 = documentSha256; }
    public AgreementStatus getStatus() { return status; }
    public void setStatus(AgreementStatus status) { this.status = status; }
    public Instant getBuyerAcceptedAt() { return buyerAcceptedAt; }
    public void setBuyerAcceptedAt(Instant buyerAcceptedAt) { this.buyerAcceptedAt = buyerAcceptedAt; }
    public UUID getBuyerAcceptedBy() { return buyerAcceptedBy; }
    public void setBuyerAcceptedBy(UUID buyerAcceptedBy) { this.buyerAcceptedBy = buyerAcceptedBy; }
    public String getBuyerSignerIp() { return buyerSignerIp; }
    public void setBuyerSignerIp(String buyerSignerIp) { this.buyerSignerIp = buyerSignerIp; }
    public Instant getSellerAcceptedAt() { return sellerAcceptedAt; }
    public void setSellerAcceptedAt(Instant sellerAcceptedAt) { this.sellerAcceptedAt = sellerAcceptedAt; }
    public UUID getSellerAcceptedBy() { return sellerAcceptedBy; }
    public void setSellerAcceptedBy(UUID sellerAcceptedBy) { this.sellerAcceptedBy = sellerAcceptedBy; }
    public String getSellerSignerIp() { return sellerSignerIp; }
    public void setSellerSignerIp(String sellerSignerIp) { this.sellerSignerIp = sellerSignerIp; }
    public Instant getEffectiveDate() { return effectiveDate; }
    public void setEffectiveDate(Instant effectiveDate) { this.effectiveDate = effectiveDate; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
