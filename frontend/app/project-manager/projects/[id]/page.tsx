"use client";

import { useEffect, useState } from "react";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card, CardContent } from "@/components/ui/Card";
import { api } from "@/lib/api";
import { MilestoneStatusBadge } from "@/components/ui/MilestoneStatusBadge";
import Link from "next/link";
import { Button } from "@/components/ui/Button";
import { ArrowRight, FileCheck2, ArrowLeft } from "lucide-react";

export default function ProjectManagerProjectDetail({ params }: { params: { id: string } }) {
    const [project, setProject] = useState<any>(null);
    const [milestones, setMilestones] = useState<any[]>([]);
    const [isLoading, setIsLoading] = useState(true);

    useEffect(() => {
        // Fetch project and milestones
        Promise.all([
            api.get(`/projects/${params.id}`).then(res => res.data.data || res.data).catch(() => null),
            api.get(`/projects/${params.id}/milestones`).then(res => res.data.data || res.data).catch(() => [])
        ]).then(([p, m]) => {
            setProject(p);
            setMilestones(m);
        }).finally(() => setIsLoading(false));
    }, [params.id]);

    if (isLoading) return <DashboardLayout><div className="p-8">Syncing project details...</div></DashboardLayout>;
    if (!project) return <DashboardLayout><div className="p-8 text-red-600">Project not found or access restricted to assigned PM.</div></DashboardLayout>;

    return (
        <DashboardLayout>
            <div className="space-y-6">
                <div>
                    <Link href="/project-manager/projects" className="text-sm text-blue-600 hover:underline mb-2 inline-flex items-center gap-1">
                        <ArrowLeft size={14} /> Back to Projects
                    </Link>
                    <h1 className="text-2xl font-bold text-slate-900">{project.title}</h1>
                    <p className="text-sm text-slate-500 mt-1 font-mono">ID: {project.projectId || params.id}</p>
                </div>

                <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                    <Card><CardContent className="p-5">
                        <p className="text-xs font-semibold text-slate-500 uppercase tracking-widest">Project Value</p>
                        <p className="text-2xl font-bold text-slate-900 mt-2">{project.currency} {Number(project.totalAmount || 0).toLocaleString()}</p>
                    </CardContent></Card>
                    <Card><CardContent className="p-5">
                        <p className="text-xs font-semibold text-slate-500 uppercase tracking-widest">Status</p>
                        <p className="text-2xl font-bold text-blue-600 mt-2">{project.status}</p>
                    </CardContent></Card>
                    <Card><CardContent className="p-5">
                        <p className="text-xs font-semibold text-slate-500 uppercase tracking-widest">Milestones</p>
                        <p className="text-2xl font-bold text-slate-900 mt-2">{milestones.length}</p>
                    </CardContent></Card>
                </div>

                <h2 className="text-lg font-semibold text-slate-900 mt-8 mb-4">Milestone Roadmaps</h2>
                <div className="space-y-4">
                    {milestones.length === 0 && <p className="text-sm text-slate-500">No milestones registered yet.</p>}
                    {milestones.map((m: any) => (
                        <Card key={m.id} className="p-5">
                            <div className="flex flex-col md:flex-row justify-between md:items-center gap-4">
                                <div>
                                    <div className="flex items-center gap-3 mb-1">
                                        <h3 className="font-semibold text-slate-900 text-lg">{m.title}</h3>
                                        <MilestoneStatusBadge status={m.status} />
                                    </div>
                                    <p className="text-sm text-slate-600 max-w-2xl">{m.description || 'No description provided.'}</p>
                                    <div className="flex gap-4 mt-3 text-xs text-slate-500 font-medium">
                                        <span>Amount: {project.currency} {Number(m.amount).toLocaleString()}</span>
                                        <span>•</span>
                                        <span>Due: {m.dueDate ? new Date(m.dueDate).toLocaleDateString() : 'N/A'}</span>
                                    </div>
                                </div>
                                <div className="shrink-0">
                                    {(m.status === 'UNDER_REVIEW' || m.status === 'SUBMITTED' || m.status === 'REJECTED') ? (
                                        <Link href={`/project-manager/reviews/${m.id}`}>
                                            <Button className="w-full sm:w-auto bg-slate-900 text-white hover:bg-slate-800 shadow">
                                                Inspect Submissions <ArrowRight size={16} className="ml-2" />
                                            </Button>
                                        </Link>
                                    ) : (m.status === 'APPROVED' || m.status === 'COMPLETED') ? (
                                        <Button disabled className="w-full sm:w-auto bg-emerald-50 text-emerald-700 border-none shadow-none font-medium gap-2 items-center flex">
                                            <FileCheck2 size={16} /> Approved & Closed
                                        </Button>
                                    ) : (
                                        <Button disabled variant="outline" className="w-full sm:w-auto bg-slate-50 text-slate-400">
                                            Awaiting Vendor Work
                                        </Button>
                                    )}
                                </div>
                            </div>
                        </Card>
                    ))}
                </div>
            </div>
        </DashboardLayout>
    );
}
