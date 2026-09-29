"use client";

import { useState } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, RefreshCw, AlertCircle, ShieldCheck, CheckCircle2 } from "lucide-react";
import { EvidenceRow, riskService } from "@/services/risk.service";
import { RiskGauge, RiskFactorList, RiskHistoryChart, RiskReviewPanel, date, delta, commandClass, riskError } from "./RiskComponents";
import { Loading, ErrorState, Empty, Pagination } from "./RiskWorkspace";

export function RiskProfile({ id }: { id: string }) {
    const client = useQueryClient();
    const [tab, setTab] = useState("Factors");
    const [historyPage, setHistoryPage] = useState(0);
    const q = useQuery({ queryKey: ["risk", "profile", id], queryFn: () => riskService.profile(id) });
    const history = useQuery({ queryKey: ["risk", "history", id, historyPage], queryFn: () => riskService.history(id, historyPage), enabled: tab === "History" });
    const mutation = useMutation({ mutationFn: () => riskService.recalculate(id), onSuccess: () => client.invalidateQueries({ queryKey: ["risk"] }) });

    if (q.isPending) return <Loading />;
    if (q.error) return <ErrorState error={q.error} retry={() => q.refetch()} />;

    const p = q.data;
    const a = p.assessment;

    return (
        <div className="space-y-6">
            <div className="flex flex-wrap items-start justify-between gap-3">
                <div className="min-w-0">
                    <Link href={`/admin/risk/${p.subject.userType === "BUYER" ? "buyers" : "vendors"}`} className="mb-2.5 inline-flex items-center gap-1.5 text-xs font-semibold text-emerald-800 hover:text-emerald-900 transition-colors">
                        <ArrowLeft size={14} /> Back to {p.subject.userType === "BUYER" ? "Buyers" : "Vendors"}
                    </Link>
                    <div className="flex items-center gap-3">
                        <h2 className="break-words text-2xl font-bold tracking-tight text-gray-950">{p.subject.name}</h2>
                        <span className="rounded-full bg-emerald-100/80 px-2.5 py-0.5 text-xs font-semibold text-emerald-800">
                            {p.subject.userType}
                        </span>
                    </div>
                    <p className="mt-1 text-xs text-gray-500 font-medium">
                        Entity ID: <span className="font-mono text-gray-700">{p.subject.id}</span> &bull; {p.subject.organizationId ? "Corporate Organization Account" : "Individual Account"}
                    </p>
                </div>
                <button className={commandClass} disabled={mutation.isPending} onClick={() => mutation.mutate()}>
                    <RefreshCw size={15} className={mutation.isPending ? "animate-spin" : ""} />
                    {mutation.isPending ? "Recalculating..." : "Recalculate Risk"}
                </button>
            </div>

            {mutation.error && <p role="alert" className="rounded-lg bg-rose-50 p-3 text-sm font-medium text-rose-700">{riskError(mutation.error)}</p>}

            {a ? (
                <div className="space-y-4">
                    <section className="grid gap-6 rounded-2xl border border-gray-200/90 bg-white p-6 shadow-xs sm:grid-cols-[240px_1fr]">
                        <RiskGauge score={a.totalRiskScore} level={a.riskLevel} />
                        <div className="grid grid-cols-2 content-start gap-x-6 gap-y-4 text-sm md:grid-cols-3">
                            <div className="rounded-lg border border-gray-100 bg-gray-50/60 p-3">
                                <p className="text-xs font-medium text-gray-400">Previous Score</p>
                                <p className="mt-1 text-base font-bold text-gray-900 tabular-nums">{a.previousScore ?? "First Assessment"}</p>
                            </div>
                            <div className="rounded-lg border border-gray-100 bg-gray-50/60 p-3">
                                <p className="text-xs font-medium text-gray-400">Score Change</p>
                                <p className={`mt-1 text-base font-bold tabular-nums ${a.scoreChange > 0 ? "text-rose-700" : a.scoreChange < 0 ? "text-emerald-700" : "text-gray-700"}`}>
                                    {delta(a.scoreChange)}
                                </p>
                            </div>
                            <div className="rounded-lg border border-gray-100 bg-gray-50/60 p-3">
                                <p className="text-xs font-medium text-gray-400">Calculated Action</p>
                                <p className="mt-1 text-base font-bold text-gray-900">{a.action.replaceAll("_", " ")}</p>
                            </div>
                            <div className="rounded-lg border border-gray-100 bg-gray-50/60 p-3">
                                <p className="text-xs font-medium text-gray-400">Manual Review Status</p>
                                <p className="mt-1 text-base font-bold text-gray-900">{p.reviews[0]?.status.replaceAll("_", " ") || "No review required"}</p>
                            </div>
                            <div className="rounded-lg border border-gray-100 bg-gray-50/60 p-3">
                                <p className="text-xs font-medium text-gray-400">Last Calculated</p>
                                <p className="mt-1 text-xs font-semibold text-gray-700">{date(a.calculatedAt)}</p>
                            </div>
                            <div className="rounded-lg border border-gray-100 bg-gray-50/60 p-3">
                                <p className="text-xs font-medium text-gray-400">Active Rule Version</p>
                                <p className="mt-1 text-xs font-mono font-medium text-gray-700 break-all">{a.ruleVersion}</p>
                            </div>
                        </div>
                    </section>

                    {/* Explanatory "Why Score Changed" Section */}
                    {a.previousScore !== null && (
                        <div className="rounded-xl border border-amber-200/90 bg-gradient-to-r from-amber-50/80 to-amber-50/30 p-4 shadow-2xs">
                            <div className="flex flex-wrap items-center justify-between gap-2 border-b border-amber-200/60 pb-2">
                                <div className="flex items-center gap-1.5 text-xs font-bold uppercase tracking-wider text-amber-900">
                                    <AlertCircle size={14} className="text-amber-700" />
                                    <span>Why Score Changed</span>
                                </div>
                                <span className="text-xs font-semibold text-amber-900">
                                    Previous: <strong className="font-bold">{a.previousScore}</strong> &rarr; Current: <strong className="font-bold">{a.totalRiskScore}</strong> ({delta(a.scoreChange)})
                                </span>
                            </div>
                            <ul className="mt-3 divide-y divide-amber-100 text-xs">
                                {a.topRiskFactors.filter(f => Math.abs(f.change) >= 0.05).map(f => (
                                    <li key={f.code} className="flex items-center justify-between gap-2 py-1.5">
                                        <span className="text-gray-800 font-medium">{f.reason}</span>
                                        <span className={`font-mono font-bold tabular-nums ${f.change > 0 ? "text-rose-700" : "text-emerald-700"}`}>
                                            {delta(f.change)} pts
                                        </span>
                                    </li>
                                ))}
                                {a.topRiskFactors.every(f => Math.abs(f.change) < 0.05) && (
                                    <li className="py-1 text-gray-600 italic">No score variation across contributing risk rules.</li>
                                )}
                            </ul>
                        </div>
                    )}
                </div>
            ) : (
                <Empty>Awaiting first assessment.</Empty>
            )}

            <nav aria-label="Business risk detail tabs" className="flex gap-2 overflow-x-auto border-b border-gray-200 pb-px">
                {["Factors", "History", "Verification", "Payments", "Milestones", "Disputes", "Security", "Bank Accounts", "Activity", "Reviews"].map(t => (
                    <button
                        key={t}
                        className={`whitespace-nowrap border-b-2 px-3.5 pb-2.5 text-xs font-semibold transition-colors ${tab === t ? "border-emerald-700 text-emerald-800" : "border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700"}`}
                        aria-pressed={tab === t}
                        onClick={() => setTab(t)}
                    >
                        {t}
                    </button>
                ))}
            </nav>

            {tab === "Factors" && a && (
                <section className="space-y-6">
                    <div className="grid grid-cols-2 gap-3.5 rounded-xl border border-gray-200/80 bg-white p-4 shadow-2xs sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-7">
                        {Object.entries(a.factorScores).map(([category, score]) => (
                            <div key={category} className="rounded-lg bg-gray-50/70 p-2.5 text-center">
                                <p className="text-[11px] font-semibold uppercase tracking-wider text-gray-400">{category}</p>
                                <p className="mt-1 text-lg font-bold tabular-nums text-gray-900">{score.toFixed(1)} <span className="text-[10px] font-normal text-gray-400">/ 100</span></p>
                            </div>
                        ))}
                    </div>
                    <div className="rounded-xl border border-gray-200/80 bg-white p-5 shadow-xs">
                        <h3 className="mb-2 text-sm font-bold text-gray-900">Contributing Risk Factors</h3>
                        <RiskFactorList factors={a.topRiskFactors} />
                        <p className="mt-4 border-t border-gray-100 pt-3 text-xs text-gray-400">
                            Rounding contribution to final score: <span className="font-mono">{delta(a.roundingAdjustment)}</span>
                        </p>
                    </div>
                    {a.unavailableSignals.length > 0 && (
                        <details className="rounded-xl border border-gray-200/80 bg-white p-4 shadow-2xs text-sm">
                            <summary className="cursor-pointer font-medium text-gray-600 hover:text-gray-900">
                                Unavailable Telemetry Signals ({a.unavailableSignals.length})
                            </summary>
                            <ul className="mt-3 grid gap-2 text-xs text-gray-500 sm:grid-cols-2 md:grid-cols-3">
                                {a.unavailableSignals.map(s => <li className="rounded bg-gray-50 p-2 font-mono" key={s}>{s}</li>)}
                            </ul>
                        </details>
                    )}
                </section>
            )}

            {tab === "History" && (
                history.isPending ? <Loading /> : history.error ? <ErrorState error={history.error} retry={() => history.refetch()} /> : (
                    <div className="space-y-4">
                        <RiskHistoryChart history={history.data.items} />
                        <Pagination data={history.data} setPage={setHistoryPage} />
                    </div>
                )
            )}

            {tab === "Verification" && (
                <div className="space-y-6">
                    <EvidenceTable title="Individual KYC Records" rows={p.verification} />
                    <EvidenceTable title="Corporate KYB Records" rows={p.businessVerification} />
                </div>
            )}

            {tab === "Payments" && (
                <div className="space-y-6">
                    <EvidenceTable title="Bank Payment Instructions (Orchestration)" rows={p.payments} />
                    <EvidenceTable title="Direct Payments (Settlement)" rows={p.legacyPayments} />
                </div>
            )}

            {tab === "Milestones" && <EvidenceTable title="Milestone Delivery & Acceptance" rows={p.milestones} />}

            {tab === "Disputes" && <EvidenceTable title="Dispute History & Outlier Records" rows={p.disputes} />}

            {tab === "Security" && (
                <EvidenceTable title="Security & Authentication Events" rows={p.securityEvents || []} />
            )}

            {tab === "Bank Accounts" && (
                <EvidenceTable title="Bank Account Change History" rows={p.bankAccountChanges || []} />
            )}

            {tab === "Activity" && (
                <section className="rounded-xl border border-gray-200/80 bg-white p-5 shadow-xs">
                    <h3 className="mb-4 text-sm font-bold text-gray-900">Raw Risk Events &amp; Observations</h3>
                    {!p.events.length ? <Empty>No events recorded.</Empty> : (
                        <ul className="divide-y divide-gray-100">
                            {p.events.map(e => (
                                <li className="py-3.5" key={e.id}>
                                    <div className="flex flex-wrap items-center justify-between gap-2">
                                        <p className="text-xs font-semibold text-gray-900">{e.eventType.replaceAll("_", " ")}</p>
                                        <time className="text-[11px] text-gray-400 font-medium">{date(e.occurredAt)}</time>
                                    </div>
                                    <p className="mt-1 break-all font-mono text-[11px] text-gray-500">{e.sourceKey}</p>
                                    <p className="mt-1 text-[11px] text-gray-400">
                                        Status: <span className={e.deadLetter ? "font-semibold text-rose-600" : e.processedAt ? "text-emerald-700" : "text-amber-700"}>
                                            {e.deadLetter ? "Processing Failed" : e.processedAt ? "Processed" : "Pending Queue"}
                                        </span> &bull; {e.attempts} attempts
                                    </p>
                                </li>
                            ))}
                        </ul>
                    )}
                </section>
            )}

            {tab === "Reviews" && (
                <div className="space-y-6">
                    <h3 className="text-sm font-bold text-gray-900">Active &amp; Historical Manual Reviews</h3>
                    {!p.reviews.length ? <Empty>No manual reviews required or recorded.</Empty> : p.reviews.map(r => (
                        <RiskReviewPanel key={`${r.id}:${r.version}`} review={r} />
                    ))}
                    <section className="rounded-xl border border-gray-200/80 bg-white p-5 shadow-xs">
                        <h3 className="mb-3 text-sm font-bold text-gray-900">Review Decision Audit Log</h3>
                        {!p.reviewHistory.length ? <Empty>No review decisions logged in audit records.</Empty> : p.reviewHistory.map(row => (
                            <details key={String(row.id)} className="border-b border-gray-100 py-3 text-xs">
                                <summary className="cursor-pointer font-medium text-gray-700 hover:text-gray-900">
                                    {date(String(row.created_at))} &bull; Reviewer: {String(row.actor_user_id).slice(0, 8)}...
                                </summary>
                                <pre className="mt-2.5 overflow-auto rounded-lg bg-gray-50 p-3 whitespace-pre-wrap break-words font-mono text-[11px] text-gray-600">
                                    {formatDecision(row.metadata)}
                                </pre>
                            </details>
                        ))}
                    </section>
                </div>
            )}
        </div>
    );
}

