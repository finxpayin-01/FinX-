"use client";

import { useEffect, useState } from "react";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card, CardContent } from "@/components/ui/Card";
import { api } from "@/lib/api";

export default function FinanceProjectDetail({ params }: { params: { id: string } }) {
    const [project, setProject] = useState<any>(null);
    const [isLoading, setIsLoading] = useState(true);

    useEffect(() => {
        api.get(`/finance/projects/${params.id}`)
            .then(res => setProject(res.data.data || res.data))
            .catch(() => setProject(null))
            .finally(() => setIsLoading(false));
    }, [params.id]);

    if (isLoading) return <DashboardLayout><div className="p-8">Querying project ledger...</div></DashboardLayout>;
    if (!project) return <DashboardLayout><div className="p-8 text-red-600">Project ledger not found or access denied.</div></DashboardLayout>;

    return (
        <DashboardLayout>
            <div className="space-y-6">
                <div>
                    <h1 className="text-2xl font-bold text-slate-900">{project.title} - Ledger</h1>
                    <p className="text-sm text-slate-500 mt-1 font-mono">{project.projectId}</p>
                </div>

                <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                    <Card><CardContent className="p-4"><p className="text-xs text-slate-500">Total Value</p><p className="text-xl font-bold">{project.currency} {Number(project.totalValue).toLocaleString()}</p></CardContent></Card>
                    <Card><CardContent className="p-4 border-l-4 border-blue-500"><p className="text-xs text-blue-600 font-medium">Funded Balance</p><p className="text-xl font-bold text-blue-700">{project.currency} {Number(project.fundedAmount).toLocaleString()}</p></CardContent></Card>
                    <Card><CardContent className="p-4 border-l-4 border-amber-500"><p className="text-xs text-amber-600 font-medium">Milestones</p><p className="text-xl font-bold text-amber-700">{project.milestones?.length || 0}</p></CardContent></Card>
                    <Card><CardContent className="p-4 border-l-4 border-slate-500"><p className="text-xs text-slate-500 font-medium">Transaction Log</p><p className="text-xl font-bold text-slate-700">{project.transactions?.length || 0} Events</p></CardContent></Card>
                </div>

                <h2 className="text-lg font-semibold text-slate-900 mt-8 mb-4">Escrow Ledger History</h2>
                <Card>
                    <div className="overflow-x-auto">
                        <table className="w-full text-left text-sm">
                            <thead className="bg-slate-50 border-b border-slate-200">
                                <tr>
                                    <th className="p-4 font-semibold text-slate-600">Type</th>
                                    <th className="p-4 font-semibold text-slate-600">Amount</th>
                                    <th className="p-4 font-semibold text-slate-600">Reference</th>
                                    <th className="p-4 font-semibold text-slate-600">Date</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-100">
                                {project.transactions?.length === 0 && (
                                    <tr><td colSpan={4} className="p-8 text-center text-slate-500">No transactions recorded for this project yet.</td></tr>
                                )}
                                {project.transactions?.map((tx: any) => (
                                    <tr key={tx.id} className="hover:bg-slate-50">
                                        <td className="p-4">
                                            <span className={`px-2 py-0.5 rounded text-xs font-semibold ${tx.transactionType === 'DEPOSIT' ? 'bg-blue-50 text-blue-700' : 'bg-emerald-50 text-emerald-700'}`}>
                                                {tx.transactionType}
                                            </span>
                                        </td>
                                        <td className="p-4 font-bold text-slate-900">{project.currency} {Number(tx.amount).toLocaleString()}</td>
                                        <td className="p-4 font-mono text-xs text-slate-500 truncate max-w-[200px]" title={tx.referenceId}>{tx.referenceId || '--'}</td>
                                        <td className="p-4 text-xs text-slate-500">{new Date(tx.createdAt).toLocaleString()}</td>
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
