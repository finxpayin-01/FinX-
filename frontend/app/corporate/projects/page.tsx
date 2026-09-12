"use client";

import React, { useEffect, useState } from "react";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/Card";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { dealService, CreateDealRequest } from "@/services/deal.service";
import { Deal, DealStatus, SellerSummary } from "@/types";
import { RefreshCw, Search, Plus, Eye, AlertCircle } from "lucide-react";
import Link from "next/link";
import { useAuth } from "@/components/auth/AuthProvider";

export default function CorporateProjectsPage() {
    const { user } = useAuth();
    const [deals, setDeals] = useState<Deal[]>([]);
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [searchQuery, setSearchQuery] = useState("");
    const [statusFilter, setStatusFilter] = useState<DealStatus | "ALL">("ALL");
    const [isCreateModalOpen, setIsCreateModalOpen] = useState(false);
    const [isCreating, setIsCreating] = useState(false);

    // Form state
    const [title, setTitle] = useState("");
    const [description, setDescription] = useState("");
    const [amount, setAmount] = useState("");
    const [currency, setCurrency] = useState("INR");
    const [sellerId, setSellerId] = useState("");
    const [sellers, setSellers] = useState<SellerSummary[]>([]);
    const [isLoadingSellers, setIsLoadingSellers] = useState(false);
    const [isManualSeller, setIsManualSeller] = useState(false);
    const [vendorEmail, setVendorEmail] = useState("");
    const [isSendingEmail, setIsSendingEmail] = useState(false);
    const [emailSuccess, setEmailSuccess] = useState(false);

    const fetchDeals = async () => {
        setIsLoading(true);
        setError(null);
        try {
            const data = await dealService.getDeals();
            setDeals(data);
        } catch (err: any) {
            setError(err?.response?.data?.message || err.message || "Failed to fetch deals");
        } finally {
            setIsLoading(false);
        }
    };

    const fetchSellers = async () => {
        setIsLoadingSellers(true);
        try {
            const data = await dealService.getSellers();
            setSellers(data);
            if (data.length > 0 && !sellerId) {
                setSellerId(data[0].id);
            }
        } catch (e) {
            setIsManualSeller(true);
        } finally {
            setIsLoadingSellers(false);
        }
    };

    useEffect(() => {
        fetchDeals();
        fetchSellers();
    }, []);

    const handleCreateSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        setIsCreating(true);
        setError(null);
        try {
            const request: CreateDealRequest & { vendorEmail?: string } = {
                title,
                description,
                totalAmount: parseFloat(amount),
                currency,
                sellerId: (sellerId || "").trim(),
                vendorEmail: (vendorEmail || "").trim() || undefined
            };
            await dealService.createDeal(request);
            setIsCreateModalOpen(false);

            // Show a mock email successfully queued message natively
            if (vendorEmail) {
                setEmailSuccess(true);
                setTimeout(() => setEmailSuccess(false), 5000);
            }

            // Reset form
            setTitle("");
            setDescription("");
            setAmount("");
            setCurrency("INR");
            setSellerId("");
            setVendorEmail("");

            // Refresh listing
            fetchDeals();
        } catch (err: any) {
            setError(err?.response?.data?.message || err.message || "Failed to create deal");
        } finally {
            setIsCreating(false);
        }
    };

    const StatusBadge = ({ status }: { status: DealStatus }) => {
        const styles = {
            DRAFT: "bg-gray-100 text-gray-700",
            PENDING_ACCEPTANCE: "bg-amber-100 text-amber-700",
            ACTIVE: "bg-blue-100 text-blue-700",
            COMPLETED: "bg-emerald-100 text-emerald-700",
            CANCELLED: "bg-red-100 text-red-700",
            DISPUTED: "bg-orange-100 text-orange-700"
        };
        const defaultStyle = "bg-slate-100 text-slate-700";
        return (
            <span className={`px-2.5 py-1 text-xs font-semibold rounded-full ${styles[status] || defaultStyle}`}>
                {status.replace("_", " ")}
            </span>
        );
    };

    const filteredDeals = deals.filter(deal => {
        const matchesSearch = deal.title.toLowerCase().includes(searchQuery.toLowerCase()) ||
            deal.description?.toLowerCase().includes(searchQuery.toLowerCase());
        const matchesStatus = statusFilter === "ALL" || deal.status === statusFilter;
        return matchesSearch && matchesStatus;
    }).sort((a, b) => new Date(b.createdAt || 0).getTime() - new Date(a.createdAt || 0).getTime());

    return (
        <DashboardLayout>
            <div className="space-y-6 relative">
                <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4">
                    <div>
                        <h1 className="text-2xl font-bold text-slate-900">Projects & Deals</h1>
                        <p className="text-sm text-slate-500 mt-1">Manage all your corporate B2B deals securely.</p>
                    </div>
                    <Button onClick={() => setIsCreateModalOpen(true)} className="gap-2">
                        <Plus size={16} /> Create Project
                    </Button>
                </div>

                <Card>
                    <CardHeader className="flex flex-col sm:flex-row gap-4 justify-between pb-4 border-b border-slate-100">
                        <div className="flex flex-1 gap-4 items-center">
                            <div className="relative max-w-sm w-full">
                                <Search className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" size={18} />
                                <input
                                    className="pl-10 h-10 w-full rounded-md border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                                    placeholder="Search by title..."
                                    value={searchQuery}
                                    onChange={e => setSearchQuery(e.target.value)}
                                />
                            </div>
                            <select
                                className="h-10 rounded-md border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                                value={statusFilter}
                                onChange={e => setStatusFilter(e.target.value as any)}
                            >
                                <option value="ALL">All Statuses</option>
                                <option value="DRAFT">Draft</option>
                                <option value="PENDING_ACCEPTANCE">Pending Acceptance</option>
                                <option value="ACTIVE">Active</option>
                                <option value="COMPLETED">Completed</option>
                                <option value="CANCELLED">Cancelled</option>
                                <option value="DISPUTED">Disputed</option>
                            </select>
                        </div>
                        <Button variant="outline" size="sm" onClick={fetchDeals} disabled={isLoading} className="gap-2 text-slate-600">
                            <RefreshCw size={14} className={isLoading ? "animate-spin" : ""} /> Refresh
                        </Button>
                    </CardHeader>
                    <CardContent className="p-0">
                        {error && !isCreateModalOpen && (
                            <div className="p-6 text-center text-red-600 flex flex-col items-center">
                                <AlertCircle className="mb-2" size={24} />
                                <p>{error}</p>
                                <Button variant="outline" className="mt-4" onClick={fetchDeals}>Try Again</Button>
                            </div>
                        )}

                        {!error && isLoading && (
                            <div className="p-12 text-center text-slate-500 flex flex-col items-center">
                                <RefreshCw size={32} className="animate-spin text-blue-500 mb-4" />
                                <p>Loading your deals...</p>
                            </div>
                        )}

                        {!error && !isLoading && filteredDeals.length === 0 && (
                            <div className="p-12 text-center text-slate-500">
                                <p>No deals found. Create a new one to get started.</p>
                            </div>
                        )}

                        {!error && !isLoading && filteredDeals.length > 0 && (
                            <div className="overflow-x-auto">
                                <table className="w-full text-sm text-left">
                                    <thead className="bg-slate-50 text-slate-600 font-medium border-b border-slate-100">
                                        <tr>
                                            <th className="px-6 py-4">Title</th>
                                            <th className="px-6 py-4">Amount</th>
                                            <th className="px-6 py-4">Status</th>
                                            <th className="px-6 py-4">Created</th>
                                            <th className="px-6 py-4 text-right">Action</th>
                                        </tr>
                                    </thead>
                                    <tbody className="divide-y divide-slate-100">
                                        {filteredDeals.map((deal) => (
                                            <tr key={deal.id} className="hover:bg-slate-50/50 transition-colors">
                                                <td className="px-6 py-4">
                                                    <div className="font-semibold text-slate-900">
                                                        {deal.title}
                                                        {deal.projectId && <span className="ml-2 text-[10px] font-mono font-medium text-slate-500 bg-slate-200/50 px-1.5 py-0.5 rounded">{deal.projectId}</span>}
                                                    </div>
                                                    <div className="text-slate-500 text-xs mt-1 max-w-[200px] truncate">{deal.description}</div>
                                                </td>
                                                <td className="px-6 py-4 font-medium">₹{deal.totalAmount?.toLocaleString()} {deal.currency}</td>
                                                <td className="px-6 py-4">
                                                    <StatusBadge status={deal.status} />
                                                </td>
                                                <td className="px-6 py-4 text-slate-500">
                                                    {new Date(deal.createdAt).toLocaleDateString()}
                                                </td>
                                                <td className="px-6 py-4 text-right">
                                                    <Link href={`/corporate/projects/${deal.id}`}>
                                                        <Button variant="outline" size="sm" className="gap-2 h-8">
                                                            <Eye size={14} /> View
                                                        </Button>
                                                    </Link>
                                                </td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </div>
                        )}
                    </CardContent>
                </Card>
            </div>

            {/* Create Deal Modal */}
            {isCreateModalOpen && (
                <div className="fixed inset-0 bg-slate-900/50 flex items-center justify-center p-4 z-50">
                    <Card className="w-full max-w-lg shadow-xl animate-in fade-in zoom-in-95 duration-200">
                        <CardHeader className="border-b border-slate-100 pb-4">
                            <CardTitle>Create New Project (Deal)</CardTitle>
                        </CardHeader>
                        <CardContent className="mt-4">
                            {error && (
                                <div className="mb-4 p-3 bg-red-50 text-red-600 rounded-md text-sm">
                                    {error}
                                </div>
                            )}
                            <form onSubmit={handleCreateSubmit} className="space-y-4">
                                <div>
                                    <label className="block text-sm font-medium text-slate-700 mb-1">Title *</label>
                                    <Input
                                        required
                                        value={title}
                                        onChange={e => setTitle(e.target.value)}
                                        placeholder="e.g. Acme Website Redesign"
                                    />
                                </div>
                                <div>
                                    <label className="block text-sm font-medium text-slate-700 mb-1">Description *</label>
                                    <textarea
                                        required
                                        value={description}
                                        onChange={e => setDescription(e.target.value)}
                                        className="w-full rounded-md border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent min-h-[80px]"
                                        placeholder="Project details..."
                                    />
                                </div>
                                <div className="flex gap-4">
                                    <div className="flex-1">
                                        <label className="block text-sm font-medium text-slate-700 mb-1">Total Amount *</label>
                                        <Input
                                            required
                                            type="number"
                                            min="0"
                                            step="0.01"
                                            value={amount}
                                            onChange={e => setAmount(e.target.value)}
                                            placeholder="50000"
                                        />
                                    </div>
                                    <div className="w-1/3">
                                        <label className="block text-sm font-medium text-slate-700 mb-1">Currency</label>
                                        <select
                                            value={currency}
                                            onChange={e => setCurrency(e.target.value)}
                                            className="w-full h-10 rounded-md border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                                        >
                                            <option value="INR" className="text-slate-900 bg-white">INR</option>
                                        </select>
                                    </div>
                                </div>
                                <div>
                                    <div className="flex justify-between items-center mb-1">
                                        <label className="block text-sm font-medium text-slate-700">Assign to Seller (Vendor) *</label>
                                        {sellers.length > 0 && (
                                            <button
                                                type="button"
                                                onClick={() => setIsManualSeller(!isManualSeller)}
                                                className="text-xs text-blue-600 hover:underline"
                                            >
                                                {isManualSeller ? "Select from registered sellers" : "Enter Vendor UID manually"}
                                            </button>
                                        )}
                                    </div>

                                    {isManualSeller ? (
                                        <Input
                                            required
                                            value={sellerId}
                                            onChange={e => setSellerId(e.target.value)}
                                            placeholder="Vendor UID (e.g. USR-8F3A21C7)"
                                        />
                                    ) : sellers.length > 0 ? (
                                        <select
                                            required
                                            value={sellerId}
                                            onChange={e => {
                                                setSellerId(e.target.value);
                                                const s = sellers.find(x => x.id === e.target.value || x.uid === e.target.value);
                                                if (s && !vendorEmail) setVendorEmail(s.email);
                                            }}
                                            className="w-full h-10 rounded-md border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                                        >
                                            {sellers.map((s) => (
                                                <option key={s.id} value={s.uid || s.id} className="text-slate-900 bg-white">
                                                    {s.name} ({s.uid || s.id.substring(0, 8)})
                                                </option>
                                            ))}
                                        </select>
                                    ) : (
                                        <Input
                                            required
                                            value={sellerId}
                                            onChange={e => setSellerId(e.target.value)}
                                            placeholder="Vendor UID (e.g. USR-8F3A21C7)"
                                        />
                                    )}
                                    <p className="text-xs text-slate-500 mt-1">
                                        Enter or select the Vendor's public User Tracker UID (e.g. USR-8F3A21C7) to assign to this deal.
                                    </p>
                                </div>

                                <div className="pt-2 border-t border-slate-100">
                                    <label className="block text-sm font-medium text-slate-700 mb-1">Vendor Email (Optional for Notification)</label>
                                    <div className="flex gap-2">
                                        <Input
                                            type="email"
                                            value={vendorEmail}
                                            onChange={e => setVendorEmail(e.target.value)}
                                            placeholder="vendor@example.com"
                                            className="flex-1"
                                        />
                                        <Button
                                            type="button"
                                            variant="secondary"
                                            disabled={true}
                                            className="whitespace-nowrap bg-slate-100 text-slate-500 cursor-help"
                                            title="Emails are now dispatched automatically when you click Create Deal!"
                                        >
                                            Auto-Dispatched on Create
                                        </Button>
                                    </div>
                                    {emailSuccess && <p className="text-xs text-emerald-600 mt-1">✓ Project details natively queued for vendor email!</p>}
                                </div>

                                <div className="flex justify-end gap-3 mt-6">
                                    <Button type="button" variant="outline" onClick={() => setIsCreateModalOpen(false)}>
                                        Cancel
                                    </Button>
                                    <Button type="submit" disabled={isCreating}>
                                        {isCreating ? "Creating..." : "Create Deal"}
                                    </Button>
                                </div>
                            </form>
                        </CardContent>
                    </Card>
                </div>
            )}
        </DashboardLayout>
    );
}