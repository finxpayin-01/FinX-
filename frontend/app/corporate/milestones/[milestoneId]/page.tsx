"use client";

import React, { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card, CardContent } from "@/components/ui/Card";
import { Button } from "@/components/ui/Button";
import { milestoneService } from "@/services/milestone.service";
import { Milestone } from "@/types";
import { ArrowLeft, AlertCircle, RefreshCw } from "lucide-react";
import { MilestoneStatusBadge } from "@/components/ui/MilestoneStatusBadge";

export default function CorporateMilestoneDetailsPage() {
    const params = useParams();
    const router = useRouter();
    const milestoneId = params.milestoneId as string;

    const [milestone, setMilestone] = useState<Milestone | null>(null);
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    const fetchMilestone = async () => {
        setIsLoading(true);
        setError(null);
        try {
            const data = await milestoneService.getMilestone(milestoneId);
            setMilestone(data);
            if (data?.projectId || data?.dealId) {
                router.replace(`/corporate/projects/${data.projectId || data.dealId}/milestones/${data.id}`);
            }
        } catch (err: any) {
            setError(err?.response?.data?.message || err.message || "Failed to fetch milestone details");
        } finally {
            setIsLoading(false);
        }
    };

    useEffect(() => {
        if (milestoneId) {
            fetchMilestone();
        }
    }, [milestoneId]);

    return (
        <DashboardLayout>
            <div className="space-y-6 max-w-4xl">
                <Button variant="ghost" onClick={() => router.back()} className="gap-2 -ml-3 text-slate-500 hover:text-slate-900">
                    <ArrowLeft size={16} /> Back to Milestones
                </Button>

                {error ? (
                    <div className="p-6 text-center text-red-600 bg-red-50 rounded-lg flex flex-col items-center">
                        <AlertCircle className="mb-2" size={24} />
                        <p>{error}</p>
                        <Button variant="outline" className="mt-4" onClick={fetchMilestone}>Try Again</Button>
                    </div>
                ) : isLoading ? (
                    <div className="p-24 text-center text-slate-500 flex flex-col items-center">
                        <RefreshCw size={32} className="animate-spin text-blue-500 mb-4" />
                        <p>Loading milestone details...</p>
                    </div>
                ) : milestone ? (
                    <Card className="border border-slate-200">
                        <CardContent className="p-6 space-y-4">
                            <div className="flex justify-between items-start">
                                <div>
                                    <h2 className="text-xl font-bold text-slate-900">{milestone.title}</h2>
                                    <p className="text-sm text-slate-500 mt-1">{milestone.description}</p>
                                </div>
                                <MilestoneStatusBadge status={milestone.status} />
                            </div>
                            <div className="pt-4 border-t border-slate-100 flex gap-6 text-sm">
                                <div>
                                    <span className="text-slate-500">Amount:</span>{" "}
                                    <strong className="text-slate-900">₹{Number(milestone.amount).toLocaleString("en-IN")} {milestone.currency || "INR"}</strong>
                                </div>
                                {milestone.dueDate && (
                                    <div>
                                        <span className="text-slate-500">Due Date:</span>{" "}
                                        <strong className="text-slate-900">{new Date(milestone.dueDate).toLocaleDateString("en-IN")}</strong>
                                    </div>
                                )}
                            </div>
                        </CardContent>
                    </Card>
                ) : null}
            </div>
        </DashboardLayout>
    );
}
