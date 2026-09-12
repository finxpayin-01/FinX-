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
        const data = (response.data as ApiResponse<Deal[]>)?.data || (response.data as Deal[]);
        return data || [];
    },

    getDealById: async (id: string): Promise<Deal> => {
        const response = await api.get<ApiResponse<Deal> | Deal>(`/deals/${id}`);
        const data = (response.data as ApiResponse<Deal>)?.data || (response.data as Deal);
        return data;
    },

    createDeal: async (request: CreateDealRequest): Promise<Deal> => {
        const response = await api.post<ApiResponse<Deal> | Deal>("/deals", request);
        const data = (response.data as ApiResponse<Deal>)?.data || (response.data as Deal);
        return data;
    },

    getSellers: async (): Promise<SellerSummary[]> => {
        const response = await api.get<ApiResponse<SellerSummary[]> | SellerSummary[]>("/deals/sellers");
        const data = (response.data as ApiResponse<SellerSummary[]>)?.data || (response.data as SellerSummary[]);
        return data || [];
    },

    acceptDeal: async (id: string): Promise<Deal> => {
        const response = await api.patch<ApiResponse<Deal> | Deal>(`/deals/${id}/accept`);
        const data = (response.data as ApiResponse<Deal>)?.data || (response.data as Deal);
        return data;
    },

    rejectDeal: async (id: string, reason?: string): Promise<Deal> => {
        const response = await api.post<ApiResponse<Deal> | Deal>(`/deals/${id}/reject`, { reason });
        const data = (response.data as ApiResponse<Deal>)?.data || (response.data as Deal);
        return data;
    },

    cancelDeal: async (id: string): Promise<Deal> => {
        const response = await api.patch<ApiResponse<Deal> | Deal>(`/deals/${id}/cancel`);
        const data = (response.data as ApiResponse<Deal>)?.data || (response.data as Deal);
        return data;
    }
};
