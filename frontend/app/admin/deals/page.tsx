"use client";

import React, { useState, useEffect } from "react";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Briefcase, CheckCircle2, Clock, Search, RefreshCw, FileText } from "lucide-react";
import { api } from "@/lib/api";

interface DealAdminRecord {
    id: string;
    dealReference?: string;
    title: string;
    description?: string;
    totalAmount: number;
    currency: string;
    status: string;
    buyerName?: string;
    sellerName?: string;
    createdAt: string;
}

export default function AdminDealsPage() {
    const [deals, setDeals] = useState<DealAdminRecord[]>([]);
    const [loading, setLoading] = useState(true);
    const [searchTerm, setSearchTerm] = useState("");

    const fetchDeals = async () => {
        setLoading(true);
        try {
            const res = await api.get("/api/v1/deals");
            setDeals(res.data?.data || []);
        } catch (err) {
            console.error("Failed to load deals", err);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchDeals();
    }, []);

    const filtered = deals.filter(d =>
        d.title?.toLowerCase().includes(searchTerm.toLowerCase()) ||
        d.dealReference?.toLowerCase().includes(searchTerm.toLowerCase()) ||
        d.status?.toLowerCase().includes(searchTerm.toLowerCase())
    );

    return (
        <DashboardLayout>
            <div className="space-y-6">
                <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4">
                    <div>
                        <h1 className="text-2xl font-bold text-slate-900">B2B Deals & Contracts</h1>
                        <p className="text-sm text-slate-500 mt-1">Enterprise B2B transactions, milestones, and dual-party agreement statuses</p>
                    </div>
                    <button
                        onClick={fetchDeals}
                        className="flex items-center gap-2 px-3 py-2 bg-white border border-slate-200 text-slate-700 rounded-lg text-sm hover:bg-slate-50"
                    >
                        <RefreshCw size={14} className={loading ? "animate-spin" : ""} />
                        Refresh
                    </button>
                </div>

                {/* Metrics */}
                <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
                    <div className="bg-white p-4 rounded-xl border border-slate-200">
                        <p className="text-xs font-semibold text-slate-500 uppercase">Total Deals</p>
                        <p className="text-2xl font-bold text-slate-900 mt-1">{deals.length}</p>
                    </div>
                    <div className="bg-white p-4 rounded-xl border border-slate-200">
                        <p className="text-xs font-semibold text-slate-500 uppercase">Funded / Active</p>
                        <p className="text-2xl font-bold text-emerald-600 mt-1">
                            {deals.filter(d => d.status === "ACTIVE" || d.status === "FUNDED").length}
                        </p>
                    </div>
                    <div className="bg-white p-4 rounded-xl border border-slate-200">
                        <p className="text-xs font-semibold text-slate-500 uppercase">Total Deal Value</p>
                        <p className="text-2xl font-bold text-blue-600 mt-1">
                            ₹{deals.reduce((sum, d) => sum + (Number(d.totalAmount) || 0), 0).toLocaleString()}
                        </p>
                    </div>
                    <div className="bg-white p-4 rounded-xl border border-slate-200">
                        <p className="text-xs font-semibold text-slate-500 uppercase">Completed</p>
                        <p className="text-2xl font-bold text-purple-600 mt-1">
                            {deals.filter(d => d.status === "COMPLETED").length}
                        </p>
                    </div>
                </div>

                {/* Search */}
                <div className="flex justify-between items-center gap-4">
                    <div className="relative w-full sm:w-80">
                        <Search size={16} className="absolute left-3 top-2.5 text-slate-400" />
                        <input
                            type="text"
                            placeholder="Search by title, reference, or status..."
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
                                <th className="py-3 px-4">Deal Reference / Title</th>
                                <th className="py-3 px-4">Total Amount</th>
                                <th className="py-3 px-4">Status</th>
                                <th className="py-3 px-4">Created At</th>
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-slate-100 text-sm">
                            {loading ? (
                                <tr>
                                    <td colSpan={4} className="py-8 text-center text-slate-400">Loading deals...</td>
                                </tr>
                            ) : filtered.length === 0 ? (
                                <tr>
                                    <td colSpan={4} className="py-8 text-center text-slate-400">No deals found</td>
                                </tr>
                            ) : (
                                filtered.map((d) => (
                                    <tr key={d.id} className="hover:bg-slate-50/60">
                                        <td className="py-3 px-4">
                                            <p className="font-semibold text-slate-900">{d.title}</p>
                                            <p className="font-mono text-xs text-blue-600">{d.dealReference || d.id.slice(0, 13)}</p>
                                        </td>
                                        <td className="py-3 px-4 font-mono font-semibold text-xs text-slate-900">
                                            ₹{Number(d.totalAmount).toLocaleString()} {d.currency || "INR"}
                                        </td>
                                        <td className="py-3 px-4">
                                            <span className={`inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-xs font-semibold ${
                                                d.status === "ACTIVE" || d.status === "FUNDED" ? "bg-emerald-50 text-emerald-700" :
                                                d.status === "COMPLETED" ? "bg-purple-50 text-purple-700" :
                                                d.status === "DRAFT" ? "bg-slate-100 text-slate-600" :
                                                "bg-amber-50 text-amber-700"
                                            }`}>
                                                {d.status === "COMPLETED" || d.status === "ACTIVE" ? <CheckCircle2 size={12} /> : <Clock size={12} />}
                                                {d.status}
                                            </span>
                                        </td>
                                        <td className="py-3 px-4 text-xs text-slate-500">
                                            {d.createdAt ? new Date(d.createdAt).toLocaleDateString() : "—"}
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
