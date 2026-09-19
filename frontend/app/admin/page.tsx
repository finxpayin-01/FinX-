"use client";

import { useEffect, useState } from "react";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card, CardContent } from "@/components/ui/Card";
import { Users, Briefcase, Lock, Unlock, ShieldAlert, FileWarning, Wallet, Link as LinkIcon, Menu, FileText } from "lucide-react";
import { adminService } from "@/services/day6.service";
import Link from "next/link";
import { Button } from "@/components/ui/Button";

export default function AdminDashboard() {
    const [data, setData] = useState<any>(null);
    const [isLoading, setIsLoading] = useState(true);

    useEffect(() => {
        adminService.getDashboard()
            .then(setData)
            .finally(() => setIsLoading(false));
    }, []);

    if (isLoading || !data) return <DashboardLayout><div className="p-8 font-medium">Aggregating secure endpoints...</div></DashboardLayout>;

    // Unwrap ApiResponse safely
    const stats = data.data || data;

    // Safely map
    const totalProjectValue = stats.totalProjectValue || 0;
    const totalFundsDeposited = stats.totalFundsDeposited || 0;
    const totalFundsHeld = stats.totalFundsHeld || 0;
    const totalFundsReleased = stats.totalFundsReleased || 0;

    const totalUsers = stats.totalUsers || 0;
    const activeProjects = stats.activeDeals || stats.activeProjects || 0;
    const pendingReleases = stats.pendingReleases || 0;
    const openDisputes = stats.totalDisputes || stats.openDisputes || 0;
    const failedTxs = (stats.failedPayments || 0) + (stats.failedReleases || 0);

    return (
        <DashboardLayout>
            <div className="space-y-6">
                <div>
                    <h1 className="text-2xl font-bold text-slate-900">FINX Master Admin Dashboard</h1>
                    <p className="text-sm text-slate-500 mt-1">Global ecosystem overview. Strict readout only.</p>
                </div>

                <h2 className="text-sm font-bold uppercase tracking-wider text-slate-400 mt-8 mb-4">Financial Overview</h2>
                <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
                    <Card>
                        <CardContent className="p-5">
                            <p className="text-sm font-medium text-slate-500">Total Project Value</p>
                            <p className="text-2xl font-bold text-slate-900 mt-1">${totalProjectValue.toLocaleString()}</p>
                        </CardContent>
                    </Card>
                    <Card>
                        <CardContent className="p-5">
                            <p className="text-sm font-medium text-slate-500">Total Funds Deposited</p>
                            <p className="text-2xl font-bold text-emerald-600 mt-1">${totalFundsDeposited.toLocaleString()}</p>
                        </CardContent>
                    </Card>
                    <Card>
                        <CardContent className="p-5">
                            <p className="text-sm font-medium text-slate-500">Total Funds Held (Escrow)</p>
                            <p className="text-2xl font-bold text-blue-600 mt-1">${totalFundsHeld.toLocaleString()}</p>
                        </CardContent>
                    </Card>
                    <Card>
                        <CardContent className="p-5">
                            <p className="text-sm font-medium text-slate-500">Total Funds Released</p>
                            <p className="text-2xl font-bold text-purple-600 mt-1">${totalFundsReleased.toLocaleString()}</p>
                        </CardContent>
                    </Card>
                </div>

                <h2 className="text-sm font-bold uppercase tracking-wider text-slate-400 mt-8 mb-4">System Logistics</h2>
                <div className="grid grid-cols-2 md:grid-cols-5 gap-4">
                    <Card className="bg-slate-50 border-0 shadow-none ring-1 ring-slate-200">
                        <CardContent className="p-4 text-center">
                            <Users className="mx-auto text-slate-400 mb-2" size={20} />
                            <p className="text-xl font-bold text-slate-900">{totalUsers}</p>
                            <p className="text-xs font-medium text-slate-500 uppercase">Total Users</p>
                        </CardContent>
                    </Card>
                    <Card className="bg-slate-50 border-0 shadow-none ring-1 ring-slate-200">
                        <CardContent className="p-4 text-center">
                            <Briefcase className="mx-auto text-slate-400 mb-2" size={20} />
                            <p className="text-xl font-bold text-slate-900">{activeProjects}</p>
                            <p className="text-xs font-medium text-slate-500 uppercase">Active Projects</p>
                        </CardContent>
                    </Card>
                    <Card className="bg-amber-50 border-0 shadow-none ring-1 ring-amber-200">
                        <CardContent className="p-4 text-center">
                            <Lock className="mx-auto text-amber-500 mb-2" size={20} />
                            <p className="text-xl font-bold text-amber-900">{pendingReleases}</p>
                            <p className="text-xs font-medium text-amber-700 uppercase">Pending Releases</p>
                        </CardContent>
                    </Card>
                    <Card className="bg-red-50 border-0 shadow-none ring-1 ring-red-200">
                        <CardContent className="p-4 text-center">
                            <ShieldAlert className="mx-auto text-red-500 mb-2" size={20} />
                            <p className="text-xl font-bold text-red-900">{openDisputes}</p>
                            <p className="text-xs font-medium text-red-700 uppercase">Open Disputes</p>
                        </CardContent>
                    </Card>
                    <Card className="bg-red-50 border-0 shadow-none ring-1 ring-red-200">
                        <CardContent className="p-4 text-center">
                            <FileWarning className="mx-auto text-red-500 mb-2" size={20} />
                            <p className="text-xl font-bold text-red-900">{failedTxs}</p>
                            <p className="text-xs font-medium text-red-700 uppercase">Failed Txs</p>
                        </CardContent>
                    </Card>
                </div>

                <div className="grid grid-cols-1 md:grid-cols-2 gap-6 mt-8">
                    <Card>
                        <div className="p-5 border-b border-slate-100 flex justify-between items-center bg-slate-50">
                            <h3 className="font-semibold text-slate-900 flex items-center gap-2"><Lock size={18} /> Escrow Sweeps Engine</h3>
                        </div>
                        <CardContent className="p-6">
                            <p className="text-sm text-slate-600 mb-4">Directly authorize PM-approved deliverables into final banking releases.</p>
                            <Link href="/admin/escrow">
                                <Button className="w-full justify-between bg-slate-900 hover:bg-slate-800 text-white">
                                    Launch Escrow Command <ArrowRight size={16} />
                                </Button>
                            </Link>
                        </CardContent>
                    </Card>

                    <Card>
                        <div className="p-5 border-b border-slate-100 flex justify-between items-center bg-slate-50">
                            <h3 className="font-semibold text-slate-900 flex items-center gap-2"><FileText size={18} /> Audit Node Logs</h3>
                        </div>
                        <CardContent className="p-6">
                            <p className="text-sm text-slate-600 mb-4">Immutable read-only view of all transactional actions across FINX.</p>
                            <Link href="/admin/audit-logs">
                                <Button variant="outline" className="w-full justify-between">
                                    View Audit History <ArrowRight size={16} />
                                </Button>
                            </Link>
                        </CardContent>
                    </Card>
                </div>
            </div>
        </DashboardLayout>
    );
}

const ArrowRight = ({ size }: { size?: number }) => (
    <svg xmlns="http://www.w3.org/2000/svg" width={size || 24} height={size || 24} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M5 12h14" /><path d="m12 5 7 7-7 7" /></svg>
);
