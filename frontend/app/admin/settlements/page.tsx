"use client";

import React, { useState, useEffect } from "react";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { CreditCard, CheckCircle2, Clock, Search, RefreshCw, AlertCircle } from "lucide-react";
import { api } from "@/lib/api";
import { SettlementResponse } from "@/types/b2b";

export default function AdminSettlementsPage() {
    const [settlements, setSettlements] = useState<SettlementResponse[]>([]);
    const [loading, setLoading] = useState(true);
    const [searchTerm, setSearchTerm] = useState("");

    const fetchSettlements = async () => {
        setLoading(true);
        try {
            const res = await api.get("/api/v1/admin/settlements");
            setSettlements(res.data?.data || []);
        } catch (err) {
            console.error("Failed to load settlements", err);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchSettlements();
    }, []);

    const filtered = settlements.filter(s =>
        s.settlementReference?.toLowerCase().includes(searchTerm.toLowerCase()) ||
        s.utrNumber?.toLowerCase().includes(searchTerm.toLowerCase()) ||
        s.dealId?.toLowerCase().includes(searchTerm.toLowerCase())
    );

    const totalSettledAmount = settlements
        .filter(s => s.status === "SETTLED")
        .reduce((sum, s) => sum + (Number(s.amount) || 0), 0);

    return (
        <DashboardLayout>
            <div className="space-y-6">
                <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4">
                    <div>
                        <h1 className="text-2xl font-bold text-slate-900">Bank Settlement Payouts</h1>
                        <p className="text-sm text-slate-500 mt-1">Disbursements from bank escrow/nodal accounts to verified beneficiary bank accounts</p>
                    </div>
                    <button
                        onClick={fetchSettlements}
                        className="flex items-center gap-2 px-3 py-2 bg-white border border-slate-200 text-slate-700 rounded-lg text-sm hover:bg-slate-50"
                    >
                        <RefreshCw size={14} className={loading ? "animate-spin" : ""} />
                        Refresh
                    </button>
                </div>

                {/* Summary Cards */}
                <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
                    <div className="bg-white p-4 rounded-xl border border-slate-200">
                        <p className="text-xs font-semibold text-slate-500 uppercase">Total Settled Volume</p>
                        <p className="text-2xl font-bold text-emerald-600 mt-1">₹{totalSettledAmount.toLocaleString()}</p>
                    </div>
                    <div className="bg-white p-4 rounded-xl border border-slate-200">
                        <p className="text-xs font-semibold text-slate-500 uppercase">Total Disbursements</p>
                        <p className="text-2xl font-bold text-slate-900 mt-1">{settlements.length}</p>
                    </div>
                    <div className="bg-white p-4 rounded-xl border border-slate-200">
                        <p className="text-xs font-semibold text-slate-500 uppercase">Settled Rate</p>
                        <p className="text-2xl font-bold text-blue-600 mt-1">
                            {settlements.length ? `${Math.round((settlements.filter(s => s.status === "SETTLED").length / settlements.length) * 100)}%` : "100%"}
                        </p>
                    </div>
                    <div className="bg-white p-4 rounded-xl border border-slate-200">
                        <p className="text-xs font-semibold text-slate-500 uppercase">Banking Provider</p>
                        <p className="text-lg font-bold text-slate-800 mt-1">MOCK / SANDBOX</p>
                    </div>
                </div>

                {/* Search */}
                <div className="flex justify-between items-center gap-4">
                    <div className="relative w-full sm:w-80">
                        <Search size={16} className="absolute left-3 top-2.5 text-slate-400" />
                        <input
                            type="text"
                            placeholder="Search Settlement Ref or UTR..."
                            value={searchTerm}
                            onChange={(e) => setSearchTerm(e.target.value)}
                            className="w-full pl-9 pr-4 py-2 text-sm border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500"
                        />
                    </div>
                </div>

                {/* Table */}
                <div className="bg-white rounded-xl border border-slate-200 shadow-sm overflow-hidden">
                    <table className="w-full text-left border-collapse">
                        <thead>
                            <tr className="border-b border-slate-100 bg-slate-50/50 text-xs font-semibold text-slate-500 uppercase tracking-wider">
                                <th className="py-3 px-4">Settlement Reference</th>
                                <th className="py-3 px-4">Deal / Milestone</th>
                                <th className="py-3 px-4">Disbursed Amount</th>
                                <th className="py-3 px-4">Bank UTR Number</th>
                                <th className="py-3 px-4">Status</th>
                                <th className="py-3 px-4">Settled At</th>
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-slate-100 text-sm">
                            {loading ? (
                                <tr>
                                    <td colSpan={6} className="py-8 text-center text-slate-400">Loading settlements...</td>
                                </tr>
                            ) : filtered.length === 0 ? (
                                <tr>
                                    <td colSpan={6} className="py-8 text-center text-slate-400">No bank settlements recorded yet</td>
                                </tr>
                            ) : (
                                filtered.map((s) => (
                                    <tr key={s.id} className="hover:bg-slate-50/60">
                                        <td className="py-3 px-4 font-mono text-xs font-bold text-slate-900">
                                            {s.settlementReference}
                                        </td>
                                        <td className="py-3 px-4">
                                            <p className="font-mono text-xs text-slate-700">Deal: {s.dealId?.slice(0, 8)}...</p>
                                            <p className="font-mono text-[11px] text-slate-400">MS: {s.milestoneId?.slice(0, 8)}...</p>
                                        </td>
                                        <td className="py-3 px-4 font-mono font-semibold text-xs text-slate-900">
                                            ₹{Number(s.amount).toLocaleString()} {s.currency}
                                        </td>
                                        <td className="py-3 px-4 font-mono text-xs text-blue-600">
                                            {s.utrNumber || "—"}
                                        </td>
                                        <td className="py-3 px-4">
                                            <span className={`inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-xs font-semibold ${
                                                s.status === "SETTLED" ? "bg-emerald-50 text-emerald-700" :
                                                s.status === "PROCESSING" ? "bg-blue-50 text-blue-700" :
                                                "bg-amber-50 text-amber-700"
                                            }`}>
                                                {s.status === "SETTLED" ? <CheckCircle2 size={12} /> : <Clock size={12} />}
                                                {s.status}
                                            </span>
                                        </td>
                                        <td className="py-3 px-4 text-xs text-slate-500">
                                            {s.settledAt ? new Date(s.settledAt).toLocaleString() : "—"}
                                        </td>
                                    </tr>
                                ))
                            )}
                        </tbody>
                    </table>
                </div>
            </div>
        </DashboardLayout>
    );
}
