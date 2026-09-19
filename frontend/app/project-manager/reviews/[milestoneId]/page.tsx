"use client";

import { useCallback, useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card, CardContent } from "@/components/ui/Card";
import { MilestoneStatusBadge } from "@/components/ui/MilestoneStatusBadge";
import { milestoneService } from "@/services/milestone.service";
import { Milestone } from "@/types";
import { Button } from "@/components/ui/Button";
import Link from "next/link";
import { ArrowLeft, CheckCircle, XCircle, FileCheck, RefreshCw } from "lucide-react";

export default function MilestoneReviewDetail() {
    const { milestoneId } = useParams();
    const router = useRouter();
    const [milestone, setMilestone] = useState<Milestone | null>(null);
    const [isLoading, setIsLoading] = useState(true);

    const [isRejectModalOpen, setIsRejectModalOpen] = useState(false);
    const [isChangeModalOpen, setIsChangeModalOpen] = useState(false);
    const [reason, setReason] = useState("");

    const fetchData = useCallback(() => {
        milestoneService.getMilestone(milestoneId as string)
            .then(setMilestone)
            .finally(() => setIsLoading(false));
    }, [milestoneId]);

    useEffect(() => {
        fetchData();
    }, [fetchData]);

    const handleApprove = async () => {
        try {
            await milestoneService.approveMilestone(milestoneId as string);
            alert("Milestone globally authorized! Escrow is now permitted.");
            router.push('/project-manager');
        } catch (err) {
            alert("Failed to approve milestone.");
        }
    };

    const handleReject = async () => {
        if (!reason.trim()) {
            alert("Rejection reason is absolutely legally required.");
            return;
        }
        try {
            await milestoneService.rejectMilestone(milestoneId as string, reason);
            alert("Milestone rejected.");
            router.push('/project-manager');
        } catch (err) {
            alert("Failed to reject milestone.");
        }
    };

    const handleRequestChanges = async () => {
        if (!reason.trim()) {
            alert("Change request instructions are required.");
            return;
        }
        try {
            await milestoneService.requestChanges(milestoneId as string, reason);
            alert("Changes requested from vendor.");
            router.push('/project-manager');
        } catch (err) {
            alert("Failed to request changes.");
        }
    };

    if (!milestone) return <DashboardLayout><div className="p-8">Loading...</div></DashboardLayout>;

    return (
        <DashboardLayout>
            <div className="space-y-6 max-w-4xl mx-auto">
                <div>
                    <Link href="/project-manager" className="text-sm text-blue-600 flex items-center gap-1 mb-4 hover:underline">
                        <ArrowLeft size={16} /> Back to Audit Queue
                    </Link>
                    <div className="flex items-center justify-between">
                        <div>
                            <h1 className="text-2xl font-bold text-slate-900">Arbitration Review</h1>
                            <p className="text-sm text-slate-500 mt-1">Inspect S3 deliverables against SLA contracts.</p>
                        </div>
                        <MilestoneStatusBadge status={milestone.status} />
                    </div>
                </div>

                <Card>
                    <CardContent className="p-8">
                        <div className="mb-8">
                            <h2 className="text-xl font-bold text-slate-900 mb-2">{milestone.title}</h2>
                            <p className="text-slate-600">{milestone.description}</p>
                            <div className="mt-4 p-4 bg-slate-50 rounded border border-slate-200 flex justify-between">
                                <span className="text-slate-600 font-medium">Escrow Limit Attached</span>
                                <span className="font-bold text-blue-900">${milestone.amount.toLocaleString()} {milestone.currency}</span>
                            </div>
                        </div>

                        <h3 className="font-semibold text-slate-900 mb-4 border-b pb-2">Submitted S3 Assets</h3>
                        {(!milestone.deliverables || milestone.deliverables.length === 0) ? (
                            <p className="text-slate-500 italic mb-8">No files were legally attached to this submission payload.</p>
                        ) : (
                            <div className="space-y-3 mb-8">
                                {milestone.deliverables.map(d => (
                                    <div key={d.id} className="p-4 border border-slate-200 rounded flex justify-between items-center text-sm">
                                        <div className="flex items-center gap-3 text-slate-700">
                                            <FileCheck size={18} className="text-indigo-600" />
                                            <span className="font-medium">{d.fileName}</span>
                                        </div>
                                        <a href={d.fileUrl} target="_blank" className="font-medium text-blue-600 hover:text-blue-800 underline">
                                            Vault Source
                                        </a>
                                    </div>
                                ))}
                            </div>
                        )}

                        {milestone.status === 'SUBMITTED' ? (
                            <div className="flex gap-4 pt-4 border-t border-slate-200">
                                <Button onClick={handleApprove} className="flex-1 bg-emerald-600 hover:bg-emerald-700 h-12 text-white gap-2">
                                    <CheckCircle size={20} /> Authorize Deliverables
                                </Button>
                                <Button onClick={() => setIsChangeModalOpen(true)} variant="outline" className="flex-1 border-amber-200 bg-amber-50 text-amber-700 hover:bg-amber-100 h-12 gap-2">
                                    <RefreshCw size={20} /> Request Changes
                                </Button>
                                <Button onClick={() => setIsRejectModalOpen(true)} className="flex-1 bg-red-600 hover:bg-red-700 text-white h-12 gap-2">
                                    <XCircle size={20} /> Force Reject
                                </Button>
                            </div>
                        ) : (
                            <div className="mt-6 p-4 bg-slate-50 border border-slate-200 rounded text-center">
                                <p className="text-slate-600">This milestone has already transacted past the PM arbitration layer.</p>
                            </div>
                        )}
                    </CardContent>
                </Card>

                {isChangeModalOpen && (
                    <div className="fixed inset-0 bg-slate-900/50 flex items-center justify-center p-4 z-50">
                        <div className="bg-white rounded-xl shadow-xl max-w-md w-full overflow-hidden p-6">
                            <h3 className="text-lg font-bold text-slate-900 mb-2">Request Revisions</h3>
                            <p className="text-sm text-slate-600 mb-4">The vendor will be notified to revise their upload.</p>
                            <textarea
                                className="w-full border border-slate-300 rounded p-3 min-h-[100px] mb-4 text-sm bg-white text-slate-900 placeholder:text-slate-400 focus:ring-blue-500 focus:border-blue-500"
                                placeholder="Enter specific required changes..."
                                value={reason}
                                onChange={e => setReason(e.target.value)}
                            ></textarea>
                            <div className="flex gap-3">
                                <Button onClick={() => setIsChangeModalOpen(false)} variant="outline" className="flex-1">Cancel</Button>
                                <Button onClick={handleRequestChanges} className="flex-1 bg-amber-600 text-white">Send Request</Button>
                            </div>
                        </div>
                    </div>
                )}

                {isRejectModalOpen && (
                    <div className="fixed inset-0 bg-slate-900/50 flex items-center justify-center p-4 z-50">
                        <div className="bg-white rounded-xl shadow-xl max-w-md w-full overflow-hidden p-6">
                            <h3 className="text-lg font-bold text-red-600 mb-2">Final Rejection</h3>
                            <p className="text-sm text-slate-600 mb-4">This terminates the current delivery trajectory. Legal comments required.</p>
                            <textarea
                                className="w-full border border-slate-300 rounded p-3 min-h-[100px] mb-4 text-sm bg-white text-slate-900 placeholder:text-slate-400 focus:ring-blue-500 focus:border-blue-500"
                                placeholder="State explicit rejection reasons..."
                                value={reason}
                                onChange={e => setReason(e.target.value)}
                            ></textarea>
                            <div className="flex gap-3">
                                <Button onClick={() => setIsRejectModalOpen(false)} variant="outline" className="flex-1">Cancel</Button>
                                <Button onClick={handleReject} className="flex-1 bg-red-600 text-white">Commit Rejection</Button>
                            </div>
                        </div>
                    </div>
                )}
            </div>
        </DashboardLayout>
    );
}
