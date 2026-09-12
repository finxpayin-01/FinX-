"use client";

import React, { useEffect, useState, useMemo, Suspense } from "react";
import { useSearchParams } from "next/navigation";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/Card";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { milestoneService } from "@/services/milestone.service";
import { dealService } from "@/services/deal.service";
import { Deal, Milestone, MilestoneStatus } from "@/types";
import {
    RefreshCw,
    Search,
    Plus,
    Eye,
    AlertCircle,
    CheckCircle2,
    Clock,
    DollarSign,
    Building2,
    Layers,
    AlertTriangle,
    Calendar,
    ArrowRight,
    X,
    CreditCard,
    TrendingUp,
    ShieldCheck
} from "lucide-react";
import Link from "next/link";
import { MilestoneStatusBadge } from "@/components/ui/MilestoneStatusBadge";

function CorporateMilestonesContent() {
    const searchParams = useSearchParams();
    const dealParam = searchParams.get("dealId");

    const [milestones, setMilestones] = useState<Milestone[]>([]);
    const [deals, setDeals] = useState<Deal[]>([]);
    const [selectedDealId, setSelectedDealId] = useState<string>(dealParam || "ALL");
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [successMessage, setSuccessMessage] = useState<string | null>(null);
    const [searchQuery, setSearchQuery] = useState("");
    const [statusFilter, setStatusFilter] = useState<MilestoneStatus | "ALL">("ALL");

    // Create Modal state
    const [isCreateModalOpen, setIsCreateModalOpen] = useState(false);
    const [isSubmitting, setIsSubmitting] = useState(false);
    const [formError, setFormError] = useState<string | null>(null);
    const [formDealId, setFormDealId] = useState<string>("");
    const [title, setTitle] = useState("");
    const [description, setDescription] = useState("");
    const [amount, setAmount] = useState("");
    const [dueDate, setDueDate] = useState("");

    // Fetch deals
    const fetchDeals = async () => {
        try {
            const data = await dealService.getDeals();
            setDeals(data);
            return data;
        } catch {
            return [];
        }
    };

    // Fetch milestones
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

    // Handle initial dealId param
    useEffect(() => {
        if (dealParam) {
            setSelectedDealId(dealParam);
        }
    }, [dealParam]);

    // Get selected deal details
    const selectedDeal = useMemo(() => {
        if (selectedDealId === "ALL") return null;
        return deals.find((d) => d.id === selectedDealId) || null;
    }, [deals, selectedDealId]);

    // Financial allocation calculations for selected deal
    const { totalValue, allocatedAmount, remainingAmount, percentAllocated } = useMemo(() => {
        if (!selectedDeal) {
            return { totalValue: 0, allocatedAmount: 0, remainingAmount: 0, percentAllocated: 0 };
        }

        const total = Number(selectedDeal.totalAmount || 0);
        // Milestones belonging to this deal
        const dealMilestones = milestones.filter(
            (m) => (m.dealId === selectedDeal.id || m.projectId === selectedDeal.id) && m.status !== "CANCELLED"
        );
        const allocated = dealMilestones.reduce((sum, m) => sum + Number(m.amount || 0), 0);
        const remaining = Math.max(0, total - allocated);
        const percent = total > 0 ? Math.min(100, Math.round((allocated / total) * 100)) : 0;

        return {
            totalValue: total,
            allocatedAmount: allocated,
            remainingAmount: remaining,
            percentAllocated: percent,
        };
    }, [selectedDeal, milestones]);

    // Compute remaining budget for modal target deal
    const modalTargetDeal = useMemo(() => {
        if (!formDealId) return null;
        return deals.find((d) => d.id === formDealId) || null;
    }, [deals, formDealId]);

    const modalRemainingBudget = useMemo(() => {
        if (!modalTargetDeal) return 0;
        const total = Number(modalTargetDeal.totalAmount || 0);
        const dealMilestones = milestones.filter(
            (m) => (m.dealId === modalTargetDeal.id || m.projectId === modalTargetDeal.id) && m.status !== "CANCELLED"
        );
        const allocated = dealMilestones.reduce((sum, m) => sum + Number(m.amount || 0), 0);
        return Math.max(0, total - allocated);
    }, [modalTargetDeal, milestones]);

    // Filter milestones
    const filteredMilestones = useMemo(() => {
        return milestones.filter((m) => {
            const matchesSearch =
                m.title.toLowerCase().includes(searchQuery.toLowerCase()) ||
                (m.description && m.description.toLowerCase().includes(searchQuery.toLowerCase()));
            const matchesStatus = statusFilter === "ALL" || m.status === statusFilter;
            return matchesSearch && matchesStatus;
        });
    }, [milestones, searchQuery, statusFilter]);

    // Open create milestone modal
    const handleOpenCreateModal = () => {
        setFormError(null);
        setTitle("");
        setDescription("");
        setAmount("");
        // Set default due date to 14 days from today
        const defaultDue = new Date();
        defaultDue.setDate(defaultDue.getDate() + 14);
        setDueDate(defaultDue.toISOString().split("T")[0]);

        if (selectedDealId !== "ALL") {
            setFormDealId(selectedDealId);
        } else if (deals.length > 0) {
            setFormDealId(deals[0].id);
        } else {
            setFormDealId("");
        }

        setIsCreateModalOpen(true);
    };

    // Submit new milestone
    const handleCreateMilestone = async (e: React.FormEvent) => {
        e.preventDefault();
        setFormError(null);

        if (!formDealId) {
            setFormError("Please select a project/deal.");
            return;
        }

        if (!title.trim()) {
            setFormError("Milestone title is required.");
            return;
        }

        const numAmount = parseFloat(amount);
        if (isNaN(numAmount) || numAmount <= 0) {
            setFormError("Amount must be a positive number greater than 0.");
            return;
        }

        if (numAmount > modalRemainingBudget) {
            setFormError(
                `Amount (₹${numAmount.toLocaleString("en-IN")}) exceeds remaining unallocated budget (₹${modalRemainingBudget.toLocaleString("en-IN")}).`
            );
            return;
        }

        if (!dueDate) {
            setFormError("Due date is required.");
            return;
        }

        setIsSubmitting(true);
        try {
            // ISO instant for backend
            const isoDueDate = new Date(`${dueDate}T23:59:59.000Z`).toISOString();

            await milestoneService.createMilestone(formDealId, {
                title: title.trim(),
                description: description.trim() || undefined,
                amount: numAmount,
                currency: "INR",
                dueDate: isoDueDate,
            });

            setSuccessMessage(`Milestone "${title.trim()}" created successfully!`);
            setTimeout(() => setSuccessMessage(null), 5000);

            setIsCreateModalOpen(false);

            // If user created for a specific deal, select that deal
            if (selectedDealId === "ALL") {
                setSelectedDealId(formDealId);
            } else {
                fetchMilestones();
            }
        } catch (err: any) {
            setFormError(err?.response?.data?.message || err.message || "Failed to create milestone");
        } finally {
            setIsSubmitting(false);
        }
    };

    const isFullyAllocated = selectedDeal !== null && remainingAmount <= 0;

    return (
        <DashboardLayout>
            <div className="space-y-6">
                {/* Header */}
                <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4">
                    <div>
                        <h1 className="text-2xl font-bold text-slate-900 tracking-tight">Milestones</h1>
                        <p className="text-sm text-slate-500 mt-1">
                            Track project milestones, inspect deliverables, approve releases, and manage escrow funding stages.
                        </p>
                    </div>
                    <Button
                        onClick={handleOpenCreateModal}
                        disabled={isFullyAllocated}
                        title={
                            isFullyAllocated
                                ? `All funds for ${selectedDeal?.title} (₹${totalValue.toLocaleString("en-IN")}) are already allocated.`
                                : "Create a new milestone for this project"
                        }
                        className={`gap-2 font-medium shadow-sm transition-all ${
                            isFullyAllocated
                                ? "bg-slate-200 text-slate-500 cursor-not-allowed hover:bg-slate-200"
                                : "bg-blue-600 hover:bg-blue-700 text-white"
                        }`}
                    >
                        <Plus size={16} /> Create Milestone
                    </Button>
                </div>

                {/* Success alert */}
                {successMessage && (
                    <div className="p-4 rounded-xl bg-emerald-50 border border-emerald-200 text-emerald-800 flex items-center justify-between animate-fadeIn">
                        <div className="flex items-center gap-2">
                            <CheckCircle2 size={18} className="text-emerald-600 shrink-0" />
                            <span className="font-medium text-sm">{successMessage}</span>
                        </div>
                        <button
                            onClick={() => setSuccessMessage(null)}
                            className="text-emerald-600 hover:text-emerald-800 text-xs font-semibold"
                        >
                            Dismiss
                        </button>
                    </div>
                )}

                {/* Selected Deal Status & Financial Overview */}
                {selectedDeal && (
                    <div className="space-y-4">
                        {/* Vendor Acceptance Notice */}
                        {(selectedDeal.status === "DRAFT" || selectedDeal.status === "PENDING_ACCEPTANCE") && (
                            <div className="p-4 rounded-xl bg-amber-50/90 border border-amber-200/80 flex items-start gap-3 shadow-xs">
                                <AlertTriangle className="h-5 w-5 text-amber-600 mt-0.5 shrink-0" />
                                <div>
                                    <h4 className="text-sm font-semibold text-amber-900">
                                        Waiting for Vendor to accept this project
                                    </h4>
                                    <p className="text-xs text-amber-700 mt-1 leading-relaxed">
                                        This project is currently in draft awaiting acceptance from the assigned vendor. You can create milestones and define deliverables right now. The vendor will be able to review these milestones immediately upon accepting the project.
                                    </p>
                                </div>
                            </div>
                        )}

                        {/* Financial Allocation Cards */}
                        <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
                            <Card className="border border-slate-200/80 shadow-xs bg-white">
                                <CardContent className="p-5 flex items-center gap-4">
                                    <div className="p-3 bg-blue-50 text-blue-600 rounded-xl">
                                        <Building2 size={22} />
                                    </div>
                                    <div>
                                        <p className="text-xs font-medium text-slate-500 uppercase tracking-wider">Project Value</p>
                                        <p className="text-xl font-bold text-slate-900 mt-0.5">
                                            ₹{totalValue.toLocaleString("en-IN")}{" "}
                                            <span className="text-xs font-normal text-slate-400">INR</span>
                                        </p>
                                    </div>
                                </CardContent>
                            </Card>

                            <Card className="border border-slate-200/80 shadow-xs bg-white">
                                <CardContent className="p-5 flex items-center gap-4">
                                    <div className="p-3 bg-indigo-50 text-indigo-600 rounded-xl">
                                        <Layers size={22} />
                                    </div>
                                    <div className="flex-1">
                                        <div className="flex justify-between items-center">
                                            <p className="text-xs font-medium text-slate-500 uppercase tracking-wider">Allocated</p>
                                            <span className="text-xs font-semibold text-indigo-600">{percentAllocated}%</span>
                                        </div>
                                        <p className="text-xl font-bold text-slate-900 mt-0.5">
                                            ₹{allocatedAmount.toLocaleString("en-IN")}{" "}
                                            <span className="text-xs font-normal text-slate-400">INR</span>
                                        </p>
                                    </div>
                                </CardContent>
                            </Card>

                            <Card className={`border shadow-xs bg-white ${remainingAmount === 0 ? "border-amber-200" : "border-slate-200/80"}`}>
                                <CardContent className="p-5 flex items-center gap-4">
                                    <div className={`p-3 rounded-xl ${remainingAmount === 0 ? "bg-amber-50 text-amber-600" : "bg-emerald-50 text-emerald-600"}`}>
                                        <CreditCard size={22} />
                                    </div>
                                    <div>
                                        <p className="text-xs font-medium text-slate-500 uppercase tracking-wider">Remaining</p>
                                        <p className={`text-xl font-bold mt-0.5 ${remainingAmount === 0 ? "text-amber-700" : "text-emerald-700"}`}>
                                            ₹{remainingAmount.toLocaleString("en-IN")}{" "}
                                            <span className="text-xs font-normal text-slate-400">INR</span>
                                        </p>
                                    </div>
                                </CardContent>
                            </Card>
                        </div>

                        {/* Allocation Progress Bar */}
                        <div className="bg-white p-3 rounded-xl border border-slate-200/80 shadow-xs space-y-2">
                            <div className="flex justify-between items-center text-xs font-medium text-slate-600">
                                <span>Budget Allocation Progress</span>
                                <span>
                                    ₹{allocatedAmount.toLocaleString("en-IN")} of ₹{totalValue.toLocaleString("en-IN")} (
                                    {percentAllocated}%)
                                </span>
                            </div>
                            <div className="w-full bg-slate-100 rounded-full h-2.5 overflow-hidden">
                                <div
                                    className={`h-2.5 rounded-full transition-all duration-500 ${
                                        percentAllocated >= 100
                                            ? "bg-amber-500"
                                            : percentAllocated > 75
                                            ? "bg-blue-600"
                                            : "bg-indigo-600"
                                    }`}
                                    style={{ width: `${percentAllocated}%` }}
                                />
                            </div>
                        </div>
                    </div>
                )}

                {/* Filters and List */}
                <Card className="border border-slate-200 shadow-xs">
                    <CardHeader className="flex flex-col sm:flex-row gap-4 justify-between pb-4 border-b border-slate-100">
                        <div className="flex flex-1 flex-wrap gap-4 items-center">
                            {/* Search */}
                            <div className="relative max-w-xs w-full">
                                <Search className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" size={16} />
                                <input
                                    className="pl-9 h-10 w-full rounded-md border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                                    placeholder="Search milestones..."
                                    value={searchQuery}
                                    onChange={(e) => setSearchQuery(e.target.value)}
                                />
                            </div>

                            {/* Deal selector */}
                            <select
                                className="h-10 rounded-md border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                                value={selectedDealId}
                                onChange={(e) => setSelectedDealId(e.target.value)}
                            >
                                <option value="ALL">All Deals / Projects</option>
                                {deals.map((d) => (
                                    <option key={d.id} value={d.id}>
                                        {d.title} ({d.status})
                                    </option>
                                ))}
                            </select>

                            {/* Status filter */}
                            <select
                                className="h-10 rounded-md border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                                value={statusFilter}
                                onChange={(e) => setStatusFilter(e.target.value as any)}
                            >
                                <option value="ALL">All Statuses</option>
                                <option value="PENDING">Pending Funding</option>
                                <option value="IN_PROGRESS">In Progress</option>
                                <option value="SUBMITTED">Deliverables Submitted</option>
                                <option value="UNDER_REVIEW">Under Review</option>
                                <option value="APPROVED">Approved</option>
                                <option value="REJECTED">Rejected</option>
                                <option value="RELEASE_PENDING">Release Pending</option>
                                <option value="RELEASED">Released</option>
                                <option value="COMPLETED">Completed</option>
                                <option value="CANCELLED">Cancelled</option>
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
                        {/* Error state */}
                        {error && (
                            <div className="p-8 text-center text-red-600 flex flex-col items-center">
                                <AlertCircle className="mb-2" size={28} />
                                <p className="font-medium">{error}</p>
                                <Button variant="outline" className="mt-4" onClick={fetchMilestones}>
                                    Try Again
                                </Button>
                            </div>
                        )}

                        {/* Loading state */}
                        {!error && isLoading && (
                            <div className="p-16 text-center text-slate-500 flex flex-col items-center">
                                <RefreshCw size={28} className="animate-spin text-blue-500 mb-3" />
                                <p className="text-sm font-medium">Loading project milestones...</p>
                            </div>
                        )}

                        {/* Empty state */}
                        {!error && !isLoading && filteredMilestones.length === 0 && (
                            <div className="p-16 text-center text-slate-500 flex flex-col items-center">
                                <div className="h-16 w-16 bg-slate-100 text-slate-400 rounded-full flex items-center justify-center mb-4">
                                    <Layers size={28} />
                                </div>
                                <h3 className="text-base font-semibold text-slate-900">No milestones found</h3>
                                <p className="text-sm text-slate-500 mt-1 max-w-sm">
                                    {selectedDeal
                                        ? `No milestones exist for "${selectedDeal.title}". Click "+ Create Milestone" to set up your deliverables.`
                                        : "No milestones match your current filters. Select a project or create a milestone to begin."}
                                </p>
                                <Button
                                    onClick={handleOpenCreateModal}
                                    disabled={isFullyAllocated}
                                    className="mt-5 gap-2 bg-blue-600 hover:bg-blue-700 text-white font-medium"
                                >
                                    <Plus size={16} /> Create Milestone
                                </Button>
                            </div>
                        )}

                        {/* Milestones Data Table */}
                        {!error && !isLoading && filteredMilestones.length > 0 && (
                            <div className="overflow-x-auto">
                                <table className="w-full text-left text-sm text-slate-600 border-collapse">
                                    <thead className="bg-slate-50/80 text-xs uppercase font-semibold text-slate-500 border-b border-slate-100">
                                        <tr>
                                            <th className="py-3.5 px-4 w-12 text-center">#</th>
                                            <th className="py-3.5 px-4">Milestone & Deliverables</th>
                                            <th className="py-3.5 px-4">Project</th>
                                            <th className="py-3.5 px-4 text-right">Amount</th>
                                            <th className="py-3.5 px-4">Due Date</th>
                                            <th className="py-3.5 px-4">Status</th>
                                            <th className="py-3.5 px-4 text-right">Actions</th>
                                        </tr>
                                    </thead>
                                    <tbody className="divide-y divide-slate-100 font-normal">
                                        {filteredMilestones.map((m, idx) => {
                                            const project = deals.find((d) => d.id === (m.dealId || m.projectId));
                                            const projectTitle = project?.title || m.projectId || "Project";
                                            const detailUrl = `/corporate/projects/${m.projectId || m.dealId}/milestones/${m.id}`;
                                            const paymentUrl = `/corporate/projects/${m.projectId || m.dealId}/milestones/${m.id}/payment`;

                                            return (
                                                <tr key={m.id} className="hover:bg-slate-50/80 transition-colors">
                                                    <td className="py-4 px-4 text-center font-mono text-xs font-semibold text-slate-400">
                                                        {m.sequence || idx + 1}
                                                    </td>
                                                    <td className="py-4 px-4">
                                                        <Link href={detailUrl} className="font-semibold text-slate-900 hover:text-blue-600 transition-colors">
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
                                                        <div className="flex items-center justify-end gap-2">
                                                            {m.status === "PENDING" && (
                                                                <Link href={paymentUrl}>
                                                                    <Button size="sm" className="gap-1.5 bg-emerald-600 hover:bg-emerald-700 text-white text-xs h-8">
                                                                        <CreditCard size={13} /> Fund
                                                                    </Button>
                                                                </Link>
                                                            )}
                                                            <Link href={detailUrl}>
                                                                <Button variant="outline" size="sm" className="gap-1 text-slate-700 border-slate-200 hover:bg-slate-100 text-xs h-8">
                                                                    <Eye size={13} /> View
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

            {/* Create Milestone Modal */}
            {isCreateModalOpen && (
                <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/60 backdrop-blur-xs animate-fadeIn">
                    <div className="bg-white rounded-2xl max-w-xl w-full p-6 shadow-2xl border border-slate-100 max-h-[90vh] overflow-y-auto">
                        <div className="flex justify-between items-center pb-4 border-b border-slate-100">
                            <div>
                                <h3 className="text-lg font-bold text-slate-900">Create New Milestone</h3>
                                <p className="text-xs text-slate-500 mt-0.5">
                                    Define requirements, completion date, and allocated INR funds.
                                </p>
                            </div>
                            <button
                                onClick={() => setIsCreateModalOpen(false)}
                                className="text-slate-400 hover:text-slate-600 p-1.5 rounded-lg hover:bg-slate-100 transition-colors"
                            >
                                <X size={20} />
                            </button>
                        </div>

                        <form onSubmit={handleCreateMilestone} className="space-y-4 pt-4">
                            {formError && (
                                <div className="p-3 bg-red-50 border border-red-200 text-red-700 rounded-xl text-xs flex items-start gap-2">
                                    <AlertCircle size={16} className="shrink-0 mt-0.5" />
                                    <span>{formError}</span>
                                </div>
                            )}

                            {/* Target Deal Selection */}
                            <div>
                                <label className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5">
                                    Project / Deal <span className="text-red-500">*</span>
                                </label>
                                <select
                                    className="w-full h-10 rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 focus:outline-none focus:ring-2 focus:ring-blue-500"
                                    value={formDealId}
                                    onChange={(e) => setFormDealId(e.target.value)}
                                    required
                                >
                                    <option value="" disabled>
                                        Select target project
                                    </option>
                                    {deals.map((d) => (
                                        <option key={d.id} value={d.id}>
                                            {d.title} (Value: ₹{Number(d.totalAmount).toLocaleString("en-IN")})
                                        </option>
                                    ))}
                                </select>
                                {modalTargetDeal && (
                                    <div className="mt-1.5 flex items-center justify-between text-xs text-slate-500">
                                        <span>Total Deal Value: ₹{Number(modalTargetDeal.totalAmount).toLocaleString("en-IN")}</span>
                                        <span className={`font-semibold ${modalRemainingBudget > 0 ? "text-emerald-600" : "text-amber-600"}`}>
                                            Available to allocate: ₹{modalRemainingBudget.toLocaleString("en-IN")}
                                        </span>
                                    </div>
                                )}
                            </div>

                            {/* Milestone Title */}
                            <div>
                                <label className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5">
                                    Milestone Title <span className="text-red-500">*</span>
                                </label>
                                <Input
                                    placeholder="e.g. Civil Foundation & Structural Signoff"
                                    value={title}
                                    onChange={(e) => setTitle(e.target.value)}
                                    required
                                    className="h-10"
                                />
                            </div>

                            {/* Description / Deliverables */}
                            <div>
                                <label className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5">
                                    Deliverables & Requirements <span className="text-red-500">*</span>
                                </label>
                                <textarea
                                    className="w-full rounded-lg border border-slate-200 p-3 text-sm text-slate-900 focus:outline-none focus:ring-2 focus:ring-blue-500 min-h-[90px]"
                                    placeholder="Describe specific work items, documents, inspection criteria, or technical deliverables expected for milestone approval..."
                                    value={description}
                                    onChange={(e) => setDescription(e.target.value)}
                                    required
                                />
                            </div>

                            {/* Amount & Due Date */}
                            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                                <div>
                                    <label className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5">
                                        Milestone Amount (INR) <span className="text-red-500">*</span>
                                    </label>
                                    <div className="relative">
                                        <span className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400 text-sm font-semibold">
                                            ₹
                                        </span>
                                        <input
                                            type="number"
                                            step="0.01"
                                            min="1"
                                            max={modalRemainingBudget > 0 ? modalRemainingBudget : undefined}
                                            placeholder="e.g. 20000"
                                            className="w-full pl-8 h-10 rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 focus:outline-none focus:ring-2 focus:ring-blue-500"
                                            value={amount}
                                            onChange={(e) => setAmount(e.target.value)}
                                            required
                                        />
                                    </div>
                                    {parseFloat(amount) > modalRemainingBudget && (
                                        <p className="text-xs text-red-600 mt-1 font-medium">
                                            Exceeds available balance (₹{modalRemainingBudget.toLocaleString("en-IN")})
                                        </p>
                                    )}
                                </div>

                                <div>
                                    <label className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5">
                                        Due Date <span className="text-red-500">*</span>
                                    </label>
                                    <input
                                        type="date"
                                        min={new Date().toISOString().split("T")[0]}
                                        className="w-full h-10 rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 focus:outline-none focus:ring-2 focus:ring-blue-500"
                                        value={dueDate}
                                        onChange={(e) => setDueDate(e.target.value)}
                                        required
                                    />
                                </div>
                            </div>

                            {/* Modal Actions */}
                            <div className="flex justify-end gap-3 pt-4 border-t border-slate-100">
                                <Button
                                    type="button"
                                    variant="outline"
                                    onClick={() => setIsCreateModalOpen(false)}
                                    disabled={isSubmitting}
                                >
                                    Cancel
                                </Button>
                                <Button
                                    type="submit"
                                    disabled={
                                        isSubmitting ||
                                        !formDealId ||
                                        !title.trim() ||
                                        !description.trim() ||
                                        parseFloat(amount) <= 0 ||
                                        parseFloat(amount) > modalRemainingBudget ||
                                        !dueDate
                                    }
                                    className="gap-2 bg-blue-600 hover:bg-blue-700 text-white font-medium"
                                >
                                    {isSubmitting && <RefreshCw size={14} className="animate-spin" />}
                                    Create Milestone
                                </Button>
                            </div>
                        </form>
                    </div>
                </div>
            )}
        </DashboardLayout>
    );
}

export default function CorporateMilestonesPage() {
    return (
        <Suspense fallback={<div className="p-8 text-center text-slate-500">Loading milestones...</div>}>
            <CorporateMilestonesContent />
        </Suspense>
    );
}