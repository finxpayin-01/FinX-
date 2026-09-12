package com.finx.deal.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

public class RejectDealRequest {

    @Schema(description = "Reason for rejecting the project offer", example = "Project timeline conflicts with existing delivery commitments")
    private String reason;

    public RejectDealRequest() {
    }

    public RejectDealRequest(String reason) {
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
