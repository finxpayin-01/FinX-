"use client";

import { useEffect, useState } from "react";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card, CardContent } from "@/components/ui/Card";
import { dealService } from "@/services/deal.service";
import { Deal } from "@/types";
import { Briefcase, CheckCircle, Clock, Search, Filter } from "lucide-react";
import Link from "next/link";

export default function ProjectManagerProjectsPage() {
    const [deals, setDeals] = useState<Deal[]>([]);
    const [isLoading, setIsLoading] = useState(true);
    const [searchTerm, setSearchTerm] = useState("");
    const [statusFilter, setStatusFilter] = useState("ALL");

    useEffect(() => {
        dealService.getDeals()
            .then(setDeals)
            .catch(() => setDeals([]))
            .finally(() => setIsLoading(false));
    }, []);

    const filtered = deals.filter(deal => {
        const matchesSearch = deal.title.toLowerCase().includes(searchTerm.toLowerCase()) ||
            deal.id.toLowerCase().includes(searchTerm.toLowerCase());
        const matchesStatus = statusFilter === "ALL" || deal.status === statusFilter;
        return matchesSearch && matchesStatus;
    });

    const activeStatuses = ['ACCEPTED', 'MILESTONES_PENDING', 'AWAITING_FUNDING', 'FUNDED', 'IN_PROGRESS'];
    const activeCount = deals.filter(d => activeStatuses.includes(d.status)).length;
    const completedCount = deals.filter(d => d.status === "COMPLETED").length;

    return (
        <DashboardLayout>
            <div className="space-y-6">
                <div>
                    <h1 className="text-2xl font-bold text-slate-900">Project Operations Directory</h1>
                    <p className="text-sm text-slate-500 mt-1">Track milestone deliverables, schedule adherence, and escrow-backed deals.</p>
                </div>

                {/* Metrics */}
                <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                    <Card>
                        <CardContent className="p-6">
                            <div className="flex items-center justify-between">
                                <div>
                                    <p className="text-sm font-medium text-slate-500">Total Tracked Projects</p>
                                    <p className="text-3xl font-bold text-slate-900 mt-2">{deals.length}</p>
                                </div>
                                <div className="h-12 w-12 bg-blue-50 flex items-center justify-center rounded-full text-blue-600">
                                    <Briefcase size={24} />
                                </div>
                            </div>
                        </CardContent>
                    </Card>

                    <Card>
                        <CardContent className="p-6">
                            <div className="flex items-center justify-between">
                                <div>
                                    <p className="text-sm font-medium text-slate-500">Active Delivery Phase</p>
                                    <p className="text-3xl font-bold text-emerald-600 mt-2">{activeCount}</p>
                                </div>
                                <div className="h-12 w-12 bg-emerald-50 flex items-center justify-center rounded-full text-emerald-600">
                                    <Clock size={24} />
                                </div>
                            </div>
                        </CardContent>
                    </Card>

                    <Card>
                        <CardContent className="p-6">
                            <div className="flex items-center justify-between">
                                <div>
                                    <p className="text-sm font-medium text-slate-500">Completed & Closed</p>
                                    <p className="text-3xl font-bold text-blue-600 mt-2">{completedCount}</p>
                                </div>
                                <div className="h-12 w-12 bg-blue-50 flex items-center justify-center rounded-full text-blue-600">
                                    <CheckCircle size={24} />
                                </div>
                            </div>
                        </CardContent>
                    </Card>
                </div>

                {/* Filters */}
                <Card>
                    <CardContent className="p-4">
                        <div className="flex flex-col md:flex-row gap-4 justify-between">
                            <div className="relative flex-1 max-w-md">
                                <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 text-slate-400" size={18} />
                                <input
                                    type="text"
                                    placeholder="Search projects by title or deal ID..."
                                    value={searchTerm}
                                    onChange={(e) => setSearchTerm(e.target.value)}
                                    className="w-full pl-10 pr-4 py-2 border border-slate-200 rounded-lg text-sm bg-white text-slate-900 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-blue-500"
                                />
                            </div>

                            <div className="flex items-center gap-2">
                                <Filter size={18} className="text-slate-400" />
                                <select
                                    value={statusFilter}
                                    onChange={(e) => setStatusFilter(e.target.value)}
                                    className="border border-slate-200 rounded-lg px-3 py-2 text-sm text-slate-900 focus:outline-none focus:ring-2 focus:ring-blue-500 bg-white"
                                >
                                    <option value="ALL" className="text-slate-900 bg-white">All Statuses</option>
                                    <option value="PENDING_VENDOR_ACCEPTANCE" className="text-slate-900 bg-white">Pending Acceptance</option>
                                    <option value="ACCEPTED" className="text-slate-900 bg-white">Accepted</option>
                                    <option value="MILESTONES_PENDING" className="text-slate-900 bg-white">Milestones Pending</option>
                                    <option value="AWAITING_FUNDING" className="text-slate-900 bg-white">Awaiting Funding</option>
                                    <option value="FUNDED" className="text-slate-900 bg-white">Funded</option>
                                    <option value="IN_PROGRESS" className="text-slate-900 bg-white">In Progress</option>
                                    <option value="DRAFT" className="text-slate-900 bg-white">Draft</option>
                                    <option value="COMPLETED" className="text-slate-900 bg-white">Completed</option>
                                    <option value="CANCELLED" className="text-slate-900 bg-white">Cancelled</option>
                                    <option value="DISPUTED" className="text-slate-900 bg-white">Disputed</option>
                                </select>
                            </div>
                        </div>
                    </CardContent>
                </Card>

                {/* Projects Table */}
                <Card>
                    <div className="overflow-x-auto">
                        <table className="w-full text-left text-sm">
                            <thead className="bg-slate-50 border-b border-slate-200">
                                <tr>
                                    <th className="p-4 font-semibold text-slate-600">Project Title</th>
                                    <th className="p-4 font-semibold text-slate-600">Project ID</th>
                                    <th className="p-4 font-semibold text-slate-600">Budget</th>
                                    <th className="p-4 font-semibold text-slate-600">Status</th>
                                    <th className="p-4 font-semibold text-slate-600">Created</th>
                                    <th className="p-4 font-semibold text-slate-600 text-right">Action</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-100">
                                {isLoading && (
                                    <tr>
                                        <td colSpan={6} className="p-8 text-center text-slate-500">
                                            Loading project directory...
                                        </td>
                                    </tr>
                                )}
                                {!isLoading && filtered.length === 0 && (
                                    <tr>
                                        <td colSpan={6} className="p-8 text-center text-slate-500">
                                            No projects match your filter.
                                        </td>
                                    </tr>
                                )}
                                {!isLoading && filtered.map((d) => (
                                    <tr key={d.id} className="hover:bg-slate-50 transition-colors">
                                        <td className="p-4">
                                            <p className="font-semibold text-slate-900">{d.title}</p>
                                            {d.description && <p className="text-xs text-slate-500 truncate max-w-xs">{d.description}</p>}
                                        </td>
                                        <td className="p-4 font-mono text-xs text-slate-500">
                                            {d.id.slice(0, 8)}...
                                        </td>
                                        <td className="p-4 font-bold text-slate-900">
                                            {d.currency} {Number(d.totalAmount).toLocaleString()}
                                        </td>
                                        <td className="p-4">
                                            <span className={`inline-flex items-center px-2.5 py-0.5 rounded text-xs font-semibold ${activeStatuses.includes(d.status) ? "bg-emerald-50 text-emerald-700" :
                                                    d.status === "COMPLETED" ? "bg-blue-50 text-blue-700" :
                                                        d.status === "DISPUTED" ? "bg-red-50 text-red-700" :
                                                            "bg-slate-100 text-slate-700"
                                                }`}>
                                                {d.status}
                                            </span>
                                        </td>
                                        <td className="p-4 text-xs text-slate-500">
                                            {d.createdAt ? new Date(d.createdAt).toLocaleDateString() : "—"}
                                        </td>
                                        <td className="p-4 text-right">
                                            <Link
                                                href={`/corporate/projects/${d.id}`}
                                                className="text-xs text-blue-600 hover:text-blue-800 font-medium"
                                            >
                                                Details →
                                            </Link>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                </Card>
            </div>
        </DashboardLayout>
    );
}