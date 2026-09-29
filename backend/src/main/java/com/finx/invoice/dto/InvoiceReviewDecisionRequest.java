package com.finx.invoice.dto;

import com.finx.invoice.enums.ReviewDecision;
import jakarta.validation.constraints.NotNull;

public class InvoiceReviewDecisionRequest {

    @NotNull(message = "Review decision is required")
    private ReviewDecision decision;

    private String decisionReason;
    private String notes;
    private String evidenceReferences;

    public InvoiceReviewDecisionRequest() {
    }

    public InvoiceReviewDecisionRequest(ReviewDecision decision, String decisionReason, String notes) {
        this.decision = decision;
        this.decisionReason = decisionReason;
        this.notes = notes;
    }

    public ReviewDecision getDecision() {
        return decision;
    }

    public void setDecision(ReviewDecision decision) {
        this.decision = decision;
    }

    public String getDecisionReason() {
        return decisionReason;
    }

    public void setDecisionReason(String decisionReason) {
        this.decisionReason = decisionReason;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getEvidenceReferences() {
        return evidenceReferences;
    }

    public void setEvidenceReferences(String evidenceReferences) {
        this.evidenceReferences = evidenceReferences;
    }
}
