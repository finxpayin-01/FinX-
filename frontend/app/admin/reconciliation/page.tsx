"use client";

import React, { useState, useEffect } from "react";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { CheckSquare, AlertTriangle, Play, RefreshCw, CheckCircle2, Clock } from "lucide-react";
import { b2bService } from "@/services/b2b.service";
import { ReconciliationRunResponse, ReconciliationItemResponse } from "@/types/b2b";

export default function AdminReconciliationPage() {
    const [runs, setRuns] = useState<ReconciliationRunResponse[]>([]);
    const [selectedRun, setSelectedRun] = useState<ReconciliationRunResponse | null>(null);
    const [items, setItems] = useState<ReconciliationItemResponse[]>([]);
    const [loading, setLoading] = useState(true);
    const [triggering, setTriggering] = useState(false);

    const fetchRuns = async () => {
        setLoading(true);
        try {
            const data = await b2bService.listReconciliationRuns();
            setRuns(data || []);
            if (data && data.length > 0 && !selectedRun) {
                selectRun(data[0]);
            }
        } catch (err) {
            console.error("Failed to load reconciliation runs", err);
        } finally {
            setLoading(false);
        }
    };

    const selectRun = async (run: ReconciliationRunResponse) => {
        setSelectedRun(run);
        try {
            const runItems = await b2bService.getReconciliationItems(run.id);
            setItems(runItems || []);
        } catch (err) {
            console.error("Failed to load run items", err);
        }
    };

    const handleTrigger = async () => {
        setTriggering(true);
        try {
            const newRun = await b2bService.triggerReconciliation();
            await fetchRuns();
            if (newRun) {
                selectRun(newRun);
            }
        } catch (err) {
            console.error("Failed to trigger reconciliation run", err);
        } finally {
            setTriggering(false);
        }
    };

    useEffect(() => {
        fetchRuns();
    }, []);

    return (
        <DashboardLayout>
            <div className="space-y-6">
                <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4">
                    <div>
                        <h1 className="text-2xl font-bold text-slate-900">Bank Reconciliation Engine</h1>
                        <p className="text-sm text-slate-500 mt-1">Automated comparison of FinX internal ledger transactions against bank statements</p>
                    </div>
                    <div className="flex gap-2">
                        <button
                            onClick={fetchRuns}
                            className="flex items-center gap-2 px-3 py-2 bg-white border border-slate-200 text-slate-700 rounded-lg text-sm hover:bg-slate-50"
                        >
                            <RefreshCw size={14} className={loading ? "animate-spin" : ""} />
                            Refresh
                        </button>
                        <button
                            onClick={handleTrigger}
                            disabled={triggering}
                            className="flex items-center gap-2 px-4 py-2 bg-blue-600 text-white rounded-lg text-sm font-semibold hover:bg-blue-700 disabled:opacity-50"
                        >
                            <Play size={14} />
                            {triggering ? "Running Reconciliation..." : "Run Reconciliation"}
                        </button>
                    </div>
                </div>

                {/* Runs Summary Cards */}
                <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
                    <div className="bg-white p-4 rounded-xl border border-slate-200">
                        <p className="text-xs font-semibold text-slate-500 uppercase">Total Runs</p>
                        <p className="text-2xl font-bold text-slate-900 mt-1">{runs.length}</p>
                    </div>
                    <div className="bg-white p-4 rounded-xl border border-slate-200">
                        <p className="text-xs font-semibold text-slate-500 uppercase">Last Run Status</p>
                        <p className="text-lg font-bold text-emerald-600 mt-1">{runs[0]?.status || "N/A"}</p>
                    </div>
                    <div className="bg-white p-4 rounded-xl border border-slate-200">
                        <p className="text-xs font-semibold text-slate-500 uppercase">Matched in Selected</p>
                        <p className="text-2xl font-bold text-emerald-600 mt-1">{selectedRun?.matchedCount ?? 0}</p>
                    </div>
                    <div className="bg-white p-4 rounded-xl border border-slate-200">
                        <p className="text-xs font-semibold text-slate-500 uppercase">Mismatches</p>
                        <p className="text-2xl font-bold text-amber-600 mt-1">{selectedRun?.mismatchCount ?? 0}</p>
                    </div>
                </div>

                {/* Runs List & Selected Items */}
                <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
                    {/* Runs sidebar */}
                    <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
                        <div className="p-4 border-b border-slate-100 bg-slate-50/50">
                            <h2 className="text-sm font-semibold text-slate-700">Reconciliation Batches</h2>
                        </div>
                        <div className="divide-y divide-slate-100 max-h-96 overflow-y-auto">
                            {runs.length === 0 ? (
                                <div className="p-6 text-center text-slate-400 text-sm">No runs executed yet</div>
                            ) : (
                                runs.map((run) => (
                                    <div
                                        key={run.id}
                                        onClick={() => selectRun(run)}
                                        className={`p-4 cursor-pointer transition-colors ${
                                            selectedRun?.id === run.id ? "bg-blue-50/70 border-l-4 border-blue-600" : "hover:bg-slate-50"
                                        }`}
                                    >
                                        <div className="flex justify-between items-center">
                                            <p className="text-xs font-mono font-bold text-slate-800">{run.runReference}</p>
                                            <span className="text-[11px] px-2 py-0.5 rounded-full bg-emerald-100 text-emerald-700 font-semibold">
                                                {run.status}
                                            </span>
                                        </div>
                                        <p className="text-xs text-slate-500 mt-1">Provider: {run.provider} • Matched: {run.matchedCount}</p>
                                        <p className="text-[11px] text-slate-400 mt-0.5">{new Date(run.executedAt).toLocaleString()}</p>
                                    </div>
                                ))
                            )}
                        </div>
                    </div>

                    {/* Items table */}
                    <div className="lg:col-span-2 bg-white rounded-xl border border-slate-200 overflow-hidden">
                        <div className="p-4 border-b border-slate-100 bg-slate-50/50 flex justify-between items-center">
                            <h2 className="text-sm font-semibold text-slate-700">
                                Batch Items: <span className="font-mono text-blue-600">{selectedRun?.runReference || "Select a run"}</span>
                            </h2>
                            <span className="text-xs text-slate-500">Provider: {selectedRun?.provider || "MOCK"}</span>
                        </div>
                        <div className="overflow-x-auto">
                            <table className="w-full text-left border-collapse">
                                <thead>
                                    <tr className="border-b border-slate-100 bg-slate-50/30 text-xs font-semibold text-slate-500 uppercase tracking-wider">
                                        <th className="py-3 px-4">Internal Tx Ref</th>
                                        <th className="py-3 px-4">Bank Tx Ref</th>
                                        <th className="py-3 px-4">Internal Amt</th>
                                        <th className="py-3 px-4">Bank Amt</th>
                                        <th className="py-3 px-4">Status</th>
                                    </tr>
                                </thead>
                                <tbody className="divide-y divide-slate-100 text-sm">
                                    {items.length === 0 ? (
                                        <tr>
                                            <td colSpan={5} className="py-8 text-center text-slate-400">
                                                {selectedRun ? "No mismatched or anomalous items in this batch" : "Select a run to view item details"}
                                            </td>
                                        </tr>
                                    ) : (
                                        items.map((item) => (
                                            <tr key={item.id} className="hover:bg-slate-50/60">
                                                <td className="py-3 px-4 font-mono text-xs">{item.internalTxReference || "—"}</td>
                                                <td className="py-3 px-4 font-mono text-xs text-slate-500">{item.bankTxReference || "—"}</td>
                                                <td className="py-3 px-4 font-mono text-xs">₹{item.internalAmount?.toLocaleString() ?? "—"}</td>
                                                <td className="py-3 px-4 font-mono text-xs">₹{item.bankAmount?.toLocaleString() ?? "—"}</td>
                                                <td className="py-3 px-4">
                                                    <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs font-semibold ${
                                                        item.status === "MATCHED" ? "bg-emerald-50 text-emerald-700" :
                                                        item.status === "REQUIRES_REVIEW" ? "bg-rose-50 text-rose-700" :
                                                        "bg-amber-50 text-amber-700"
                                                    }`}>
                                                        {item.status === "MATCHED" ? <CheckCircle2 size={12} /> : <AlertTriangle size={12} />}
                                                        {item.status}
                                                    </span>
                                                </td>
                                            </tr>
                                        ))
                                    )}
                                </tbody>
                            </table>
                        </div>
                    </div>
                </div>
            </div>
        </DashboardLayout>
    );
}
