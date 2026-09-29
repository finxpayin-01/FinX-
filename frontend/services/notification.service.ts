import { api } from '@/lib/api';
import { ApiResponse } from '@/types';

export interface Notification {
    id: string;
    title: string;
    message: string;
    route: string;
    isRead: boolean;
    createdAt: string;
}

export const notificationService = {
    getNotifications: async (): Promise<Notification[]> => {
        try {
            const response = await api.get<ApiResponse<Notification[]> | Notification[]>('/notifications');
            const data = (response.data as ApiResponse<Notification[]>)?.data || (response.data as Notification[]);
            return data || [];
        } catch {
            return [];
        }
    },
    getUnreadCount: async (): Promise<number> => {
        try {
            const response = await api.get<any>('/notifications/unread-count');
            const data = response.data?.data || response.data;
            return typeof data?.unreadCount === 'number' ? data.unreadCount : 0;
        } catch {
            return 0;
        }
    },
    markAsRead: async (id: string) => {
        try {
            await api.patch(`/notifications/${id}/read`);
        } catch {
            // gracefully catch if transient error
        }
    },
    markAllAsRead: async () => {
        try {
            await api.patch('/notifications/read-all');
        } catch {
            // gracefully catch if transient error
        }
    }
};
