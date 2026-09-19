"use client";

import React, { useCallback, useEffect, useState } from "react";
import { BlockchainEscrow } from "@/components/escrow/BlockchainEscrow";
import { useParams, useRouter } from "next/navigation";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/Card";
import { Button } from "@/components/ui/Button";
import { dealService } from "@/services/deal.service";
import { projectManagerService } from "@/services/project-manager.service";
import { Deal, Milestone, DealProjectManagerInfo, ProjectManagerSummary } from "@/types";
import { milestoneService } from "@/services/milestone.service";
import { MilestoneStatusBadge } from "@/components/ui/MilestoneStatusBadge";
import Link from "next/link";
import {
    ArrowLeft, RefreshCw, AlertCircle, Calendar, DollarSign, User, ShieldCheck,
    XCircle, CheckCircle2, Plus, Eye, CreditCard, AlertTriangle, Layers, Building2,
    UserCheck, UserPlus, Mail, Clock
} from "lucide-react";

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

    // Project Manager Assignment State
    const [pmInfo, setPmInfo] = useState<DealProjectManagerInfo | null>(null);
    const [isPmLoading, setIsPmLoading] = useState(true);
    const [eligiblePms, setEligiblePms] = useState<ProjectManagerSummary[]>([]);
    const [showInviteModal, setShowInviteModal] = useState(false);
    const [selectedPmId, setSelectedPmId] = useState("");
    const [manualPmId, setManualPmId] = useState("");
    const [inviteMode, setInviteMode] = useState<"SELECT" | "MANUAL">("SELECT");
    const [isInvitingPm, setIsInvitingPm] = useState(false);
    const [isCancellingPm, setIsCancellingPm] = useState(false);

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

    const fetchPmInfo = useCallback(async () => {
        setIsPmLoading(true);
        try {
            const data = await projectManagerService.getDealProjectManager(projectId);
            setPmInfo(data);
        } catch {
            setPmInfo(null);
        } finally {
            setIsPmLoading(false);
        }
    }, [projectId]);

    const fetchMilestones = useCallback(async () => {
        setIsMilestonesLoading(true);
        try {
            const data = await milestoneService.getProjectMilestones(projectId);
            setMilestones(data);
        } catch (e) {
            setMilestones([]);
        } finally {
            setIsMilestonesLoading(false);
        }
    }, [projectId]);

    useEffect(() => {
        if (!projectId) return;
        fetchDeal();
        fetchMilestones();
        fetchPmInfo();

        let intervalId: any;
        // Auto-refresh while waiting for vendor or PM
        if (deal?.status === "PENDING_VENDOR_ACCEPTANCE" || deal?.status === "DRAFT" || pmInfo?.activeInvitation) {
            intervalId = setInterval(() => {
                dealService.getDealById(projectId).then(updated => {
                    if (updated && updated.status !== deal?.status) {
                        setDeal(updated);
                        fetchMilestones();
                    }
                }).catch(() => { });
                projectManagerService.getDealProjectManager(projectId).then(setPmInfo).catch(() => {});
            }, 6000);
        }

        return () => {
            if (intervalId) clearInterval(intervalId);
        };
    }, [deal?.status, fetchDeal, fetchMilestones, fetchPmInfo, pmInfo?.activeInvitation, projectId]);

    const handleOpenInviteModal = async () => {
        setShowInviteModal(true);
        try {
            const pms = await projectManagerService.getEligibleProjectManagers();
            setEligiblePms(pms);
            if (pms.length > 0 && !selectedPmId) {
                setSelectedPmId(pms[0].id);
            }
        } catch (e) {
            console.warn("Failed to load eligible PMs:", e);
        }
    };

    const handleSendPmInvitation = async () => {
        if (!deal) return;
        setIsInvitingPm(true);
        setActionError(null);
        setActionSuccess(null);
        try {
            const payload = inviteMode === "SELECT"
                ? { projectManagerId: selectedPmId }
                : { pmId: manualPmId.trim().toUpperCase() };

            if (inviteMode === "MANUAL" && !manualPmId.trim()) {
                setActionError("Please enter a valid PM ID (e.g. FINX-PM-XXXXXX)");
                setIsInvitingPm(false);
                return;
            }

            await projectManagerService.inviteProjectManager(deal.id, payload);
            setActionSuccess("Project Manager invitation sent successfully. The PM will review and accept or decline.");
            setShowInviteModal(false);
            setManualPmId("");
            await fetchPmInfo();
            await fetchDeal();
        } catch (err: any) {
            setActionError(err?.response?.data?.message || err.message || "Failed to invite Project Manager");
        } finally {
            setIsInvitingPm(false);
        }
    };

    const handleCancelPmInvitation = async () => {
        if (!deal || !pmInfo?.activeInvitation) return;
        const confirm = window.confirm("Are you sure you want to cancel this Project Manager invitation?");
        if (!confirm) return;

        setIsCancellingPm(true);
        setActionError(null);
        setActionSuccess(null);
        try {
            await projectManagerService.cancelInvitation(deal.id, pmInfo.activeInvitation.id);
            setActionSuccess("Project Manager invitation cancelled.");
            await fetchPmInfo();
            await fetchDeal();
        } catch (err: any) {
            setActionError(err?.response?.data?.message || err.message || "Failed to cancel invitation");
        } finally {
            setIsCancellingPm(false);
        }
    };

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
        const label = status === "PENDING_VENDOR_ACCEPTANCE" ? "Waiting for Vendor Acceptance" : status.replace(/_/g, " ");
        return (
            <span className={`px-3 py-1 text-sm font-semibold rounded-full border ${styles[status] || defaultStyle}`}>
                {label}
            </span>
        );
    };

    const canCancel = deal && (deal.status === 'DRAFT' || deal.status === 'PENDING_VENDOR_ACCEPTANCE');

    return (
        <DashboardLayout>
            <div className="space-y-6 max-w-4xl">
                <BlockchainEscrow dealId={projectId}/>
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
                                <CardHeader className="pb-3 border-b border-slate-100 flex flex-row items-center justify-between">
                                    <div className="flex items-center gap-2 text-slate-700 font-semibold">
                                        <User size={18} className="text-blue-500" /> Parties &amp; Governance
                                    </div>
                                    {!deal.projectManagerId && !pmInfo?.projectManager && !pmInfo?.activeInvitation && (
                                        <Button
                                            size="sm"
                                            onClick={handleOpenInviteModal}
                                            className="gap-1.5 bg-blue-600 hover:bg-blue-700 text-white text-xs h-7"
                                        >
                                            <UserPlus size={13} /> Assign Project Manager
                                        </Button>
                                    )}
                                </CardHeader>
                                <CardContent className="pt-4 grid grid-cols-1 md:grid-cols-3 gap-6">
                                    <div className="space-y-1">
                                        <p className="text-sm font-medium text-slate-500">Buyer (You)</p>
                                        <p className="text-xs font-mono text-slate-900 bg-slate-50 p-2.5 rounded border border-slate-200 select-all truncate">{deal.buyerId}</p>
                                    </div>
                                    <div className="space-y-1">
                                        <p className="text-sm font-medium text-slate-500">Seller (Vendor)</p>
                                        <p className="text-xs font-mono text-slate-900 bg-slate-50 p-2.5 rounded border border-slate-200 select-all truncate">{deal.sellerId}</p>
                                    </div>
                                    <div className="space-y-1">
                                        <div className="flex items-center justify-between mb-1">
                                            <p className="text-sm font-medium text-slate-500">Project Manager</p>
                                            {pmInfo?.projectManager || deal.projectManager ? (
                                                <span className="px-2 py-0.5 text-[11px] font-bold rounded-full bg-emerald-100 text-emerald-800 border border-emerald-200">
                                                    Assigned
                                                </span>
                                            ) : pmInfo?.activeInvitation ? (
                                                <span className="px-2 py-0.5 text-[11px] font-bold rounded-full bg-amber-100 text-amber-800 border border-amber-200">
                                                    Invited
                                                </span>
                                            ) : (
                                                <span className="px-2 py-0.5 text-[11px] font-semibold rounded-full bg-slate-100 text-slate-600 border border-slate-200">
                                                    None
                                                </span>
                                            )}
                                        </div>

                                        {pmInfo?.projectManager || deal.projectManager ? (
                                            <div className="bg-emerald-50/60 p-2.5 rounded border border-emerald-200 space-y-1">
                                                <div className="flex items-center justify-between">
                                                    <span className="text-xs font-semibold text-emerald-950 truncate">
                                                        {(pmInfo?.projectManager || deal.projectManager)?.name || (pmInfo?.projectManager || deal.projectManager)?.fullName || "Assigned PM"}
                                                    </span>
                                                    <span className="font-mono text-[11px] font-bold text-emerald-800 bg-emerald-100 px-1.5 py-0.5 rounded shrink-0">
                                                        {(pmInfo?.projectManager || deal.projectManager)?.pmId || "FINX-PM"}
                                                    </span>
                                                </div>
                                                <p className="text-[11px] text-emerald-700 truncate">
                                                    {(pmInfo?.projectManager || deal.projectManager)?.email}
                                                </p>
                                            </div>
                                        ) : pmInfo?.activeInvitation ? (
                                            <div className="bg-amber-50/60 p-2.5 rounded border border-amber-200 space-y-1.5">
                                                <div className="flex items-center justify-between">
                                                    <span className="text-xs font-semibold text-amber-950 truncate">
                                                        {pmInfo.activeInvitation.projectManagerName || pmInfo.activeInvitation.projectManagerEmail || "Invited PM"}
                                                    </span>
                                                    {pmInfo.activeInvitation.projectManagerPmId && (
                                                        <span className="font-mono text-[11px] font-bold text-amber-800 bg-amber-100 px-1.5 py-0.5 rounded shrink-0">
                                                            {pmInfo.activeInvitation.projectManagerPmId}
                                                        </span>
                                                    )}
                                                </div>
                                                <div className="flex items-center justify-between pt-0.5">
                                                    <span className="text-[10px] text-amber-700">
                                                        Sent {new Date(pmInfo.activeInvitation.invitedAt).toLocaleDateString()}
                                                    </span>
                                                    <Button
                                                        size="sm"
                                                        variant="outline"
                                                        disabled={isCancellingPm}
                                                        onClick={handleCancelPmInvitation}
                                                        className="h-6 px-2 text-[10px] font-semibold text-rose-700 hover:text-rose-900 border-rose-200 hover:bg-rose-50"
                                                    >
                                                        {isCancellingPm ? "Cancelling..." : "Cancel Invite"}
                                                    </Button>
                                                </div>
                                            </div>
                                        ) : (
                                            <div className="bg-slate-50 p-2.5 rounded border border-slate-200 flex items-center justify-between">
                                                <span className="text-xs text-slate-500">Not assigned</span>
                                                <Button
                                                    size="sm"
                                                    variant="outline"
                                                    onClick={handleOpenInviteModal}
                                                    className="h-6 px-2 text-[11px] font-medium text-blue-600 border-blue-200 hover:bg-blue-50"
                                                >
                                                    Assign PM
                                                </Button>
                                            </div>
                                        )}
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

                            {/* Vendor Acceptance Notice / Project State Notice */}
                            {(deal.status === "DRAFT" || deal.status === "PENDING_VENDOR_ACCEPTANCE") && (
                                <div className="p-4 rounded-xl bg-amber-50/90 border border-amber-200/80 flex items-start gap-3 shadow-xs">
                                    <AlertTriangle className="h-5 w-5 text-amber-600 mt-0.5 shrink-0" />
                                    <div>
                                        <div className="flex items-center gap-2">
                                            <h4 className="text-sm font-semibold text-amber-900">
                                                Waiting for Vendor Acceptance
                                            </h4>
                                            <span className="flex h-2 w-2 relative">
                                                <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-amber-400 opacity-75"></span>
                                                <span className="relative inline-flex rounded-full h-2 w-2 bg-amber-500"></span>
                                            </span>
                                        </div>
                                        <p className="text-xs text-amber-700 mt-1 leading-relaxed">
                                            Project invitation has been sent to the assigned vendor. You can configure and review milestones now so work can begin immediately once accepted.
                                        </p>
                                    </div>
                                </div>
                            )}

                            {['ACCEPTED', 'MILESTONES_PENDING', 'AWAITING_FUNDING', 'FUNDED', 'IN_PROGRESS'].includes(deal.status) && (
                                <div className="p-4 rounded-xl bg-emerald-50/90 border border-emerald-200/80 flex flex-col sm:flex-row sm:items-center justify-between gap-3 shadow-xs">
                                    <div className="flex items-center gap-3">
                                        <CheckCircle2 className="h-5 w-5 text-emerald-600 shrink-0" />
                                        <div>
                                            <h4 className="text-sm font-semibold text-emerald-900">
                                                Vendor Accepted · Project Active
                                            </h4>
                                            <p className="text-xs text-emerald-700 mt-0.5 leading-relaxed">
                                                The assigned vendor accepted this project. You can now fund milestones to authorize deliverable execution.
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

                            {deal.status === "REJECTED" && (
                                <div className="p-4 rounded-xl bg-rose-50/90 border border-rose-200/80 flex items-start gap-3 shadow-xs">
                                    <XCircle className="h-5 w-5 text-rose-600 mt-0.5 shrink-0" />
                                    <div className="space-y-1">
                                        <h4 className="text-sm font-semibold text-rose-900">
                                            Vendor Rejected Project
                                        </h4>
                                        <p className="text-xs text-rose-700 leading-relaxed">
                                            The assigned vendor declined this invitation. Reason: <strong>{deal.rejectionReason || "No specific reason provided."}</strong>
                                        </p>
                                        {deal.rejectedAt && (
                                            <p className="text-xs text-rose-500 font-mono pt-1">
                                                Declined on: {new Date(deal.rejectedAt).toLocaleString()}
                                            </p>
                                        )}
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

            {/* Assign / Invite Project Manager Modal */}
            {showInviteModal && (
                <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-xs p-4">
                    <div className="bg-white rounded-xl max-w-lg w-full p-6 shadow-xl space-y-5 border border-slate-200">
                        <div className="flex items-start justify-between">
                            <div className="flex items-center gap-3">
                                <div className="h-10 w-10 bg-blue-50 text-blue-600 rounded-full flex items-center justify-center shrink-0">
                                    <UserPlus size={20} />
                                </div>
                                <div>
                                    <h3 className="text-base font-bold text-slate-900">Assign Project Manager</h3>
                                    <p className="text-xs text-slate-500 mt-0.5">
                                        Invite an independent Project Manager to audit deliverables.
                                    </p>
                                </div>
                            </div>
                            <button
                                onClick={() => setShowInviteModal(false)}
                                className="text-slate-400 hover:text-slate-600 p-1"
                            >
                                <XCircle size={18} />
                            </button>
                        </div>

                        {/* Mode Selection Tabs */}
                        <div className="flex bg-slate-100 p-1 rounded-lg">
                            <button
                                type="button"
                                onClick={() => setInviteMode("SELECT")}
                                className={`flex-1 py-1.5 text-xs font-semibold rounded-md transition-colors ${
                                    inviteMode === "SELECT"
                                        ? "bg-white text-slate-900 shadow-xs"
                                        : "text-slate-600 hover:text-slate-900"
                                }`}
                            >
                                Registered PM Directory
                            </button>
                            <button
                                type="button"
                                onClick={() => setInviteMode("MANUAL")}
                                className={`flex-1 py-1.5 text-xs font-semibold rounded-md transition-colors ${
                                    inviteMode === "MANUAL"
                                        ? "bg-white text-slate-900 shadow-xs"
                                        : "text-slate-600 hover:text-slate-900"
                                }`}
                            >
                                Enter PM ID
                            </button>
                        </div>

                        {inviteMode === "SELECT" ? (
                            <div className="space-y-2">
                                <label className="block text-xs font-semibold text-slate-700">
                                    Select Project Manager
                                </label>
                                {eligiblePms.length === 0 ? (
                                    <div className="p-4 text-center border border-dashed rounded-lg bg-slate-50 text-xs text-slate-500">
                                        No registered Project Managers found. Switch to "Enter PM ID" to invite by ID.
                                    </div>
                                ) : (
                                    <select
                                        value={selectedPmId}
                                        onChange={(e) => setSelectedPmId(e.target.value)}
                                        className="w-full text-xs p-2.5 border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 bg-white text-slate-900"
                                    >
                                        {eligiblePms.map((pm) => (
                                            <option key={pm.id} value={pm.id}>
                                                {pm.name} ({pm.email}) — {pm.pmId || "FINX-PM"}
                                            </option>
                                        ))}
                                    </select>
                                )}
                            </div>
                        ) : (
                            <div className="space-y-2">
                                <label className="block text-xs font-semibold text-slate-700">
                                    Permanent Project Manager ID (PM ID)
                                </label>
                                <input
                                    type="text"
                                    value={manualPmId}
                                    onChange={(e) => setManualPmId(e.target.value)}
                                    placeholder="FINX-PM-XXXXXX"
                                    className="w-full text-xs font-mono uppercase tracking-wider p-2.5 border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 bg-slate-50 text-slate-900 placeholder:text-slate-400"
                                />
                                <p className="text-[11px] text-slate-500">
                                    Enter the 6-character identifier provided by the Project Manager (e.g., <span className="font-mono font-medium">FINX-PM-A1B2C3</span>).
                                </p>
                            </div>
                        )}

                        <div className="p-3 bg-blue-50/60 rounded-lg border border-blue-200/80 text-[11px] text-blue-800 space-y-1">
                            <p className="font-semibold flex items-center gap-1.5">
                                <ShieldCheck size={14} className="text-blue-600 shrink-0" />
                                Two-Sided Acceptance Workflow
                            </p>
                            <p className="text-blue-700 leading-relaxed">
                                FinX requires mutual consent. The invited Project Manager will receive a notification and must accept before becoming assigned.
                            </p>
                        </div>

                        <div className="flex items-center justify-end gap-2.5 pt-2 border-t border-slate-100">
                            <Button
                                variant="outline"
                                size="sm"
                                onClick={() => setShowInviteModal(false)}
                                className="text-xs"
                            >
                                Cancel
                            </Button>
                            <Button
                                size="sm"
                                onClick={handleSendPmInvitation}
                                disabled={isInvitingPm || (inviteMode === "SELECT" && eligiblePms.length === 0)}
                                className="bg-blue-600 hover:bg-blue-700 text-white text-xs gap-1.5"
                            >
                                {isInvitingPm && <RefreshCw size={14} className="animate-spin" />}
                                Send Project Invitation
                            </Button>
                        </div>
                    </div>
                </div>
            )}
        </DashboardLayout>
    );
}
