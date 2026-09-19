"use client";

import React, { useState, useEffect } from "react";
import { useAuth } from "@/components/auth/AuthProvider";
import Link from "next/link";
import { usePathname } from "next/navigation";
import {
    Users, Briefcase, CreditCard, ShieldCheck,
    Settings, LogOut, Menu, Bell, X, FileText, FileSpreadsheet, Activity, CheckSquare, Mail
} from "lucide-react";
import { NotificationBell } from "@/components/notifications/NotificationBell";
import { projectManagerService } from "@/services/project-manager.service";

interface NavLinkItem {
    name: string;
    href: string;
    icon: any;
    badge?: number;
}

export const DashboardLayout = ({ children }: { children: React.ReactNode }) => {
    const { user, logout } = useAuth();
    const pathname = usePathname();
    const [isMobileMenuOpen, setIsMobileMenuOpen] = useState(false);
    const [pendingInvitesCount, setPendingInvitesCount] = useState<number>(0);

    useEffect(() => {
        if (user?.role === "PROJECT_MANAGER") {
            projectManagerService.getPendingCount()
                .then(count => setPendingInvitesCount(count))
                .catch(() => {});
        }
    }, [user?.role, pathname]);

    // Helper Breadcrumbs
    const pathSegments = pathname.split('/').filter(Boolean);
    const breadcrumbs = pathSegments.map((segment, index) => {
        const href = '/' + pathSegments.slice(0, index + 1).join('/');
        return { name: segment.charAt(0).toUpperCase() + segment.slice(1).replace('-', ' '), href };
    });

    const getNavLinks = (): NavLinkItem[] => {
        const baseLinks: NavLinkItem[] = [
            { name: "Blockchain Escrow", href: "/blockchain", icon: ShieldCheck },
            { name: "Compliance (KYC)", href: "/kyc", icon: ShieldCheck },
            { name: "Settings", href: `/${(user?.role === 'BUYER' ? 'corporate' : user?.role === 'SELLER' ? 'vendor' : user?.role?.toLowerCase().replace('_', '-') || 'corporate')}/settings`, icon: Settings },
        ];

        switch (user?.role) {
            case "ADMIN":
                return [
                    { name: "Overview", href: "/admin", icon: Activity },
                    { name: "Users", href: "/admin/users", icon: Users },
                    { name: "Projects", href: "/admin/projects", icon: Briefcase },
                    { name: "Escrow Transactions", href: "/admin/escrow", icon: ShieldCheck },
                    { name: "Disputes", href: "/admin/disputes", icon: ShieldCheck },
                    { name: "Audit Logs", href: "/admin/audit-logs", icon: FileText },
                    ...baseLinks
                ];
            case "FINANCE":
                return [
                    { name: "Overview", href: "/finance", icon: Activity },
                    { name: "Payments", href: "/finance/payments", icon: CreditCard },
                    { name: "Invoices", href: "/finance/invoices", icon: FileSpreadsheet },
                    { name: "Transactions", href: "/finance/transactions", icon: Briefcase },
                    { name: "Reports", href: "/finance/reports", icon: FileText },
                    ...baseLinks
                ];
            case "PROJECT_MANAGER":
                return [
                    { name: "Overview", href: "/project-manager", icon: Activity },
                    { name: "Invitations", href: "/project-manager/invitations", icon: Mail, badge: pendingInvitesCount > 0 ? pendingInvitesCount : undefined },
                    { name: "Projects", href: "/project-manager/projects", icon: Briefcase },
                    { name: "Milestone Reviews", href: "/project-manager/reviews", icon: CheckSquare },
                    { name: "Deliverables", href: "/project-manager/deliverables", icon: FileText },
                    { name: "Reports", href: "/project-manager/reports", icon: FileSpreadsheet },
                    ...baseLinks
                ];
            case "VENDOR":
            case "SELLER":
                return [
                    { name: "Overview", href: "/vendor", icon: Activity },
                    { name: "My Projects", href: "/vendor/projects", icon: Briefcase },
                    { name: "Milestones", href: "/vendor/milestones", icon: CheckSquare },
                    { name: "Deliverables", href: "/vendor/deliverables", icon: FileText },
                    { name: "Payments", href: "/vendor/payments", icon: CreditCard },
                    { name: "Escrow", href: "/vendor/escrow", icon: ShieldCheck },
                    ...baseLinks
                ];
            case "CORPORATE":
            case "BUYER":
            default:
                return [
                    { name: "Overview", href: "/corporate", icon: Activity },
                    { name: "Projects", href: "/corporate/projects", icon: Briefcase },
                    { name: "Milestones", href: "/corporate/milestones", icon: CheckSquare },
                    { name: "Payments", href: "/corporate/payments", icon: CreditCard },
                    { name: "Escrow", href: "/corporate/escrow", icon: ShieldCheck },
                    ...baseLinks
                ];
        }
    };

    const navLinks = getNavLinks();

    return (
        <div className="flex h-screen bg-slate-50">
            {/* Sidebar for Desktop */}
            <aside className="hidden md:flex flex-col w-64 bg-white border-r border-slate-200">
                <div className="h-16 flex items-center px-6 border-b border-slate-200">
                    <span className="text-xl font-bold text-blue-600 tracking-tight">FINX</span>
                </div>

                <nav className="flex-1 py-6 px-4 space-y-1 overflow-y-auto">
                    {navLinks.map((link) => {
                        const Icon = link.icon;
                        const isActive = pathname === link.href || (pathname.startsWith(link.href) && link.href !== `/${user?.role?.toLowerCase().replace('_', '-')}`);
                        return (
                            <Link
                                key={link.name}
                                href={link.href}
                                className={`flex items-center justify-between px-3 py-2 rounded-md text-sm font-medium transition-colors ${isActive
                                    ? "bg-blue-50 text-blue-700"
                                    : "text-slate-600 hover:bg-slate-50 hover:text-slate-900"
                                    }`}
                            >
                                <div className="flex items-center gap-3">
                                    <Icon size={18} />
                                    {link.name}
                                </div>
                                {link.badge !== undefined && (
                                    <span className="px-2 py-0.5 text-xs font-bold rounded-full bg-amber-500 text-white leading-none">
                                        {link.badge}
                                    </span>
                                )}
                            </Link>
                        );
                    })}
                </nav>

                <div className="p-4 border-t border-slate-200">
                    <div className="flex items-center gap-4">
                        <NotificationBell />
                        <div className="w-8 h-8 rounded-full bg-blue-100 border border-blue-200 shadow-sm flex items-center justify-center text-blue-700 font-bold text-sm">
                            {(user?.name || user?.fullName || "User").charAt(0).toUpperCase()}
                        </div>
                        <div className="flex-1 min-w-0">
                            <p className="text-sm font-medium text-slate-900 truncate">{user?.name || user?.fullName || "User"}</p>
                            <p className="text-xs text-slate-500 truncate">{user?.role?.replace('_', ' ')}</p>
                        </div>
                    </div>
                    <button
                        onClick={logout}
                        className="flex items-center gap-3 px-3 py-2 w-full rounded-md text-sm font-medium text-slate-600 hover:bg-red-50 hover:text-red-700 transition-colors"
                    >
                        <LogOut size={18} />
                        Sign Out
                    </button>
                </div>
            </aside>

            {/* Mobile Menu Backdrop */}
            {isMobileMenuOpen && (
                <div
                    className="fixed inset-0 bg-black bg-opacity-50 z-40 md:hidden"
                    onClick={() => setIsMobileMenuOpen(false)}
                />
            )}

            {/* Mobile Sidebar */}
            <aside className={`fixed inset-y-0 left-0 bg-white w-64 z-50 transform transition-transform duration-200 ease-in-out md:hidden flex flex-col ${isMobileMenuOpen ? 'translate-x-0' : '-translate-x-full'}`}>
                <div className="h-16 flex items-center justify-between px-6 border-b border-slate-200">
                    <span className="text-xl font-bold text-blue-600 tracking-tight">FINX</span>
                    <button onClick={() => setIsMobileMenuOpen(false)} className="text-slate-500 hover:text-slate-700">
                        <X size={20} />
                    </button>
                </div>

                <nav className="flex-1 py-6 px-4 space-y-1 overflow-y-auto">
                    {navLinks.map((link) => {
                        const Icon = link.icon;
                        const isActive = pathname === link.href;
                        return (
                            <Link
                                key={link.name}
                                href={link.href}
                                onClick={() => setIsMobileMenuOpen(false)}
                                className={`flex items-center justify-between px-3 py-2 rounded-md text-sm font-medium transition-colors ${isActive
                                    ? "bg-blue-50 text-blue-700"
                                    : "text-slate-600 hover:bg-slate-50 hover:text-slate-900"
                                    }`}
                            >
                                <div className="flex items-center gap-3">
                                    <Icon size={18} />
                                    {link.name}
                                </div>
                                {link.badge !== undefined && (
                                    <span className="px-2 py-0.5 text-xs font-bold rounded-full bg-amber-500 text-white leading-none">
                                        {link.badge}
                                    </span>
                                )}
                            </Link>
                        );
                    })}
                </nav>

                <div className="p-4 border-t border-slate-200">
                    <button
                        onClick={logout}
                        className="flex items-center gap-3 px-3 py-2 w-full rounded-md text-sm font-medium text-slate-600 hover:text-red-600 hover:bg-red-50 transition-colors"
                    >
                        <LogOut size={18} />
                        Sign Out
                    </button>
                </div>
            </aside>

            {/* Main Content */}
            <main className="flex-1 flex flex-col min-w-0 overflow-hidden">
                {/* Top Navbar */}
                <header className="h-16 flex items-center justify-between px-6 bg-white border-b border-slate-200">
                    <div className="flex items-center gap-4">
                        <button
                            className="md:hidden text-slate-500 hover:text-slate-700"
                            onClick={() => setIsMobileMenuOpen(true)}
                        >
                            <Menu size={20} />
                        </button>
                        <div className="hidden sm:flex items-center text-sm">
                            {breadcrumbs.map((crumb, idx) => (
                                <React.Fragment key={crumb.href}>
                                    {idx > 0 && <span className="mx-2 text-slate-300">/</span>}
                                    <Link href={crumb.href} className={`${idx === breadcrumbs.length - 1 ? 'text-slate-800 font-semibold' : 'text-slate-500 hover:text-slate-700'}`}>
                                        {crumb.name}
                                    </Link>
                                </React.Fragment>
                            ))}
                            {breadcrumbs.length === 0 && <span className="text-slate-800 font-semibold">Dashboard</span>}
                        </div>
                    </div>

                    <div className="flex items-center gap-4">
                        <button className="relative p-2 text-slate-400 hover:text-slate-500 transition-colors">
                            <span className="absolute top-1 right-1 h-2 w-2 rounded-full bg-blue-500"></span>
                            <Bell size={20} />
                        </button>
                    </div>
                </header>

                {/* Page Content */}
                <div className="flex-1 overflow-auto p-6">
                    <div className="max-w-7xl mx-auto">
                        {children}
                    </div>
                </div>
            </main>
        </div>
    );
};
