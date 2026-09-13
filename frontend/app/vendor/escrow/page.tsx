"use client";

import { useEffect, useState } from "react";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card, CardContent } from "@/components/ui/Card";
import { Briefcase, ShieldCheck, Clock, RefreshCw, AlertCircle, HardHat, ArrowRight } from "lucide-react";
import { dealService } from "@/services/deal.service";
import { Deal } from "@/types";
import Link from "next/link";
import { Button } from "@/components/ui/Button";

export default function VendorEscrowDashboard() {
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
            setError(err?.response?.data?.message || err.message || "Failed to fetch deals");
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
    const totalValue = deals.reduce((sum, d) => sum + (d.totalAmount || 0), 0);
    const activeValue = deals.filter(d => activeStatuses.includes(d.status)).reduce((sum, d) => sum + (d.totalAmount || 0), 0);

    return (
        <DashboardLayout>
            <div className="space-y-6">
                <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4">
                    <div>
                        <h1 className="text-2xl font-bold text-slate-900">Vendor Escrow & Earnings</h1>
                        <p className="text-sm text-slate-500 mt-1">Track funds securely committed across your active assignments.</p>
                    </div>
                    <Button variant="outline" size="sm" onClick={fetchDeals} disabled={isLoading} className="gap-2">
                        <RefreshCw size={14} className={isLoading ? "animate-spin" : ""} /> Refresh
                    </Button>
                </div>

                {/* API Status Notice */}
                <div className="p-4 bg-emerald-50 border border-emerald-200 rounded-xl flex items-start gap-3">
                    <ShieldCheck size={20} className="text-emerald-600 shrink-0 mt-0.5" />
                    <div>
                        <h4 className="text-sm font-semibold text-emerald-900">Live Escrow Protection Active</h4>
                        <p className="text-xs text-emerald-800 mt-1 leading-relaxed">
                            Escrow balances reflect committed contract funds deposited into FINX escrow vaults by your corporate buyers.
                        </p>
                    </div>
                </div>

                <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                    <Card>
                        <CardContent className="p-6">
                            <div className="flex items-center justify-between">
                                <div>
                                    <p className="text-sm font-medium text-slate-500">Total Assigned Value</p>
                                    <p className="text-3xl font-bold text-slate-900 mt-2">${totalValue.toLocaleString()}</p>
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
                                    <p className="text-sm font-medium text-slate-500">Active Escrow Deals</p>
                                    <p className="text-3xl font-bold text-emerald-600 mt-2">${activeValue.toLocaleString()}</p>
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
                                    <p className="text-sm font-medium text-slate-500">Total Deals</p>
                                    <p className="text-3xl font-bold text-amber-600 mt-2">{totalDeals} <span className="text-sm font-normal text-slate-500">({activeDeals} active)</span></p>
                                </div>
                                <div className="h-12 w-12 bg-amber-50 flex items-center justify-center rounded-full text-amber-600">
                                    <Clock size={24} />
                                </div>
                            </div>
                        </CardContent>
                    </Card>
                </div>

                <h2 className="text-lg font-semibold text-slate-900 mt-8 mb-4">Assigned Escrow Vaults</h2>
                <Card>
                    <div className="overflow-x-auto">
                        <table className="w-full text-left text-sm">
                            <thead className="bg-slate-50 border-b border-slate-200">
                                <tr>
                                    <th className="p-4 font-semibold text-slate-600">Deal Title</th>
                                    <th className="p-4 font-semibold text-slate-600">Committed Amount</th>
                                    <th className="p-4 font-semibold text-slate-600">Status</th>
                                    <th className="p-4 font-semibold text-slate-600 text-right">Action</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-100">
                                {error && (
                                    <tr>
                                        <td colSpan={4} className="p-6 text-center text-red-600">
                                            <p>{error}</p>
                                            <Button variant="outline" size="sm" className="mt-2" onClick={fetchDeals}>Try Again</Button>
                                        </td>
                                    </tr>
                                )}
                                {isLoading && (
                                    <tr><td colSpan={4} className="p-8 text-center text-slate-500">Loading escrow records...</td></tr>
                                )}
                                {!error && !isLoading && deals.length === 0 && (
                                    <tr><td colSpan={4} className="p-8 text-center text-slate-500">No deals assigned to your account.</td></tr>
                                )}
                                {!error && !isLoading && deals.map(deal => (
                                    <tr key={deal.id} className="hover:bg-slate-50 transition-colors">
                                        <td className="p-4">
                                            <p className="font-semibold text-slate-900">{deal.title}</p>
                                            <p className="text-xs font-mono text-slate-400 mt-0.5">ID: {deal.id}</p>
                                        </td>
                                        <td className="p-4 font-semibold text-slate-700">
                                            ${deal.totalAmount?.toLocaleString()} {deal.currency}
                                        </td>
                                        <td className="p-4">
                                            <span className="px-2.5 py-0.5 text-xs font-semibold rounded-full bg-slate-100 text-slate-700">
                                                {deal.status.replace("_", " ")}
                                            </span>
                                        </td>
                                        <td className="p-4 text-right">
                                            <Link href={`/vendor/projects/${deal.id}`}>
                                                <Button variant="outline" size="sm" className="gap-1.5 h-8">
                                                    View Deal <ArrowRight size={14} />
                                                </Button>
                                            </Link>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                </Card>
            </div>
        </DashboardLayout>
    );
}
