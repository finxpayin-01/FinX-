import { api } from "@/lib/api";
import {
  ProjectManagerInvitation,
  DealProjectManagerInfo,
  ProjectManagerSummary,
  ApiResponse,
} from "@/types";

export interface InvitePmRequest {
  projectManagerId?: string;
  pmId?: string;
}

export interface DeclineInvitationRequest {
  reason?: string;
}

export const projectManagerService = {
  /**
   * Get all invitations sent to the currently logged in Project Manager.
   */
  getMyInvitations: async (): Promise<ProjectManagerInvitation[]> => {
    const response = await api.get<ApiResponse<ProjectManagerInvitation[]> | ProjectManagerInvitation[]>(
      "/project-manager/invitations"
    );
    const data =
      (response.data as ApiResponse<ProjectManagerInvitation[]>)?.data ||
      (response.data as ProjectManagerInvitation[]);
    return data || [];
  },

  /**
   * Get pending invitation count for badge display.
   */
  getPendingCount: async (): Promise<number> => {
    const response = await api.get<ApiResponse<{ pending: number }> | { pending: number }>(
      "/project-manager/invitations/count"
    );
    const data =
      (response.data as ApiResponse<{ pending: number }>)?.data ||
      (response.data as { pending: number });
    return data?.pending ?? 0;
  },

  /**
   * Get a specific invitation by ID.
   */
  getInvitationById: async (id: string): Promise<ProjectManagerInvitation> => {
    const response = await api.get<ApiResponse<ProjectManagerInvitation> | ProjectManagerInvitation>(
      `/project-manager/invitations/${id}`
    );
    const data =
      (response.data as ApiResponse<ProjectManagerInvitation>)?.data ||
      (response.data as ProjectManagerInvitation);
    return data;
  },

  /**
   * Accept an invitation (PM only).
   */
  acceptInvitation: async (id: string): Promise<ProjectManagerInvitation> => {
    const response = await api.post<ApiResponse<ProjectManagerInvitation> | ProjectManagerInvitation>(
      `/project-manager/invitations/${id}/accept`
    );
    const data =
      (response.data as ApiResponse<ProjectManagerInvitation>)?.data ||
      (response.data as ProjectManagerInvitation);
    return data;
  },

  /**
   * Decline an invitation (PM only).
   */
  declineInvitation: async (id: string, reason?: string): Promise<ProjectManagerInvitation> => {
    const response = await api.post<ApiResponse<ProjectManagerInvitation> | ProjectManagerInvitation>(
      `/project-manager/invitations/${id}/decline`,
      { reason }
    );
    const data =
      (response.data as ApiResponse<ProjectManagerInvitation>)?.data ||
      (response.data as ProjectManagerInvitation);
    return data;
  },

  /**
   * Get list of all eligible Project Managers for dropdown/selection.
   */
  getEligibleProjectManagers: async (): Promise<ProjectManagerSummary[]> => {
    const response = await api.get<ApiResponse<ProjectManagerSummary[]> | ProjectManagerSummary[]>(
      "/deals/project-managers"
    );
    const data =
      (response.data as ApiResponse<ProjectManagerSummary[]>)?.data ||
      (response.data as ProjectManagerSummary[]);
    return data || [];
  },

  /**
   * Get current PM status and invitation history for a specific deal.
   */
  getDealProjectManager: async (dealId: string): Promise<DealProjectManagerInfo> => {
    const response = await api.get<ApiResponse<DealProjectManagerInfo> | DealProjectManagerInfo>(
      `/deals/${dealId}/project-manager`
    );
    const data =
      (response.data as ApiResponse<DealProjectManagerInfo>)?.data ||
      (response.data as DealProjectManagerInfo);
    return data;
  },

  /**
   * Invite a PM to a deal (Buyer or Admin).
   */
  inviteProjectManager: async (
    dealId: string,
    request: InvitePmRequest
  ): Promise<ProjectManagerInvitation> => {
    const response = await api.post<ApiResponse<ProjectManagerInvitation> | ProjectManagerInvitation>(
      `/deals/${dealId}/project-manager/invitations`,
      request
    );
    const data =
      (response.data as ApiResponse<ProjectManagerInvitation>)?.data ||
      (response.data as ProjectManagerInvitation);
    return data;
  },

  /**
   * Cancel a pending PM invitation (Buyer or Admin).
   */
  cancelInvitation: async (
    dealId: string,
    invitationId: string
  ): Promise<ProjectManagerInvitation> => {
    const response = await api.post<ApiResponse<ProjectManagerInvitation> | ProjectManagerInvitation>(
      `/deals/${dealId}/project-manager/invitations/${invitationId}/cancel`
    );
    const data =
      (response.data as ApiResponse<ProjectManagerInvitation>)?.data ||
      (response.data as ProjectManagerInvitation);
    return data;
  },
};