function formatDecision(value: unknown) {
    try {
        const data = JSON.parse(String(value)) as { decision: { decision: string; action: string; reason: string; notes: string; evidenceReferences: string[] } };
        return `Decision: ${data.decision.decision}\nAction: ${data.decision.action}\nReason: ${data.decision.reason}\nNotes: ${data.decision.notes || "None"}\nEvidence: ${data.decision.evidenceReferences?.join(", ") || "None"}`;
    } catch {
        return "Decision details unavailable";
    }
}

function EvidenceTable({ title, rows }: { title: string; rows: EvidenceRow[] }) {
    const columns = rows.length ? Object.keys(rows[0]) : [];
    return (
        <section className="rounded-xl border border-gray-200/80 bg-white p-5 shadow-xs">
            <h3 className="mb-3.5 text-sm font-bold text-gray-900">{title}</h3>
            {!rows.length ? <Empty>No records available.</Empty> : (
                <div className="overflow-x-auto">
                    <table className="w-full text-left text-xs">
                        <thead className="border-y border-gray-100 bg-gray-50/80 text-[11px] font-semibold uppercase tracking-wider text-gray-400">
                            <tr>
                                {columns.map(c => <th className="whitespace-nowrap px-3.5 py-2.5 font-medium" key={c}>{c.replaceAll("_", " ")}</th>)}
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-gray-100">
                            {rows.map((r, i) => (
                                <tr key={i} className="hover:bg-gray-50/50">
                                    {columns.map(c => <td className="max-w-72 break-words px-3.5 py-3 font-medium text-gray-700" key={c}>{r[c] === null ? "—" : String(r[c])}</td>)}
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )}
        </section>
    );
}
