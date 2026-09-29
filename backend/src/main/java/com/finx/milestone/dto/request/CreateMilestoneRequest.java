package com.finx.milestone.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

public class CreateMilestoneRequest {

    private boolean invoiceRequired;
    public boolean isInvoiceRequired() { return invoiceRequired; }
    public void setInvoiceRequired(boolean value) { invoiceRequired=value; }

    @NotBlank(message = "Title is required")
    private String title;

    private String description;

    private Integer sequence;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
    private BigDecimal amount;

    @jakarta.validation.constraints.Pattern(regexp = "^(?i)INR$", message = "Currency must be INR")
    private String currency = "INR";

    private Instant dueDate;
    private Instant submissionDeadline;

    public CreateMilestoneRequest() {
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

    public Integer getSequence() {
        return sequence;
    }

    public void setSequence(Integer sequence) {
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
}
