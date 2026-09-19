"use client";

import { useEffect, useState } from "react";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card } from "@/components/ui/Card";
import { api } from "@/lib/api";
import { ShieldAlert, CheckCircle } from "lucide-react";

export default function FinanceReconciliationPage() {
    const [mismatches, setMismatches] = useState<any[]>([]);
    const [isLoading, setIsLoading] = useState(true);

    useEffect(() => {
        api.get('/finance/reconciliation')
            .then(res => setMismatches(res.data.data || res.data))
            .catch(() => setMismatches([]))
            .finally(() => setIsLoading(false));
    }, []);

    return (
        <DashboardLayout>
            <div className="space-y-6">
                <div>
                    <h1 className="text-2xl font-bold text-slate-900">Reconciliation Engine</h1>
                    <p className="text-sm text-slate-500 mt-1">Automatically identifies edge cases, broken transactions, and webhook desyncs between provider and internal ledgers.</p>
                </div>

                <Card>
                    <div className="p-6 border-b border-slate-100 flex justify-between items-center bg-slate-50">
                        <div className="flex items-center gap-3">
                            <ShieldAlert className="text-amber-500" size={24} />
                            <h2 className="font-semibold text-slate-900">Flagged Mismatches ({mismatches.length})</h2>
                        </div>
                    </div>
                    <div className="overflow-x-auto">
                        <table className="w-full text-left text-sm">
                            <thead className="bg-white border-b border-slate-200">
                                <tr>
                                    <th className="p-4 font-semibold text-slate-600">Payment ID</th>
                                    <th className="p-4 font-semibold text-slate-600">Amount</th>
                                    <th className="p-4 font-semibold text-slate-600">Discrepancy Reason</th>
                                    <th className="p-4 font-semibold text-slate-600 text-right">Status</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-100">
                                {isLoading && <tr><td colSpan={4} className="p-8 text-center text-slate-500">Running reconciliation scripts...</td></tr>}
                                {!isLoading && mismatches.length === 0 && (
                                    <tr>
                                        <td colSpan={4} className="p-12 text-center">
                                            <CheckCircle className="mx-auto text-emerald-500 mb-3" size={32} />
                                            <p className="text-emerald-700 font-semibold mb-1">Ledgers perfectly synchronized</p>
                                            <p className="text-sm text-slate-500">No orphans or webhook mismatches detected.</p>
                                        </td>
                                    </tr>
                                )}
                                {!isLoading && mismatches.map((m: any) => (
                                    <tr key={m.paymentId} className="hover:bg-slate-50">
                                        <td className="p-4">
                                            <span className="font-semibold text-slate-900 block truncate max-w-[200px]" title={m.paymentId}>{m.paymentId}</span>
                                            <span className="text-xs font-mono text-slate-400">{m.provider} • {m.providerOrderId || '--'}</span>
                                        </td>
                                        <td className="p-4 font-bold text-slate-900">{Number(m.amount || 0).toLocaleString()}</td>
                                        <td className="p-4 text-rose-600 font-medium text-xs">{m.reason}</td>
                                        <td className="p-4 text-right">
                                            <span className="px-2 py-0.5 bg-rose-50 text-rose-700 rounded text-xs font-semibold uppercase">{m.status}</span>
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
