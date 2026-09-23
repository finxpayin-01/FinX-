package com.finx.deal.dto.response;

import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class DealResponse {

    private UUID id;
    private String projectId;
    private String dealReference;
    private UUID buyerOrganizationId;
    private UUID sellerOrganizationId;
    private String invitationEmail;
    private String invitationPhone;
    private String invitationGstin;
    private String agreementStatus;
    private String title;
    private String description;
    private UUID buyerId;
    private UUID sellerId;
    private UUID projectManagerId;
    private com.finx.auth.dto.response.UserSummaryResponse projectManager;
    private BigDecimal totalAmount;
    private String currency;
    private DealStatus status;
    private Instant acceptedAt;
    private UUID acceptedBy;
    private Instant rejectedAt;
    private UUID rejectedBy;
    private String rejectionReason;
    private Instant createdAt;
    private Instant updatedAt;

    public DealResponse() {
    }

    public DealResponse(UUID id, String projectId, String title, String description, UUID buyerId, UUID sellerId,
                        BigDecimal totalAmount, String currency, DealStatus status,
                        Instant acceptedAt, UUID acceptedBy, Instant rejectedAt, UUID rejectedBy, String rejectionReason,
                        Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.projectId = projectId;
        this.title = title;
        this.description = description;
        this.buyerId = buyerId;
        this.sellerId = sellerId;
        this.totalAmount = totalAmount;
        this.currency = currency;
        this.status = status;
        this.acceptedAt = acceptedAt;
        this.acceptedBy = acceptedBy;
        this.rejectedAt = rejectedAt;
        this.rejectedBy = rejectedBy;
        this.rejectionReason = rejectionReason;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static DealResponse fromEntity(Deal deal) {
        DealResponse resp = new DealResponse(
                deal.getId(),
                deal.getProjectId(),
                deal.getTitle(),
                deal.getDescription(),
                deal.getBuyerId(),
                deal.getSellerId(),
                deal.getTotalAmount(),
                deal.getCurrency(),
                deal.getStatus(),
                deal.getAcceptedAt(),
                deal.getAcceptedBy(),
                deal.getRejectedAt(),
                deal.getRejectedBy(),
                deal.getRejectionReason(),
                deal.getCreatedAt(),
                deal.getUpdatedAt()
        );
        resp.setDealReference(deal.getDealReference());
        resp.setBuyerOrganizationId(deal.getBuyerOrganizationId());
        resp.setSellerOrganizationId(deal.getSellerOrganizationId());
        resp.setInvitationEmail(deal.getInvitationEmail());
        resp.setInvitationPhone(deal.getInvitationPhone());
        resp.setInvitationGstin(deal.getInvitationGstin());
        resp.setAgreementStatus(deal.getAgreementStatus());
        resp.setProjectManagerId(deal.getProjectManagerId());
        return resp;
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

    public UUID getProjectManagerId() {
        return projectManagerId;
    }

    public void setProjectManagerId(UUID projectManagerId) {
        this.projectManagerId = projectManagerId;
    }

    public com.finx.auth.dto.response.UserSummaryResponse getProjectManager() {
        return projectManager;
    }

    public void setProjectManager(com.finx.auth.dto.response.UserSummaryResponse projectManager) {
        this.projectManager = projectManager;
    }
}
