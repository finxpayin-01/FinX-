"use client";

import React, { useState, useEffect } from "react";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Building2, CheckCircle2, Clock, Search, RefreshCw, Users, ShieldCheck } from "lucide-react";
import { b2bService } from "@/services/b2b.service";
import { Organization } from "@/types/b2b";

export default function AdminOrganizationsPage() {
    const [orgs, setOrgs] = useState<Organization[]>([]);
    const [loading, setLoading] = useState(true);
    const [searchTerm, setSearchTerm] = useState("");

    const fetchOrgs = async () => {
        setLoading(true);
        try {
            const data = await b2bService.getAdminOrganizations();
            setOrgs(data || []);
        } catch (err) {
            console.error("Failed to load organizations", err);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchOrgs();
    }, []);

    const filtered = orgs.filter(o =>
        o.name?.toLowerCase().includes(searchTerm.toLowerCase()) ||
        o.legalName?.toLowerCase().includes(searchTerm.toLowerCase()) ||
        o.pan?.toLowerCase().includes(searchTerm.toLowerCase())
    );

    return (
        <DashboardLayout>
            <div className="space-y-6">
                <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4">
                    <div>
                        <h1 className="text-2xl font-bold text-slate-900">B2B Verified Business Network</h1>
                        <p className="text-sm text-slate-500 mt-1">Multi-tenant enterprise organizations and verified profiles</p>
                    </div>
                    <button
                        onClick={fetchOrgs}
                        className="flex items-center gap-2 px-3 py-2 bg-white border border-slate-200 text-slate-700 rounded-lg text-sm hover:bg-slate-50"
                    >
                        <RefreshCw size={14} className={loading ? "animate-spin" : ""} />
                        Refresh
                    </button>
                </div>

                {/* Metrics */}
                <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
                    <div className="bg-white p-4 rounded-xl border border-slate-200">
                        <p className="text-xs font-semibold text-slate-500 uppercase">Total Organizations</p>
                        <p className="text-2xl font-bold text-slate-900 mt-1">{orgs.length}</p>
                    </div>
                    <div className="bg-white p-4 rounded-xl border border-slate-200">
                        <p className="text-xs font-semibold text-slate-500 uppercase">KYB Verified</p>
                        <p className="text-2xl font-bold text-emerald-600 mt-1">
                            {orgs.filter(o => o.verified).length}
                        </p>
                    </div>
                    <div className="bg-white p-4 rounded-xl border border-slate-200">
                        <p className="text-xs font-semibold text-slate-500 uppercase">Avg Trust Score</p>
                        <p className="text-2xl font-bold text-blue-600 mt-1">
                            {orgs.length ? Math.round(orgs.reduce((acc, o) => acc + (o.trustScore || 100), 0) / orgs.length) : 100}%
                        </p>
                    </div>
                    <div className="bg-white p-4 rounded-xl border border-slate-200">
                        <p className="text-xs font-semibold text-slate-500 uppercase">Pending KYB</p>
                        <p className="text-2xl font-bold text-amber-600 mt-1">
                            {orgs.filter(o => !o.verified).length}
                        </p>
                    </div>
                </div>

                {/* Search */}
                <div className="flex justify-between items-center gap-4">
                    <div className="relative w-full sm:w-80">
                        <Search size={16} className="absolute left-3 top-2.5 text-slate-400" />
                        <input
                            type="text"
                            placeholder="Search by legal name or PAN..."
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
                                <th className="py-3 px-4">Entity & Legal Name</th>
                                <th className="py-3 px-4">Entity Type</th>
                                <th className="py-3 px-4">PAN / GSTIN</th>
                                <th className="py-3 px-4">KYB Status</th>
                                <th className="py-3 px-4">Trust Score</th>
                                <th className="py-3 px-4">Deals / Milestones</th>
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-slate-100 text-sm">
                            {loading ? (
                                <tr>
                                    <td colSpan={6} className="py-8 text-center text-slate-400">Loading organizations...</td>
                                </tr>
                            ) : filtered.length === 0 ? (
                                <tr>
                                    <td colSpan={6} className="py-8 text-center text-slate-400">No organizations registered yet</td>
                                </tr>
                            ) : (
                                filtered.map((org) => (
                                    <tr key={org.id} className="hover:bg-slate-50/60">
                                        <td className="py-3 px-4">
                                            <p className="font-semibold text-slate-900">{org.name}</p>
                                            <p className="text-xs text-slate-400">{org.legalName}</p>
                                        </td>
                                        <td className="py-3 px-4 text-xs font-medium text-slate-600">{org.entityType}</td>
                                        <td className="py-3 px-4">
                                            <p className="font-mono text-xs text-slate-800">{org.pan}</p>
                                            <p className="font-mono text-[11px] text-slate-400">{org.gstin || "No GSTIN"}</p>
                                        </td>
                                        <td className="py-3 px-4">
                                            <span className={`inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-xs font-semibold ${
                                                org.verified ? "bg-emerald-50 text-emerald-700" : "bg-amber-50 text-amber-700"
                                            }`}>
                                                {org.verified ? <CheckCircle2 size={12} /> : <Clock size={12} />}
                                                {org.kybStatus}
                                            </span>
                                        </td>
                                        <td className="py-3 px-4">
                                            <div className="flex items-center gap-2">
                                                <div className="w-16 h-2 bg-slate-100 rounded-full overflow-hidden">
                                                    <div
                                                        className="h-full bg-blue-600 rounded-full"
                                                        style={{ width: `${org.trustScore || 100}%` }}
                                                    ></div>
                                                </div>
                                                <span className="text-xs font-semibold text-slate-700">{org.trustScore || 100}%</span>
                                            </div>
                                        </td>
                                        <td className="py-3 px-4 text-xs text-slate-500">
                                            {org.completedDealsCount || 0} deals • {org.completedMilestonesCount || 0} ms
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
