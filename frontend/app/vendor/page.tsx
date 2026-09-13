"use client";

import { useEffect, useState } from "react";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card, CardContent } from "@/components/ui/Card";
import { Briefcase, Activity, ArrowRight, RefreshCw } from "lucide-react";
import { dealService } from "@/services/deal.service";
import { Deal } from "@/types";
import Link from "next/link";
import { Button } from "@/components/ui/Button";

export default function VendorDashboard() {
    const [deals, setDeals] = useState<Deal[]>([]);
    const [isLoading, setIsLoading] = useState(true);

    useEffect(() => {
        dealService.getDeals()
            .then(setDeals)
            .finally(() => setIsLoading(false));
    }, []);

    const totalDeals = deals.length;
    const activeStatuses = ['ACCEPTED', 'MILESTONES_PENDING', 'AWAITING_FUNDING', 'FUNDED', 'IN_PROGRESS'];
    const activeDeals = deals.filter(d => activeStatuses.includes(d.status)).length;
    const pendingDeals = deals.filter(d => d.status === 'PENDING_VENDOR_ACCEPTANCE');

    return (
        <DashboardLayout>
            <div className="space-y-6">
                <div>
                    <h1 className="text-2xl font-bold text-slate-900">Vendor Contracts & Deals</h1>
                    <p className="text-sm text-slate-500 mt-1">Manage assigned deals, respond to invitations, and submit deliverables.</p>
                </div>

                <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                    <Card className={pendingDeals.length > 0 ? "border-amber-300 bg-amber-50/40" : ""}>
                        <CardContent className="p-6">
                            <div className="flex items-center justify-between">
                                <div>
                                    <p className="text-sm font-medium text-slate-500">Pending Invitations</p>
                                    <p className={`text-3xl font-bold mt-2 ${pendingDeals.length > 0 ? "text-amber-600" : "text-slate-900"}`}>
                                        {pendingDeals.length}
                                    </p>
                                </div>
                                <div className="h-12 w-12 bg-amber-100 flex items-center justify-center rounded-full text-amber-600">
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
                                    <p className="text-3xl font-bold text-emerald-600 mt-2">{activeDeals}</p>
                                </div>
                                <div className="h-12 w-12 bg-emerald-50 flex items-center justify-center rounded-full text-emerald-600">
                                    <Activity size={24} />
                                </div>
                            </div>
                        </CardContent>
                    </Card>
                    <Card>
                        <CardContent className="p-6">
                            <div className="flex items-center justify-between">
                                <div>
                                    <p className="text-sm font-medium text-slate-500">Total Deals Assigned</p>
                                    <p className="text-3xl font-bold text-slate-900 mt-2">{totalDeals}</p>
                                </div>
                                <div className="h-12 w-12 bg-blue-50 flex items-center justify-center rounded-full text-blue-600">
                                    <Briefcase size={24} />
                                </div>
                            </div>
                        </CardContent>
                    </Card>
                </div>

                {/* Dedicated Pending Invitations Section */}
                {pendingDeals.length > 0 && (
                    <div className="mt-8">
                        <div className="flex justify-between items-center mb-3">
                            <div className="flex items-center gap-2">
                                <span className="flex h-3 w-3 relative">
                                    <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-amber-400 opacity-75"></span>
                                    <span className="relative inline-flex rounded-full h-3 w-3 bg-amber-500"></span>
                                </span>
                                <h2 className="text-lg font-semibold text-amber-950">Pending Project Invitations</h2>
                            </div>
                            <span className="text-xs font-semibold px-2.5 py-1 bg-amber-100 text-amber-800 rounded-full">
                                Action Required
                            </span>
                        </div>
                        <div className="grid grid-cols-1 gap-4">
                            {pendingDeals.map((deal) => (
                                <Card key={deal.id} className="border-amber-200 bg-amber-50/20 hover:border-amber-300 transition-colors shadow-xs">
                                    <CardContent className="p-6 flex flex-col md:flex-row items-start md:items-center justify-between gap-4">
                                        <div className="flex-1 space-y-1">
                                            <div className="flex items-center gap-3">
                                                <h3 className="font-bold text-slate-900 text-lg">{deal.title}</h3>
                                                <span className="px-2.5 py-0.5 text-xs font-semibold rounded-full bg-amber-100 text-amber-800 border border-amber-200">
                                                    Awaiting Your Acceptance
                                                </span>
                                            </div>
                                            <p className="text-sm text-slate-600 line-clamp-2 max-w-2xl">{deal.description}</p>
                                            <div className="flex items-center gap-4 text-xs text-slate-500 pt-1">
                                                <span>Value: <strong className="text-slate-800 font-semibold">{deal.currency} {deal.totalAmount?.toLocaleString()}</strong></span>
                                                <span>•</span>
                                                <span>Offered: {new Date(deal.createdAt).toLocaleDateString()}</span>
                                            </div>
                                        </div>
                                        <div className="flex items-center gap-3 shrink-0">
                                            <Link href={`/vendor/projects/${deal.id}`}>
                                                <Button className="gap-2 bg-amber-600 hover:bg-amber-700 text-white shadow-xs">
                                                    Review & Respond <ArrowRight size={16} />
                                                </Button>
                                            </Link>
                                        </div>
                                    </CardContent>
                                </Card>
                            ))}
                        </div>
                    </div>
                )}

                <div>
                    <div className="flex justify-between items-center mb-4 mt-8">
                        <h2 className="text-lg font-semibold text-slate-900">Assigned Deals</h2>
                        <Link href="/vendor/projects" className="text-sm text-blue-600 hover:underline">View All</Link>
                    </div>

                    <Card>
                        <div className="divide-y divide-slate-100">
                            {isLoading && <div className="p-8 text-center text-slate-500"><RefreshCw size={24} className="animate-spin mx-auto text-blue-500" /></div>}

                            {!isLoading && deals.length === 0 && (
                                <div className="p-4 text-sm text-slate-500 text-center py-8">No deals currently assigned to you.</div>
                            )}

                            {!isLoading && deals.slice(0, 5).map(m => (
                                <div key={m.id} className="p-6 flex flex-col md:flex-row items-center justify-between hover:bg-slate-50 gap-4">
                                    <div className="flex-1">
                                        <div className="flex items-center gap-3">
                                            <h3 className="font-semibold text-slate-900">{m.title}</h3>
                                            <span className="px-2.5 py-1 text-xs font-semibold rounded-full bg-slate-100 text-slate-700">
                                                {m.status.replace("_", " ")}
                                            </span>
                                        </div>
                                        <p className="text-sm text-slate-500 mt-1 max-w-xl truncate">{m.description}</p>
                                    </div>
                                    <div className="flex shrink-0">
                                        <Link href={`/vendor/projects/${m.id}`}>
                                            <Button className="gap-2 bg-slate-900 text-white hover:bg-slate-800">
                                                Open Workspace <ArrowRight size={16} />
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
