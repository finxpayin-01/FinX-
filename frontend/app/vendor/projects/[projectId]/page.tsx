"use client";

import React, { useCallback, useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card, CardContent, CardHeader } from "@/components/ui/Card";
import { Button } from "@/components/ui/Button";
import { dealService } from "@/services/deal.service";
import { milestoneService } from "@/services/milestone.service";
import { Deal, Milestone } from "@/types";
import { MilestoneStatusBadge } from "@/components/ui/MilestoneStatusBadge";
import {
    ArrowLeft, RefreshCw, AlertCircle, Calendar, DollarSign,
    User, ShieldCheck, CheckCircle2, XCircle, Clock,
    Layers, AlertTriangle, FileText, Send
} from "lucide-react";

export default function VendorProjectDetailsPage() {
    const params = useParams();
    const router = useRouter();
    const projectId = params.projectId as string;

    const [deal, setDeal] = useState<Deal | null>(null);
    const [milestones, setMilestones] = useState<Milestone[]>([]);
    const [isLoading, setIsLoading] = useState(true);
    const [isMilestonesLoading, setIsMilestonesLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const [isAccepting, setIsAccepting] = useState(false);
    const [isRejecting, setIsRejecting] = useState(false);
    const [isRejectModalOpen, setIsRejectModalOpen] = useState(false);
    const [rejectionReason, setRejectionReason] = useState("");
    const [actionSuccess, setActionSuccess] = useState<string | null>(null);
    const [actionError, setActionError] = useState<string | null>(null);

    const fetchDeal = useCallback(async () => {
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
    }, [projectId]);

    const fetchMilestones = useCallback(async () => {
        if (!projectId) return;
        setIsMilestonesLoading(true);
        try {
            const data = await milestoneService.getProjectMilestones(projectId);
            setMilestones(data || []);
        } catch (err) {
            setMilestones([]);
        } finally {
            setIsMilestonesLoading(false);
        }
    }, [projectId]);

    useEffect(() => {
        if (projectId) {
            fetchDeal();
            fetchMilestones();
        }
    }, [fetchDeal, fetchMilestones, projectId]);

    const handleAcceptDeal = async () => {
        if (!deal) return;
        setIsAccepting(true);
        setActionError(null);
        setActionSuccess(null);
        try {
            const updated = await dealService.acceptDeal(deal.id);
            setDeal(updated);
            setActionSuccess("Project accepted successfully! The project status is now ACTIVE.");
            fetchMilestones();
        } catch (err: any) {
            setActionError(err?.response?.data?.message || err.message || "Failed to accept project");
        } finally {
            setIsAccepting(false);
        }
    };

    const handleConfirmReject = async () => {
        if (!deal) return;
        setIsRejecting(true);
        setActionError(null);
        setActionSuccess(null);
        try {
            const updated = await dealService.rejectDeal(deal.id, rejectionReason.trim() || undefined);
            setDeal(updated);
            setIsRejectModalOpen(false);
            setRejectionReason("");
            setActionSuccess("Project has been rejected.");
        } catch (err: any) {
            setActionError(err?.response?.data?.message || err.message || "Failed to reject project");
        } finally {
            setIsRejecting(false);
        }
    };

    const StatusBadge = ({ status }: { status: string }) => {
        const styles: Record<string, string> = {
            DRAFT: "bg-gray-100 text-gray-700 border-gray-200",
            PENDING_VENDOR_ACCEPTANCE: "bg-amber-100 text-amber-800 border-amber-300",
            ACCEPTED: "bg-blue-100 text-blue-700 border-blue-200",
            MILESTONES_PENDING: "bg-indigo-100 text-indigo-700 border-indigo-200",
            AWAITING_FUNDING: "bg-purple-100 text-purple-700 border-purple-200",
            FUNDED: "bg-emerald-100 text-emerald-800 border-emerald-300",
            IN_PROGRESS: "bg-teal-100 text-teal-800 border-teal-200",
            COMPLETED: "bg-emerald-100 text-emerald-700 border-emerald-200",
            CANCELLED: "bg-red-100 text-red-700 border-red-200",
            DISPUTED: "bg-orange-100 text-orange-700 border-orange-200",
            REJECTED: "bg-rose-100 text-rose-700 border-rose-200"
        };
        const defaultStyle = "bg-slate-100 text-slate-700 border-slate-200";
        const label = status === "PENDING_VENDOR_ACCEPTANCE" ? "Awaiting Your Acceptance" : status.replace(/_/g, " ");
        return (
            <span className={`px-3 py-1 text-xs font-semibold rounded-full border ${styles[status] || defaultStyle}`}>
                {label}
            </span>
        );
    };

    const isPending = deal && (deal.status === 'PENDING_VENDOR_ACCEPTANCE' || deal.status === 'DRAFT');
    const isActive = deal && ['ACCEPTED', 'MILESTONES_PENDING', 'AWAITING_FUNDING', 'FUNDED', 'IN_PROGRESS'].includes(deal.status);
    const isRejected = deal && deal.status === 'REJECTED';

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
                    <div className="p-4 bg-emerald-50 text-emerald-800 border border-emerald-200 rounded-lg flex items-center gap-3 text-sm font-medium shadow-xs">
                        <CheckCircle2 size={18} className="text-emerald-600 shrink-0" />
                        <p>{actionSuccess}</p>
                    </div>
                )}

                {actionError && (
                    <div className="p-4 bg-red-50 text-red-700 border border-red-200 rounded-lg flex items-center gap-3 text-sm">
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
                        {/* Header */}
                        <div className="flex flex-col md:flex-row md:items-start justify-between gap-4">
                            <div>
                                <h1 className="text-3xl font-bold text-slate-900">{deal.title}</h1>
                                <p className="text-slate-500 mt-1 font-mono text-xs">Project ID: {deal.id}</p>
                            </div>
                            <div className="flex items-center gap-3">
                                <StatusBadge status={deal.status} />
                            </div>
                        </div>

                        {/* PROJECT INVITATION BANNER (when Awaiting Acceptance) */}
                        {isPending && (
                            <div className="p-6 bg-amber-50/90 border-2 border-amber-300 rounded-xl shadow-xs space-y-4">
                                <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 border-b border-amber-200/80 pb-4">
                                    <div>
                                        <div className="flex items-center gap-2">
                                            <span className="text-xs font-bold uppercase tracking-wider text-amber-900 bg-amber-200/80 px-2.5 py-0.5 rounded">
                                                PROJECT INVITATION
                                            </span>
                                            <span className="text-xs font-semibold text-amber-700">Action Required</span>
                                        </div>
                                        <h2 className="text-xl font-bold text-slate-900 mt-2">Awaiting Your Acceptance</h2>
                                        <p className="text-sm text-slate-600 mt-1 max-w-xl">
                                            The corporate buyer has assigned this contract to your vendor account. Review the terms, scope, and milestone allocations below, then accept or reject this invitation.
                                        </p>
                                    </div>
                                    <div className="flex items-center gap-3 shrink-0">
                                        <Button
                                            variant="outline"
                                            onClick={() => setIsRejectModalOpen(true)}
                                            disabled={isAccepting || isRejecting}
                                            className="text-rose-700 border-rose-300 hover:bg-rose-50 gap-1.5 h-10 px-4"
                                        >
                                            <XCircle size={16} /> Reject Project
                                        </Button>
                                        <Button
                                            onClick={handleAcceptDeal}
                                            disabled={isAccepting || isRejecting}
                                            className="bg-emerald-600 hover:bg-emerald-700 text-white font-semibold gap-1.5 h-10 px-5 shadow-xs"
                                        >
                                            <CheckCircle2 size={16} /> {isAccepting ? "Accepting..." : "Accept Project"}
                                        </Button>
                                    </div>
                                </div>

                                <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 text-sm pt-1">
                                    <div>
                                        <span className="text-xs text-slate-500 font-medium">Buyer UID</span>
                                        <p className="font-mono text-xs text-slate-800 font-semibold mt-0.5 bg-white/70 p-2 rounded border border-amber-200/70 select-all">
                                            {deal.buyerId}
                                        </p>
                                    </div>
                                    <div>
                                        <span className="text-xs text-slate-500 font-medium">Total Contract Value</span>
                                        <p className="text-lg font-bold text-slate-900 mt-0.5">
                                            {deal.currency} {deal.totalAmount?.toLocaleString()}
                                        </p>
                                    </div>
                                    <div>
                                        <span className="text-xs text-slate-500 font-medium">Status</span>
                                        <p className="text-sm font-semibold text-amber-800 mt-0.5">
                                            Awaiting Your Acceptance
                                        </p>
                                    </div>
                                </div>
                            </div>
                        )}

                        {/* ACTIVE STATUS BANNER */}
                        {isActive && (
                            <div className="p-4 bg-emerald-50 border border-emerald-200 rounded-xl flex flex-col sm:flex-row sm:items-center justify-between gap-3 text-sm shadow-xs">
                                <div className="flex items-center gap-3">
                                    <CheckCircle2 size={20} className="text-emerald-600 shrink-0" />
                                    <div>
                                        <p className="font-semibold text-emerald-900">Project Accepted · Active</p>
                                        <p className="text-emerald-700 text-xs mt-0.5">
                                            This project is active. You may now perform milestone work and submit evidence for funded milestones.
                                        </p>
                                    </div>
                                </div>
                                {deal.acceptedAt && (
                                    <span className="text-xs text-emerald-800 bg-emerald-100/70 px-2.5 py-1 rounded-md font-mono shrink-0">
                                        Accepted: {new Date(deal.acceptedAt).toLocaleDateString()}
                                    </span>
                                )}
                            </div>
                        )}

                        {/* REJECTED STATUS BANNER */}
                        {isRejected && (
                            <div className="p-4 bg-rose-50 border border-rose-200 rounded-xl text-sm space-y-1 shadow-xs">
                                <div className="flex items-center gap-2 text-rose-900 font-semibold">
                                    <XCircle size={18} className="text-rose-600 shrink-0" />
                                    Project Invitation Rejected
                                </div>
                                <p className="text-xs text-rose-700">
                                    This invitation was declined by the vendor. Reason: <strong>{deal.rejectionReason || "No specific reason provided."}</strong>
                                </p>
                                {deal.rejectedAt && (
                                    <p className="text-xs text-rose-500 font-mono pt-1">
                                        Declined on: {new Date(deal.rejectedAt).toLocaleString()}
                                    </p>
                                )}
                            </div>
                        )}

                        {/* Project Details Cards */}
                        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                            <Card>
                                <CardHeader className="pb-3 border-b border-slate-100">
                                    <div className="flex items-center gap-2 text-slate-700 font-semibold">
                                        <DollarSign size={18} className="text-blue-500" /> Financial Terms
                                    </div>
                                </CardHeader>
                                <CardContent className="pt-4 space-y-4">
                                    <div>
                                        <p className="text-sm font-medium text-slate-500">Contract Amount</p>
                                        <p className="text-2xl font-bold text-slate-900">
                                            {deal.currency} {deal.totalAmount?.toLocaleString()}
                                        </p>
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
                                        <p className="text-sm font-medium text-slate-500">Invited / Created At</p>
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
                                        <User size={18} className="text-blue-500" /> Contract Parties
                                    </div>
                                </CardHeader>
                                <CardContent className="pt-4 flex flex-col md:flex-row gap-8">
                                    <div className="flex-1">
                                        <p className="text-sm font-medium text-slate-500 mb-1">Buyer (Corporate) UID</p>
                                        <p className="text-xs font-mono text-slate-900 bg-slate-50 p-2.5 rounded border border-slate-200 select-all font-semibold">
                                            {deal.buyerId}
                                        </p>
                                    </div>
                                    <div className="flex-1">
                                        <p className="text-sm font-medium text-slate-500 mb-1">Assigned Vendor UID</p>
                                        <p className="text-xs font-mono text-slate-900 bg-slate-50 p-2.5 rounded border border-slate-200 select-all font-semibold">
                                            {deal.sellerId}
                                        </p>
                                    </div>
                                </CardContent>
                            </Card>

                            <Card className="md:col-span-2">
                                <CardHeader className="pb-3 border-b border-slate-100">
                                    <div className="flex items-center gap-2 text-slate-700 font-semibold">
                                        <ShieldCheck size={18} className="text-blue-500" /> Project Scope & Description
                                    </div>
                                </CardHeader>
                                <CardContent className="pt-4">
                                    <p className="text-slate-700 whitespace-pre-wrap leading-relaxed">{deal.description}</p>
                                </CardContent>
                            </Card>
                        </div>

                        {/* Milestones Section */}
                        <div className="pt-8 border-t border-slate-200 space-y-4">
                            <div className="flex items-center justify-between">
                                <div>
                                    <h2 className="text-lg font-semibold text-slate-900">Project Milestones</h2>
                                    <p className="text-xs text-slate-500 mt-0.5">
                                        Milestone stages configured by the corporate buyer for this project.
                                    </p>
                                </div>
                                <span className="text-xs font-semibold px-2.5 py-1 bg-slate-100 text-slate-700 rounded-full">
                                    {milestones.length} {milestones.length === 1 ? "Milestone" : "Milestones"}
                                </span>
                            </div>

                            {/* Guard Notice if Pending Acceptance */}
                            {isPending && (
                                <div className="p-3.5 bg-amber-50/70 border border-amber-200 rounded-lg text-xs text-amber-800 flex items-center gap-2.5">
                                    <Clock size={16} className="text-amber-600 shrink-0" />
                                    <span>
                                        <strong>Deliverable submissions locked:</strong> You must accept this project invitation above before work can commence and deliverables can be submitted.
                                    </span>
                                </div>
                            )}

                            {isMilestonesLoading && (
                                <div className="p-8 text-center text-slate-400">
                                    <RefreshCw size={20} className="animate-spin mx-auto mb-2 text-blue-500" />
                                    <p className="text-xs">Loading milestones...</p>
                                </div>
                            )}

                            {!isMilestonesLoading && milestones.length === 0 && (
                                <Card className="bg-slate-50/50 border-dashed border-2 border-slate-200">
                                    <CardContent className="p-8 text-center text-slate-500 text-sm">
                                        No milestones have been configured yet by the Buyer.
                                    </CardContent>
                                </Card>
                            )}

                            {!isMilestonesLoading && milestones.length > 0 && (
                                <div className="space-y-3">
                                    {milestones.map((m, idx) => (
                                        <Card key={m.id} className="border-slate-200 hover:border-slate-300 transition-colors">
                                            <CardContent className="p-4 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
                                                <div className="flex-1 min-w-0">
                                                    <div className="flex items-center gap-2">
                                                        <span className="text-xs font-bold text-slate-500 uppercase tracking-wider">
                                                            Stage {m.sequence || idx + 1}
                                                        </span>
                                                        <h4 className="font-semibold text-slate-900 text-sm truncate">{m.title}</h4>
                                                    </div>
                                                    <p className="text-xs text-slate-500 mt-1 line-clamp-2">{m.description}</p>
                                                    {m.dueDate && (
                                                        <p className="text-xs text-slate-400 mt-1">Due: {new Date(m.dueDate).toLocaleDateString()}</p>
                                                    )}
                                                </div>
                                                <div className="flex items-center gap-4 shrink-0 sm:border-l sm:border-slate-100 sm:pl-4">
                                                    <div className="text-right">
                                                        <span className="text-xs text-slate-400 block">Amount</span>
                                                        <span className="font-bold text-slate-900 text-sm">
                                                            {m.currency} {Number(m.amount).toLocaleString()}
                                                        </span>
                                                    </div>
                                                    <MilestoneStatusBadge status={m.status} />
                                                </div>
                                            </CardContent>
                                        </Card>
                                    ))}
                                </div>
                            )}
                        </div>
                    </>
                )}
            </div>

            {/* REJECTION REASON MODAL */}
            {isRejectModalOpen && (
                <div className="fixed inset-0 bg-black/50 backdrop-blur-xs flex items-center justify-center p-4 z-50">
                    <div className="bg-white rounded-xl shadow-xl max-w-md w-full p-6 space-y-4 border border-slate-200">
                        <div className="flex items-start gap-3">
                            <div className="h-10 w-10 bg-rose-100 text-rose-600 rounded-full flex items-center justify-center shrink-0">
                                <XCircle size={22} />
                            </div>
                            <div>
                                <h3 className="text-lg font-bold text-slate-900">Decline Project Invitation</h3>
                                <p className="text-xs text-slate-500 mt-0.5">
                                    Are you sure you want to reject this project? This will notify the buyer.
                                </p>
                            </div>
                        </div>

                        <div className="space-y-1.5 pt-2">
                            <label className="text-xs font-semibold text-slate-700 block">
                                Reason for Declining (Optional / Recommended)
                            </label>
                            <textarea
                                rows={3}
                                className="w-full rounded-lg border border-slate-200 p-2.5 text-sm text-slate-900 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-rose-500 focus:border-transparent"
                                placeholder="e.g., Timeline conflicts, capacity constraints, budget misalignment..."
                                value={rejectionReason}
                                onChange={(e) => setRejectionReason(e.target.value)}
                            />
                        </div>

                        <div className="flex items-center justify-end gap-2.5 pt-2 border-t border-slate-100">
                            <Button
                                variant="outline"
                                size="sm"
                                onClick={() => setIsRejectModalOpen(false)}
                                disabled={isRejecting}
                            >
                                Cancel
                            </Button>
                            <Button
                                size="sm"
                                onClick={handleConfirmReject}
                                disabled={isRejecting}
                                className="bg-rose-600 hover:bg-rose-700 text-white font-semibold"
                            >
                                {isRejecting ? "Declining..." : "Confirm Rejection"}
                            </Button>
                        </div>
                    </div>
                </div>
            )}
        </DashboardLayout>
    );
}
