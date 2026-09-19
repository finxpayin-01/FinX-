import { api } from '@/lib/api';

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
    }
};

export const financeService = {
    getDashboard: async () => {
        const { data } = await api.get('/finance/dashboard');
        return data.data || data;
    },
    getTransactions: async () => {
        const { data } = await api.get('/finance/transactions');
        return data.data || data;
    }
};
