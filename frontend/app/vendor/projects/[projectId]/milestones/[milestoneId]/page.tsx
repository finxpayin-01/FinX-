"use client";

import { useCallback, useEffect, useState } from "react";
import { useParams } from "next/navigation";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card, CardContent } from "@/components/ui/Card";
import { MilestoneStatusBadge } from "@/components/ui/MilestoneStatusBadge";
import { Milestone } from "@/types";
import { milestoneService } from "@/services/milestone.service";
import Link from "next/link";
import { ArrowLeft, Clock, UploadCloud, Play, Send, FileCheck } from "lucide-react";
import { Button } from "@/components/ui/Button";

export default function VendorMilestoneWorkspace() {
    const { milestoneId } = useParams();
    const [milestone, setMilestone] = useState<Milestone | null>(null);
    const [isStarting, setIsStarting] = useState(false);
    const [isSubmitting, setIsSubmitting] = useState(false);

    const fetchData = useCallback(() => {
        if (milestoneId) {
            milestoneService.getMilestone(milestoneId as string).then(setMilestone);
        }
    }, [milestoneId]);

    useEffect(() => {
        fetchData();
    }, [fetchData]);

    const handleStart = async () => {
        setIsStarting(true);
        try {
            await milestoneService.startMilestone(milestoneId as string);
            await fetchData();
        } finally { setIsStarting(false); }
    }

    const handleUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
        const file = e.target.files?.[0];
        if (!file) return;
        try {
            await milestoneService.submitDeliverable(milestoneId as string, {
                fileName: file.name,
                fileUrl: `https://storage.finx.local/deliverables/${file.name}`,
                description: `Deliverable document: ${file.name}`
            });
            alert('Deliverable uploaded and submitted for review successfully!');
            await fetchData();
        } catch (err: any) {
            alert('Upload failed: ' + (err.response?.data?.message || err.message));
        }
    }

    const handleSubmit = async () => {
        setIsSubmitting(true);
        try {
            await milestoneService.submitMilestone(milestoneId as string);
            alert('Milestone submitted to PM for final review!');
            await fetchData();
        } finally { setIsSubmitting(false); }
    }

    if (!milestone) return <DashboardLayout><div className="p-8">Loading workspace...</div></DashboardLayout>;

    return (
        <DashboardLayout>
            <div className="space-y-6">
                <div>
                    <Link href="/vendor" className="text-sm text-blue-600 flex items-center gap-1 mb-4 hover:underline">
                        <ArrowLeft size={16} /> Back to Contracts
                    </Link>
                    <div className="flex items-center justify-between">
                        <div>
                            <h1 className="text-2xl font-bold text-slate-900">{milestone.title}</h1>
                            <p className="text-sm text-slate-500 mt-1">{milestone.description}</p>
                        </div>
                        <MilestoneStatusBadge status={milestone.status} />
                    </div>
                </div>

                {milestone.status === 'PENDING' && (
                    <Card className="bg-blue-50 border-blue-200">
                        <CardContent className="p-6 text-center space-y-4">
                            <p className="text-blue-800 font-medium">This milestone has been funded and is ready for you to begin work.</p>
                            <Button onClick={handleStart} isLoading={isStarting} className="gap-2 bg-blue-600 hover:bg-blue-700">
                                <Play size={16} /> Start Working on Milestone
                            </Button>
                        </CardContent>
                    </Card>
                )}

                {['IN_PROGRESS', 'SUBMITTED', 'REJECTED'].includes(milestone.status) && (
                    <Card className="border-indigo-200">
                        <CardContent className="p-6">
                            <h3 className="font-semibold text-slate-900 mb-4">Secure Deliverable Upload</h3>
                            <div className="border-2 border-dashed border-slate-300 rounded-lg p-8 text-center hover:bg-slate-50 transition-colors">
                                <UploadCloud className="mx-auto h-12 w-12 text-slate-400 mb-3" />
                                <p className="text-sm text-slate-600">Drag and drop your deliverable archive here, or click to browse</p>
                                <p className="text-xs text-slate-500 mt-1 mb-4">ZIP, PDF, MP4 up to 500MB</p>
                                <label className="cursor-pointer bg-white border border-slate-300 text-slate-700 px-4 py-2 rounded-md hover:bg-slate-50 text-sm font-medium">
                                    Select File
                                    <input type="file" className="hidden" onChange={handleUpload} />
                                </label>
                            </div>
                        </CardContent>
                    </Card>
                )}

                <h2 className="text-lg font-semibold text-slate-900 mt-8 mb-4">Uploaded Assets</h2>
                <Card>
                    <div className="divide-y divide-slate-100">
                        {(!milestone.deliverables || milestone.deliverables.length === 0) && (
                            <div className="p-4 text-center text-slate-500 text-sm">No files uploaded yet.</div>
                        )}
                        {milestone.deliverables?.map(d => (
                            <div key={d.id} className="p-4 flex items-center justify-between">
                                <div className="flex items-center gap-3">
                                    <FileCheck size={18} className="text-indigo-600" />
                                    <span className="font-medium text-sm text-slate-900">{d.fileName}</span>
                                </div>
                                <span className="text-xs text-slate-500">{new Date(d.submittedAt || d.uploadedAt || Date.now()).toLocaleString()}</span>
                            </div>
                        ))}
                    </div>
                </Card>

                {['IN_PROGRESS', 'SUBMITTED', 'REJECTED'].includes(milestone.status) && milestone.deliverables && milestone.deliverables.length > 0 && (
                    <div className="flex justify-end pt-4">
                        <Button onClick={handleSubmit} isLoading={isSubmitting} className="gap-2 bg-emerald-600 hover:bg-emerald-700">
                            <Send size={16} /> Submit Deliverables to PM Review
                        </Button>
                    </div>
                )}
            </div>
        </DashboardLayout>
    );
}
