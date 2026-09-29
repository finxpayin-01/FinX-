"use client";

import React, { useState, useEffect } from "react";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import {
    ShieldCheck,
    CheckCircle2,
    Clock,
    XCircle,
    Search,
    RefreshCw,
    AlertTriangle,
    Eye,
    Landmark,
    FileText,
    UserCheck,
    Check,
    X,
    Filter
} from "lucide-react";
import { verificationService, KycResponse, KybResponse, BankAccountResponse, VerificationEventDto, AdminVerificationSummary } from "@/services/verification.service";
import { Button } from "@/components/ui/Button";

export default function AdminVerificationsPage() {
    const [summary, setSummary] = useState<AdminVerificationSummary | null>(null);
    const [loading, setLoading] = useState(true);
    const [activeTab, setActiveTab] = useState<"KYB" | "KYC" | "BANK_MISMATCHES" | "AUDIT_LOGS">("KYB");
    const [searchTerm, setSearchTerm] = useState("");
    const [statusFilter, setStatusFilter] = useState<string>("ALL");

    // Modal state for review action
    const [selectedItem, setSelectedItem] = useState<{ id: string; type: "KYC" | "KYB" | "BANK"; title: string; currentStatus: string; providerEvidence: any } | null>(null);
    const [reviewAction, setReviewAction] = useState<"APPROVE_REVIEW" | "REJECT" | "REQUEST_INFORMATION" | "REVERIFY">("APPROVE_REVIEW");
    const [reviewReason, setReviewReason] = useState("");
    const [reviewNotes, setReviewNotes] = useState("");
    const [actionLoading, setActionLoading] = useState(false);
    const [actionMsg, setActionMsg] = useState<string | null>(null);

    const fetchData = async () => {
        setLoading(true);
        try {
            const data = await verificationService.getAdminVerificationSummary();
            setSummary(data);
        } catch (err) {
            console.error("Failed to load admin verifications", err);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchData();
    }, []);

    const handleExecuteReview = async () => {
        if (!selectedItem || !reviewReason.trim()) {
            alert("A valid compliance reason is required for any administrative action.");
            return;
        }
        setActionLoading(true);
        try {
            await verificationService.reviewVerification(selectedItem.id, reviewAction, reviewReason, reviewNotes);
            setActionMsg(`Decision recorded: ${reviewAction}`);
            setTimeout(() => {
                setSelectedItem(null);
                setReviewReason("");
                setReviewNotes("");
                setActionMsg(null);
                fetchData();
            }, 1000);
        } catch (err: any) {
            alert(err.response?.data?.message || err.message || "Failed to execute review action");
        } finally {
            setActionLoading(false);
        }
    };

    const kybList = summary?.pendingKyb || [];
    const kycList = summary?.pendingKyc || [];
    const bankMismatches = summary?.bankMismatches || [];
    const auditLogs = summary?.recentEvents || [];

    const filteredKyb = kybList.filter(k => {
        const matchesSearch = (k.legalName || "").toLowerCase().includes(searchTerm.toLowerCase()) ||
            (k.businessPanMasked || "").toLowerCase().includes(searchTerm.toLowerCase()) ||
            (k.gstin || "").toLowerCase().includes(searchTerm.toLowerCase());
        const matchesStatus = statusFilter === "ALL" || k.status === statusFilter;
        return matchesSearch && matchesStatus;
    });

    const filteredKyc = kycList.filter(k => {
        const matchesSearch = (k.fullName || "").toLowerCase().includes(searchTerm.toLowerCase()) ||
            (k.panNumberMasked || "").toLowerCase().includes(searchTerm.toLowerCase());
        const matchesStatus = statusFilter === "ALL" || k.status === statusFilter;
        return matchesSearch && matchesStatus;
    });

    return (
        <DashboardLayout>
            <div className="space-y-6 max-w-7xl mx-auto pb-16">
                {/* Header */}
                <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4">
                    <div>
                        <div className="flex items-center gap-2">
                            <h1 className="text-2xl font-bold text-slate-900">Admin Compliance & Verifications</h1>
                            <span className="px-2 py-0.5 bg-purple-100 text-purple-800 text-xs font-semibold rounded-full border border-purple-200">
                                RBAC Gated
                            </span>
                        </div>
                        <p className="text-sm text-slate-500 mt-1">
                            Review pending KYC, KYB records, penny drop bank mismatches, and compliance audit decisions.
                        </p>
                    </div>

                    <button
                        onClick={fetchData}
                        className="flex items-center gap-2 px-3 py-2 bg-white border border-slate-200 text-slate-700 rounded-lg text-sm hover:bg-slate-50 shadow-xs"
                    >
                        <RefreshCw size={14} className={loading ? "animate-spin" : ""} />
                        Refresh Data
                    </button>
                </div>

                {/* Tabs & Controls */}
                <div className="flex flex-col md:flex-row justify-between items-stretch md:items-center gap-4 border-b border-slate-200 pb-4">
                    <div className="flex gap-2 overflow-x-auto pb-1">
                        <button
                            onClick={() => setActiveTab("KYB")}
                            className={`px-3 py-2 text-xs font-bold rounded-lg transition-colors whitespace-nowrap ${
                                activeTab === "KYB" ? "bg-blue-600 text-white shadow-xs" : "bg-white text-slate-600 border border-slate-200 hover:bg-slate-50"
                            }`}
                        >
                            Corporate KYB ({kybList.length})
                        </button>
                        <button
                            onClick={() => setActiveTab("KYC")}
                            className={`px-3 py-2 text-xs font-bold rounded-lg transition-colors whitespace-nowrap ${
                                activeTab === "KYC" ? "bg-blue-600 text-white shadow-xs" : "bg-white text-slate-600 border border-slate-200 hover:bg-slate-50"
                            }`}
                        >
                            Individual KYC ({kycList.length})
                        </button>
                        <button
                            onClick={() => setActiveTab("BANK_MISMATCHES")}
                            className={`px-3 py-2 text-xs font-bold rounded-lg transition-colors whitespace-nowrap ${
                                activeTab === "BANK_MISMATCHES" ? "bg-amber-600 text-white shadow-xs" : "bg-white text-slate-600 border border-slate-200 hover:bg-slate-50"
                            }`}
                        >
                            Bank Mismatches ({bankMismatches.length})
                        </button>
                        <button
                            onClick={() => setActiveTab("AUDIT_LOGS")}
                            className={`px-3 py-2 text-xs font-bold rounded-lg transition-colors whitespace-nowrap ${
                                activeTab === "AUDIT_LOGS" ? "bg-slate-800 text-white shadow-xs" : "bg-white text-slate-600 border border-slate-200 hover:bg-slate-50"
                            }`}
                        >
                            Immutable Audit Log ({auditLogs.length})
                        </button>
                    </div>

                    <div className="flex items-center gap-2">
                        <div className="relative w-full sm:w-60">
                            <Search size={14} className="absolute left-3 top-2.5 text-slate-400" />
                            <input
                                type="text"
                                placeholder="Search Name, PAN, GST..."
                                value={searchTerm}
                                onChange={(e) => setSearchTerm(e.target.value)}
                                className="w-full pl-8 pr-3 py-1.5 text-xs border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 bg-white"
                            />
                        </div>

                        <select
                            value={statusFilter}
                            onChange={(e) => setStatusFilter(e.target.value)}
                            className="text-xs border border-slate-200 rounded-lg px-2.5 py-1.5 bg-white text-slate-700"
                        >
                            <option value="ALL">All Statuses</option>
                            <option value="PENDING_REVIEW">Pending Review</option>
                            <option value="IN_PROGRESS">In Progress</option>
                            <option value="VERIFIED">Verified</option>
                            <option value="FAILED">Failed</option>
                            <option value="REJECTED">Rejected</option>
                        </select>
                    </div>
                </div>

                {/* Content Table */}
                <div className="bg-white rounded-xl border border-slate-200 shadow-xs overflow-hidden">
                    {loading ? (
                        <div className="p-12 text-center text-slate-400 text-sm">Loading compliance verification data...</div>
                    ) : activeTab === "KYB" ? (
                        <table className="w-full text-left border-collapse text-xs">
                            <thead>
                                <tr className="border-b border-slate-200 bg-slate-50 text-slate-600 uppercase font-bold">
                                    <th className="py-3 px-4">Organization / Legal Name</th>
                                    <th className="py-3 px-4">Business PAN</th>
                                    <th className="py-3 px-4">GSTIN</th>
                                    <th className="py-3 px-4">Registration</th>
                                    <th className="py-3 px-4">Match Score</th>
                                    <th className="py-3 px-4">Status</th>
                                    <th className="py-3 px-4 text-right">Actions</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-100">
                                {filteredKyb.length === 0 ? (
                                    <tr>
                                        <td colSpan={7} className="py-8 text-center text-slate-400">No corporate KYB profiles found</td>
                                    </tr>
                                ) : (
                                    filteredKyb.map((k) => (
                                        <tr key={k.id} className="hover:bg-slate-50/70 transition-colors">
                                            <td className="py-3 px-4">
                                                <p className="font-bold text-slate-900">{k.legalName || "Organization"}</p>
                                                <p className="text-[11px] text-slate-400 font-mono">Org ID: {k.organizationId.substring(0, 8)}...</p>
                                            </td>
                                            <td className="py-3 px-4 font-mono">{k.businessPanMasked || "—"}</td>
                                            <td className="py-3 px-4 font-mono">{k.gstin || "—"}</td>
                                            <td className="py-3 px-4 font-mono">{k.cinOrLlpin || k.udyamNumber || "—"}</td>
                                            <td className="py-3 px-4">
                                                <span className="font-semibold text-slate-700">{k.nameMatchScore ? `${k.nameMatchScore}%` : "—"}</span>
                                                <span className="text-[10px] text-slate-400 block">{k.nameMatchStatus}</span>
                                            </td>
                                            <td className="py-3 px-4">
                                                <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full font-semibold ${
                                                    k.status === "VERIFIED" ? "bg-emerald-100 text-emerald-800" :
                                                    k.status === "PENDING_REVIEW" ? "bg-amber-100 text-amber-800" :
                                                    k.status === "FAILED" ? "bg-rose-100 text-rose-800" :
                                                    "bg-slate-100 text-slate-700"
                                                }`}>
                                                    {k.status === "VERIFIED" ? <CheckCircle2 size={12} /> : <Clock size={12} />}
                                                    {k.status}
                                                </span>
                                            </td>
                                            <td className="py-3 px-4 text-right">
                                                <button
                                                    onClick={() => setSelectedItem({
                                                        id: k.id,
                                                        type: "KYB",
                                                        title: k.legalName,
                                                        currentStatus: k.status,
                                                        providerEvidence: k
                                                    })}
                                                    className="px-2.5 py-1 text-xs bg-blue-50 text-blue-700 hover:bg-blue-100 rounded font-semibold transition-colors"
                                                >
                                                    Review
                                                </button>
                                            </td>
                                        </tr>
                                    ))
                                )}
                            </tbody>
                        </table>
                    ) : activeTab === "KYC" ? (
                        <table className="w-full text-left border-collapse text-xs">
                            <thead>
                                <tr className="border-b border-slate-200 bg-slate-50 text-slate-600 uppercase font-bold">
                                    <th className="py-3 px-4">Signatory Name</th>
                                    <th className="py-3 px-4">Masked PAN</th>
                                    <th className="py-3 px-4">PAN Status</th>
                                    <th className="py-3 px-4">Identity Status</th>
                                    <th className="py-3 px-4">Match Score</th>
                                    <th className="py-3 px-4">Overall KYC</th>
                                    <th className="py-3 px-4 text-right">Actions</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-100">
                                {filteredKyc.length === 0 ? (
                                    <tr>
                                        <td colSpan={7} className="py-8 text-center text-slate-400">No individual KYC profiles found</td>
                                    </tr>
                                ) : (
                                    filteredKyc.map((k) => (
                                        <tr key={k.id} className="hover:bg-slate-50/70 transition-colors">
                                            <td className="py-3 px-4 font-bold text-slate-900">{k.fullName}</td>
                                            <td className="py-3 px-4 font-mono">{k.panNumberMasked || "—"}</td>
                                            <td className="py-3 px-4 font-semibold text-slate-700">{k.panStatus}</td>
                                            <td className="py-3 px-4 font-semibold text-slate-700">{k.identityStatus}</td>
                                            <td className="py-3 px-4 font-semibold text-slate-700">{k.nameMatchScore ? `${k.nameMatchScore}%` : "—"}</td>
                                            <td className="py-3 px-4">
                                                <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full font-semibold ${
                                                    k.status === "VERIFIED" ? "bg-emerald-100 text-emerald-800" :
                                                    k.status === "PENDING_REVIEW" ? "bg-amber-100 text-amber-800" :
                                                    "bg-slate-100 text-slate-700"
                                                }`}>
                                                    {k.status === "VERIFIED" ? <CheckCircle2 size={12} /> : <Clock size={12} />}
                                                    {k.status}
                                                </span>
                                            </td>
                                            <td className="py-3 px-4 text-right">
                                                <button
                                                    onClick={() => setSelectedItem({
                                                        id: k.id,
                                                        type: "KYC",
                                                        title: k.fullName,
                                                        currentStatus: k.status,
                                                        providerEvidence: k
                                                    })}
                                                    className="px-2.5 py-1 text-xs bg-blue-50 text-blue-700 hover:bg-blue-100 rounded font-semibold transition-colors"
                                                >
                                                    Review
                                                </button>
                                            </td>
                                        </tr>
                                    ))
                                )}
                            </tbody>
                        </table>
                    ) : activeTab === "BANK_MISMATCHES" ? (
                        <table className="w-full text-left border-collapse text-xs">
                            <thead>
                                <tr className="border-b border-slate-200 bg-slate-50 text-slate-600 uppercase font-bold">
                                    <th className="py-3 px-4">Account Holder / Bank</th>
                                    <th className="py-3 px-4">Masked Account</th>
                                    <th className="py-3 px-4">Registered Name Returned</th>
                                    <th className="py-3 px-4">Name Match Score</th>
                                    <th className="py-3 px-4">Status</th>
                                    <th className="py-3 px-4 text-right">Actions</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-100">
                                {bankMismatches.length === 0 ? (
                                    <tr>
                                        <td colSpan={6} className="py-8 text-center text-slate-400">No bank account mismatches detected</td>
                                    </tr>
                                ) : (
                                    bankMismatches.map((b) => (
                                        <tr key={b.id} className="hover:bg-slate-50/70 transition-colors">
                                            <td className="py-3 px-4">
                                                <p className="font-bold text-slate-900">{b.accountHolderName}</p>
                                                <p className="text-[11px] text-slate-400">{b.bankName} ({b.ifscCode})</p>
                                            </td>
                                            <td className="py-3 px-4 font-mono">{b.accountNumberMasked}</td>
                                            <td className="py-3 px-4 font-medium text-slate-800">{b.registeredNameReturned || "—"}</td>
                                            <td className="py-3 px-4 font-bold text-rose-600">{b.nameMatchScore || 0}%</td>
                                            <td className="py-3 px-4 font-semibold text-rose-700">{b.verificationStatus}</td>
                                            <td className="py-3 px-4 text-right">
                                                <button
                                                    onClick={() => setSelectedItem({
                                                        id: b.id,
                                                        type: "BANK",
                                                        title: b.accountHolderName,
                                                        currentStatus: b.verificationStatus,
                                                        providerEvidence: b
                                                    })}
                                                    className="px-2.5 py-1 text-xs bg-amber-50 text-amber-800 hover:bg-amber-100 rounded font-semibold transition-colors"
                                                >
                                                    Audit Bank
                                                </button>
                                            </td>
                                        </tr>
                                    ))
                                )}
                            </tbody>
                        </table>
                    ) : (
                        <div className="divide-y divide-slate-100 text-xs">
                            {auditLogs.length === 0 ? (
                                <div className="p-8 text-center text-slate-400">No verification audit events recorded</div>
                            ) : (
                                auditLogs.map((e) => (
                                    <div key={e.id} className="p-4 flex items-start justify-between gap-4 hover:bg-slate-50/60">
                                        <div className="space-y-0.5">
                                            <div className="flex items-center gap-2">
                                                <span className="font-bold text-slate-900">{e.verificationType}</span>
                                                <span className="text-[10px] px-1.5 py-0.5 bg-slate-100 text-slate-600 rounded">
                                                    {e.provider}
                                                </span>
                                                <span className="text-[10px] font-mono text-slate-400">
                                                    Actor: {e.actorId.substring(0, 8)}...
                                                </span>
                                            </div>
                                            <p className="text-slate-600">{e.reason}</p>
                                        </div>
                                        <div className="text-right shrink-0">
                                            <span className="font-bold text-slate-800">{e.newStatus}</span>
                                            <p className="text-[10px] text-slate-400 mt-0.5">{new Date(e.timestamp).toLocaleString()}</p>
                                        </div>
                                    </div>
                                ))
                            )}
                        </div>
                    )}
                </div>

                {/* Review Modal Dialog */}
                {selectedItem && (
                    <div className="fixed inset-0 z-50 bg-slate-900/60 backdrop-blur-xs flex items-center justify-center p-4">
                        <div className="bg-white rounded-xl shadow-xl max-w-xl w-full p-6 space-y-5 border border-slate-200">
                            <div className="flex items-center justify-between border-b border-slate-100 pb-3">
                                <div>
                                    <h3 className="text-base font-bold text-slate-900">
                                        Compliance Review: {selectedItem.title}
                                    </h3>
                                    <p className="text-xs text-slate-500">
                                        Type: {selectedItem.type} | Current Status: {selectedItem.currentStatus}
                                    </p>
                                </div>
                                <button
                                    onClick={() => setSelectedItem(null)}
                                    className="p-1 rounded-lg text-slate-400 hover:text-slate-700 hover:bg-slate-100"
                                >
                                    <X size={18} />
                                </button>
                            </div>

                            {/* Provider Evidence (Untouched by Admin) */}
                            <div className="p-3.5 bg-slate-50 rounded-lg border border-slate-200 space-y-1 text-xs">
                                <span className="font-bold text-slate-500 uppercase tracking-wider block mb-1">
                                    Authoritative Provider Evidence (Read Only):
                                </span>
                                <div className="font-mono text-[11px] text-slate-700 space-y-0.5">
                                    <p>Provider: {selectedItem.providerEvidence?.provider || "MOCK / SANDBOX"}</p>
                                    <p>Ref: {selectedItem.providerEvidence?.providerReference || selectedItem.providerEvidence?.verificationReference || "REF-UNAVAILABLE"}</p>
                                    <p>Match Score: {selectedItem.providerEvidence?.nameMatchScore ?? "N/A"}% ({selectedItem.providerEvidence?.nameMatchStatus || "N/A"})</p>
                                    {selectedItem.providerEvidence?.rejectionReason && (
                                        <p className="text-rose-600 font-sans">Flag Reason: {selectedItem.providerEvidence?.rejectionReason}</p>
                                    )}
                                </div>
                            </div>

                            {/* Action Form */}
                            <div className="space-y-3">
                                <div>
                                    <label className="text-xs font-bold text-slate-700 uppercase block mb-1">Compliance Action</label>
                                    <select
                                        value={reviewAction}
                                        onChange={(e) => setReviewAction(e.target.value as any)}
                                        className="w-full border border-slate-300 rounded-lg p-2 text-xs bg-white font-semibold"
                                    >
                                        <option value="APPROVE_REVIEW">APPROVE REVIEW (Override with Compliance Sign-off)</option>
                                        <option value="REQUEST_INFORMATION">REQUEST INFORMATION (Notify Applicant for Clarification)</option>
                                        <option value="REVERIFY">REVERIFY (Clear and Re-trigger Provider Verification)</option>
                                        <option value="REJECT">REJECT (Mark Application Rejected)</option>
                                    </select>
                                </div>

                                <div>
                                    <label className="text-xs font-bold text-slate-700 uppercase block mb-1">
                                        Audit Reason (Mandatory)
                                    </label>
                                    <input
                                        type="text"
                                        value={reviewReason}
                                        onChange={(e) => setReviewReason(e.target.value)}
                                        placeholder="e.g. Verified trade license name variation meets compliance standards"
                                        className="w-full border border-slate-300 rounded-lg p-2 text-xs focus:ring-2 focus:ring-blue-500"
                                    />
                                </div>

                                <div>
                                    <label className="text-xs font-bold text-slate-700 uppercase block mb-1">Internal Notes</label>
                                    <textarea
                                        value={reviewNotes}
                                        onChange={(e) => setReviewNotes(e.target.value)}
                                        placeholder="Optional internal compliance notes..."
                                        rows={2}
                                        className="w-full border border-slate-300 rounded-lg p-2 text-xs focus:ring-2 focus:ring-blue-500"
                                    />
                                </div>
                            </div>

                            {actionMsg && (
                                <p className="text-xs font-semibold text-emerald-700 bg-emerald-50 p-2 rounded">
                                    {actionMsg}
                                </p>
                            )}

                            <div className="flex justify-end gap-2 pt-2 border-t border-slate-100">
                                <Button variant="outline" onClick={() => setSelectedItem(null)}>
                                    Cancel
                                </Button>
                                <Button
                                    onClick={handleExecuteReview}
                                    disabled={actionLoading || !reviewReason.trim()}
                                    className="bg-blue-600 hover:bg-blue-700 text-white"
                                >
                                    {actionLoading ? <RefreshCw size={14} className="animate-spin mr-1" /> : null}
                                    Record Administrative Decision
                                </Button>
                            </div>
                        </div>
                    </div>
                )}
            </div>
        </DashboardLayout>
    );
}
