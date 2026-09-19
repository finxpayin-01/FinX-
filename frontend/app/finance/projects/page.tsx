"use client";

import { useEffect, useState } from "react";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card } from "@/components/ui/Card";
import { api } from "@/lib/api";
import Link from "next/link";
import { Banknote } from "lucide-react";

export default function FinanceProjectsLedger() {
    const [projects, setProjects] = useState<any[]>([]);
    const [isLoading, setIsLoading] = useState(true);

    useEffect(() => {
        api.get('/finance/projects')
            .then(res => setProjects(res.data.data || res.data))
            .catch(() => setProjects([]))
            .finally(() => setIsLoading(false));
    }, []);

    return (
        <DashboardLayout>
            <div className="space-y-6">
                <div>
                    <h1 className="text-2xl font-bold text-slate-900">Project Master Ledgers</h1>
                    <p className="text-sm text-slate-500 mt-1">Review aggregated financial ledgers and funded amounts across all active projects.</p>
                </div>

                <Card>
                    <div className="overflow-x-auto">
                        <table className="w-full text-left text-sm">
                            <thead className="bg-slate-50 border-b border-slate-200">
                                <tr>
                                    <th className="p-4 font-semibold text-slate-600">Project</th>
                                    <th className="p-4 font-semibold text-slate-600">Status</th>
                                    <th className="p-4 font-semibold text-slate-600">Total Value</th>
                                    <th className="p-4 font-semibold text-slate-600">Funded (Escrow)</th>
                                    <th className="p-4 font-semibold text-slate-600">Released</th>
                                    <th className="p-4 font-semibold text-slate-600 text-right">Actions</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-100">
                                {isLoading && <tr><td colSpan={6} className="p-8 text-center text-slate-500">Querying ledgers...</td></tr>}
                                {!isLoading && projects.length === 0 && (
                                    <tr><td colSpan={6} className="p-8 text-center text-slate-500">No active financial ledgers found.</td></tr>
                                )}
                                {!isLoading && projects.map((p: any) => (
                                    <tr key={p.id} className="hover:bg-slate-50">
                                        <td className="p-4">
                                            <span className="font-semibold text-slate-900 block">{p.title}</span>
                                            <span className="text-xs font-mono text-slate-400">{p.projectId}</span>
                                        </td>
                                        <td className="p-4">
                                            <span className="px-2 py-0.5 bg-slate-100 text-slate-700 rounded uppercase text-[10px] font-medium">{p.status}</span>
                                        </td>
                                        <td className="p-4 font-bold text-slate-900">{p.currency} {Number(p.totalValue || 0).toLocaleString()}</td>
                                        <td className="p-4 font-medium text-blue-600">{p.currency} {Number(p.fundedAmount || 0).toLocaleString()}</td>
                                        <td className="p-4 font-medium text-emerald-600">{p.currency} {Number(p.releasedAmount || 0).toLocaleString()}</td>
                                        <td className="p-4 text-right">
                                            <Link href={`/finance/projects/${p.id}`} className="text-blue-600 hover:text-blue-700 font-medium text-xs inline-flex items-center gap-1">
                                                <Banknote size={14} /> Open Ledger
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
