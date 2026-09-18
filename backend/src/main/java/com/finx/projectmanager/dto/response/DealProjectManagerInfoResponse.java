package com.finx.projectmanager.dto.response;

import com.finx.auth.dto.response.UserSummaryResponse;

import java.util.UUID;

public class DealProjectManagerInfoResponse {

    private UUID dealId;
    private String projectId;
    private boolean hasProjectManager;
    private UserSummaryResponse activeProjectManager;
    private ProjectManagerInvitationResponse latestInvitation;

    public DealProjectManagerInfoResponse() {
    }

    public DealProjectManagerInfoResponse(UUID dealId, String projectId, boolean hasProjectManager,
                                         UserSummaryResponse activeProjectManager,
                                         ProjectManagerInvitationResponse latestInvitation) {
        this.dealId = dealId;
        this.projectId = projectId;
        this.hasProjectManager = hasProjectManager;
        this.activeProjectManager = activeProjectManager;
        this.latestInvitation = latestInvitation;
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

    public boolean isHasProjectManager() {
        return hasProjectManager;
    }

    public void setHasProjectManager(boolean hasProjectManager) {
        this.hasProjectManager = hasProjectManager;
    }

    public UserSummaryResponse getActiveProjectManager() {
        return activeProjectManager;
    }

    public void setActiveProjectManager(UserSummaryResponse activeProjectManager) {
        this.activeProjectManager = activeProjectManager;
    }

    public ProjectManagerInvitationResponse getLatestInvitation() {
        return latestInvitation;
    }

    public void setLatestInvitation(ProjectManagerInvitationResponse latestInvitation) {
        this.latestInvitation = latestInvitation;
    }
}
