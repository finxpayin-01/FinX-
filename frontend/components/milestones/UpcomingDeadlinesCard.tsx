"use client";

import { useEffect, useState } from "react";
import { Card, CardContent } from "@/components/ui/Card";
import { Button } from "@/components/ui/Button";
import { Milestone } from "@/types";
import { milestoneService } from "@/services/milestone.service";
import { Clock, AlertTriangle, ArrowRight, RefreshCw, Calendar, CheckCircle2 } from "lucide-react";
import Link from "next/link";

export function UpcomingDeadlinesCard() {
    const [milestones, setMilestones] = useState<Milestone[]>([]);
    const [isLoading, setIsLoading] = useState(true);

    const fetchDeadlines = () => {
        setIsLoading(true);
        milestoneService.getUpcomingMilestones()
            .then(setMilestones)
            .catch(() => setMilestones([]))
            .finally(() => setIsLoading(false));
    };

    useEffect(() => {
        fetchDeadlines();
    }, []);

    const formatDeadline = (isoString?: string) => {
        if (!isoString) return "No deadline set";
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

    const getDeadlineBadge = (milestone: Milestone) => {
        const text = milestone.timeUntilDeadline || (milestone.overdue ? "OVERDUE" : "Upcoming");
        const isOverdue = milestone.overdue || text.startsWith("OVERDUE");
        const isUrgent = text.includes("hour") || text.includes("minute") || text.includes("tomorrow");

        if (isOverdue) {
            return (
                <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-bold bg-rose-100 text-rose-800 border border-rose-300 animate-pulse">
                    <AlertTriangle size={13} className="text-rose-600" />
                    {text}
                </span>
            );
        }

        if (isUrgent) {
            return (
                <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-semibold bg-amber-100 text-amber-900 border border-amber-300">
                    <Clock size={13} className="text-amber-700" />
                    {text}
                </span>
            );
        }

        return (
            <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-medium bg-blue-50 text-blue-700 border border-blue-200">
                <Calendar size={13} className="text-blue-600" />
                {text}
            </span>
        );
    };

    return (
        <Card id="upcoming-deadlines-widget" className="border-slate-200 shadow-xs">
            <div className="p-6 border-b border-slate-100 flex items-center justify-between">
                <div className="flex items-center gap-3">
                    <div className="h-10 w-10 rounded-xl bg-blue-50 text-blue-600 flex items-center justify-center font-bold">
                        <Clock size={20} />
                    </div>
                    <div>
                        <h2 className="text-lg font-bold text-slate-900">Upcoming Submission Deadlines</h2>
                        <p className="text-xs text-slate-500 mt-0.5">
                            Milestones requiring deliverable submission, ordered by closest deadline.
                        </p>
                    </div>
                </div>
                <Button
                    variant="outline"
                    size="sm"
                    onClick={fetchDeadlines}
                    className="h-8 gap-1.5 text-xs text-slate-600 hover:text-slate-900"
                >
                    <RefreshCw size={13} className={isLoading ? "animate-spin" : ""} />
                    Refresh
                </Button>
            </div>

            <CardContent className="p-0">
                {isLoading ? (
                    <div className="p-8 text-center text-slate-400">
                        <RefreshCw size={24} className="animate-spin mx-auto text-blue-500 mb-2" />
                        <span className="text-sm">Loading deadlines...</span>
                    </div>
                ) : milestones.length === 0 ? (
                    <div className="p-8 text-center" id="empty-deadlines-msg">
                        <CheckCircle2 size={32} className="mx-auto text-emerald-500 mb-2" />
                        <h4 className="text-sm font-semibold text-slate-800">All submissions on track</h4>
                        <p className="text-xs text-slate-500 mt-1">No upcoming milestone submission deadlines pending.</p>
                    </div>
                ) : (
                    <div className="divide-y divide-slate-100" id="deadlines-list">
                        {milestones.map((m) => (
                            <div
                                key={m.id}
                                id={`upcoming-milestone-${m.id}`}
                                className="p-5 flex flex-col md:flex-row md:items-center justify-between gap-4 hover:bg-slate-50/70 transition-colors"
                            >
                                <div className="space-y-1.5 flex-1">
                                    <div className="flex flex-wrap items-center gap-2">
                                        <h3 className="font-bold text-slate-900 text-sm">{m.title}</h3>
                                        {getDeadlineBadge(m)}
                                    </div>
                                    <p className="text-xs text-slate-600">
                                        Project: <strong className="text-slate-800 font-semibold">{m.dealTitle || "Contract"}</strong>
                                    </p>
                                    <div className="flex flex-wrap items-center gap-3 text-xs text-slate-500 pt-0.5">
                                        <span>Deadline: <span className="font-mono text-slate-700">{formatDeadline(m.submissionDeadline || m.dueDate)}</span></span>
                                        <span>•</span>
                                        <span>Amount: <span className="font-semibold text-slate-700">₹{Number(m.amount).toLocaleString("en-IN")}</span></span>
                                    </div>
                                </div>
                                <div className="flex items-center gap-2 shrink-0">
                                    <Link href={m.dealId ? `/vendor/projects/${m.dealId}` : `/vendor/projects`}>
                                        <Button
                                            size="sm"
                                            className="gap-1.5 text-xs bg-blue-600 hover:bg-blue-700 text-white font-medium shadow-xs"
                                        >
                                            Submit Milestone <ArrowRight size={14} />
                                        </Button>
                                    </Link>
                                </div>
                            </div>
                        ))}
                    </div>
                )}
            </CardContent>
        </Card>
    );
}
