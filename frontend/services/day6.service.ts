import { api } from '@/lib/api';
import { ApiResponse, FinanceSummary, FinanceTransaction } from '@/types';

export const adminService = {
    getDashboard: async () => {
        const { data } = await api.get('/admin/dashboard');
        return data.data || data;
    },
    getUsers: async () => {
        const { data } = await api.get('/admin/users');
        return data.data || data;
    },
    getAuditLogs: async () => {
        const { data } = await api.get('/admin/audit-logs');
        return data.data || data;
    },
    resetPassword: async (userId: string, newPassword: string) => {
        const { data } = await api.post(`/admin/users/${userId}/reset-password`, { newPassword });
        return data.data || data;
    }
};

export const financeService = {
    getDashboard: async (): Promise<FinanceSummary> => {
        const res = await api.get<ApiResponse<FinanceSummary>>('/v1/finance/summary');
        return res.data.data || (res.data as unknown as FinanceSummary);
    },
    getTransactions: async (): Promise<FinanceTransaction[]> => {
        const res = await api.get<ApiResponse<FinanceTransaction[]>>('/v1/finance/transactions');
        return res.data.data || (res.data as unknown as FinanceTransaction[]);
    }
};
