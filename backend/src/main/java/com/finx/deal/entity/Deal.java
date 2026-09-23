package com.finx.deal.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "deals", indexes = {
        @Index(name = "idx_deals_buyer_id", columnList = "buyer_id"),
        @Index(name = "idx_deals_seller_id", columnList = "seller_id"),
        @Index(name = "idx_deals_project_manager_id", columnList = "project_manager_id"),
        @Index(name = "idx_deals_status", columnList = "status"),
        @Index(name = "idx_deals_created_at", columnList = "created_at"),
        @Index(name = "idx_deals_deal_reference", columnList = "deal_reference", unique = true),
        @Index(name = "idx_deals_buyer_org", columnList = "buyer_organization_id"),
        @Index(name = "idx_deals_seller_org", columnList = "seller_organization_id")
})
public class Deal {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "project_id", unique = true, updatable = false, length = 20)
    private String projectId;

    @Column(name = "deal_reference", unique = true, length = 100)
    private String dealReference;

    @Column(name = "buyer_organization_id")
    private UUID buyerOrganizationId;

    @Column(name = "seller_organization_id")
    private UUID sellerOrganizationId;

    @Column(name = "invitation_email")
    private String invitationEmail;

    @Column(name = "invitation_phone", length = 50)
    private String invitationPhone;

    @Column(name = "invitation_gstin", length = 50)
    private String invitationGstin;

    @Column(name = "agreement_status", length = 50)
    private String agreementStatus = "DRAFT";

    @PrePersist
    protected void onCreate() {
        if (this.projectId == null) {
            this.projectId = "PRJ-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        }
        if (this.dealReference == null) {
            this.dealReference = "FINX-DL-" + java.time.Year.now().getValue() + "-" + String.format("%06d", (int)(Math.random() * 900000) + 100000);
        }
    }

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description")
    private String description;

    @Column(name = "buyer_id", nullable = false)
    private UUID buyerId;

    @Column(name = "seller_id", nullable = false)
    private UUID sellerId;

    @Column(name = "project_manager_id")
    private UUID projectManagerId;

    @Column(name = "total_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "currency", nullable = false, length = 10)
    private String currency = "INR";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private DealStatus status = DealStatus.DRAFT;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "accepted_by")
    private UUID acceptedBy;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "rejected_by")
    private UUID rejectedBy;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Deal() {
    }

    public Deal(String title, String description, UUID buyerId, UUID sellerId, BigDecimal totalAmount, String currency, DealStatus status) {
        this.title = title;
        this.description = description;
        this.buyerId = buyerId;
        this.sellerId = sellerId;
        this.totalAmount = totalAmount;
        this.currency = (currency != null && !currency.isBlank()) ? currency.toUpperCase() : "INR";
        this.status = (status != null) ? status : DealStatus.DRAFT;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getDealReference() {
        return dealReference;
    }

    public void setDealReference(String dealReference) {
        this.dealReference = dealReference;
    }

    public UUID getBuyerOrganizationId() {
        return buyerOrganizationId;
    }

    public void setBuyerOrganizationId(UUID buyerOrganizationId) {
        this.buyerOrganizationId = buyerOrganizationId;
    }

    public UUID getSellerOrganizationId() {
        return sellerOrganizationId;
    }

    public void setSellerOrganizationId(UUID sellerOrganizationId) {
        this.sellerOrganizationId = sellerOrganizationId;
    }

    public String getInvitationEmail() {
        return invitationEmail;
    }

    public void setInvitationEmail(String invitationEmail) {
        this.invitationEmail = invitationEmail;
    }

    public String getInvitationPhone() {
        return invitationPhone;
    }

    public void setInvitationPhone(String invitationPhone) {
        this.invitationPhone = invitationPhone;
    }

    public String getInvitationGstin() {
        return invitationGstin;
    }

    public void setInvitationGstin(String invitationGstin) {
        this.invitationGstin = invitationGstin;
    }

    public String getAgreementStatus() {
        return agreementStatus;
    }

    public void setAgreementStatus(String agreementStatus) {
        this.agreementStatus = agreementStatus;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public UUID getBuyerId() {
        return buyerId;
    }

    public void setBuyerId(UUID buyerId) {
        this.buyerId = buyerId;
    }

    public UUID getSellerId() {
        return sellerId;
    }

    public void setSellerId(UUID sellerId) {
        this.sellerId = sellerId;
    }

    public UUID getProjectManagerId() {
        return projectManagerId;
    }

    public void setProjectManagerId(UUID projectManagerId) {
        this.projectManagerId = projectManagerId;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public DealStatus getStatus() {
        return status;
    }

    public void setStatus(DealStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public void setAcceptedAt(Instant acceptedAt) {
        this.acceptedAt = acceptedAt;
    }

    public UUID getAcceptedBy() {
        return acceptedBy;
    }

    public void setAcceptedBy(UUID acceptedBy) {
        this.acceptedBy = acceptedBy;
    }

    public Instant getRejectedAt() {
        return rejectedAt;
    }

    public void setRejectedAt(Instant rejectedAt) {
        this.rejectedAt = rejectedAt;
    }

    public UUID getRejectedBy() {
        return rejectedBy;
    }

    public void setRejectedBy(UUID rejectedBy) {
        this.rejectedBy = rejectedBy;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Deal deal)) return false;
        return Objects.equals(id, deal.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
