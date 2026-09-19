"use client";

import { useEffect, useState } from "react";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card, CardContent } from "@/components/ui/Card";
import { Lock, Unlock, ShieldAlert } from "lucide-react";
import { escrowService } from "@/services/escrow.service";
import { milestoneService } from "@/services/milestone.service";
import { Milestone } from "@/types";
import { Button } from "@/components/ui/Button";
import { EscrowRailView } from "@/components/escrow/EscrowRailView";

interface EscrowDash {
    pending: Milestone[];
    released: Milestone[];
    disputed: Milestone[];
}

export default function AdminEscrowDashboard() { return <EscrowRailView><LegacyAdminEscrowDashboard/></EscrowRailView>; }
function LegacyAdminEscrowDashboard() {
    const [data, setData] = useState<EscrowDash>({ pending: [], released: [], disputed: [] });
    const [isLoading, setIsLoading] = useState(true);
    const [processingId, setProcessingId] = useState<string | null>(null);

    useEffect(() => {
        fetchData();
    }, []);

    const fetchData = () => {
        setIsLoading(true);
        milestoneService.getAllMilestones()
            .then((milestones) => {
                const list = Array.isArray(milestones) ? milestones : [];
                setData({
                    pending: list.filter(m => m.status === 'APPROVED'),
                    released: list.filter(m => m.status === 'RELEASED' || (m.status as string) === 'COMPLETED'),
                    disputed: list.filter(m => (m.status as string) === 'DISPUTED')
                });
            })
            .catch(() => {})
            .finally(() => setIsLoading(false));
    };

    const handleRelease = async (milestoneId: string) => {
        setProcessingId(milestoneId);
        try {
            await escrowService.releaseEscrow(milestoneId, "Admin authorized release");
            alert("Funds have been released to the vendor.");
            await fetchData();
        } catch (err: any) {
            alert('Release failed. ' + err.message);
        } finally {
            setProcessingId(null);
        }
    }

    const heldVolume = data.pending.reduce((acc, m) => acc + m.amount, 0);
    const totalReleased = data.released.reduce((acc, m) => acc + m.amount, 0);

    return (
        <DashboardLayout>
            <div className="space-y-6">
                <div>
                    <h1 className="text-2xl font-bold text-slate-900">FINX Admin Escrow Center</h1>
                    <p className="text-sm text-slate-500 mt-1">Review approved milestones and authorize final fiat release.</p>
                </div>

                <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                    <Card>
                        <CardContent className="p-6">
                            <div className="flex items-center justify-between">
                                <div>
                                    <p className="text-sm font-medium text-slate-500">Global Locked Volume</p>
                                    <p className="text-3xl font-bold text-slate-900 mt-2">${heldVolume.toLocaleString()}</p>
                                </div>
                                <div className="h-12 w-12 bg-blue-50 flex items-center justify-center rounded-full text-blue-600">
                                    <Lock size={24} />
                                </div>
                            </div>
                        </CardContent>
                    </Card>
                    <Card>
                        <CardContent className="p-6">
                            <div className="flex items-center justify-between">
                                <div>
                                    <p className="text-sm font-medium text-slate-500">Ready to Release</p>
                                    <p className="text-3xl font-bold text-amber-600 mt-2">{data.pending.length}</p>
                                </div>
                                <div className="h-12 w-12 bg-amber-50 flex items-center justify-center rounded-full text-amber-600">
                                    <ShieldAlert size={24} />
                                </div>
                            </div>
                        </CardContent>
                    </Card>
                    <Card>
                        <CardContent className="p-6">
                            <div className="flex items-center justify-between">
                                <div>
                                    <p className="text-sm font-medium text-slate-500">Released Txs</p>
                                    <p className="text-3xl font-bold text-slate-900 mt-2">{data.released.length}</p>
                                </div>
                                <div className="h-12 w-12 bg-emerald-50 flex items-center justify-center rounded-full text-emerald-600">
                                    <Unlock size={24} />
                                </div>
                            </div>
                        </CardContent>
                    </Card>
                </div>

                <h2 className="text-lg font-semibold text-slate-900 mt-8 mb-4">Pending Authorization Queue</h2>
                <Card className="border-amber-300 border-2">
                    <div className="divide-y divide-slate-100">
                        {isLoading && <div className="p-8 text-center text-slate-500">Syncing ledgers...</div>}

                        {!isLoading && data.pending.length === 0 && (
                            <div className="p-4 text-sm text-slate-500">No approved milestones in queue for release.</div>
                        )}

                        {!isLoading && data.pending.map((m) => (
                            <div key={m.id} className="p-6 flex flex-col md:flex-row items-center justify-between hover:bg-slate-50 transition-colors gap-4">
                                <div>
                                    <p className="text-base font-semibold text-slate-900">{m.title} <span className="ml-2 inline-flex items-center rounded-full bg-emerald-50 px-2 py-0.5 text-xs font-medium text-emerald-700 ring-1 ring-inset ring-emerald-600/20">PM Approved</span></p>
                                    <p className="text-sm text-slate-600 mt-1">Locked Vault Payload: <span className="font-bold">${m.amount.toLocaleString()} {m.currency}</span></p>
                                </div>
                                <div className="flex gap-3">
                                    <Button variant="outline" className="border-red-200 text-red-600 hover:bg-red-50">Place on Hold</Button>
                                    <Button
                                        onClick={() => handleRelease(m.id)}
                                        isLoading={processingId === m.id}
                                        disabled={processingId !== null}
                                        className="gap-2 bg-slate-900 text-white hover:bg-slate-800"
                                    >
                                        <Unlock size={16} /> {processingId === m.id ? 'Processing via Gateway...' : 'Authorize Escrow Release'}
                                    </Button>
                                </div>
                            </div>
                        ))}
                    </div>
                </Card>
            </div>
        </DashboardLayout>
    );
}
