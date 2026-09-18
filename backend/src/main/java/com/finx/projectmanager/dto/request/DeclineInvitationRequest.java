package com.finx.projectmanager.dto.request;

public class DeclineInvitationRequest {

    private String reason;

    public DeclineInvitationRequest() {
    }

    public DeclineInvitationRequest(String reason) {
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
