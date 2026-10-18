"use client";

import { useEffect, useState } from "react";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card, CardContent } from "@/components/ui/Card";
import { CreditCard, FileSpreadsheet, Vault, AlertCircle, RefreshCw } from "lucide-react";
import { financeService } from "@/services/day6.service";
import { FinanceSummary } from "@/types";
import Link from "next/link";
import { Button } from "@/components/ui/Button";

export default function FinanceDashboard() {
    const [data, setData] = useState<FinanceSummary | null>(null);
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    const fetchSummary = () => {
        setIsLoading(true);
        setError(null);
        financeService.getDashboard()
            .then((res: any) => {
                const unwrapped = res?.data || res;
                setData(unwrapped);
            })
            .catch((err: any) => {
                const status = err?.response?.status;
                if (status === 403) {
                    setError("Access Denied: You do not have permission to view financial operations (ADMIN or FINANCE role required).");
                } else if (status >= 500) {
                    setError("Backend service unavailable. Please ensure the banking ledger service is operational.");
                } else {
                    setError("Failed to load financial operations summary. Please check your connection and retry.");
                }
            })
            .finally(() => setIsLoading(false));
    };

    useEffect(() => {
        fetchSummary();
    }, []);

    if (isLoading) {
        return (
            <DashboardLayout>
                <div className="p-8 text-center text-slate-500">
                    <RefreshCw className="animate-spin mx-auto mb-2 text-slate-400" size={24} />
                    Syncing banking ledgers...
                </div>
            </DashboardLayout>
        );
    }

    if (error) {
        return (
            <DashboardLayout>
                <div className="p-8 max-w-xl mx-auto">
                    <Card className="border-rose-200 bg-rose-50">
                        <CardContent className="p-6 text-center space-y-4">
                            <AlertCircle className="mx-auto text-rose-500" size={36} />
                            <h2 className="text-lg font-semibold text-rose-900">Financial Ledger Sync Notice</h2>
                            <p className="text-sm text-rose-700">{error}</p>
                            <Button variant="outline" onClick={fetchSummary} className="mt-2 border-rose-300 text-rose-800 hover:bg-rose-100">
                                Retry Connection
                            </Button>
                        </CardContent>
                    </Card>
                </div>
            </DashboardLayout>
        );
    }

    const stats = data || {
        totalInflowVolume: 0,
        totalSettledVolume: 0,
        pendingSettlementsCount: 0,
        totalInstructionsCount: 0,
        totalReconciliationRuns: 0
    };

    // Actual backend fields from /v1/finance/summary
    const totalInflowVolume = Number(stats.totalInflowVolume ?? 0);
    const totalSettledVolume = Number(stats.totalSettledVolume ?? 0);
    const pendingSettlementsCount = Number(stats.pendingSettlementsCount ?? 0);
    const totalInstructionsCount = Number(stats.totalInstructionsCount ?? 0);
    const totalReconciliationRuns = Number(stats.totalReconciliationRuns ?? 0);

    return (
        <DashboardLayout>
            <div className="space-y-6">
                <div>
                    <h1 className="text-2xl font-bold text-slate-900">Finance & Settlement Dashboard</h1>
                    <p className="text-sm text-slate-500 mt-1">Review aggregated platform ledgers, revenue, and master transaction pipelines.</p>
                </div>

                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
                    <Card>
                        <CardContent className="p-6">
                            <div className="flex items-center justify-between">
                                <div>
                                    <p className="text-sm font-medium text-slate-500">Total Inflow Volume</p>
                                    <p className="text-3xl font-bold text-slate-900 mt-2">₹{totalInflowVolume.toLocaleString()}</p>
                                </div>
                                <div className="h-12 w-12 bg-emerald-50 flex items-center justify-center rounded-full text-emerald-600">
                                    <CreditCard size={24} />
                                </div>
                            </div>
                        </CardContent>
                    </Card>

                    <Card>
                        <CardContent className="p-6">
                            <div className="flex items-center justify-between">
                                <div>
                                    <p className="text-sm font-medium text-slate-500">Total Settled Volume</p>
                                    <p className="text-3xl font-bold text-slate-900 mt-2">₹{totalSettledVolume.toLocaleString()}</p>
                                </div>
                                <div className="h-12 w-12 bg-purple-50 flex items-center justify-center rounded-full text-purple-600">
                                    <FileSpreadsheet size={24} />
                                </div>
                            </div>
                        </CardContent>
                    </Card>

                    <Card>
                        <CardContent className="p-6">
                            <div className="flex items-center justify-between">
                                <div>
                                    <p className="text-sm font-medium text-slate-500">Pending Settlements</p>
                                    <p className="text-3xl font-bold text-slate-900 mt-2">{pendingSettlementsCount}</p>
                                </div>
                                <div className="h-12 w-12 bg-blue-50 flex items-center justify-center rounded-full text-blue-600">
                                    <Vault size={24} />
                                </div>
                            </div>
                        </CardContent>
                    </Card>

                    <Card>
                        <CardContent className="p-6">
                            <div className="flex items-center justify-between">
                                <div>
                                    <p className="text-sm font-medium text-slate-500">Instructions / Recon</p>
                                    <p className="text-3xl font-bold text-slate-900 mt-2">{totalInstructionsCount} <span className="text-sm font-normal text-slate-500">({totalReconciliationRuns} runs)</span></p>
                                </div>
                                <div className="h-12 w-12 bg-slate-50 flex items-center justify-center rounded-full text-slate-600">
                                    <AlertCircle size={24} />
                                </div>
                            </div>
                        </CardContent>
                    </Card>
                </div>

                <h2 className="text-lg font-semibold text-slate-900 mt-8 mb-4">Operations & Reporting Hub</h2>
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
                    <Link href="/finance/transactions">
                        <Button variant="outline" className="w-full h-16 justify-start text-left px-4">
                            Master Transaction Ledger
                        </Button>
                    </Link>
                    <Link href="/finance/settlements">
                        <Button variant="outline" className="w-full h-16 justify-start text-left px-4">
                            Bank Settlements
                        </Button>
                    </Link>
                    <Link href="/finance/invoices">
                        <Button variant="outline" className="w-full h-16 justify-start text-left px-4">
                            Invoicing System
                        </Button>
                    </Link>
                    <Link href="/finance/reports">
                        <Button variant="outline" className="w-full h-16 justify-start text-left px-4 bg-slate-50">
                            Analytics & Export Reports
                        </Button>
                    </Link>
                </div>
            </div>
        </DashboardLayout>
    );
}
