package com.finx.projectmanager.dto.request;

import jakarta.validation.constraints.NotBlank;

public class InviteProjectManagerRequest {

    @NotBlank(message = "Project Manager identifier (UUID, PM ID, or UID) is required")
    private String projectManagerId;

    public InviteProjectManagerRequest() {
    }

    public InviteProjectManagerRequest(String projectManagerId) {
        this.projectManagerId = projectManagerId;
    }

    public String getProjectManagerId() {
        return projectManagerId;
    }

    public void setProjectManagerId(String projectManagerId) {
        this.projectManagerId = projectManagerId;
    }
}
