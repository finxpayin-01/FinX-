"use client";

import { useEffect, useState } from "react";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { MyTrustScore } from "@/components/trust/MyTrustScore";
import { Card, CardContent } from "@/components/ui/Card";
import { Briefcase, CreditCard, ShieldCheck, ArrowRight, ShieldAlert, RefreshCw } from "lucide-react";
import { dealService } from "@/services/deal.service";
import { Deal } from "@/types";
import Link from "next/link";
import { Button } from "@/components/ui/Button";

export default function CorporateDashboard() {
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
    const totalAmount = deals.reduce((sum, d) => sum + (d.totalAmount || 0), 0);

    return (
        <DashboardLayout>
            <div className="space-y-6">
                <MyTrustScore />
                <div>
                    <h1 className="text-2xl font-bold text-slate-900">Corporate Buyer Dashboard</h1>
                    <p className="text-sm text-slate-500 mt-1">Track active projects and B2B deals.</p>
                </div>

                <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                    <Card>
                        <CardContent className="p-6">
                            <div className="flex items-center justify-between">
                                <div>
                                    <p className="text-sm font-medium text-slate-500">Total Deals</p>
                                    <p className="text-3xl font-bold text-slate-900 mt-2">{totalDeals}</p>
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
                                    <p className="text-3xl font-bold text-emerald-600 mt-2">{activeDeals}</p>
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
                                    <p className="text-3xl font-bold text-slate-900 mt-2">${totalAmount.toLocaleString()}</p>
                                </div>
                                <div className="h-12 w-12 bg-purple-50 flex items-center justify-center rounded-full text-purple-600">
                                    <CreditCard size={24} />
                                </div>
                            </div>
                        </CardContent>
                    </Card>
                </div>

                <div className="grid grid-cols-1 gap-6 mt-8">
                    <div>
                        <div className="flex justify-between items-center mb-4">
                            <h2 className="text-lg font-semibold text-slate-900">Recent Deals</h2>
                            <Link href="/corporate/projects" className="text-sm text-blue-600 hover:underline">View All Projects</Link>
                        </div>
                        <Card>
                            <div className="divide-y divide-slate-100">
                                {isLoading && <div className="p-8 text-center text-slate-500"><RefreshCw size={24} className="animate-spin mx-auto text-blue-500" /></div>}
                                {!isLoading && deals.length === 0 && (
                                    <div className="p-8 text-center text-slate-500">No deals found.</div>
                                )}
                                {!isLoading && deals.slice(0, 5).map(deal => (
                                    <div key={deal.id} className="p-6 flex items-center justify-between hover:bg-slate-50 gap-4">
                                        <div>
                                            <h3 className="font-semibold text-slate-900">{deal.title}</h3>
                                            <p className="text-sm text-slate-500 mt-1">Amount: ${deal.totalAmount?.toLocaleString()} {deal.currency} • Status: {deal.status}</p>
                                        </div>
                                        <div className="flex shrink-0">
                                            <Link href={`/corporate/projects/${deal.id}`}>
                                                <Button className="bg-slate-900 text-white gap-2">View Deal <ArrowRight size={16} /></Button>
                                            </Link>
                                        </div>
                                    </div>
                                ))}
                            </div>
                        </Card>
                    </div>
                </div>
            </div>
        </DashboardLayout>
    );
}
