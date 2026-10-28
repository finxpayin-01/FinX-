"use client";

import { useEffect, useState } from "react";
import { useParams } from "next/navigation";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card, CardContent } from "@/components/ui/Card";
import { paymentService } from "@/services/payment.service";
import { milestoneService } from "@/services/milestone.service";
import { escrowService } from "@/services/escrow.service";
import { dealService } from "@/services/deal.service";
import { Payment, Milestone, EscrowAccount, Deal } from "@/types";
import { Button } from "@/components/ui/Button";
import Link from "next/link";
import { ArrowRight, Wallet, ShieldCheck, Lock, AlertCircle } from "lucide-react";

export default function ProjectPayments() {
    const { projectId } = useParams();
    const [deal, setDeal] = useState<Deal | null>(null);
    const [escrowAccount, setEscrowAccount] = useState<EscrowAccount | null>(null);
    const [payments, setPayments] = useState<Payment[]>([]);
    const [milestones, setMilestones] = useState<Milestone[]>([]);
    const [isLoading, setIsLoading] = useState(true);

    useEffect(() => {
        if (!projectId) return;
        Promise.all([
            paymentService.getProjectPayments(projectId as string).catch(() => []),
            milestoneService.getProjectMilestones(projectId as string).catch(() => []),
            escrowService.getDealEscrow(projectId as string).catch(() => null),
            dealService.getDealById(projectId as string).catch(() => null)
        ]).then(([pData, mData, eData, dData]) => {
            setPayments(pData);
            setMilestones(mData);
            setEscrowAccount(eData);
            setDeal(dData);
        }).finally(() => setIsLoading(false));
    }, [projectId]);

    const hasBuyerOrg = Boolean(deal?.buyerOrganizationId);
    const hasSellerOrg = Boolean(deal?.sellerOrganizationId);
    const isAgreementFullySigned = deal?.agreementStatus === "FULLY_SIGNED";
    const isFundingReady = Boolean(deal && hasBuyerOrg && hasSellerOrg && isAgreementFullySigned);

    const totalValue = milestones.reduce((sum, m) => sum + Number(m.amount || 0), 0);
    // Real escrow funded value read from actual ledger/escrow account
    const fundedValue = Number(escrowAccount?.totalFunded || escrowAccount?.balance || 0);
    const remainingValue = Math.max(0, totalValue - fundedValue);

    return (
        <DashboardLayout>
            <div className="space-y-6">
                <div>
                    <h1 className="text-2xl font-bold text-slate-900">Project Funding Hub</h1>
                    <p className="text-sm text-slate-500 mt-1">Review verified escrow balances and manage milestone funding.</p>
                </div>

                {!isFundingReady && !isLoading && (
                    <div className="p-4 bg-amber-50 border border-amber-300 rounded-xl flex items-start gap-3">
                        <Lock className="text-amber-700 shrink-0 mt-0.5" size={20} />
                        <div>
                            <h4 className="text-sm font-semibold text-amber-950">Escrow Funding Actions Locked</h4>
                            <p className="text-xs text-amber-800 mt-1 leading-relaxed">
                                Milestone funding is locked by platform governance policy. Corporate buyer and vendor organizations must be verified and bound, and the bilateral agreement must be fully signed before payment instructions can be generated.
                            </p>
                        </div>
                    </div>
                )}

                <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                    <Card>
                        <CardContent className="p-6">
                            <p className="text-sm font-medium text-slate-500">Total Project Value</p>
                            <p className="text-3xl font-bold text-slate-900 mt-2">₹{totalValue.toLocaleString("en-IN")}</p>
                            <p className="text-xs text-slate-400 mt-1">{milestones[0]?.currency || deal?.currency || "INR"}</p>
                        </CardContent>
                    </Card>
                    <Card>
                        <CardContent className="p-6">
                            <p className="text-sm font-medium text-slate-500">Currently Funded (Escrow Vault)</p>
                            <p className={`text-3xl font-bold mt-2 ${fundedValue > 0 ? "text-emerald-600" : "text-slate-500"}`}>
                                ₹{fundedValue.toLocaleString("en-IN")}
                            </p>
                            <p className="text-xs text-slate-400 mt-1">
                                {fundedValue > 0 ? "Secured in Nodal Escrow" : "Unfunded (₹0 held)"}
                            </p>
                        </CardContent>
                    </Card>
                    <Card>
                        <CardContent className="p-6">
                            <p className="text-sm font-medium text-slate-500">Remaining to Fund</p>
                            <p className={`text-3xl font-bold mt-2 ${remainingValue > 0 ? "text-amber-600" : "text-emerald-600"}`}>
                                ₹{remainingValue.toLocaleString("en-IN")}
                            </p>
                            <p className="text-xs text-slate-400 mt-1">Pending escrow authorization</p>
                        </CardContent>
                    </Card>
                </div>

                <h2 className="text-lg font-semibold text-slate-900 mt-8 mb-4">Milestone Funding Schedule</h2>
                <Card>
                    <div className="divide-y divide-slate-100">
                        {isLoading && <div className="p-8 text-center text-slate-500">Loading schedule...</div>}

                        {!isLoading && milestones.map(m => {
                            const isThisMilestoneFunded = fundedValue > 0 && fundedValue >= Number(m.amount);
                            const isApprovedUnfunded = m.status === 'APPROVED' && !isThisMilestoneFunded;

                            return (
                                <div key={m.id} className="p-6 flex flex-col md:flex-row items-center justify-between hover:bg-slate-50 gap-4">
                                    <div className="flex-1">
                                        <div className="flex items-center gap-3">
                                            <h3 className="font-semibold text-slate-900">{m.title}</h3>
                                            {isThisMilestoneFunded ? (
                                                <span className="inline-flex items-center rounded-full bg-emerald-50 px-2.5 py-1 text-xs font-medium text-emerald-700 ring-1 ring-emerald-600/20">
                                                    Secured in Escrow
                                                </span>
                                            ) : isApprovedUnfunded ? (
                                                <span className="inline-flex items-center rounded-full bg-slate-100 px-2.5 py-1 text-xs font-medium text-slate-700 ring-1 ring-slate-600/20">
                                                    Approved (Escrow Unfunded)
                                                </span>
                                            ) : (
                                                <span className="inline-flex items-center rounded-full bg-amber-50 px-2.5 py-1 text-xs font-medium text-amber-700 ring-1 ring-amber-600/20">
                                                    Pending Funding (Unfunded)
                                                </span>
                                            )}
                                        </div>
                                        <p className="text-sm font-medium text-slate-700 mt-2">
                                            Amount: ₹{Number(m.amount).toLocaleString("en-IN")} {m.currency || "INR"}
                                        </p>
                                    </div>
                                    <div className="flex shrink-0 items-center gap-2">
                                        {m.status === 'PENDING' ? (
                                            isFundingReady ? (
                                                <Link href={`/corporate/projects/${projectId}/milestones/${m.id}/payment`}>
                                                    <Button className="gap-2 bg-blue-600 hover:bg-blue-700 text-white">
                                                        <Wallet size={16} /> Fund Milestone
                                                    </Button>
                                                </Link>
                                            ) : (
                                                <Button disabled className="gap-2 bg-slate-100 text-slate-400 border border-slate-200 cursor-not-allowed">
                                                    <Lock size={16} /> Fund (Locked)
                                                </Button>
                                            )
                                        ) : (
                                            <Link href={`/corporate/projects/${projectId}/milestones/${m.id}`}>
                                                <Button variant="outline" className="gap-2">
                                                    View Milestone <ArrowRight size={16} />
                                                </Button>
                                            </Link>
                                        )}
                                    </div>
                                </div>
                            );
                        })}
                    </div>
                </Card>
            </div>
        </DashboardLayout>
    );
}
