"use client";

import { useEffect, useState } from "react";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card, CardContent } from "@/components/ui/Card";
import { ShieldCheck, Activity, ArrowRight, RefreshCw, AlertCircle, HardHat } from "lucide-react";
import { dealService } from "@/services/deal.service";
import { Deal } from "@/types";
import Link from "next/link";
import { Button } from "@/components/ui/Button";
import { EscrowRailView } from "@/components/escrow/EscrowRailView";

export default function BuyerEscrowDashboard() { return <EscrowRailView><LegacyBuyerEscrowDashboard/></EscrowRailView>; }
function LegacyBuyerEscrowDashboard() {
    const [deals, setDeals] = useState<Deal[]>([]);
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    const fetchDeals = async () => {
        setIsLoading(true);
        setError(null);
        try {
            const data = await dealService.getDeals();
            setDeals(data);
        } catch (err: any) {
            setError(err?.response?.data?.message || err.message || "Failed to fetch escrow deals");
        } finally {
            setIsLoading(false);
        }
    };

    useEffect(() => {
        fetchDeals();
    }, []);

    const totalDeals = deals.length;
    const activeStatuses = ['ACCEPTED', 'MILESTONES_PENDING', 'AWAITING_FUNDING', 'FUNDED', 'IN_PROGRESS'];
    const activeDeals = deals.filter(d => activeStatuses.includes(d.status)).length;
    const totalPipelineValue = deals.reduce((sum, d) => sum + (d.totalAmount || 0), 0);
    const activeValue = deals.filter(d => activeStatuses.includes(d.status)).reduce((sum, d) => sum + (d.totalAmount || 0), 0);

    return (
        <DashboardLayout>
            <div className="space-y-6">
                <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4">
                    <div>
                        <h1 className="text-2xl font-bold text-slate-900">Escrow Vault Overview</h1>
                        <p className="text-sm text-slate-500 mt-1">Track funds allocated across active corporate contracts and deals.</p>
                    </div>
                    <Button variant="outline" size="sm" onClick={fetchDeals} disabled={isLoading} className="gap-2">
                        <RefreshCw size={14} className={isLoading ? "animate-spin" : ""} /> Refresh
                    </Button>
                </div>

                {/* API Status Notice */}
                <div className="p-4 bg-emerald-50 border border-emerald-200 rounded-xl flex items-start gap-3">
                    <ShieldCheck size={20} className="text-emerald-600 shrink-0 mt-0.5" />
                    <div>
                        <h4 className="text-sm font-semibold text-emerald-900">B2B Fiat Escrow Engine Active</h4>
                        <p className="text-xs text-emerald-800 mt-1 leading-relaxed">
                            Each deal is backed by a dedicated escrow account and double-entry immutable ledger in the Spring Boot backend with complete auditability.
                        </p>
                    </div>
                </div>

                <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                    <Card>
                        <CardContent className="p-6">
                            <p className="text-sm font-medium text-slate-500">Total Contract Value</p>
                            <p className="text-3xl font-bold text-slate-900 mt-2">${totalPipelineValue.toLocaleString()}</p>
                        </CardContent>
                    </Card>
                    <Card>
                        <CardContent className="p-6">
                            <p className="text-sm font-medium text-slate-500">Active Deals In Escrow</p>
                            <p className="text-3xl font-bold text-blue-600 mt-2">${activeValue.toLocaleString()}</p>
                        </CardContent>
                    </Card>
                    <Card>
                        <CardContent className="p-6">
                            <p className="text-sm font-medium text-slate-500">Active Projects</p>
                            <p className="text-3xl font-bold text-emerald-600 mt-2">{activeDeals} <span className="text-sm font-normal text-slate-500">of {totalDeals} total</span></p>
                        </CardContent>
                    </Card>
                </div>

                <div className="flex justify-between items-center mt-8 mb-4">
                    <h2 className="text-lg font-semibold text-slate-900">Registered Deal Escrows</h2>
                </div>

                <Card>
                    <div className="divide-y divide-slate-100">
                        {error && (
                            <div className="p-6 text-center text-red-600 flex flex-col items-center">
                                <AlertCircle className="mb-2" size={24} />
                                <p>{error}</p>
                                <Button variant="outline" className="mt-4" onClick={fetchDeals}>Try Again</Button>
                            </div>
                        )}

                        {isLoading && (
                            <div className="p-8 text-center text-slate-500 flex flex-col items-center">
                                <RefreshCw size={24} className="animate-spin text-blue-500 mb-2" />
                                <p>Loading escrow commitments...</p>
                            </div>
                        )}

                        {!error && !isLoading && deals.length === 0 && (
                            <div className="p-8 text-center text-slate-500">
                                No registered deals found. Create a deal to initiate escrow tracking.
                            </div>
                        )}

                        {!error && !isLoading && deals.map(deal => (
                            <div key={deal.id} className="p-6 flex flex-col md:flex-row items-start md:items-center justify-between hover:bg-slate-50 gap-4">
                                <div className="flex-1">
                                    <div className="flex items-center gap-3">
                                        <h3 className="font-semibold text-slate-900">{deal.title}</h3>
                                        <span className="px-2.5 py-0.5 text-xs font-semibold rounded-full bg-slate-100 text-slate-700">
                                            {deal.status.replace("_", " ")}
                                        </span>
                                    </div>
                                    <p className="text-xs font-mono text-slate-500 mt-1">Seller ID: {deal.sellerId}</p>
                                    <p className="text-xs font-medium text-slate-700 mt-1">Target Escrow: ${deal.totalAmount?.toLocaleString()} {deal.currency}</p>
                                </div>
                                <div className="flex shrink-0">
                                    <Link href={`/corporate/projects/${deal.id}`}>
                                        <Button variant="outline" className="gap-2 border-slate-300">
                                            View Deal <ArrowRight size={16} />
                                        </Button>
                                    </Link>
                                </div>
                            </div>
                        ))}
                    </div>
                </Card>
            </div>
        </DashboardLayout>
    );
}
