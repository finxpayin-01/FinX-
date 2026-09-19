"use client";

import { useEffect, useState } from "react";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card, CardContent } from "@/components/ui/Card";
import { CreditCard, FileSpreadsheet, Vault, AlertCircle } from "lucide-react";
import { financeService } from "@/services/day6.service";
import Link from "next/link";
import { Button } from "@/components/ui/Button";

export default function FinanceDashboard() {
    const [data, setData] = useState<any>(null);
    const [isLoading, setIsLoading] = useState(true);

    useEffect(() => {
        financeService.getDashboard()
            .then(setData)
            .finally(() => setIsLoading(false));
    }, []);

    if (isLoading || !data) return <DashboardLayout><div className="p-8">Syncing banking ledgers...</div></DashboardLayout>;

    // Unwrap ApiResponse if the Java backend wraps the payload 
    const stats = data.data || data;

    // Map the expected frontend fields to the Java backend fields
    const totalDeposits = (stats.escrowFunds || 0) + (stats.releasedFunds || 0); // Gross captured
    const totalPendingFunds = stats.pendingFunds || 0;
    const totalReleasedFunds = stats.releasedFunds || 0;
    const totalFailedPayments = stats.failedPayments || 0;

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
                                    <p className="text-sm font-medium text-slate-500">Gross Deposits Captured</p>
                                    <p className="text-3xl font-bold text-slate-900 mt-2">${totalDeposits.toLocaleString()}</p>
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
                                    <p className="text-sm font-medium text-slate-500">Funds Held (Liabilities)</p>
                                    <p className="text-3xl font-bold text-slate-900 mt-2">${totalPendingFunds.toLocaleString()}</p>
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
                                    <p className="text-sm font-medium text-slate-500">Total Cleared Releases</p>
                                    <p className="text-3xl font-bold text-slate-900 mt-2">${totalReleasedFunds.toLocaleString()}</p>
                                </div>
                                <div className="h-12 w-12 bg-purple-50 flex items-center justify-center rounded-full text-purple-600">
                                    <FileSpreadsheet size={24} />
                                </div>
                            </div>
                        </CardContent>
                    </Card>

                    <Card className="border-red-200">
                        <CardContent className="p-6">
                            <div className="flex items-center justify-between">
                                <div>
                                    <p className="text-sm font-medium text-red-600">Failed / Refunded Txs</p>
                                    <p className="text-3xl font-bold text-red-900 mt-2">${totalFailedPayments.toLocaleString()}</p>
                                </div>
                                <div className="h-12 w-12 bg-red-50 flex items-center justify-center rounded-full text-red-600">
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
