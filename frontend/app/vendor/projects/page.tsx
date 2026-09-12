"use client";

import React, { useEffect, useState } from "react";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card, CardContent, CardHeader } from "@/components/ui/Card";
import { Button } from "@/components/ui/Button";
import { dealService } from "@/services/deal.service";
import { Deal, DealStatus } from "@/types";
import { RefreshCw, Search, Eye, AlertCircle } from "lucide-react";
import Link from "next/link";
import { useAuth } from "@/components/auth/AuthProvider";

export default function VendorProjectsPage() {
    const { user } = useAuth();
    const [deals, setDeals] = useState<Deal[]>([]);
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [searchQuery, setSearchQuery] = useState("");
    const [statusFilter, setStatusFilter] = useState<DealStatus | "ALL">("ALL");

    const fetchDeals = async () => {
        setIsLoading(true);
        setError(null);
        try {
            const data = await dealService.getDeals();
            setDeals(data);
        } catch (err: any) {
            setError(err?.response?.data?.message || err.message || "Failed to fetch deals");
        } finally {
            setIsLoading(false);
        }
    };

    useEffect(() => {
        fetchDeals();
    }, []);

    const StatusBadge = ({ status }: { status: DealStatus }) => {
        const styles: Record<string, string> = {
            DRAFT: "bg-gray-100 text-gray-700",
            PENDING_ACCEPTANCE: "bg-amber-100 text-amber-800 border border-amber-200",
            ACTIVE: "bg-blue-100 text-blue-700",
            COMPLETED: "bg-emerald-100 text-emerald-700",
            CANCELLED: "bg-red-100 text-red-700",
            DISPUTED: "bg-orange-100 text-orange-700",
            REJECTED: "bg-rose-100 text-rose-700 border border-rose-200"
        };
        const defaultStyle = "bg-slate-100 text-slate-700";
        const label = status === "PENDING_ACCEPTANCE" ? "Awaiting Acceptance" : status.replace("_", " ");
        return (
            <span className={`px-2.5 py-1 text-xs font-semibold rounded-full ${styles[status] || defaultStyle}`}>
                {label}
            </span>
        );
    };

    const filteredDeals = deals.filter(deal => {
        const matchesSearch = deal.title.toLowerCase().includes(searchQuery.toLowerCase()) ||
            deal.description?.toLowerCase().includes(searchQuery.toLowerCase());
        const matchesStatus = statusFilter === "ALL" || deal.status === statusFilter;
        return matchesSearch && matchesStatus;
    }).sort((a, b) => new Date(b.createdAt || 0).getTime() - new Date(a.createdAt || 0).getTime());

    return (
        <DashboardLayout>
            <div className="space-y-6">
                <div>
                    <h1 className="text-2xl font-bold text-slate-900">My Assigned Projects (Deals)</h1>
                    <p className="text-sm text-slate-500 mt-1">View all your B2B deals securely.</p>
                </div>

                <Card>
                    <CardHeader className="flex flex-col sm:flex-row gap-4 justify-between pb-4 border-b border-slate-100">
                        <div className="flex flex-1 gap-4 items-center">
                            <div className="relative max-w-sm w-full">
                                <Search className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" size={18} />
                                <input
                                    className="pl-10 h-10 w-full rounded-md border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                                    placeholder="Search by title..."
                                    value={searchQuery}
                                    onChange={e => setSearchQuery(e.target.value)}
                                />
                            </div>
                            <select
                                className="h-10 rounded-md border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                                value={statusFilter}
                                onChange={e => setStatusFilter(e.target.value as any)}
                            >
                                <option value="ALL" className="text-slate-900 bg-white">All Statuses</option>
                                <option value="PENDING_ACCEPTANCE" className="text-slate-900 bg-white">Pending Acceptance</option>
                                <option value="ACTIVE" className="text-slate-900 bg-white">Active</option>
                                <option value="DRAFT" className="text-slate-900 bg-white">Draft</option>
                                <option value="COMPLETED" className="text-slate-900 bg-white">Completed</option>
                                <option value="REJECTED" className="text-slate-900 bg-white">Rejected</option>
                                <option value="CANCELLED" className="text-slate-900 bg-white">Cancelled</option>
                                <option value="DISPUTED" className="text-slate-900 bg-white">Disputed</option>
                            </select>
                        </div>
                        <Button variant="outline" size="sm" onClick={fetchDeals} disabled={isLoading} className="gap-2 text-slate-600">
                            <RefreshCw size={14} className={isLoading ? "animate-spin" : ""} /> Refresh
                        </Button>
                    </CardHeader>
                    <CardContent className="p-0">
                        {error && (
                            <div className="p-6 text-center text-red-600 flex flex-col items-center">
                                <AlertCircle className="mb-2" size={24} />
                                <p>{error}</p>
                                <Button variant="outline" className="mt-4" onClick={fetchDeals}>Try Again</Button>
                            </div>
                        )}

                        {!error && isLoading && (
                            <div className="p-12 text-center text-slate-500 flex flex-col items-center">
                                <RefreshCw size={32} className="animate-spin text-blue-500 mb-4" />
                                <p>Loading your assigned deals...</p>
                            </div>
                        )}

                        {!error && !isLoading && filteredDeals.length === 0 && (
                            <div className="p-12 text-center text-slate-500">
                                <p>No deals found. You have no active assignments.</p>
                            </div>
                        )}

                        {!error && !isLoading && filteredDeals.length > 0 && (
                            <div className="overflow-x-auto">
                                <table className="w-full text-sm text-left">
                                    <thead className="bg-slate-50 text-slate-600 font-medium border-b border-slate-100">
                                        <tr>
                                            <th className="px-6 py-4">Title</th>
                                            <th className="px-6 py-4">Amount</th>
                                            <th className="px-6 py-4">Status</th>
                                            <th className="px-6 py-4">Created</th>
                                            <th className="px-6 py-4 text-right">Action</th>
                                        </tr>
                                    </thead>
                                    <tbody className="divide-y divide-slate-100">
                                        {filteredDeals.map((deal) => (
                                            <tr key={deal.id} className="hover:bg-slate-50/50 transition-colors">
                                                <td className="px-6 py-4">
                                                    <div className="font-semibold text-slate-900">{deal.title}</div>
                                                    <div className="text-slate-500 text-xs mt-1 max-w-[200px] truncate">{deal.description}</div>
                                                </td>
                                                <td className="px-6 py-4 font-medium">${deal.totalAmount?.toLocaleString()} {deal.currency}</td>
                                                <td className="px-6 py-4">
                                                    <StatusBadge status={deal.status} />
                                                </td>
                                                <td className="px-6 py-4 text-slate-500">
                                                    {new Date(deal.createdAt).toLocaleDateString()}
                                                </td>
                                                <td className="px-6 py-4 text-right">
                                                    <Link href={`/vendor/projects/${deal.id}`}>
                                                        <Button variant="outline" size="sm" className="gap-2 h-8">
                                                            <Eye size={14} /> View
                                                        </Button>
                                                    </Link>
                                                </td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </div>
                        )}
                    </CardContent>
                </Card>
            </div>
        </DashboardLayout>
    );
}