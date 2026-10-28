"use client";

import { useEffect, useState, useCallback } from "react";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card, CardContent } from "@/components/ui/Card";
import {
    Briefcase,
    CreditCard,
    ShieldCheck,
    ArrowRight,
    RefreshCw,
    AlertCircle,
    CheckCircle2,
    Clock,
    PlusCircle,
    Layers
} from "lucide-react";
import { dealService } from "@/services/deal.service";
import { Deal } from "@/types";
import Link from "next/link";
import { Button } from "@/components/ui/Button";
import { OverdueMilestonesCard } from "@/components/milestones/OverdueMilestonesCard";
import { useAuth } from "@/components/auth/AuthProvider";

export default function CorporateDashboard() {
    const { user } = useAuth();
    const [deals, setDeals] = useState<Deal[]>([]);
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    const fetchDeals = useCallback(async () => {
        setIsLoading(true);
        setError(null);
        try {
            const data = await dealService.getDeals();
            setDeals(Array.isArray(data) ? data : []);
        } catch (err: any) {
            console.error("Failed to load corporate deals:", err);
            const msg = err?.response?.data?.message || err?.message || "Failed to connect to FINX backend.";
            setError(msg);
            setDeals([]);
        } finally {
            setIsLoading(false);
        }
    }, []);

    useEffect(() => {
        fetchDeals();
    }, [fetchDeals]);

    const totalDeals = deals.length;
    const activeStatuses = ['ACCEPTED', 'MILESTONES_PENDING', 'AWAITING_FUNDING', 'FUNDED', 'IN_PROGRESS'];
    const activeDeals = deals.filter(d => activeStatuses.includes(d.status)).length;
    const totalAmount = deals.reduce((sum, d) => sum + (d.totalAmount || 0), 0);

    const formatINR = (val: number) => {
        return "₹" + Number(val || 0).toLocaleString("en-IN", {
            maximumFractionDigits: 2,
            minimumFractionDigits: 0
        });
    };

    return (
        <DashboardLayout>
            <div className="space-y-6">
                <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4">
                    <div>
                        <h1 className="text-2xl font-bold text-slate-900">Corporate Buyer Dashboard</h1>
                        <p className="text-sm text-slate-500 mt-1">
                            Welcome back, <span className="font-semibold text-slate-800">{user?.name || user?.fullName || "Corporate Buyer"}</span>. Track active projects, milestone verifications, and escrow funding.
                        </p>
                    </div>
                    <div className="flex gap-2">
                        <Button variant="outline" size="sm" onClick={fetchDeals} disabled={isLoading} className="gap-2 text-slate-600">
                            <RefreshCw size={14} className={isLoading ? "animate-spin" : ""} /> Refresh
                        </Button>
                        <Link href="/corporate/projects">
                            <Button size="sm" className="gap-2">
                                <PlusCircle size={14} /> New Deal
                            </Button>
                        </Link>
                    </div>
                </div>

                {/* API Failure State Banner */}
                {error && (
                    <div className="p-4 bg-red-50 border border-red-200 rounded-xl flex items-start justify-between gap-3 text-red-700">
                        <div className="flex items-start gap-3">
                            <AlertCircle className="shrink-0 mt-0.5 text-red-600" size={20} />
                            <div>
                                <h4 className="text-sm font-semibold text-red-900">Backend Communication Error</h4>
                                <p className="text-xs text-red-700 mt-0.5">{error}</p>
                                <p className="text-[11px] text-red-500 mt-1">Please ensure the FINX backend service is running on http://localhost:8080.</p>
                            </div>
                        </div>
                        <Button variant="outline" size="sm" onClick={fetchDeals} className="border-red-300 text-red-700 hover:bg-red-100 shrink-0">
                            Retry
                        </Button>
                    </div>
                )}

                {/* Metric Summary Cards */}
                <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                    <Card>
                        <CardContent className="p-6">
                            <div className="flex items-center justify-between">
                                <div>
                                    <p className="text-sm font-medium text-slate-500">Total Deals</p>
                                    <p className="text-3xl font-bold text-slate-900 mt-2">
                                        {isLoading ? "—" : error ? "—" : totalDeals}
                                    </p>
                                    <p className="text-xs text-slate-400 mt-1">Authorized for this corporate entity</p>
                                </div>
                                <div className="h-12 w-12 bg-blue-50 flex items-center justify-center rounded-full text-blue-600">
                                    <Briefcase size={24} />
                                </div>
                            </div>
                        </CardContent>
                    </Card>

                    <Card>
                        <CardContent className="p-6">
                            <div className="flex items-center justify-between">
                                <div>
                                    <p className="text-sm font-medium text-slate-500">Active Deals</p>
                                    <p className="text-3xl font-bold text-emerald-600 mt-2">
                                        {isLoading ? "—" : error ? "—" : activeDeals}
                                    </p>
                                    <p className="text-xs text-slate-400 mt-1">In progress or funded in escrow</p>
                                </div>
                                <div className="h-12 w-12 bg-emerald-50 flex items-center justify-center rounded-full text-emerald-600">
                                    <ShieldCheck size={24} />
                                </div>
                            </div>
                        </CardContent>
                    </Card>

                    <Card>
                        <CardContent className="p-6">
                            <div className="flex items-center justify-between">
                                <div>
                                    <p className="text-sm font-medium text-slate-500">Total Pipeline Value</p>
                                    <p className="text-3xl font-bold text-slate-900 mt-2">
                                        {isLoading ? "—" : error ? "—" : formatINR(totalAmount)}
                                    </p>
                                    <p className="text-xs text-slate-400 mt-1">Contracted value across all deals (INR)</p>
                                </div>
                                <div className="h-12 w-12 bg-purple-50 flex items-center justify-center rounded-full text-purple-600">
                                    <CreditCard size={24} />
                                </div>
                            </div>
                        </CardContent>
                    </Card>
                </div>

                {/* Quick Navigation Hub to Required Corporate Areas */}
                <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
                    <Link href="/corporate/projects" className="p-4 bg-white border border-slate-200 rounded-lg hover:border-blue-400 hover:shadow-sm transition-all group">
                        <div className="flex items-center gap-2 text-slate-700 font-semibold text-sm group-hover:text-blue-600">
                            <Briefcase size={16} /> Deals & Agreements
                        </div>
                        <p className="text-xs text-slate-500 mt-1">View contracts and create proposals</p>
                    </Link>

                    <Link href="/corporate/milestones" className="p-4 bg-white border border-slate-200 rounded-lg hover:border-blue-400 hover:shadow-sm transition-all group">
                        <div className="flex items-center gap-2 text-slate-700 font-semibold text-sm group-hover:text-blue-600">
                            <Layers size={16} /> Milestones
                        </div>
                        <p className="text-xs text-slate-500 mt-1">Review work and approve releases</p>
                    </Link>

                    <Link href="/corporate/escrow" className="p-4 bg-white border border-slate-200 rounded-lg hover:border-blue-400 hover:shadow-sm transition-all group">
                        <div className="flex items-center gap-2 text-slate-700 font-semibold text-sm group-hover:text-blue-600">
                            <ShieldCheck size={16} /> Escrow Vault
                        </div>
                        <p className="text-xs text-slate-500 mt-1">Audit trust balances and allocations</p>
                    </Link>

                    <Link href="/corporate/payments" className="p-4 bg-white border border-slate-200 rounded-lg hover:border-blue-400 hover:shadow-sm transition-all group">
                        <div className="flex items-center gap-2 text-slate-700 font-semibold text-sm group-hover:text-blue-600">
                            <CreditCard size={16} /> Bank Payments
                        </div>
                        <p className="text-xs text-slate-500 mt-1">Payment orders and mock rail</p>
                    </Link>
                </div>

                {/* Overdue Milestones Alert */}
                <OverdueMilestonesCard roleContext="BUYER" />

                {/* Recent Deals Table / Listing */}
                <div className="mt-8">
                    <div className="flex justify-between items-center mb-4">
                        <div>
                            <h2 className="text-lg font-semibold text-slate-900">Recent Deals & Projects</h2>
                            <p className="text-xs text-slate-500">Live contracts associated with this buyer account</p>
                        </div>
                        <Link href="/corporate/projects" className="text-sm text-blue-600 hover:underline flex items-center gap-1">
                            View All Projects <ArrowRight size={14} />
                        </Link>
                    </div>

                    <Card>
                        <div className="divide-y divide-slate-100">
                            {isLoading && (
                                <div className="p-12 text-center text-slate-500 flex flex-col items-center">
                                    <RefreshCw size={28} className="animate-spin text-blue-500 mb-2" />
                                    <p className="text-sm">Retrieving your deals from the backend...</p>
                                </div>
                            )}

                            {!isLoading && error && (
                                <div className="p-8 text-center text-slate-500">
                                    <AlertCircle className="mx-auto text-amber-500 mb-2" size={24} />
                                    <p className="text-sm font-medium text-slate-700">Unable to load deals</p>
                                    <p className="text-xs text-slate-400 mt-1">Resolve the backend connection issue above to display active contracts.</p>
                                </div>
                            )}

                            {!isLoading && !error && deals.length === 0 && (
                                <div className="p-12 text-center text-slate-500">
                                    <Briefcase className="mx-auto text-slate-300 mb-3" size={36} />
                                    <p className="text-sm font-medium text-slate-800">No deals found for this account</p>
                                    <p className="text-xs text-slate-500 mt-1">You are authenticated, but no deals are assigned to your buyer identity.</p>
                                    <Link href="/corporate/projects" className="inline-block mt-4">
                                        <Button size="sm">Create First Deal</Button>
                                    </Link>
                                </div>
                            )}

                            {!isLoading && !error && deals.slice(0, 5).map(deal => (
                                <div key={deal.id} className="p-6 flex flex-col sm:flex-row sm:items-center justify-between hover:bg-slate-50 gap-4 transition-colors">
                                    <div>
                                        <div className="flex items-center gap-2">
                                            <h3 className="font-semibold text-slate-900">{deal.title}</h3>
                                            <span className="text-[10px] font-semibold px-2 py-0.5 rounded-full bg-blue-50 text-blue-700 border border-blue-200">
                                                {deal.status}
                                            </span>
                                        </div>
                                        <p className="text-xs text-slate-500 mt-1">
                                            Contract Value: <span className="font-semibold text-slate-700">{formatINR(deal.totalAmount)}</span> {deal.currency || "INR"}
                                            {deal.createdAt && ` • Created ${new Date(deal.createdAt).toLocaleDateString()}`}
                                        </p>
                                        {deal.description && (
                                            <p className="text-xs text-slate-500 mt-1 line-clamp-1 max-w-xl">
                                                {deal.description}
                                            </p>
                                        )}
                                    </div>
                                    <div className="flex shrink-0">
                                        <Link href={`/corporate/projects/${deal.id}`}>
                                            <Button variant="outline" size="sm" className="gap-2">
                                                View Deal <ArrowRight size={14} />
                                            </Button>
                                        </Link>
                                    </div>
                                </div>
                            ))}
                        </div>
                    </Card>
                </div>
            </div>
        </DashboardLayout>
    );
}
