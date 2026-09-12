"use client";

import React, { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/Card";
import { Button } from "@/components/ui/Button";
import { dealService } from "@/services/deal.service";
import { Deal, Milestone } from "@/types";
import { milestoneService } from "@/services/milestone.service";
import { MilestoneStatusBadge } from "@/components/ui/MilestoneStatusBadge";
import Link from "next/link";
import { ArrowLeft, RefreshCw, AlertCircle, Calendar, DollarSign, User, ShieldCheck, XCircle, CheckCircle2, Plus, Eye, CreditCard, AlertTriangle, Layers, Building2 } from "lucide-react";

export default function ProjectDetailsPage() {
    const params = useParams();
    const router = useRouter();
    const projectId = params.projectId as string;

    const [deal, setDeal] = useState<Deal | null>(null);
    const [milestones, setMilestones] = useState<Milestone[]>([]);
    const [isMilestonesLoading, setIsMilestonesLoading] = useState(true);
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [isCancelling, setIsCancelling] = useState(false);
    const [actionSuccess, setActionSuccess] = useState<string | null>(null);
    const [actionError, setActionError] = useState<string | null>(null);

    const fetchDeal = async () => {
        setIsLoading(true);
        setError(null);
        try {
            const data = await dealService.getDealById(projectId);
            setDeal(data);
        } catch (err: any) {
            setError(err?.response?.data?.message || err.message || "Failed to fetch deal details");
        } finally {
            setIsLoading(false);
        }
    };

    const fetchMilestones = async () => {
        setIsMilestonesLoading(true);
        try {
            const data = await milestoneService.getProjectMilestones(projectId);
            setMilestones(data);
        } catch (e) {
            setMilestones([]);
        } finally {
            setIsMilestonesLoading(false);
        }
    };

    useEffect(() => {
        if (projectId) {
            fetchDeal();
            fetchMilestones();
        }
    }, [projectId]);

    const handleCancelDeal = async () => {
        if (!deal) return;
        const confirm = window.confirm("Are you sure you want to cancel this deal? This action will mark the deal as CANCELLED.");
        if (!confirm) return;

        setIsCancelling(true);
        setActionError(null);
        setActionSuccess(null);
        try {
            const updated = await dealService.cancelDeal(deal.id);
            setDeal(updated);
            setActionSuccess("Deal cancelled successfully.");
        } catch (err: any) {
            setActionError(err?.response?.data?.message || err.message || "Failed to cancel deal");
        } finally {
            setIsCancelling(false);
        }
    };

    const StatusBadge = ({ status }: { status: string }) => {
        const styles: Record<string, string> = {
            DRAFT: "bg-gray-100 text-gray-700 border-gray-200",
            PENDING_ACCEPTANCE: "bg-amber-100 text-amber-700 border-amber-200",
            ACTIVE: "bg-blue-100 text-blue-700 border-blue-200",
            COMPLETED: "bg-emerald-100 text-emerald-700 border-emerald-200",
            CANCELLED: "bg-red-100 text-red-700 border-red-200",
            DISPUTED: "bg-orange-100 text-orange-700 border-orange-200"
        };
        const defaultStyle = "bg-slate-100 text-slate-700 border-slate-200";
        return (
            <span className={`px-3 py-1 text-sm font-semibold rounded-full border ${styles[status] || defaultStyle}`}>
                {status.replace("_", " ")}
            </span>
        );
    };

    const canCancel = deal && (deal.status === 'DRAFT' || deal.status === 'PENDING_ACCEPTANCE');

    return (
        <DashboardLayout>
            <div className="space-y-6 max-w-4xl">
                <Button variant="ghost" onClick={() => router.back()} className="gap-2 -ml-3 text-slate-500 hover:text-slate-900">
                    <ArrowLeft size={16} /> Back to Projects
                </Button>

                {error && (
                    <div className="p-6 text-center text-red-600 bg-red-50 rounded-lg flex flex-col items-center">
                        <AlertCircle className="mb-2" size={24} />
                        <p>{error}</p>
                        <Button variant="outline" className="mt-4" onClick={fetchDeal}>Try Again</Button>
                    </div>
                )}

                {actionSuccess && (
                    <div className="p-4 bg-emerald-50 text-emerald-700 border border-emerald-200 rounded-lg flex items-center gap-3 text-sm">
                        <CheckCircle2 size={18} className="text-emerald-600 shrink-0" />
                        <p>{actionSuccess}</p>
                    </div>
                )}

                {actionError && (
                    <div className="p-4 bg-red-50 text-red-600 border border-red-200 rounded-lg flex items-center gap-3 text-sm">
                        <AlertCircle size={18} className="text-red-500 shrink-0" />
                        <p>{actionError}</p>
                    </div>
                )}

                {!error && isLoading && (
                    <div className="p-24 text-center text-slate-500 flex flex-col items-center">
                        <RefreshCw size={32} className="animate-spin text-blue-500 mb-4" />
                        <p>Loading project details...</p>
                    </div>
                )}

                {!error && !isLoading && deal && (
                    <>
                        <div className="flex flex-col md:flex-row md:items-start justify-between gap-4">
                            <div>
                                <h1 className="text-3xl font-bold text-slate-900">{deal.title}</h1>
                                <p className="text-slate-500 mt-2 font-mono text-xs">ID: {deal.id}</p>
                            </div>
                            <div className="flex items-center gap-3">
                                <StatusBadge status={deal.status} />
                                {canCancel && (
                                    <Button
                                        variant="outline"
                                        size="sm"
                                        onClick={handleCancelDeal}
                                        disabled={isCancelling}
                                        className="text-red-600 border-red-200 hover:bg-red-50 gap-1.5"
                                    >
                                        <XCircle size={15} />
                                        {isCancelling ? "Cancelling..." : "Cancel Deal"}
                                    </Button>
                                )}
                            </div>
                        </div>

                        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                            <Card>
                                <CardHeader className="pb-3 border-b border-slate-100">
                                    <div className="flex items-center gap-2 text-slate-700 font-semibold">
                                        <DollarSign size={18} className="text-blue-500" /> Financials
                                    </div>
                                </CardHeader>
                                <CardContent className="pt-4 space-y-4">
                                    <div>
                                        <p className="text-sm font-medium text-slate-500">Total Amount</p>
                                        <p className="text-2xl font-bold text-slate-900">${deal.totalAmount?.toLocaleString()} <span className="text-sm text-slate-500">{deal.currency}</span></p>
                                    </div>
                                </CardContent>
                            </Card>

                            <Card>
                                <CardHeader className="pb-3 border-b border-slate-100">
                                    <div className="flex items-center gap-2 text-slate-700 font-semibold">
                                        <Calendar size={18} className="text-blue-500" /> Timeline
                                    </div>
                                </CardHeader>
                                <CardContent className="pt-4 space-y-4">
                                    <div>
                                        <p className="text-sm font-medium text-slate-500">Created At</p>
                                        <p className="text-base text-slate-900">{new Date(deal.createdAt).toLocaleString()}</p>
                                    </div>
                                    <div>
                                        <p className="text-sm font-medium text-slate-500">Last Updated</p>
                                        <p className="text-base text-slate-900">{new Date(deal.updatedAt).toLocaleString()}</p>
                                    </div>
                                </CardContent>
                            </Card>

                            <Card className="md:col-span-2">
                                <CardHeader className="pb-3 border-b border-slate-100">
                                    <div className="flex items-center gap-2 text-slate-700 font-semibold">
                                        <User size={18} className="text-blue-500" /> Parties Involved
                                    </div>
                                </CardHeader>
                                <CardContent className="pt-4 flex flex-col md:flex-row gap-8">
                                    <div className="flex-1">
                                        <p className="text-sm font-medium text-slate-500 mb-1">Buyer (You)</p>
                                        <p className="text-xs font-mono text-slate-900 bg-slate-50 p-2.5 rounded border border-slate-200 select-all">{deal.buyerId}</p>
                                    </div>
                                    <div className="flex-1">
                                        <p className="text-sm font-medium text-slate-500 mb-1">Seller (Vendor)</p>
                                        <p className="text-xs font-mono text-slate-900 bg-slate-50 p-2.5 rounded border border-slate-200 select-all">{deal.sellerId}</p>
                                    </div>
                                </CardContent>
                            </Card>

                            <Card className="md:col-span-2">
                                <CardHeader className="pb-3 border-b border-slate-100">
                                    <div className="flex items-center gap-2 text-slate-700 font-semibold">
                                        <ShieldCheck size={18} className="text-blue-500" /> Project Description
                                    </div>
                                </CardHeader>
                                <CardContent className="pt-4">
                                    <p className="text-slate-700 whitespace-pre-wrap">{deal.description}</p>
                                </CardContent>
                            </Card>
                        </div>

                        {/* Milestones & Payments Section */}
                        <div className="pt-8 border-t border-slate-200 space-y-4">
                            <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4">
                                <div>
                                    <h2 className="text-lg font-semibold text-slate-900">Milestones & Payments</h2>
                                    <p className="text-xs text-slate-500 mt-0.5">
                                        Manage delivery stages, track escrow funding, and approve completed work.
                                    </p>
                                </div>
                                <Link href={`/corporate/milestones?dealId=${deal.id}`}>
                                    <Button className="gap-2 bg-blue-600 hover:bg-blue-700 text-white text-sm">
                                        <Plus size={16} /> Create Milestone
                                    </Button>
                                </Link>
                            </div>

                            {/* Vendor Acceptance Notice */}
                            {(deal.status === "DRAFT" || deal.status === "PENDING_ACCEPTANCE") && (
                                <div className="p-4 rounded-xl bg-amber-50/90 border border-amber-200/80 flex items-start gap-3 shadow-xs">
                                    <AlertTriangle className="h-5 w-5 text-amber-600 mt-0.5 shrink-0" />
                                    <div>
                                        <h4 className="text-sm font-semibold text-amber-900">
                                            Waiting for Vendor to accept this project
                                        </h4>
                                        <p className="text-xs text-amber-700 mt-1 leading-relaxed">
                                            This project is currently in draft awaiting vendor acceptance. You can configure milestones now so they are ready for execution upon vendor sign-off.
                                        </p>
                                    </div>
                                </div>
                            )}

                            {/* Financial Allocation Summary */}
                            {(() => {
                                const totalValue = Number(deal.totalAmount || 0);
                                const allocatedAmount = milestones
                                    .filter((m) => m.status !== "CANCELLED")
                                    .reduce((sum, m) => sum + Number(m.amount || 0), 0);
                                const remainingAmount = Math.max(0, totalValue - allocatedAmount);
                                const percentAllocated = totalValue > 0 ? Math.min(100, Math.round((allocatedAmount / totalValue) * 100)) : 0;

                                return (
                                    <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
                                        <div className="p-4 bg-slate-50 border border-slate-200 rounded-xl">
                                            <p className="text-xs font-medium text-slate-500 uppercase">Project Value</p>
                                            <p className="text-lg font-bold text-slate-900 mt-1">₹{totalValue.toLocaleString("en-IN")} {deal.currency || "INR"}</p>
                                        </div>
                                        <div className="p-4 bg-slate-50 border border-slate-200 rounded-xl">
                                            <div className="flex justify-between items-center">
                                                <p className="text-xs font-medium text-slate-500 uppercase">Allocated</p>
                                                <span className="text-xs font-semibold text-indigo-600">{percentAllocated}%</span>
                                            </div>
                                            <p className="text-lg font-bold text-slate-900 mt-1">₹{allocatedAmount.toLocaleString("en-IN")} {deal.currency || "INR"}</p>
                                        </div>
                                        <div className={`p-4 border rounded-xl ${remainingAmount === 0 ? "bg-amber-50/50 border-amber-200" : "bg-slate-50 border-slate-200"}`}>
                                            <p className="text-xs font-medium text-slate-500 uppercase">Remaining</p>
                                            <p className={`text-lg font-bold mt-1 ${remainingAmount === 0 ? "text-amber-700" : "text-emerald-700"}`}>
                                                ₹{remainingAmount.toLocaleString("en-IN")} {deal.currency || "INR"}
                                            </p>
                                        </div>
                                    </div>
                                );
                            })()}

                            {/* Milestones List */}
                            <Card className="border border-slate-200">
                                <CardContent className="p-0">
                                    {isMilestonesLoading ? (
                                        <div className="p-10 text-center text-slate-500 flex flex-col items-center">
                                            <RefreshCw size={24} className="animate-spin text-blue-500 mb-2" />
                                            <p className="text-xs">Loading milestones...</p>
                                        </div>
                                    ) : milestones.length === 0 ? (
                                        <div className="p-10 text-center text-slate-500 flex flex-col items-center">
                                            <div className="h-12 w-12 bg-slate-100 text-slate-400 rounded-full flex items-center justify-center mb-3">
                                                <Layers size={22} />
                                            </div>
                                            <h4 className="text-sm font-semibold text-slate-900">No milestones yet</h4>
                                            <p className="text-xs text-slate-500 mt-1 max-w-sm">
                                                Break this project down into structured milestones with deliverables and funded payment stages.
                                            </p>
                                            <Link href={`/corporate/milestones?dealId=${deal.id}`} className="mt-4">
                                                <Button size="sm" className="gap-2 bg-blue-600 hover:bg-blue-700 text-white text-xs">
                                                    <Plus size={14} /> Create Milestone
                                                </Button>
                                            </Link>
                                        </div>
                                    ) : (
                                        <div className="overflow-x-auto">
                                            <table className="w-full text-left text-sm text-slate-600 border-collapse">
                                                <thead className="bg-slate-50 text-xs uppercase font-semibold text-slate-500 border-b border-slate-100">
                                                    <tr>
                                                        <th className="py-3 px-4 w-12 text-center">#</th>
                                                        <th className="py-3 px-4">Milestone</th>
                                                        <th className="py-3 px-4 text-right">Amount</th>
                                                        <th className="py-3 px-4">Due Date</th>
                                                        <th className="py-3 px-4">Status</th>
                                                        <th className="py-3 px-4 text-right">Actions</th>
                                                    </tr>
                                                </thead>
                                                <tbody className="divide-y divide-slate-100">
                                                    {milestones.map((m, idx) => {
                                                        const detailUrl = `/corporate/projects/${projectId}/milestones/${m.id}`;
                                                        const paymentUrl = `/corporate/projects/${projectId}/milestones/${m.id}/payment`;

                                                        return (
                                                            <tr key={m.id} className="hover:bg-slate-50/80 transition-colors">
                                                                <td className="py-3.5 px-4 text-center font-mono text-xs text-slate-400">
                                                                    {m.sequence || idx + 1}
                                                                </td>
                                                                <td className="py-3.5 px-4">
                                                                    <Link href={detailUrl} className="font-semibold text-slate-900 hover:text-blue-600">
                                                                        {m.title}
                                                                    </Link>
                                                                    {m.description && (
                                                                        <p className="text-xs text-slate-500 line-clamp-1 mt-0.5">
                                                                            {m.description}
                                                                        </p>
                                                                    )}
                                                                </td>
                                                                <td className="py-3.5 px-4 text-right font-semibold text-slate-900">
                                                                    ₹{Number(m.amount).toLocaleString("en-IN")}{" "}
                                                                    <span className="text-xs font-normal text-slate-400">{m.currency || "INR"}</span>
                                                                </td>
                                                                <td className="py-3.5 px-4 text-xs text-slate-500 whitespace-nowrap">
                                                                    {m.dueDate ? new Date(m.dueDate).toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" }) : "—"}
                                                                </td>
                                                                <td className="py-3.5 px-4">
                                                                    <MilestoneStatusBadge status={m.status} />
                                                                </td>
                                                                <td className="py-3.5 px-4 text-right whitespace-nowrap">
                                                                    <div className="flex items-center justify-end gap-2">
                                                                        {m.status === "PENDING" && (
                                                                            <Link href={paymentUrl}>
                                                                                <Button size="sm" className="gap-1 bg-emerald-600 hover:bg-emerald-700 text-white text-xs h-7">
                                                                                    <CreditCard size={12} /> Fund
                                                                                </Button>
                                                                            </Link>
                                                                        )}
                                                                        <Link href={detailUrl}>
                                                                            <Button variant="outline" size="sm" className="gap-1 text-slate-700 text-xs h-7">
                                                                                <Eye size={12} /> View
                                                                            </Button>
                                                                        </Link>
                                                                    </div>
                                                                </td>
                                                            </tr>
                                                        );
                                                    })}
                                                </tbody>
                                            </table>
                                        </div>
                                    )}
                                </CardContent>
                            </Card>
                        </div>
                    </>
                )}
            </div>
        </DashboardLayout>
    );
}
