"use client";

import React, { useEffect, useState, useMemo, Suspense } from "react";
import { useSearchParams } from "next/navigation";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card, CardContent, CardHeader } from "@/components/ui/Card";
import { Button } from "@/components/ui/Button";
import { milestoneService } from "@/services/milestone.service";
import { dealService } from "@/services/deal.service";
import { Deal, Milestone, MilestoneStatus } from "@/types";
import {
    RefreshCw,
    Search,
    AlertCircle,
    Calendar,
    Building2,
    Layers,
    ArrowRight,
    Briefcase,
    UploadCloud,
    CheckCircle2
} from "lucide-react";
import Link from "next/link";
import { MilestoneStatusBadge } from "@/components/ui/MilestoneStatusBadge";

function VendorMilestonesContent() {
    const searchParams = useSearchParams();
    const dealParam = searchParams.get("dealId");

    const [milestones, setMilestones] = useState<Milestone[]>([]);
    const [deals, setDeals] = useState<Deal[]>([]);
    const [selectedDealId, setSelectedDealId] = useState<string>(dealParam || "ALL");
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [searchQuery, setSearchQuery] = useState("");
    const [statusFilter, setStatusFilter] = useState<MilestoneStatus | "ALL">("ALL");

    const fetchDeals = async () => {
        try {
            const data = await dealService.getDeals();
            setDeals(data);
        } catch {
            // ignore
        }
    };

    const fetchMilestones = async () => {
        setIsLoading(true);
        setError(null);
        try {
            let data: Milestone[] = [];
            if (selectedDealId === "ALL") {
                data = await milestoneService.getAllMilestones();
            } else {
                data = await milestoneService.getProjectMilestones(selectedDealId);
            }
            setMilestones(data);
        } catch (err: any) {
            setError(err?.response?.data?.message || err.message || "Failed to fetch milestones");
        } finally {
            setIsLoading(false);
        }
    };

    useEffect(() => {
        fetchDeals();
    }, []);

    useEffect(() => {
        fetchMilestones();
    }, [selectedDealId]);

    useEffect(() => {
        if (dealParam) {
            setSelectedDealId(dealParam);
        }
    }, [dealParam]);

    const filteredMilestones = useMemo(() => {
        return milestones.filter((m) => {
            const matchesSearch =
                m.title.toLowerCase().includes(searchQuery.toLowerCase()) ||
                (m.description && m.description.toLowerCase().includes(searchQuery.toLowerCase()));
            const matchesStatus = statusFilter === "ALL" || m.status === statusFilter;
            return matchesSearch && matchesStatus;
        });
    }, [milestones, searchQuery, statusFilter]);

    const selectedDeal = useMemo(() => {
        if (selectedDealId === "ALL") return null;
        return deals.find((d) => d.id === selectedDealId) || null;
    }, [deals, selectedDealId]);

    return (
        <DashboardLayout>
            <div className="space-y-6">
                <div>
                    <h1 className="text-2xl font-bold text-slate-900 tracking-tight">My Milestones</h1>
                    <p className="text-sm text-slate-500 mt-1">
                        Track your assigned milestone deliverables, submit work items, and monitor approval status.
                    </p>
                </div>

                <Card className="border border-slate-200 shadow-xs">
                    <CardHeader className="flex flex-col sm:flex-row gap-4 justify-between pb-4 border-b border-slate-100">
                        <div className="flex flex-1 flex-wrap gap-4 items-center">
                            <div className="relative max-w-xs w-full">
                                <Search className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" size={16} />
                                <input
                                    className="pl-9 h-10 w-full rounded-md border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                                    placeholder="Search milestones..."
                                    value={searchQuery}
                                    onChange={(e) => setSearchQuery(e.target.value)}
                                />
                            </div>

                            <select
                                className="h-10 rounded-md border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                                value={selectedDealId}
                                onChange={(e) => setSelectedDealId(e.target.value)}
                            >
                                <option value="ALL">All Assigned Deals</option>
                                {deals.map((d) => (
                                    <option key={d.id} value={d.id}>
                                        {d.title} ({d.status})
                                    </option>
                                ))}
                            </select>

                            <select
                                className="h-10 rounded-md border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                                value={statusFilter}
                                onChange={(e) => setStatusFilter(e.target.value as any)}
                            >
                                <option value="ALL">All Statuses</option>
                                <option value="PENDING">Pending</option>
                                <option value="IN_PROGRESS">In Progress</option>
                                <option value="SUBMITTED">Submitted</option>
                                <option value="UNDER_REVIEW">Under Review</option>
                                <option value="APPROVED">Approved</option>
                                <option value="REJECTED">Rejected</option>
                                <option value="RELEASE_PENDING">Release Pending</option>
                                <option value="RELEASED">Released</option>
                                <option value="COMPLETED">Completed</option>
                            </select>
                        </div>

                        <Button
                            variant="outline"
                            size="sm"
                            onClick={fetchMilestones}
                            disabled={isLoading}
                            className="gap-2 text-slate-600 hover:text-slate-900 border-slate-200"
                        >
                            <RefreshCw size={14} className={isLoading ? "animate-spin" : ""} /> Refresh
                        </Button>
                    </CardHeader>

                    <CardContent className="p-0">
                        {error && (
                            <div className="p-8 text-center text-red-600 flex flex-col items-center">
                                <AlertCircle className="mb-2" size={28} />
                                <p className="font-medium">{error}</p>
                                <Button variant="outline" className="mt-4" onClick={fetchMilestones}>
                                    Try Again
                                </Button>
                            </div>
                        )}

                        {!error && isLoading && (
                            <div className="p-16 text-center text-slate-500 flex flex-col items-center">
                                <RefreshCw size={28} className="animate-spin text-blue-500 mb-3" />
                                <p className="text-sm font-medium">Loading your milestones...</p>
                            </div>
                        )}

                        {!error && !isLoading && filteredMilestones.length === 0 && (
                            <div className="p-16 text-center text-slate-500 flex flex-col items-center">
                                <div className="h-16 w-16 bg-slate-100 text-slate-400 rounded-full flex items-center justify-center mb-4">
                                    <Layers size={28} />
                                </div>
                                <h3 className="text-base font-semibold text-slate-900">No milestones found</h3>
                                <p className="text-sm text-slate-500 mt-1 max-w-sm">
                                    {selectedDeal
                                        ? `No milestones have been defined yet for "${selectedDeal.title}". The buyer will add deliverables.`
                                        : "No milestones match your current filters. Milestones created by buyers on your accepted projects will appear here."}
                                </p>
                            </div>
                        )}

                        {!error && !isLoading && filteredMilestones.length > 0 && (
                            <div className="overflow-x-auto">
                                <table className="w-full text-left text-sm text-slate-600 border-collapse">
                                    <thead className="bg-slate-50/80 text-xs uppercase font-semibold text-slate-500 border-b border-slate-100">
                                        <tr>
                                            <th className="py-3.5 px-4 w-12 text-center">#</th>
                                            <th className="py-3.5 px-4">Milestone & Scope</th>
                                            <th className="py-3.5 px-4">Project</th>
                                            <th className="py-3.5 px-4 text-right">Amount</th>
                                            <th className="py-3.5 px-4">Due Date</th>
                                            <th className="py-3.5 px-4">Status</th>
                                            <th className="py-3.5 px-4 text-right">Actions</th>
                                        </tr>
                                    </thead>
                                    <tbody className="divide-y divide-slate-100">
                                        {filteredMilestones.map((m, idx) => {
                                            const project = deals.find((d) => d.id === (m.dealId || m.projectId));
                                            const projectTitle = project?.title || m.projectId || "Project";
                                            const workspaceUrl = `/vendor/projects/${m.projectId || m.dealId}/milestones/${m.id}`;

                                            return (
                                                <tr key={m.id} className="hover:bg-slate-50/80 transition-colors">
                                                    <td className="py-4 px-4 text-center font-mono text-xs font-semibold text-slate-400">
                                                        {m.sequence || idx + 1}
                                                    </td>
                                                    <td className="py-4 px-4">
                                                        <Link href={workspaceUrl} className="font-semibold text-slate-900 hover:text-blue-600 transition-colors">
                                                            {m.title}
                                                        </Link>
                                                        {m.description && (
                                                            <p className="text-xs text-slate-500 line-clamp-1 mt-0.5">
                                                                {m.description}
                                                            </p>
                                                        )}
                                                    </td>
                                                    <td className="py-4 px-4">
                                                        <span className="inline-flex items-center gap-1.5 text-xs text-slate-700 bg-slate-100 px-2.5 py-1 rounded-md font-medium">
                                                            <Building2 size={12} className="text-slate-400" />
                                                            {projectTitle}
                                                        </span>
                                                    </td>
                                                    <td className="py-4 px-4 text-right font-semibold text-slate-900">
                                                        ₹{Number(m.amount).toLocaleString("en-IN")}{" "}
                                                        <span className="text-xs font-normal text-slate-400">
                                                            {m.currency || "INR"}
                                                        </span>
                                                    </td>
                                                    <td className="py-4 px-4 text-slate-500 whitespace-nowrap">
                                                        {m.dueDate ? (
                                                            <div className="flex items-center gap-1.5 text-xs">
                                                                <Calendar size={13} className="text-slate-400" />
                                                                {new Date(m.dueDate).toLocaleDateString("en-IN", {
                                                                    day: "numeric",
                                                                    month: "short",
                                                                    year: "numeric",
                                                                })}
                                                            </div>
                                                        ) : (
                                                            "—"
                                                        )}
                                                    </td>
                                                    <td className="py-4 px-4">
                                                        <MilestoneStatusBadge status={m.status} />
                                                    </td>
                                                    <td className="py-4 px-4 text-right whitespace-nowrap">
                                                        <Link href={workspaceUrl}>
                                                            <Button size="sm" className="gap-1.5 bg-blue-600 hover:bg-blue-700 text-white text-xs h-8">
                                                                <UploadCloud size={13} /> Deliverables & Workspace
                                                            </Button>
                                                        </Link>
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
        </DashboardLayout>
    );
}

export default function VendorMilestonesPage() {
    return (
        <Suspense fallback={<div className="p-8 text-center text-slate-500">Loading your milestones...</div>}>
            <VendorMilestonesContent />
        </Suspense>
    );
}