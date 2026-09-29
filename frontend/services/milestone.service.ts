import { api } from "@/lib/api";
import { Milestone, Deliverable, ApiResponse } from "@/types";

export interface CreateMilestonePayload {
    invoiceRequired?: boolean;
    title: string;
    description?: string;
    sequence?: number;
    amount: number;
    currency?: string;
    dueDate?: string;
    submissionDeadline?: string;
}

export interface SubmitDeliverablePayload {
    fileName: string;
    fileUrl: string;
    description?: string;
}

export interface UpdateDeadlinePayload {
    newDeadline: string;
    reason?: string;
}

export const milestoneService = {
    getProjectMilestones: async (dealId: string): Promise<Milestone[]> => {
        const response = await api.get<ApiResponse<Milestone[]> | Milestone[]>(`/deals/${dealId}/milestones`);
        const data = (response.data as ApiResponse<Milestone[]>)?.data || (response.data as Milestone[]);
        return data || [];
    },

    getMilestone: async (id: string): Promise<Milestone> => {
        const response = await api.get<ApiResponse<Milestone> | Milestone>(`/milestones/${id}`);
        const data = (response.data as ApiResponse<Milestone>)?.data || (response.data as Milestone);
        return data;
    },

    getAllMilestones: async (): Promise<Milestone[]> => {
        const response = await api.get<ApiResponse<Milestone[]> | Milestone[]>("/milestones");
        const data = (response.data as ApiResponse<Milestone[]>)?.data || (response.data as Milestone[]);
        return data || [];
    },

    getUpcomingMilestones: async (): Promise<Milestone[]> => {
        const response = await api.get<ApiResponse<Milestone[]> | Milestone[]>("/milestones/upcoming");
        const data = (response.data as ApiResponse<Milestone[]>)?.data || (response.data as Milestone[]);
        return data || [];
    },

    getOverdueMilestones: async (): Promise<Milestone[]> => {
        const response = await api.get<ApiResponse<Milestone[]> | Milestone[]>("/milestones/overdue");
        const data = (response.data as ApiResponse<Milestone[]>)?.data || (response.data as Milestone[]);
        return data || [];
    },

    createMilestone: async (dealId: string, payload: CreateMilestonePayload): Promise<Milestone> => {
        const response = await api.post<ApiResponse<Milestone> | Milestone>(`/deals/${dealId}/milestones`, payload);
        const data = (response.data as ApiResponse<Milestone>)?.data || (response.data as Milestone);
        return data;
    },

    updateDeadline: async (id: string, payload: UpdateDeadlinePayload): Promise<Milestone> => {
        const response = await api.patch<ApiResponse<Milestone> | Milestone>(`/milestones/${id}/deadline`, payload);
        const data = (response.data as ApiResponse<Milestone>)?.data || (response.data as Milestone);
        return data;
    },

    startMilestone: async (id: string): Promise<Milestone> => {
        const response = await api.post<ApiResponse<Milestone> | Milestone>(`/milestones/${id}/start`);
        const data = (response.data as ApiResponse<Milestone>)?.data || (response.data as Milestone);
        return data;
    },

    submitDeliverable: async (id: string, payload: SubmitDeliverablePayload): Promise<Deliverable> => {
        const response = await api.post<ApiResponse<Deliverable> | Deliverable>(`/milestones/${id}/submit`, payload);
        const data = (response.data as ApiResponse<Deliverable>)?.data || (response.data as Deliverable);
        return data;
    },

    approveMilestone: async (id: string): Promise<Milestone> => {
        const response = await api.post<ApiResponse<Milestone> | Milestone>(`/milestones/${id}/approve`);
        const data = (response.data as ApiResponse<Milestone>)?.data || (response.data as Milestone);
        return data;
    },

    rejectMilestone: async (id: string, reason: string): Promise<Milestone> => {
        const response = await api.post<ApiResponse<Milestone> | Milestone>(`/milestones/${id}/reject`, { reason });
        const data = (response.data as ApiResponse<Milestone>)?.data || (response.data as Milestone);
        return data;
    },

    uploadDeliverable: async (id: string, payload: SubmitDeliverablePayload): Promise<Deliverable> => {
        return milestoneService.submitDeliverable(id, payload);
    },

    submitMilestone: async (id: string, payload?: SubmitDeliverablePayload): Promise<Deliverable | Milestone> => {
        if (payload) {
            return milestoneService.submitDeliverable(id, payload);
        }
        return milestoneService.startMilestone(id);
    },

    requestChanges: async (id: string, reason: string): Promise<Milestone> => {
        return milestoneService.rejectMilestone(id, reason);
    },

    triggerScheduler: async (): Promise<any> => {
        const response = await api.post("/milestones/scheduler/run");
        return response.data;
    },

    setSimulationClock: async (isoInstant: string): Promise<any> => {
        const response = await api.post("/milestones/simulation/clock", { instant: isoInstant });
        return response.data;
    }
};
