"use client";

import { useCallback, useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card, CardContent } from "@/components/ui/Card";
import { MilestoneStatusBadge } from "@/components/ui/MilestoneStatusBadge";
import { EscrowTimeline } from "@/components/escrow/EscrowTimeline";
import { BlockchainEscrow } from "@/components/escrow/BlockchainEscrow";
import { Milestone, EscrowAccount, Payment } from "@/types";
import { milestoneService } from "@/services/milestone.service";
import { escrowService } from "@/services/escrow.service";
import { paymentService } from "@/services/payment.service";
import { Button } from "@/components/ui/Button";
import Link from "next/link";
import { ArrowLeft, Clock, DollarSign, FileCheck, CheckCircle2, XCircle, Unlock, Wallet, AlertCircle, Loader2, ShieldCheck, RefreshCw } from "lucide-react";

export default function CorporateMilestoneDetails() {
    const { projectId, milestoneId } = useParams();
    const router = useRouter();
    const [blockchain, setBlockchain] = useState(true);
    useEffect(() => { void paymentService.getPaymentConfig().then(c => setBlockchain(c.rail === "BLOCKCHAIN")).catch(() => {}); }, []);
    const [milestone, setMilestone] = useState<Milestone | null>(null);
    const [escrowAccount, setEscrowAccount] = useState<EscrowAccount | null>(null);
    const [payments, setPayments] = useState<Payment[]>([]);
    const [isLoading, setIsLoading] = useState(true);
    const [isActionLoading, setIsActionLoading] = useState(false);
    const [actionMessage, setActionMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

    const fetchMilestoneData = useCallback(async () => {
        if (!milestoneId) return;
        setIsLoading(true);
        try {
            const [milestoneData, escrowData, paymentsData] = await Promise.all([
                milestoneService.getMilestone(milestoneId as string),
                projectId ? escrowService.getDealEscrow(projectId as string).catch(() => null) : Promise.resolve(null),
                projectId ? paymentService.getProjectPayments(projectId as string).catch(() => []) : Promise.resolve([])
            ]);
            setMilestone(milestoneData);
            if (escrowData) {
                setEscrowAccount(escrowData);
            }
            if (paymentsData) {
                setPayments(paymentsData);
            }
        } catch (err: any) {
            setActionMessage({ type: 'error', text: err.response?.data?.message || err.message || "Failed to load milestone" });
        } finally {
            setIsLoading(false);
        }
    }, [milestoneId, projectId]);

    useEffect(() => {
        fetchMilestoneData();
    }, [fetchMilestoneData]);

    const handleApprove = async () => {
        if (!milestoneId) return;
        setIsActionLoading(true);
        setActionMessage(null);
        try {
            await milestoneService.approveMilestone(milestoneId as string);
            setActionMessage({ type: 'success', text: "Milestone deliverable approved successfully!" });
            await fetchMilestoneData();
        } catch (err: any) {
            setActionMessage({ type: 'error', text: err.response?.data?.message || err.message || "Failed to approve milestone" });
        } finally {
            setIsActionLoading(false);
        }
    };

    const handleReject = async () => {
        if (!milestoneId) return;
        const reason = window.prompt("Enter revision feedback or reason for rejection:");
        if (!reason || !reason.trim()) return;

        setIsActionLoading(true);
        setActionMessage(null);
        try {
            await milestoneService.rejectMilestone(milestoneId as string, reason.trim());
            setActionMessage({ type: 'success', text: "Milestone returned to vendor for revision." });
            await fetchMilestoneData();
        } catch (err: any) {
            setActionMessage({ type: 'error', text: err.response?.data?.message || err.message || "Failed to reject milestone" });
        } finally {
            setIsActionLoading(false);
        }
    };

    const handleReleaseEscrow = async () => {
        if (!milestoneId) return;
        if (!window.confirm(`Are you sure you want to release ₹${milestone?.amount?.toLocaleString()} from escrow to the vendor? This action is final.`)) {
            return;
        }

        setIsActionLoading(true);
        setActionMessage(null);
        try {
            await escrowService.releaseEscrow(milestoneId as string, "Corporate authorized escrow release upon milestone approval");
            setActionMessage({ type: 'success', text: "Escrow funds successfully released to vendor account!" });
            await fetchMilestoneData();
        } catch (err: any) {
            setActionMessage({ type: 'error', text: err.response?.data?.message || err.message || "Failed to release escrow funds" });
        } finally {
            setIsActionLoading(false);
        }
    };

    if (isLoading && !milestone) {
        return (
            <DashboardLayout>
                <div className="flex items-center justify-center p-12 text-slate-500">
                    <Loader2 className="animate-spin mr-2" size={20} /> Loading milestone workspace...
                </div>
            </DashboardLayout>
        );
    }

    if (!milestone) {
        return (
            <DashboardLayout>
                <div className="p-8 text-center text-slate-500">Milestone not found.</div>
            </DashboardLayout>
        );
    }

    // Determine milestone funding & escrow state
    const milestonePayment = payments.find(p => p.milestoneId === milestone.id && p.status === 'SUCCESS');
    const isFunded = !!milestonePayment || ((escrowAccount?.availableBalance ?? 0) >= (milestone.amount ?? 0) && (escrowAccount?.totalFunded ?? 0) > 0);
    const isReleased = milestone.status === 'COMPLETED';

    const milestoneValue = milestone.amount || 0;
    const milestoneFunded = isFunded ? milestoneValue : 0;
    const milestoneReleased = isReleased ? milestoneValue : 0;
    const milestoneAvailable = isFunded && !isReleased ? milestoneValue : 0;

    return (
        <DashboardLayout>
            <div className="space-y-6">
                {blockchain && <BlockchainEscrow dealId={projectId as string}/>}
                <div>
                    <Button variant="ghost" onClick={() => router.push(`/corporate/projects/${projectId}`)} className="gap-2 -ml-3 text-slate-500 hover:text-slate-900 mb-2">
                        <ArrowLeft size={16} /> Back to Project
                    </Button>
                    <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4">
                        <div>
                            <h1 className="text-2xl font-bold text-slate-900">{milestone.title}</h1>
                            <p className="text-sm text-slate-500 mt-1">{milestone.description}</p>
                        </div>
                        <div className="flex items-center gap-3">
                            <Button variant="outline" size="sm" onClick={fetchMilestoneData} className="gap-1.5 text-xs text-slate-600">
                                <RefreshCw size={14} /> Refresh
                            </Button>
                            <MilestoneStatusBadge status={milestone.status} />
                        </div>
                    </div>
                </div>

                {actionMessage && (
                    <div className={`p-4 rounded-xl flex items-center gap-3 text-sm ${actionMessage.type === 'success' ? 'bg-emerald-50 border border-emerald-200 text-emerald-800' : 'bg-red-50 border border-red-200 text-red-700'}`}>
                        {actionMessage.type === 'success' ? <CheckCircle2 size={18} className="shrink-0 text-emerald-600" /> : <AlertCircle size={18} className="shrink-0 text-red-600" />}
                        <span>{actionMessage.text}</span>
                    </div>
                )}

                {/* FINX Funding Overview Card */}
                <Card className="border-slate-200 shadow-sm overflow-hidden">
                    <div className="bg-slate-900 px-6 py-4 flex items-center justify-between">
                        <div className="flex items-center gap-2.5">
                            <ShieldCheck className="text-emerald-400" size={20} />
                            <h2 className="text-base font-semibold text-white">FINX Funding</h2>
                        </div>
                        <div className="flex items-center gap-2">
                            <span className="text-xs text-slate-400">Escrow Ledger:</span>
                            <span className={`px-2.5 py-0.5 rounded text-xs font-semibold ${escrowAccount?.status === 'ACTIVE' ? 'bg-emerald-950 text-emerald-300 border border-emerald-800' : 'bg-slate-800 text-slate-300'}`}>
                                {escrowAccount ? escrowAccount.status : 'ACTIVE'}
                            </span>
                        </div>
                    </div>
                    <CardContent className="p-6">
                        <div className="grid grid-cols-2 sm:grid-cols-4 gap-4 pb-5 border-b border-slate-100">
                            <div>
                                <p className="text-xs font-medium text-slate-500 uppercase tracking-wider">Milestone Value</p>
                                <p className="text-xl font-bold text-slate-900 mt-1">₹{milestoneValue.toLocaleString()} {milestone.currency || "INR"}</p>
                            </div>
                            <div>
                                <p className="text-xs font-medium text-slate-500 uppercase tracking-wider">Funded</p>
                                <p className={`text-xl font-bold mt-1 ${isFunded ? 'text-emerald-600' : 'text-slate-500'}`}>
                                    ₹{milestoneFunded.toLocaleString()}
                                </p>
                            </div>
                            <div>
                                <p className="text-xs font-medium text-slate-500 uppercase tracking-wider">Released</p>
                                <p className={`text-xl font-bold mt-1 ${isReleased ? 'text-indigo-600' : 'text-slate-500'}`}>
                                    ₹{milestoneReleased.toLocaleString()}
                                </p>
                            </div>
                            <div>
                                <p className="text-xs font-medium text-slate-500 uppercase tracking-wider">Available</p>
                                <p className={`text-xl font-bold mt-1 ${milestoneAvailable > 0 ? 'text-blue-600' : 'text-slate-500'}`}>
                                    ₹{milestoneAvailable.toLocaleString()}
                                </p>
                            </div>
                        </div>

                        <div className="mt-4 flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3">
                            <div className="flex flex-wrap items-center gap-2">
                                <span className="text-xs text-slate-500">Payment Status:</span>
                                {isFunded ? (
                                    <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-semibold bg-emerald-50 text-emerald-700 ring-1 ring-emerald-600/20">
                                        <CheckCircle2 size={13} /> FUNDED
                                    </span>
                                ) : (
                                    <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-semibold bg-amber-50 text-amber-700 ring-1 ring-amber-600/20">
                                        <Clock size={13} /> NOT FUNDED
                                    </span>
                                )}
                                {milestonePayment && (
                                    <span className="text-xs text-slate-400 font-mono">
                                        (Ref: {milestonePayment.razorpayPaymentId || milestonePayment.id?.slice(0, 8)})
                                    </span>
                                )}
                            </div>

                            {!isFunded && !isReleased && (
                                <Link href={`/corporate/projects/${projectId}/milestones/${milestoneId}/payment`}>
                                    <Button className="gap-2 bg-emerald-600 hover:bg-emerald-700 text-white font-medium shadow-sm">
                                        <Wallet size={16} /> Fund Milestone (₹{milestoneValue.toLocaleString()})
                                    </Button>
                                </Link>
                            )}
                        </div>
                    </CardContent>
                </Card>

                <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                    <Card>
                        <CardContent className="p-6 flex items-center gap-4">
                            <div className="p-3 bg-blue-50 text-blue-600 rounded-full">
                                <DollarSign size={24} />
                            </div>
                            <div>
                                <p className="text-sm font-medium text-slate-500">Milestone Value</p>
                                <p className="font-semibold text-lg">₹{milestone.amount.toLocaleString()} {milestone.currency || "INR"}</p>
                            </div>
                        </CardContent>
                    </Card>
                    <Card>
                        <CardContent className="p-6 flex items-center gap-4">
                            <div className="p-3 bg-slate-50 text-slate-600 rounded-full">
                                <Clock size={24} />
                            </div>
                            <div>
                                <p className="text-sm font-medium text-slate-500">Status Phase</p>
                                <p className="font-semibold">{milestone.status.replace(/_/g, ' ')}</p>
                            </div>
                        </CardContent>
                    </Card>
                    <Card>
                        <CardContent className="p-6 flex items-center gap-4">
                            <div className="p-3 bg-indigo-50 text-indigo-600 rounded-full">
                                <FileCheck size={24} />
                            </div>
                            <div>
                                <p className="text-sm font-medium text-slate-500">Deliverables</p>
                                <p className="font-semibold">{milestone.deliverables?.length || 0} Submitted</p>
                            </div>
                        </CardContent>
                    </Card>
                </div>

                {/* Milestone Lifecycle Action Bar */}
                <Card className="border-slate-200">
                    <CardContent className="p-6 flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4">
                        <div>
                            <h3 className="text-base font-semibold text-slate-900">Milestone Actions</h3>
                            <p className="text-xs text-slate-500 mt-0.5">Manage escrow deposits, deliverable review, and fund releases.</p>
                        </div>
                        <div className="flex flex-wrap items-center gap-3">
                            {/* PENDING: Pay & Fund Escrow */}
                            {!blockchain && milestone.status === 'PENDING' && !isFunded && (
                                <Link href={`/corporate/projects/${projectId}/milestones/${milestoneId}/payment`}>
                                    <Button className="gap-2 bg-slate-900 hover:bg-slate-800 text-white">
                                        <Wallet size={16} /> Pay & Fund Escrow
                                    </Button>
                                </Link>
                            )}

                            {/* UNDER_REVIEW or SUBMITTED: Review, Approve, Reject */}
                            {['UNDER_REVIEW', 'SUBMITTED'].includes(milestone.status) && (
                                <>
                                    <Button
                                        onClick={handleReject}
                                        disabled={isActionLoading}
                                        variant="outline"
                                        className="gap-2 border-red-200 text-red-600 hover:bg-red-50"
                                    >
                                        <XCircle size={16} /> Request Revisions
                                    </Button>
                                    <Button
                                        onClick={handleApprove}
                                        isLoading={isActionLoading}
                                        disabled={isActionLoading}
                                        className="gap-2 bg-emerald-600 hover:bg-emerald-700 text-white"
                                    >
                                        <CheckCircle2 size={16} /> Approve Deliverable
                                    </Button>
                                </>
                            )}

                            {/* APPROVED: If funded -> Release Escrow Funds; If not funded -> prompt to fund */}
                            {!blockchain && milestone.status === 'APPROVED' && (
                                <>
                                    {!isFunded ? (
                                        <div className="flex flex-col sm:flex-row items-start sm:items-center gap-2">
                                            <span className="text-xs text-amber-700 bg-amber-50 border border-amber-200 px-3 py-1.5 rounded-lg">
                                                Deliverables approved. Fund milestone to enable vendor release.
                                            </span>
                                            <Link href={`/corporate/projects/${projectId}/milestones/${milestoneId}/payment`}>
                                                <Button className="gap-2 bg-emerald-600 hover:bg-emerald-700 text-white shadow-sm">
                                                    <Wallet size={16} /> Fund Milestone (₹{milestoneValue.toLocaleString()})
                                                </Button>
                                            </Link>
                                        </div>
                                    ) : (
                                        <Button
                                            onClick={handleReleaseEscrow}
                                            isLoading={isActionLoading}
                                            disabled={isActionLoading}
                                            className="gap-2 bg-indigo-600 hover:bg-indigo-700 text-white shadow-sm"
                                        >
                                            <Unlock size={16} /> Release Escrow Funds to Vendor
                                        </Button>
                                    )}
                                </>
                            )}

                            {/* RELEASED or COMPLETED */}
                            {['RELEASED', 'COMPLETED'].includes(milestone.status) && (
                                <span className="inline-flex items-center rounded-full bg-emerald-50 px-3 py-1.5 text-xs font-semibold text-emerald-700 ring-1 ring-emerald-600/20">
                                    <CheckCircle2 size={14} className="mr-1.5" /> Escrow Funds Released to Vendor
                                </span>
                            )}
                        </div>
                    </CardContent>
                </Card>

                <div className="grid grid-cols-1 lg:grid-cols-3 gap-6 mt-4">
                    <div className="lg:col-span-2">
                        <h2 className="text-lg font-semibold text-slate-900 mb-4">Vendor Deliverables</h2>
                        <Card>
                            <div className="divide-y divide-slate-100">
                                {(!milestone.deliverables || milestone.deliverables.length === 0) && (
                                    <div className="p-8 text-center text-slate-500">
                                        <FileCheck className="mx-auto h-12 w-12 text-slate-300 mb-3" />
                                        <p className="font-medium text-slate-700">No deliverables submitted yet</p>
                                        <p className="text-xs text-slate-400 mt-1">When the vendor submits work, deliverable documents and URLs will appear here for review.</p>
                                    </div>
                                )}
                                {milestone.deliverables?.map(d => (
                                    <div key={d.id} className="p-6 flex items-center justify-between">
                                        <div className="flex items-center gap-3">
                                            <div className="p-2 bg-indigo-50 text-indigo-600 rounded">
                                                <FileCheck size={20} />
                                            </div>
                                            <div>
                                                <p className="font-semibold text-slate-900">{d.fileName}</p>
                                                <p className="text-xs text-slate-500">Uploaded {new Date(d.submittedAt || d.uploadedAt || Date.now()).toLocaleString()}</p>
                                                {d.description && <p className="text-xs text-slate-600 mt-1">{d.description}</p>}
                                                {d.rejectionReason && <p className="text-xs text-red-600 mt-1 font-medium">Rejection Reason: {d.rejectionReason}</p>}
                                            </div>
                                        </div>
                                        <a href={d.fileUrl} target="_blank" rel="noopener noreferrer" className="text-sm font-medium text-blue-600 hover:underline">
                                            Download / View
                                        </a>
                                    </div>
                                ))}
                            </div>
                        </Card>
                    </div>
                    <div className="lg:col-span-1">
                        <Card>
                            <CardContent className="p-6">
                                <EscrowTimeline currentStatus={milestone.status} />
                            </CardContent>
                        </Card>
                    </div>
                </div>
            </div>
        </DashboardLayout>
    );
}
