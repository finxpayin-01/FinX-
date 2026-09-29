package com.finx.milestone.dto.response;

import com.finx.milestone.entity.Milestone;
import com.finx.milestone.entity.MilestoneStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class MilestoneResponse {

    private boolean invoiceRequired;
    public boolean isInvoiceRequired() { return invoiceRequired; }
    public void setInvoiceRequired(boolean value) { invoiceRequired=value; }

    private UUID id;
    private UUID dealId;
    private UUID projectId; // Frontend alias
    private String title;
    private String description;
    private int sequence;
    private BigDecimal amount;
    private String currency;
    private Instant dueDate;
    private Instant submissionDeadline;
    private boolean overdue;
    private String timeUntilDeadline;
    private MilestoneStatus status;
    private List<DeliverableResponse> deliverables = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;

    public MilestoneResponse() {
    }

    public static MilestoneResponse fromEntity(Milestone milestone) {
        MilestoneResponse response = new MilestoneResponse();
        response.setId(milestone.getId());
        response.setInvoiceRequired(milestone.isInvoiceRequired());
        response.setDealId(milestone.getDealId());
        response.setProjectId(milestone.getDealId());
        response.setTitle(milestone.getTitle());
        response.setDescription(milestone.getDescription());
        response.setSequence(milestone.getSequence());
        response.setAmount(milestone.getAmount());
        response.setCurrency(milestone.getCurrency());
        response.setDueDate(milestone.getDueDate());
        response.setSubmissionDeadline(milestone.getSubmissionDeadline());

        Instant now = Instant.now();
        Instant deadline = milestone.getSubmissionDeadline();
        boolean isTerminal = milestone.getStatus() == MilestoneStatus.APPROVED ||
                milestone.getStatus() == MilestoneStatus.COMPLETED ||
                milestone.getStatus() == MilestoneStatus.CANCELLED;

        boolean isOverdue = milestone.getStatus() == MilestoneStatus.OVERDUE ||
                (!isTerminal && deadline != null && deadline.isBefore(now) &&
                 milestone.getStatus() != MilestoneStatus.SUBMITTED &&
                 milestone.getStatus() != MilestoneStatus.UNDER_REVIEW);
        response.setOverdue(isOverdue);

        if (deadline != null && !isTerminal) {
            response.setTimeUntilDeadline(formatTimeUntil(now, deadline));
        }

        response.setStatus(milestone.getStatus());
        if (milestone.getDeliverables() != null) {
            response.setDeliverables(milestone.getDeliverables().stream()
                    .map(DeliverableResponse::fromEntity)
                    .collect(Collectors.toList()));
        }
        response.setCreatedAt(milestone.getCreatedAt());
        response.setUpdatedAt(milestone.getUpdatedAt());
        return response;
    }

    private static String formatTimeUntil(Instant now, Instant deadline) {
        if (deadline.isBefore(now)) {
            java.time.Duration diff = java.time.Duration.between(deadline, now);
            long days = diff.toDays();
            long hours = diff.toHours() % 24;
            if (days > 0) return String.format("OVERDUE BY %d DAY%s", days, days > 1 ? "S" : "");
            if (hours > 0) return String.format("OVERDUE BY %d HOUR%s", hours, hours > 1 ? "S" : "");
            long mins = diff.toMinutes();
            return String.format("OVERDUE BY %d MINUTE%s", Math.max(1, mins), mins != 1 ? "S" : "");
        } else {
            java.time.Duration diff = java.time.Duration.between(now, deadline);
            long days = diff.toDays();
            long hours = diff.toHours() % 24;
            if (days >= 2) return String.format("Due in %d days", days);
            if (days == 1) return "Due tomorrow";
            if (hours > 0) return String.format("Due in %d hour%s", hours, hours > 1 ? "s" : "");
            long mins = diff.toMinutes();
            return String.format("Due in %d minute%s", Math.max(1, mins), mins != 1 ? "s" : "");
        }
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

    public UUID getProjectId() {
        return projectId;
    }

    public void setProjectId(UUID projectId) {
        this.projectId = projectId;
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

    public int getSequence() {
        return sequence;
    }

    public void setSequence(int sequence) {
        this.sequence = sequence;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Instant getDueDate() {
        return dueDate;
    }

    public void setDueDate(Instant dueDate) {
        this.dueDate = dueDate;
    }

    public Instant getSubmissionDeadline() {
        return submissionDeadline != null ? submissionDeadline : dueDate;
    }

    public void setSubmissionDeadline(Instant submissionDeadline) {
        this.submissionDeadline = submissionDeadline;
    }

    public boolean isOverdue() {
        return overdue;
    }

    public void setOverdue(boolean overdue) {
        this.overdue = overdue;
    }

    public String getTimeUntilDeadline() {
        return timeUntilDeadline;
    }

    public void setTimeUntilDeadline(String timeUntilDeadline) {
        this.timeUntilDeadline = timeUntilDeadline;
    }

    public MilestoneStatus getStatus() {
        return status;
    }

    public void setStatus(MilestoneStatus status) {
        this.status = status;
    }

    public List<DeliverableResponse> getDeliverables() {
        return deliverables;
    }

    public void setDeliverables(List<DeliverableResponse> deliverables) {
        this.deliverables = deliverables;
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
}
