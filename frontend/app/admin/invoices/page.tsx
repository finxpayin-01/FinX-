"use client";

import React, { useState, useEffect } from "react";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { 
    FileText, CheckCircle2, AlertTriangle, XCircle, Search, RefreshCw, 
    Copy, ExternalLink, ShieldCheck, Eye, ArrowUpRight, Filter
} from "lucide-react";
import { invoiceService, Invoice, InvoiceDashboardData } from "@/services/invoice.service";

export default function AdminInvoicesPage() {
    const [invoices, setInvoices] = useState<Invoice[]>([]);
    const [dashboardMetrics, setDashboardMetrics] = useState<InvoiceDashboardData | null>(null);
    const [page, setPage] = useState(0);
    const [loading, setLoading] = useState(true);
    const [searchTerm, setSearchTerm] = useState("");
    const [statusFilter, setStatusFilter] = useState<string>("ALL");
    const [selectedInvoice, setSelectedInvoice] = useState<Invoice | null>(null);

    const loadData = async () => {
        setLoading(true);
        try {
            const [metricsData, invoicesData] = await Promise.all([
                invoiceService.getAdminDashboard().catch(() => null),
                invoiceService.getAdminInvoices(page).catch(() => [])
            ]);
            setDashboardMetrics(metricsData);
            setInvoices(invoicesData);
        } catch (err) {
            console.error("Failed to load invoice dashboard data", err);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        loadData();
    }, [page]);

    const filtered = invoices.filter(inv => {
        const matchesSearch = 
            inv.invoiceNumber?.toLowerCase().includes(searchTerm.toLowerCase()) ||
            inv.vendorLegalName?.toLowerCase().includes(searchTerm.toLowerCase()) ||
            inv.buyerLegalName?.toLowerCase().includes(searchTerm.toLowerCase()) ||
            inv.dealId?.toLowerCase().includes(searchTerm.toLowerCase()) ||
            inv.documentHash?.toLowerCase().includes(searchTerm.toLowerCase());
        
        const matchesStatus = statusFilter === "ALL" || inv.status === statusFilter;
        return matchesSearch && matchesStatus;
    });

    const getStatusBadge = (status: string) => {
        switch (status) {
            case "MATCHED":
            case "APPROVED":
                return <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-semibold bg-emerald-100 text-emerald-800"><CheckCircle2 size={12}/> {status}</span>;
            case "PARTIAL_MATCH":
                return <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-semibold bg-amber-100 text-amber-800"><AlertTriangle size={12}/> {status}</span>;
            case "REVIEW_REQUIRED":
                return <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-semibold bg-orange-100 text-orange-800"><AlertTriangle size={12}/> {status}</span>;
            case "REJECTED":
                return <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-semibold bg-rose-100 text-rose-800"><XCircle size={12}/> {status}</span>;
            default:
                return <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-semibold bg-slate-100 text-slate-700">{status}</span>;
        }
    };

    const getScoreBadge = (score: number | null) => {
        if (score === null || score === undefined) return <span className="text-slate-400 font-medium">N/A</span>;
        if (score >= 90) {
            return <span className="font-bold text-emerald-600 bg-emerald-50 px-2 py-0.5 rounded border border-emerald-200">{score}/100</span>;
        } else if (score >= 75) {
            return <span className="font-bold text-amber-600 bg-amber-50 px-2 py-0.5 rounded border border-amber-200">{score}/100</span>;
        } else if (score >= 50) {
            return <span className="font-bold text-orange-600 bg-orange-50 px-2 py-0.5 rounded border border-orange-200">{score}/100</span>;
        } else {
            return <span className="font-bold text-rose-600 bg-rose-50 px-2 py-0.5 rounded border border-rose-200">{score}/100</span>;
        }
    };

    return (
        <DashboardLayout>
            <div className="space-y-6">
                {/* Header */}
                <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4">
                    <div>
                        <div className="flex items-center gap-2">
                            <ShieldCheck className="text-blue-600" size={26} />
                            <h1 className="text-2xl font-bold text-slate-900">Invoice Matching & Evidence Verification</h1>
                        </div>
                        <p className="text-sm text-slate-500 mt-1">
                            Auditable matching engine verifying vendor invoices against FinX transaction context, KYB identity, and payment gates
                        </p>
                    </div>
                    <button
                        onClick={loadData}
                        className="flex items-center gap-2 px-3 py-2 bg-white border border-slate-200 text-slate-700 rounded-lg text-sm hover:bg-slate-50 shadow-sm"
                    >
                        <RefreshCw size={14} className={loading ? "animate-spin" : ""} />
                        Refresh
                    </button>
                </div>

                {/* Dashboard Metrics (Requirement 39) */}
                <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-4">
                    <div className="bg-white p-4 rounded-xl border border-slate-200 shadow-sm">
                        <p className="text-xs font-semibold text-slate-500 uppercase tracking-wider">Invoices Processed</p>
                        <p className="text-2xl font-bold text-slate-900 mt-1">
                            {dashboardMetrics?.totalInvoicesProcessed ?? invoices.length}
                        </p>
                        <span className="text-[11px] text-slate-400">Total submitted</span>
                    </div>

                    <div className="bg-white p-4 rounded-xl border border-emerald-100 bg-emerald-50/20 shadow-sm">
                        <p className="text-xs font-semibold text-emerald-700 uppercase tracking-wider">Strong Matches</p>
                        <p className="text-2xl font-bold text-emerald-600 mt-1">
                            {dashboardMetrics?.strongMatches ?? invoices.filter(i => i.status === "MATCHED" || i.status === "APPROVED").length}
                        </p>
                        <span className="text-[11px] text-emerald-600 font-medium">Score 90-100</span>
                    </div>

                    <div className="bg-white p-4 rounded-xl border border-orange-100 bg-orange-50/20 shadow-sm">
                        <p className="text-xs font-semibold text-orange-700 uppercase tracking-wider">Review Required</p>
                        <p className="text-2xl font-bold text-orange-600 mt-1">
                            {dashboardMetrics?.reviewRequired ?? invoices.filter(i => i.status === "REVIEW_REQUIRED").length}
                        </p>
                        <span className="text-[11px] text-orange-600 font-medium">Score 50-74 / Warnings</span>
                    </div>

                    <div className="bg-white p-4 rounded-xl border border-rose-100 bg-rose-50/20 shadow-sm">
                        <p className="text-xs font-semibold text-rose-700 uppercase tracking-wider">Major Mismatches</p>
                        <p className="text-2xl font-bold text-rose-600 mt-1">
                            {dashboardMetrics?.majorMismatches ?? invoices.filter(i => i.status === "REJECTED").length}
                        </p>
                        <span className="text-[11px] text-rose-600 font-medium">Score &lt; 50 / Critical</span>
                    </div>

                    <div className="bg-white p-4 rounded-xl border border-purple-100 bg-purple-50/20 shadow-sm">
                        <p className="text-xs font-semibold text-purple-700 uppercase tracking-wider">Duplicates Detected</p>
                        <p className="text-2xl font-bold text-purple-600 mt-1">
                            {dashboardMetrics?.duplicatesDetected ?? invoices.filter(i => i.duplicateConfidence !== "UNIQUE").length}
                        </p>
                        <span className="text-[11px] text-purple-600 font-medium">Hash / Number collision</span>
                    </div>

                    <div className="bg-white p-4 rounded-xl border border-blue-100 bg-blue-50/20 shadow-sm">
                        <p className="text-xs font-semibold text-blue-700 uppercase tracking-wider">Pending Reviews</p>
                        <p className="text-2xl font-bold text-blue-600 mt-1">
                            {dashboardMetrics?.pendingReviews ?? invoices.filter(i => i.reviews?.some(r => r.status === "OPEN" || r.status === "IN_REVIEW")).length}
                        </p>
                        <span className="text-[11px] text-blue-600 font-medium">Awaiting PM/Buyer action</span>
                    </div>
                </div>

                {/* Filter and Search Bar */}
                <div className="flex flex-col sm:flex-row justify-between items-center gap-4 bg-white p-3 rounded-xl border border-slate-200 shadow-sm">
                    <div className="relative w-full sm:w-96">
                        <Search size={16} className="absolute left-3 top-2.5 text-slate-400" />
                        <input
                            type="text"
                            placeholder="Search invoice #, vendor, buyer, deal ID, hash..."
                            value={searchTerm}
                            onChange={(e) => setSearchTerm(e.target.value)}
                            className="w-full pl-9 pr-4 py-2 text-sm border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500"
                        />
                    </div>

                    <div className="flex items-center gap-2 w-full sm:w-auto">
                        <Filter size={16} className="text-slate-400" />
                        <select
                            value={statusFilter}
                            onChange={(e) => setStatusFilter(e.target.value)}
                            className="text-sm border border-slate-200 rounded-lg px-3 py-2 bg-white text-slate-700 focus:outline-none focus:ring-2 focus:ring-blue-500"
                        >
                            <option value="ALL">All Statuses</option>
                            <option value="MATCHED">MATCHED</option>
                            <option value="PARTIAL_MATCH">PARTIAL_MATCH</option>
                            <option value="REVIEW_REQUIRED">REVIEW_REQUIRED</option>
                            <option value="REJECTED">REJECTED</option>
                            <option value="APPROVED">APPROVED</option>
                        </select>
                    </div>
                </div>

                <div className="flex justify-between text-sm"><button disabled={!page} onClick={() => setPage(p => p - 1)}>Previous page</button><span>Page {page + 1} ? Filters apply to this page</span><button disabled={invoices.length < 20} onClick={() => setPage(p => p + 1)}>Next page</button></div>
                {/* Invoices Table */}
                <div className="bg-white rounded-xl border border-slate-200 shadow-sm overflow-hidden">
                    <div className="overflow-x-auto">
                        <table className="w-full text-left border-collapse">
                            <thead>
                                <tr className="border-b border-slate-100 bg-slate-50/75 text-xs font-semibold text-slate-500 uppercase tracking-wider">
                                    <th className="py-3 px-4">Invoice # & Document</th>
                                    <th className="py-3 px-4">Billed Parties</th>
                                    <th className="py-3 px-4">Deal / Milestone</th>
                                    <th className="py-3 px-4">Amount</th>
                                    <th className="py-3 px-4">Match Score</th>
                                    <th className="py-3 px-4">Duplicate Check</th>
                                    <th className="py-3 px-4">Status</th>
                                    <th className="py-3 px-4 text-right">Actions</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-100 text-sm">
                                {loading && invoices.length === 0 ? (
                                    <tr>
                                        <td colSpan={8} className="py-8 text-center text-slate-400">Loading invoice records...</td>
                                    </tr>
                                ) : filtered.length === 0 ? (
                                    <tr>
                                        <td colSpan={8} className="py-8 text-center text-slate-400">No invoices match the filter criteria</td>
                                    </tr>
                                ) : (
                                    filtered.map((inv) => (
                                        <tr key={inv.id} className="hover:bg-slate-50/60 transition-colors">
                                            <td className="py-3 px-4">
                                                <div className="font-semibold text-slate-900">{inv.invoiceNumber}</div>
                                                <div className="text-xs text-slate-500 flex items-center gap-1 mt-0.5">
                                                    <FileText size={12} className="text-blue-500" />
                                                    <span className="truncate max-w-[150px]">{inv.fileName}</span>
                                                </div>
                                                <div className="text-[10px] text-slate-400 font-mono mt-0.5" title={inv.documentHash}>
                                                    SHA: {inv.documentHash?.substring(0, 10)}...
                                                </div>
                                            </td>

                                            <td className="py-3 px-4">
                                                <div className="text-xs">
                                                    <span className="font-medium text-slate-700">Vendor: </span>
                                                    <span className="text-slate-900">{inv.vendorLegalName || "Unextracted"}</span>
                                                    {inv.vendorGstin && <span className="text-[10px] block font-mono text-slate-400">GST: {inv.vendorGstin}</span>}
                                                </div>
                                                <div className="text-xs mt-1">
                                                    <span className="font-medium text-slate-700">Buyer: </span>
                                                    <span className="text-slate-900">{inv.buyerLegalName || "FinX Counterparty"}</span>
                                                </div>
                                            </td>

                                            <td className="py-3 px-4">
                                                <div className="text-xs font-mono text-slate-600 truncate max-w-[140px]" title={inv.dealId}>
                                                    Deal: {inv.dealId?.substring(0, 8)}...
                                                </div>
                                                <div className="text-[11px] text-slate-500 truncate max-w-[140px]" title={inv.milestoneId}>
                                                    MS: {inv.milestoneId?.substring(0, 8)}...
                                                </div>
                                            </td>

                                            <td className="py-3 px-4">
                                                <div className="font-bold text-slate-900">
                                                    {inv.currency} {inv.totalAmount ? Number(inv.totalAmount).toLocaleString() : "0"}
                                                </div>
                                                {inv.taxAmount && (
                                                    <div className="text-[11px] text-slate-400">
                                                        Tax: {inv.currency} {Number(inv.taxAmount).toLocaleString()}
                                                    </div>
                                                )}
                                            </td>

                                            <td className="py-3 px-4">
                                                {getScoreBadge(inv.matchScore)}
                                                <div className="text-[10px] text-slate-400 mt-0.5">{inv.matchingStatus}</div>
                                            </td>

                                            <td className="py-3 px-4">
                                                {inv.duplicateConfidence === "UNIQUE" ? (
                                                    <span className="text-xs text-emerald-600 font-medium">Unique</span>
                                                ) : (
                                                    <span className="inline-flex items-center gap-1 text-xs text-purple-700 bg-purple-50 px-2 py-0.5 rounded font-semibold border border-purple-200">
                                                        <Copy size={11}/> {inv.duplicateConfidence}
                                                    </span>
                                                )}
                                            </td>

                                            <td className="py-3 px-4">
                                                {getStatusBadge(inv.status)}
                                            </td>

                                            <td className="py-3 px-4 text-right">
                                                <button
                                                    onClick={() => setSelectedInvoice(inv)}
                                                    className="inline-flex items-center gap-1 px-2.5 py-1.5 bg-slate-100 hover:bg-slate-200 text-slate-700 rounded text-xs font-medium transition-colors"
                                                >
                                                    <Eye size={13} />
                                                    Inspect
                                                </button>
                                            </td>
                                        </tr>
                                    ))
                                )}
                            </tbody>
                        </table>
                    </div>
                </div>

                {/* Inspection Detail Modal */}
                {selectedInvoice && (
                    <div className="fixed inset-0 z-50 bg-black/50 flex items-center justify-center p-4 backdrop-blur-sm animate-in fade-in duration-200">
                        <div className="bg-white rounded-2xl max-w-3xl w-full max-h-[90vh] flex flex-col shadow-2xl border border-slate-200">
                            {/* Modal Header */}
                            <div className="p-5 border-b border-slate-100 flex justify-between items-center bg-slate-50/50 rounded-t-2xl">
                                <div>
                                    <div className="flex items-center gap-2">
                                        <h3 className="text-lg font-bold text-slate-900">
                                            Invoice Evidence Audit: {selectedInvoice.invoiceNumber}
                                        </h3>
                                        {getStatusBadge(selectedInvoice.status)}
                                    </div>
                                    <p className="text-xs text-slate-500 mt-0.5">
                                        ID: {selectedInvoice.id} | SHA-256: {selectedInvoice.documentHash}
                                    </p>
                                </div>
                                <button
                                    onClick={() => setSelectedInvoice(null)}
                                    className="text-slate-400 hover:text-slate-600 p-1.5 rounded-lg hover:bg-slate-100 text-lg"
                                >
                                    ✕
                                </button>
                            </div>

                            {/* Modal Content */}
                            <div className="p-6 overflow-y-auto space-y-6">
                                {/* Score & Status Banner */}
                                <div className="grid grid-cols-1 md:grid-cols-3 gap-4 bg-slate-50 p-4 rounded-xl border border-slate-200">
                                    <div>
                                        <span className="text-xs text-slate-500 uppercase font-semibold">Match Score</span>
                                        <div className="mt-1 flex items-baseline gap-2">
                                            <span className="text-3xl font-black text-slate-900">{selectedInvoice.matchScore ?? 0}</span>
                                            <span className="text-sm text-slate-500">/ 100</span>
                                        </div>
                                        <span className="text-xs font-medium text-slate-600">{selectedInvoice.matchingStatus}</span>
                                    </div>

                                    <div>
                                        <span className="text-xs text-slate-500 uppercase font-semibold">Financial Basis</span>
                                        <div className="mt-1 text-lg font-bold text-slate-900">
                                            {selectedInvoice.currency} {Number(selectedInvoice.totalAmount).toLocaleString()}
                                        </div>
                                        <span className="text-xs text-slate-500">Basis: {selectedInvoice.comparisonBasis}</span>
                                    </div>

                                    <div>
                                        <span className="text-xs text-slate-500 uppercase font-semibold">Policy Version</span>
                                        <div className="mt-1 text-sm font-semibold text-slate-800">{selectedInvoice.policyVersion}</div>
                                        <span className="text-xs text-slate-500">Duplicate: {selectedInvoice.duplicateConfidence}</span>
                                    </div>
                                </div>

                                {/* 14 Verification Checks */}
                                <div>
                                    <h4 className="text-sm font-bold text-slate-900 mb-3 flex items-center gap-1.5">
                                        <ShieldCheck size={16} className="text-blue-600" />
                                        14-Point Evidence Checks Breakdown
                                    </h4>

                                    <div className="space-y-2 max-h-60 overflow-y-auto pr-1">
                                        {selectedInvoice.checks?.map((chk) => (
                                            <div key={chk.id || chk.checkType} className="flex items-start justify-between p-3 rounded-lg border border-slate-100 bg-white hover:bg-slate-50/70 transition-colors">
                                                <div className="space-y-0.5">
                                                    <div className="flex items-center gap-2">
                                                        <span className="font-semibold text-xs text-slate-800">{chk.checkType}</span>
                                                        <span className="text-[10px] font-mono text-slate-400">[{chk.reasonCode}]</span>
                                                    </div>
                                                    <p className="text-xs text-slate-600">{chk.message}</p>
                                                    {chk.details && <p className="text-[11px] text-slate-400 font-mono">{chk.details}</p>}
                                                </div>

                                                <div className="flex items-center gap-2 pl-4">
                                                    <span className="text-xs font-semibold text-slate-500">{chk.scoreEarned}/{chk.weight} pts</span>
                                                    {chk.status === "PASS" && (
                                                        <span className="px-2 py-0.5 rounded text-[11px] font-bold bg-emerald-100 text-emerald-800">PASS</span>
                                                    )}
                                                    {chk.status === "WARNING" && (
                                                        <span className="px-2 py-0.5 rounded text-[11px] font-bold bg-amber-100 text-amber-800">WARNING</span>
                                                    )}
                                                    {chk.status === "FAIL" && (
                                                        <span className="px-2 py-0.5 rounded text-[11px] font-bold bg-rose-100 text-rose-800">FAIL</span>
                                                    )}
                                                    {chk.status === "NOT_APPLICABLE" && (
                                                        <span className="px-2 py-0.5 rounded text-[11px] font-bold bg-slate-100 text-slate-600">N/A</span>
                                                    )}
                                                </div>
                                            </div>
                                        ))}
                                    </div>
                                </div>

                                {/* Extracted Fields with Confidence */}
                                <div>
                                    <h4 className="text-sm font-bold text-slate-900 mb-3">Extracted Fields & Confidence</h4>
                                    <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
                                        {selectedInvoice.extractedFields?.map((f) => (
                                            <div key={f.id || f.fieldName} className="p-2.5 rounded-lg border border-slate-200 bg-slate-50/50">
                                                <div className="flex justify-between items-center text-[11px]">
                                                    <span className="font-semibold text-slate-600">{f.fieldName}</span>
                                                    <span className="font-bold text-blue-600">{Math.round(f.confidence * 100)}%</span>
                                                </div>
                                                <div className="text-xs font-medium text-slate-900 truncate mt-1" title={f.normalizedValue || f.rawValue}>
                                                    {f.normalizedValue || f.rawValue || "—"}
                                                </div>
                                            </div>
                                        ))}
                                    </div>
                                </div>
                            </div>

                            {/* Modal Footer */}
                            <div className="p-4 border-t border-slate-100 bg-slate-50/50 rounded-b-2xl flex justify-between items-center">
                                <button type="button" onClick={() => invoiceService.download(selectedInvoice).catch(() => alert("Document download failed"))}
                                    
                                    
                                    className="inline-flex items-center gap-1.5 text-xs text-blue-600 hover:text-blue-800 font-medium"
                                >
                                    <FileText size={14} />
                                    Download / View Document
                                    <ExternalLink size={12} />
                                </button>
                                <button
                                    onClick={() => setSelectedInvoice(null)}
                                    className="px-4 py-2 bg-slate-800 hover:bg-slate-900 text-white rounded-lg text-sm font-medium transition-colors"
                                >
                                    Close
                                </button>
                            </div>
                        </div>
                    </div>
                )}
            </div>
        </DashboardLayout>
    );
}
