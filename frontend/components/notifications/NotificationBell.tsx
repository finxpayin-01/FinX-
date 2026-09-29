"use client";

import { useState, useEffect } from "react";
import { Bell, Check } from "lucide-react";
import { notificationService, Notification } from "@/services/notification.service";
import { useRouter } from "next/navigation";

export function NotificationBell() {
    const [notifications, setNotifications] = useState<Notification[]>([]);
    const [isOpen, setIsOpen] = useState(false);
    const router = useRouter();

    const fetchNotifications = () => {
        notificationService.getNotifications().then(setNotifications).catch(() => { });
    };

    useEffect(() => {
        fetchNotifications();
        const interval = setInterval(fetchNotifications, 15000);
        return () => clearInterval(interval);
    }, []);

    const unreadCount = notifications.filter(n => !n.isRead).length;

    const handleRead = async (id: string, route: string) => {
        setIsOpen(false);
        await notificationService.markAsRead(id);
        fetchNotifications();
        if (route) {
            router.push(route);
        }
    };

    const handleReadAll = async () => {
        await notificationService.markAllAsRead();
        fetchNotifications();
    };

    return (
        <div className="relative">
            <button
                id="notification-bell-btn"
                data-testid="notification-bell-btn"
                onClick={() => setIsOpen(!isOpen)}
                className="relative p-2 text-slate-400 hover:text-blue-600 focus:outline-none focus:ring-2 focus:ring-blue-500 rounded-full transition-colors"
                aria-label="View notifications"
            >
                <Bell size={20} />
                {unreadCount > 0 && (
                    <span
                        id="notification-unread-count"
                        data-testid="notification-unread-badge"
                        className="notification-unread-badge absolute top-1 right-1 h-4 w-4 bg-red-500 text-white rounded-full flex items-center justify-center text-[10px] font-bold ring-2 ring-white animate-pulse"
                    >
                        {unreadCount}
                    </span>
                )}
            </button>

            {isOpen && (
                <div
                    id="notifications-dropdown"
                    data-testid="notifications-dropdown"
                    className="absolute right-0 mt-2 w-88 bg-white rounded-xl shadow-xl ring-1 ring-black/5 z-50 overflow-hidden transform origin-top-right transition-all border border-slate-200"
                >
                    <div className="p-4 border-b border-slate-100 flex justify-between items-center bg-slate-50">
                        <div className="flex items-center gap-2">
                            <h3 className="font-semibold text-slate-900 text-sm">Notifications</h3>
                            {unreadCount > 0 && (
                                <span className="bg-red-100 text-red-700 text-xs font-semibold px-2 py-0.5 rounded-full">
                                    {unreadCount} new
                                </span>
                            )}
                        </div>
                        {unreadCount > 0 && (
                            <button
                                id="mark-all-read-btn"
                                onClick={handleReadAll}
                                className="text-xs text-blue-600 hover:text-blue-800 font-medium flex items-center gap-1 transition-colors"
                            >
                                <Check size={12} /> Mark all read
                            </button>
                        )}
                    </div>
                    <div className="max-h-96 overflow-y-auto divide-y divide-slate-100">
                        {notifications.length === 0 ? (
                            <div className="p-6 text-center text-slate-500 text-sm" id="empty-notifications-msg">
                                You have no new notifications.
                            </div>
                        ) : notifications.map(n => (
                            <div
                                key={n.id}
                                id={`notification-item-${n.id}`}
                                data-testid="notification-item"
                                onClick={() => handleRead(n.id, n.route)}
                                className={`p-4 cursor-pointer hover:bg-slate-50 transition-colors flex gap-3 ${!n.isRead ? 'bg-blue-50/50' : ''}`}
                            >
                                <div className="mt-1">
                                    <div className={`h-2.5 w-2.5 rounded-full ${!n.isRead ? 'bg-blue-600' : 'bg-slate-200'}`}></div>
                                </div>
                                <div className="flex-1">
                                    <p className={`text-sm ${!n.isRead ? 'font-semibold text-slate-900' : 'text-slate-700'}`}>{n.title}</p>
                                    <p className="text-xs text-slate-500 mt-1 line-clamp-2 leading-relaxed">{n.message}</p>
                                    <p className="text-[10px] text-slate-400 mt-2 font-mono">{new Date(n.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</p>
                                </div>
                            </div>
                        ))}
                    </div>
                </div>
            )}
        </div>
    );
}
