package com.finx.milestone.dto.request;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public class UpdateDeadlineRequest {

    @NotNull(message = "Submission deadline is required")
    private Instant submissionDeadline;

    private String reason;

    public UpdateDeadlineRequest() {
    }

    public UpdateDeadlineRequest(Instant submissionDeadline, String reason) {
        this.submissionDeadline = submissionDeadline;
        this.reason = reason;
    }

    public Instant getSubmissionDeadline() {
        return submissionDeadline;
    }

    public void setSubmissionDeadline(Instant submissionDeadline) {
        this.submissionDeadline = submissionDeadline;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
