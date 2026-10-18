"use client";

import { useEffect, useState } from "react";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card, CardContent } from "@/components/ui/Card";
import { financeService } from "@/services/day6.service";
import { FinanceTransaction } from "@/types";
import { AlertCircle, RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/Button";

export default function FinanceTransactionsPage() {
    const [txs, setTxs] = useState<FinanceTransaction[]>([]);
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    const loadTransactions = () => {
        setIsLoading(true);
        setError(null);
        financeService.getTransactions()
            .then((data) => {
                const list = Array.isArray(data) ? data : ((data as any)?.data || []);
                setTxs(list);
            })
            .catch((err: any) => {
                const status = err?.response?.status;
                if (status === 403) {
                    setError("Access Denied: You do not have permission to view finance transactions (ADMIN or FINANCE role required).");
                } else if (status >= 500) {
                    setError("Backend service unavailable. Please check the ledger service.");
                } else {
                    setError("Failed to query secure ledger transactions.");
                }
                setTxs([]);
            })
            .finally(() => setIsLoading(false));
    };

    useEffect(() => {
        loadTransactions();
    }, []);

    return (
        <DashboardLayout>
            <div className="space-y-6">
                <div className="flex justify-between items-center">
                    <div>
                        <h1 className="text-2xl font-bold text-slate-900">Master Ledger & Settlements</h1>
                        <p className="text-sm text-slate-500 mt-1">Review locked fiat deposits and final payouts. Sensitive details are strictly masked.</p>
                    </div>
                    <Button variant="outline" size="sm" onClick={loadTransactions} disabled={isLoading} className="gap-2">
                        <RefreshCw size={14} className={isLoading ? "animate-spin" : ""} />
                        Refresh
                    </Button>
                </div>

                {error && (
                    <Card className="border-rose-200 bg-rose-50">
                        <CardContent className="p-4 flex items-center justify-between">
                            <div className="flex items-center gap-3">
                                <AlertCircle className="text-rose-500 shrink-0" size={20} />
                                <span className="text-sm text-rose-700">{error}</span>
                            </div>
                            <Button variant="outline" size="sm" onClick={loadTransactions} className="border-rose-300 text-rose-800 hover:bg-rose-100">
                                Retry
                            </Button>
                        </CardContent>
                    </Card>
                )}

                <Card>
                    <div className="overflow-x-auto">
                        <table className="w-full text-left text-sm">
                            <thead className="bg-slate-50 border-b border-slate-200">
                                <tr>
                                    <th className="p-4 font-semibold text-slate-600">ID & Type</th>
                                    <th className="p-4 font-semibold text-slate-600">Date</th>
                                    <th className="p-4 font-semibold text-slate-600">Gross Value</th>
                                    <th className="p-4 font-semibold text-slate-600">Ref / Channel</th>
                                    <th className="p-4 font-semibold text-slate-600 text-right">Settlement Status</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-100">
                                {isLoading && <tr><td colSpan={5} className="p-8 text-center text-slate-500">Querying secure ledger...</td></tr>}
                                {!isLoading && !error && txs.length === 0 && (
                                    <tr><td colSpan={5} className="p-8 text-center text-slate-500">No active financial settlements found.</td></tr>
                                )}
                                {!isLoading && txs.map((tx: FinanceTransaction) => (
                                    <tr key={tx.id} className="hover:bg-slate-50">
                                        <td className="p-4 font-mono text-xs">
                                            <span className="font-semibold text-slate-900 mb-1 block truncate max-w-[200px]" title={tx.id}>{tx.id}</span>
                                            <div className="flex gap-1 items-center">
                                                <span className={`px-2 py-0.5 rounded uppercase text-[10px] font-semibold ${tx.type === 'FUNDING' ? 'bg-emerald-50 text-emerald-700' : 'bg-purple-50 text-purple-700'}`}>{tx.type || 'TX'}</span>
                                                <span className="px-1.5 py-0.5 bg-slate-100 text-slate-600 rounded uppercase text-[10px] font-medium">{tx.provider || 'ESCROW'}</span>
                                            </div>
                                        </td>
                                        <td className="p-4 text-xs text-slate-500">{new Date(tx.createdAt || Date.now()).toLocaleDateString()}</td>
                                        <td className="p-4 font-bold text-slate-900">{tx.currency || 'INR'} {Number(tx.amount || 0).toLocaleString()}</td>
                                        <td className="p-4 font-mono text-xs text-slate-500 border-l border-slate-100 truncate max-w-[180px]">
                                            {tx.dealId ? `Deal: ${tx.dealId.slice(0, 8)}...` : 'ESCROW-LEDGER'}
                                        </td>
                                        <td className="p-4 text-right">
                                            {tx.status === 'SETTLED' ? (
                                                <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-semibold bg-emerald-50 text-emerald-700 border border-emerald-200">
                                                    SETTLED
                                                </span>
                                            ) : tx.status === 'SUCCESS' ? (
                                                <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-semibold bg-emerald-50 text-emerald-700 border border-emerald-200">
                                                    SUCCESS
                                                </span>
                                            ) : tx.status === 'FAILED' || tx.status === 'CANCELLED' ? (
                                                <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-semibold bg-rose-50 text-rose-700 border border-rose-200">
                                                    {tx.status}
                                                </span>
                                            ) : tx.status === 'MANUAL_REVIEW' || tx.status === 'UNKNOWN_RECON_REQUIRED' ? (
                                                <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-semibold bg-amber-50 text-amber-800 border border-amber-300">
                                                    {tx.status}
                                                </span>
                                            ) : tx.status === 'UNKNOWN' ? (
                                                <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-semibold bg-slate-100 text-slate-700 border border-slate-300">
                                                    UNKNOWN
                                                </span>
                                            ) : (
                                                <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-amber-50 text-amber-700">
                                                    {tx.status || 'PENDING'}
                                                </span>
                                            )}
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
