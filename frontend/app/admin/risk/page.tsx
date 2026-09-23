"use client";

import React, { useState } from "react";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { ShieldAlert, ShieldCheck, Search, AlertCircle, CheckCircle } from "lucide-react";
import { b2bService } from "@/services/b2b.service";
import { RiskAssessmentResponse } from "@/types/b2b";

export default function AdminRiskPage() {
    const [dealIdInput, setDealIdInput] = useState("");
    const [assessment, setAssessment] = useState<RiskAssessmentResponse | null>(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const handleInspect = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!dealIdInput.trim()) return;

        setLoading(true);
        setError(null);
        try {
            const data = await b2bService.getRiskAssessment("DEAL", dealIdInput.trim());
            setAssessment(data);
        } catch (err: any) {
            setError(err?.response?.data?.message || "Failed to inspect risk assessment for this entity.");
            setAssessment(null);
        } finally {
            setLoading(false);
        }
    };

    return (
        <DashboardLayout>
            <div className="space-y-6">
                <div>
                    <h1 className="text-2xl font-bold text-slate-900">Risk Assessment Engine</h1>
                    <p className="text-sm text-slate-500 mt-1">
                        Automated multi-signal threat detection and pre-settlement clearance gates
                    </p>
                </div>

                {/* Search Bar */}
                <div className="bg-white p-6 rounded-xl border border-slate-200 shadow-sm">
                    <h2 className="text-sm font-semibold text-slate-800 mb-3">Inspect Deal Risk Profile</h2>
                    <form onSubmit={handleInspect} className="flex gap-3">
                        <div className="relative flex-1">
                            <Search size={16} className="absolute left-3 top-3 text-slate-400" />
                            <input
                                type="text"
                                placeholder="Enter Deal UUID to inspect..."
                                value={dealIdInput}
                                onChange={(e) => setDealIdInput(e.target.value)}
                                className="w-full pl-9 pr-4 py-2.5 text-sm border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500"
                            />
                        </div>
                        <button
                            type="submit"
                            disabled={loading || !dealIdInput.trim()}
                            className="px-5 py-2.5 bg-blue-600 text-white rounded-lg text-sm font-semibold hover:bg-blue-700 disabled:opacity-50"
                        >
                            {loading ? "Inspecting..." : "Assess Risk"}
                        </button>
                    </form>
                    {error && (
                        <p className="text-sm text-rose-600 mt-2 flex items-center gap-1.5">
                            <AlertCircle size={14} /> {error}
                        </p>
                    )}
                </div>

                {/* Risk Overview Grid */}
                <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                    <div className="bg-white p-4 rounded-xl border border-slate-200">
                        <p className="text-xs font-semibold text-slate-500 uppercase">Engine Policy</p>
                        <p className="text-sm font-semibold text-slate-900 mt-1">Pre-Settlement Multi-Gate</p>
                        <p className="text-xs text-slate-400 mt-0.5">Enforces KYC/KYB & Bank Match</p>
                    </div>
                    <div className="bg-white p-4 rounded-xl border border-slate-200">
                        <p className="text-xs font-semibold text-slate-500 uppercase">Critical Risk Threshold</p>
                        <p className="text-sm font-semibold text-rose-600 mt-1">Score &gt;= 75 (Auto-Freeze)</p>
                        <p className="text-xs text-slate-400 mt-0.5">Blocks payout disbursement</p>
                    </div>
                    <div className="bg-white p-4 rounded-xl border border-slate-200">
                        <p className="text-xs font-semibold text-slate-500 uppercase">Assessed Entity</p>
                        <p className="text-sm font-semibold text-slate-900 mt-1">
                            {assessment ? `${assessment.entityType}: ${assessment.entityId.slice(0, 8)}...` : "None selected"}
                        </p>
                        <p className="text-xs text-slate-400 mt-0.5">Real-time signal aggregation</p>
                    </div>
                </div>

                {/* Assessment Detail */}
                {assessment && (
                    <div className="bg-white rounded-xl border border-slate-200 p-6 shadow-sm space-y-6">
                        <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 border-b border-slate-100 pb-4">
                            <div>
                                <div className="flex items-center gap-2">
                                    <span className={`px-2.5 py-0.5 rounded-full text-xs font-bold uppercase ${
                                        assessment.riskLevel === "LOW" ? "bg-emerald-100 text-emerald-800" :
                                        assessment.riskLevel === "MEDIUM" ? "bg-amber-100 text-amber-800" :
                                        "bg-rose-100 text-rose-800"
                                    }`}>
                                        {assessment.riskLevel} RISK
                                    </span>
                                    <span className="text-sm font-mono text-slate-400">Score: {assessment.riskScore}/100</span>
                                </div>
                                <h3 className="text-lg font-bold text-slate-900 mt-2">{assessment.summary || "No active threat detected"}</h3>
                            </div>
                            <div className="flex items-center gap-2">
                                {assessment.cleared ? (
                                    <span className="flex items-center gap-1.5 px-3 py-1 bg-emerald-50 text-emerald-700 text-xs font-semibold rounded-full border border-emerald-200">
                                        <CheckCircle size={14} /> CLEARED FOR SETTLEMENT
                                    </span>
                                ) : (
                                    <span className="flex items-center gap-1.5 px-3 py-1 bg-rose-50 text-rose-700 text-xs font-semibold rounded-full border border-rose-200">
                                        <ShieldAlert size={14} /> SETTLEMENT BLOCKED
                                    </span>
                                )}
                            </div>
                        </div>

                        {/* Signals */}
                        <div>
                            <h4 className="text-sm font-semibold text-slate-700 mb-3">Detected Risk Signals ({assessment.signals.length})</h4>
                            {assessment.signals.length === 0 ? (
                                <p className="text-sm text-slate-400 italic">No risk signals triggered. All counterparty verifications match.</p>
                            ) : (
                                <div className="space-y-2">
                                    {assessment.signals.map((sig) => (
                                        <div key={sig.id} className="p-3 bg-slate-50 border border-slate-200 rounded-lg flex justify-between items-center text-sm">
                                            <div>
                                                <p className="font-semibold text-slate-800">{sig.signalType}</p>
                                                <p className="text-xs text-slate-500">{sig.description}</p>
                                            </div>
                                            <span className={`text-xs font-bold uppercase px-2 py-0.5 rounded ${
                                                sig.severity === "LOW" ? "bg-slate-200 text-slate-700" :
                                                sig.severity === "MEDIUM" ? "bg-amber-100 text-amber-800" :
                                                "bg-rose-100 text-rose-800"
                                            }`}>
                                                {sig.severity}
                                            </span>
                                        </div>
                                    ))}
                                </div>
                            )}
                        </div>
                    </div>
                )}
            </div>
        </DashboardLayout>
    );
}
