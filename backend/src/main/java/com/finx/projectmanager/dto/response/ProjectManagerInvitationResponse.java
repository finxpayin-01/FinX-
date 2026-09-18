package com.finx.projectmanager.dto.response;

import com.finx.projectmanager.entity.InvitationStatus;
import com.finx.projectmanager.entity.ProjectManagerInvitation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class ProjectManagerInvitationResponse {

    private UUID id;
    private UUID dealId;
    private String projectId;
    private String projectTitle;
    private String projectDescription;
    private UUID buyerId;
    private String buyerName;
    private String buyerEmail;
    private UUID sellerId;
    private String sellerName;
    private BigDecimal totalAmount;
    private String currency;
    private int milestoneCount;

    private UUID projectManagerId;
    private String projectManagerName;
    private String projectManagerPmId;
    private String projectManagerEmail;

    private UUID invitedBy;
    private String invitedByName;

    private InvitationStatus status;
    private Instant invitedAt;
    private Instant acceptedAt;
    private Instant declinedAt;
    private Instant cancelledAt;
    private String declineReason;

    public ProjectManagerInvitationResponse() {
    }

    public static ProjectManagerInvitationResponse fromEntity(ProjectManagerInvitation entity) {
        ProjectManagerInvitationResponse resp = new ProjectManagerInvitationResponse();
        resp.setId(entity.getId());
        resp.setDealId(entity.getDealId());
        resp.setProjectManagerId(entity.getProjectManagerId());
        resp.setInvitedBy(entity.getInvitedBy());
        resp.setStatus(entity.getStatus());
        resp.setInvitedAt(entity.getInvitedAt());
        resp.setAcceptedAt(entity.getAcceptedAt());
        resp.setDeclinedAt(entity.getDeclinedAt());
        resp.setCancelledAt(entity.getCancelledAt());
        resp.setDeclineReason(entity.getDeclineReason());
        return resp;
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

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getProjectTitle() {
        return projectTitle;
    }

    public void setProjectTitle(String projectTitle) {
        this.projectTitle = projectTitle;
    }

    public String getProjectDescription() {
        return projectDescription;
    }

    public void setProjectDescription(String projectDescription) {
        this.projectDescription = projectDescription;
    }

    public UUID getBuyerId() {
        return buyerId;
    }

    public void setBuyerId(UUID buyerId) {
        this.buyerId = buyerId;
    }

    public String getBuyerName() {
        return buyerName;
    }

    public void setBuyerName(String buyerName) {
        this.buyerName = buyerName;
    }

    public String getBuyerEmail() {
        return buyerEmail;
    }

    public void setBuyerEmail(String buyerEmail) {
        this.buyerEmail = buyerEmail;
    }

    public UUID getSellerId() {
        return sellerId;
    }

    public void setSellerId(UUID sellerId) {
        this.sellerId = sellerId;
    }

    public String getSellerName() {
        return sellerName;
    }

    public void setSellerName(String sellerName) {
        this.sellerName = sellerName;
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

    public int getMilestoneCount() {
        return milestoneCount;
    }

    public void setMilestoneCount(int milestoneCount) {
        this.milestoneCount = milestoneCount;
    }

    public UUID getProjectManagerId() {
        return projectManagerId;
    }

    public void setProjectManagerId(UUID projectManagerId) {
        this.projectManagerId = projectManagerId;
    }

    public String getProjectManagerName() {
        return projectManagerName;
    }

    public void setProjectManagerName(String projectManagerName) {
        this.projectManagerName = projectManagerName;
    }

    public String getProjectManagerPmId() {
        return projectManagerPmId;
    }

    public void setProjectManagerPmId(String projectManagerPmId) {
        this.projectManagerPmId = projectManagerPmId;
    }

    public String getProjectManagerEmail() {
        return projectManagerEmail;
    }

    public void setProjectManagerEmail(String projectManagerEmail) {
        this.projectManagerEmail = projectManagerEmail;
    }

    public UUID getInvitedBy() {
        return invitedBy;
    }

    public void setInvitedBy(UUID invitedBy) {
        this.invitedBy = invitedBy;
    }

    public String getInvitedByName() {
        return invitedByName;
    }

    public void setInvitedByName(String invitedByName) {
        this.invitedByName = invitedByName;
    }

    public InvitationStatus getStatus() {
        return status;
    }

    public void setStatus(InvitationStatus status) {
        this.status = status;
    }

    public Instant getInvitedAt() {
        return invitedAt;
    }

    public void setInvitedAt(Instant invitedAt) {
        this.invitedAt = invitedAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public void setAcceptedAt(Instant acceptedAt) {
        this.acceptedAt = acceptedAt;
    }

    public Instant getDeclinedAt() {
        return declinedAt;
    }

    public void setDeclinedAt(Instant declinedAt) {
        this.declinedAt = declinedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(Instant cancelledAt) {
        this.cancelledAt = cancelledAt;
    }

    public String getDeclineReason() {
        return declineReason;
    }

    public void setDeclineReason(String declineReason) {
        this.declineReason = declineReason;
    }
}
