import { api } from "@/lib/api";
import { Deal, SellerSummary, ApiResponse } from "@/types";

export interface CreateDealRequest {
    title: string;
    description: string;
    sellerId: string;
    totalAmount: number;
    currency: string;
}

export const dealService = {
    getDeals: async (): Promise<Deal[]> => {
        const response = await api.get<ApiResponse<Deal[]> | Deal[]>("/deals");
        const body: any = response.data;
        const data = body?.data !== undefined ? body.data : body;
        if (Array.isArray(data)) return data;
        if (Array.isArray(data?.content)) return data.content;
        return [];
    },

    getDealById: async (id: string): Promise<Deal> => {
        const response = await api.get<ApiResponse<Deal> | Deal>(`/deals/${id}`);
        const body: any = response.data;
        return (body?.data !== undefined ? body.data : body) as Deal;
    },

    createDeal: async (request: CreateDealRequest): Promise<Deal> => {
        const response = await api.post<ApiResponse<Deal> | Deal>("/deals", request);
        const body: any = response.data;
        return (body?.data !== undefined ? body.data : body) as Deal;
    },

    getSellers: async (): Promise<SellerSummary[]> => {
        const response = await api.get<ApiResponse<SellerSummary[]> | SellerSummary[]>("/deals/sellers");
        const body: any = response.data;
        const data = body?.data !== undefined ? body.data : body;
        if (Array.isArray(data)) return data;
        return [];
    },

    acceptDeal: async (id: string): Promise<Deal> => {
        const response = await api.patch<ApiResponse<Deal> | Deal>(`/deals/${id}/accept`);
        const body: any = response.data;
        return (body?.data !== undefined ? body.data : body) as Deal;
    },

    rejectDeal: async (id: string, reason?: string): Promise<Deal> => {
        const response = await api.post<ApiResponse<Deal> | Deal>(`/deals/${id}/reject`, { reason });
        const body: any = response.data;
        return (body?.data !== undefined ? body.data : body) as Deal;
    },

    cancelDeal: async (id: string): Promise<Deal> => {
        const response = await api.patch<ApiResponse<Deal> | Deal>(`/deals/${id}/cancel`);
        const body: any = response.data;
        return (body?.data !== undefined ? body.data : body) as Deal;
    }
};
