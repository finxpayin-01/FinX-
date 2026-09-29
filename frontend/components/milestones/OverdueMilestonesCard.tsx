"use client";

import { useEffect, useState } from "react";
import { Card, CardContent } from "@/components/ui/Card";
import { Button } from "@/components/ui/Button";
import { Milestone } from "@/types";
import { milestoneService } from "@/services/milestone.service";
import { AlertCircle, RefreshCw, ArrowRight, ShieldAlert, Building2 } from "lucide-react";
import Link from "next/link";
import { MilestoneStatusBadge } from "@/components/ui/MilestoneStatusBadge";

interface OverdueMilestonesCardProps {
    roleContext?: "BUYER" | "PROJECT_MANAGER";
    onUpdateDeadline?: (milestone: Milestone) => void;
}

export function OverdueMilestonesCard({ roleContext = "BUYER", onUpdateDeadline }: OverdueMilestonesCardProps) {
    const [overdueList, setOverdueList] = useState<Milestone[]>([]);
    const [isLoading, setIsLoading] = useState(true);

    const fetchOverdue = () => {
        setIsLoading(true);
        milestoneService.getOverdueMilestones()
            .then(setOverdueList)
            .catch(() => setOverdueList([]))
            .finally(() => setIsLoading(false));
    };

    useEffect(() => {
        fetchOverdue();
    }, []);

    const formatDeadline = (isoString?: string) => {
        if (!isoString) return "N/A";
        const date = new Date(isoString);
        return date.toLocaleString(undefined, {
            day: "numeric",
            month: "short",
            year: "numeric",
            hour: "2-digit",
            minute: "2-digit",
            timeZoneName: "short"
        });
    };

    if (!isLoading && overdueList.length === 0) {
        return null; // Keep dashboard clean if no overdue milestones
    }

    const baseRoute = roleContext === "PROJECT_MANAGER" ? "/project-manager" : "/corporate/projects";

    return (
        <Card id="overdue-milestones-widget" className="border-rose-200 bg-rose-50/20 shadow-xs">
            <div className="p-5 border-b border-rose-100 flex items-center justify-between">
                <div className="flex items-center gap-3">
                    <div className="h-10 w-10 rounded-xl bg-rose-100 text-rose-700 flex items-center justify-center font-bold">
                        <ShieldAlert size={20} />
                    </div>
                    <div>
                        <div className="flex items-center gap-2">
                            <h2 className="text-base font-bold text-rose-950">Overdue Deliverables Alert</h2>
                            <span className="bg-rose-600 text-white text-xs font-bold px-2 py-0.5 rounded-full">
                                {overdueList.length} Delayed
                            </span>
                        </div>
                        <p className="text-xs text-rose-800/80 mt-0.5">
                            Milestones where the vendor submission deadline has elapsed without deliverables.
                        </p>
                    </div>
                </div>
                <Button
                    variant="outline"
                    size="sm"
                    onClick={fetchOverdue}
                    className="h-8 gap-1.5 text-xs text-rose-800 border-rose-200 hover:bg-rose-100"
                >
                    <RefreshCw size={12} className={isLoading ? "animate-spin" : ""} />
                    Refresh
                </Button>
            </div>

            <CardContent className="p-0">
                <div className="divide-y divide-rose-100/70" id="overdue-milestones-list">
                    {overdueList.map((m) => (
                        <div
                            key={m.id}
                            id={`overdue-milestone-${m.id}`}
                            className="p-5 flex flex-col md:flex-row md:items-center justify-between gap-4 hover:bg-rose-50/60 transition-colors"
                        >
                            <div className="space-y-1.5 flex-1">
                                <div className="flex flex-wrap items-center gap-2">
                                    <h3 className="font-bold text-slate-900 text-sm">{m.title}</h3>
                                    <MilestoneStatusBadge status={m.status} />
                                    <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-bold bg-rose-200 text-rose-900">
                                        <AlertCircle size={12} />
                                        {m.timeUntilDeadline || "OVERDUE"}
                                    </span>
                                </div>
                                <div className="flex flex-wrap items-center gap-4 text-xs text-slate-600">
                                    <span>Project: <strong className="text-slate-800 font-semibold">{m.dealTitle || "Contract"}</strong></span>
                                    <span>•</span>
                                    <span className="flex items-center gap-1">
                                        <Building2 size={12} className="text-slate-400" />
                                        Vendor: <strong className="text-slate-800 font-semibold">{m.sellerName || "Assigned Vendor"}</strong>
                                    </span>
                                </div>
                                <div className="text-xs text-slate-500 pt-0.5">
                                    Deadline was: <span className="font-mono text-slate-700 font-medium">{formatDeadline(m.submissionDeadline || m.dueDate)}</span>
                                </div>
                            </div>

                            <div className="flex items-center gap-2 shrink-0">
                                {onUpdateDeadline && (
                                    <Button
                                        variant="outline"
                                        size="sm"
                                        onClick={() => onUpdateDeadline(m)}
                                        className="text-xs border-slate-300 text-slate-700 hover:bg-white"
                                    >
                                        Extend Deadline
                                    </Button>
                                )}
                                <Link href={m.dealId ? `${baseRoute}/${m.dealId}` : baseRoute}>
                                    <Button
                                        size="sm"
                                        className="gap-1.5 text-xs bg-rose-600 hover:bg-rose-700 text-white font-medium shadow-xs"
                                    >
                                        Inspect Milestone <ArrowRight size={14} />
                                    </Button>
                                </Link>
                            </div>
                        </div>
                    ))}
                </div>
            </CardContent>
        </Card>
    );
}
