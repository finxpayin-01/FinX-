"use client";

import React, { useState, useEffect } from "react";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { ShieldCheck, CheckCircle2, Clock, XCircle, Search, RefreshCw } from "lucide-react";
import { api } from "@/lib/api";

interface KycRecord {
    id: string;
    userId: string;
    fullName: string;
    panMasked: string;
    status: string;
    nameMatchScore: number;
    verifiedAt?: string;
}

interface KybRecord {
    id: string;
    organizationId: string;
    organizationName: string;
    legalName: string;
    businessPan: string;
    gstin?: string;
    status: string;
    verifiedAt?: string;
}

export default function AdminVerificationsPage() {
    const [kycList, setKycList] = useState<KycRecord[]>([]);
    const [kybList, setKybList] = useState<KybRecord[]>([]);
    const [loading, setLoading] = useState(true);
    const [activeTab, setActiveTab] = useState<"KYB" | "KYC">("KYB");
    const [searchTerm, setSearchTerm] = useState("");

    const fetchData = async () => {
        setLoading(true);
        try {
            const res = await api.get("/api/v1/admin/verifications");
            if (res.data?.data) {
                setKycList(res.data.data.kycProfiles || []);
                setKybList(res.data.data.kybProfiles || []);
            }
        } catch (err) {
            console.error("Failed to load verifications", err);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchData();
    }, []);

    const filteredKyb = kybList.filter(k =>
        k.legalName?.toLowerCase().includes(searchTerm.toLowerCase()) ||
        k.businessPan?.toLowerCase().includes(searchTerm.toLowerCase()) ||
        k.organizationName?.toLowerCase().includes(searchTerm.toLowerCase())
    );

    const filteredKyc = kycList.filter(k =>
        k.fullName?.toLowerCase().includes(searchTerm.toLowerCase()) ||
        k.panMasked?.toLowerCase().includes(searchTerm.toLowerCase())
    );

    return (
        <DashboardLayout>
            <div className="space-y-6">
                <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4">
                    <div>
                        <h1 className="text-2xl font-bold text-slate-900">KYC & KYB Verifications</h1>
                        <p className="text-sm text-slate-500 mt-1">Audit individual identity and corporate business verification statuses</p>
                    </div>
                    <button
                        onClick={fetchData}
                        className="flex items-center gap-2 px-3 py-2 bg-white border border-slate-200 text-slate-700 rounded-lg text-sm hover:bg-slate-50"
                    >
                        <RefreshCw size={14} className={loading ? "animate-spin" : ""} />
                        Refresh
                    </button>
                </div>

                {/* Tabs & Search */}
                <div className="flex flex-col sm:flex-row justify-between items-stretch sm:items-center gap-4 border-b border-slate-200 pb-4">
                    <div className="flex gap-2">
                        <button
                            onClick={() => setActiveTab("KYB")}
                            className={`px-4 py-2 text-sm font-semibold rounded-lg transition-colors ${
                                activeTab === "KYB" ? "bg-blue-600 text-white" : "bg-white text-slate-600 border border-slate-200 hover:bg-slate-50"
                            }`}
                        >
                            Business KYB ({kybList.length})
                        </button>
                        <button
                            onClick={() => setActiveTab("KYC")}
                            className={`px-4 py-2 text-sm font-semibold rounded-lg transition-colors ${
                                activeTab === "KYC" ? "bg-blue-600 text-white" : "bg-white text-slate-600 border border-slate-200 hover:bg-slate-50"
                            }`}
                        >
                            Individual KYC ({kycList.length})
                        </button>
                    </div>
                    <div className="relative w-full sm:w-64">
                        <Search size={16} className="absolute left-3 top-2.5 text-slate-400" />
                        <input
                            type="text"
                            placeholder="Search PAN or Name..."
                            value={searchTerm}
                            onChange={(e) => setSearchTerm(e.target.value)}
                            className="w-full pl-9 pr-4 py-2 text-sm border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500"
                        />
                    </div>
                </div>

                {/* Content Table */}
                <div className="bg-white rounded-xl border border-slate-200 shadow-sm overflow-hidden">
                    {loading ? (
                        <div className="p-12 text-center text-slate-400">Loading verifications...</div>
                    ) : activeTab === "KYB" ? (
                        <table className="w-full text-left border-collapse">
                            <thead>
                                <tr className="border-b border-slate-100 bg-slate-50/50 text-xs font-semibold text-slate-500 uppercase tracking-wider">
                                    <th className="py-3 px-4">Organization / Legal Name</th>
                                    <th className="py-3 px-4">Business PAN</th>
                                    <th className="py-3 px-4">GSTIN</th>
                                    <th className="py-3 px-4">Status</th>
                                    <th className="py-3 px-4">Verified At</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-100 text-sm">
                                {filteredKyb.length === 0 ? (
                                    <tr>
                                        <td colSpan={5} className="py-8 text-center text-slate-400">No KYB profiles found</td>
                                    </tr>
                                ) : (
                                    filteredKyb.map((item) => (
                                        <tr key={item.id} className="hover:bg-slate-50/60 transition-colors">
                                            <td className="py-3 px-4">
                                                <p className="font-semibold text-slate-900">{item.legalName || item.organizationName}</p>
                                                <p className="text-xs text-slate-400">{item.organizationName}</p>
                                            </td>
                                            <td className="py-3 px-4 font-mono text-xs">{item.businessPan}</td>
                                            <td className="py-3 px-4 font-mono text-xs text-slate-500">{item.gstin || "—"}</td>
                                            <td className="py-3 px-4">
                                                <span className={`inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-xs font-semibold ${
                                                    item.status === "VERIFIED" ? "bg-emerald-50 text-emerald-700" :
                                                    item.status === "PENDING_REVIEW" ? "bg-amber-50 text-amber-700" :
                                                    "bg-slate-100 text-slate-600"
                                                }`}>
                                                    {item.status === "VERIFIED" ? <CheckCircle2 size={12} /> : <Clock size={12} />}
                                                    {item.status}
                                                </span>
                                            </td>
                                            <td className="py-3 px-4 text-xs text-slate-500">
                                                {item.verifiedAt ? new Date(item.verifiedAt).toLocaleDateString() : "—"}
                                            </td>
                                        </tr>
                                    ))
                                )}
                            </tbody>
                        </table>
                    ) : (
                        <table className="w-full text-left border-collapse">
                            <thead>
                                <tr className="border-b border-slate-100 bg-slate-50/50 text-xs font-semibold text-slate-500 uppercase tracking-wider">
                                    <th className="py-3 px-4">Full Name</th>
                                    <th className="py-3 px-4">Masked PAN</th>
                                    <th className="py-3 px-4">Match Score</th>
                                    <th className="py-3 px-4">Status</th>
                                    <th className="py-3 px-4">Verified At</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-100 text-sm">
                                {filteredKyc.length === 0 ? (
                                    <tr>
                                        <td colSpan={5} className="py-8 text-center text-slate-400">No KYC profiles found</td>
                                    </tr>
                                ) : (
                                    filteredKyc.map((item) => (
                                        <tr key={item.id} className="hover:bg-slate-50/60 transition-colors">
                                            <td className="py-3 px-4 font-semibold text-slate-900">{item.fullName}</td>
                                            <td className="py-3 px-4 font-mono text-xs">{item.panMasked}</td>
                                            <td className="py-3 px-4 text-xs font-medium text-slate-600">{item.nameMatchScore}%</td>
                                            <td className="py-3 px-4">
                                                <span className={`inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-xs font-semibold ${
                                                    item.status === "VERIFIED" ? "bg-emerald-50 text-emerald-700" : "bg-amber-50 text-amber-700"
                                                }`}>
                                                    {item.status === "VERIFIED" ? <CheckCircle2 size={12} /> : <Clock size={12} />}
                                                    {item.status}
                                                </span>
                                            </td>
                                            <td className="py-3 px-4 text-xs text-slate-500">
                                                {item.verifiedAt ? new Date(item.verifiedAt).toLocaleDateString() : "—"}
                                            </td>
                                        </tr>
                                    ))
                                )}
                            </tbody>
                        </table>
                    )}
                </div>
            </div>
        </DashboardLayout>
    );
}
