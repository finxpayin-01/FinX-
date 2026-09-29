"use client";

import { useState } from "react";
import Link from "next/link";
import { Check, ClipboardCheck, ExternalLink, ShieldAlert } from "lucide-react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Assessment, Decision, Factor, RiskAlert, RiskLevel, RiskReview, ReviewStatus, RiskAction, riskService } from "@/services/risk.service";

export const levels: RiskLevel[] = ["LOW", "MODERATE", "ELEVATED", "HIGH", "CRITICAL"];

const tones: Record<RiskLevel, { badge: string; dot: string }> = {
    LOW: { badge: "bg-emerald-50 text-emerald-800 border border-emerald-200/80 shadow-xs", dot: "bg-emerald-500" },
    MODERATE: { badge: "bg-sky-50 text-sky-800 border border-sky-200/80 shadow-xs", dot: "bg-sky-500" },
    ELEVATED: { badge: "bg-amber-50 text-amber-900 border border-amber-200/80 shadow-xs", dot: "bg-amber-500" },
    HIGH: { badge: "bg-orange-50 text-orange-900 border border-orange-200/80 shadow-xs", dot: "bg-orange-500" },
    CRITICAL: { badge: "bg-rose-50 text-rose-800 border border-rose-200/80 shadow-xs", dot: "bg-rose-500" },
};

export const fieldClass = "w-full min-w-0 rounded-lg border border-gray-300 bg-white px-3.5 py-2 text-sm text-gray-900 shadow-2xs transition-colors focus:border-emerald-600 focus:outline-hidden focus:ring-1 focus:ring-emerald-600";
export const commandClass = "inline-flex items-center justify-center gap-2 rounded-lg bg-emerald-700 px-4 py-2 text-sm font-semibold text-white shadow-xs transition-all hover:bg-emerald-800 active:scale-98 disabled:opacity-50 disabled:pointer-events-none";

export function date(value: string | null) { return value ? new Date(value).toLocaleString() : "Pending"; }
export function delta(value: number) { return `${value > 0 ? "+" : ""}${value.toFixed(1)}`; }
export function riskError(error: unknown) {
    const e = error as { response?: { data?: { message?: string } }; message?: string };
    return e?.response?.data?.message || e?.message || "Unable to load risk data.";
}

export function RiskLevelBadge({ level }: { level: RiskLevel }) {
    const t = tones[level] || tones.LOW;
    return (
        <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-semibold tracking-wide ${t.badge}`}>
            <span className={`h-1.5 w-1.5 rounded-full ${t.dot}`} aria-hidden="true" />
            {level}
        </span>
    );
}

export function RiskScoreBadge({ score, level }: { score: number; level: RiskLevel }) {
    return (
        <span className="inline-flex items-center gap-2 whitespace-nowrap font-medium">
            <strong className="tabular-nums text-base text-gray-950 font-bold">{score}</strong>
            <span className="text-gray-300 font-light">&mdash;</span>
            <RiskLevelBadge level={level} />
        </span>
    );
}

export function RiskGauge({ score, level }: { score: number; level: RiskLevel }) {
    const gradient =
        score <= 20 ? "from-emerald-500 to-emerald-600" :
        score <= 40 ? "from-sky-500 to-sky-600" :
        score <= 60 ? "from-amber-500 to-amber-600" :
        score <= 80 ? "from-orange-500 to-orange-600" :
        "from-rose-500 to-rose-600";

    return (
        <div className="min-w-0 space-y-3.5 rounded-xl border border-gray-200/90 bg-gradient-to-br from-white to-gray-50/70 p-5 shadow-xs">
            <div className="flex items-baseline justify-between">
                <div>
                    <span className="text-4xl font-extrabold tracking-tight tabular-nums text-gray-950">{score}</span>
                    <span className="ml-1 text-sm font-medium text-gray-400">/ 100</span>
                </div>
                <RiskLevelBadge level={level} />
            </div>
            <div className="relative h-3 w-full overflow-hidden rounded-full bg-gray-200/90 shadow-inner">
                <div
                    className={`h-full rounded-full bg-gradient-to-r ${gradient} transition-all duration-700 ease-out`}
                    style={{ width: `${Math.max(4, score)}%` }}
                    role="progressbar"
                    aria-valuenow={score}
                    aria-valuemin={0}
                    aria-valuemax={100}
                    aria-label={`Risk score ${score} out of 100 (${level})`}
                />
            </div>
            <div className="flex justify-between text-[10px] font-semibold text-gray-600 uppercase tracking-wider">
                <span>0 LOW</span>
                <span>20</span>
                <span>40</span>
                <span>60</span>
                <span>80</span>
                <span>100 CRIT</span>
            </div>
        </div>
    );
}

export function RiskFactorList({ factors }: { factors: Factor[] }) {
    if (!factors.length) return <p className="py-5 text-sm text-gray-500">No rules currently contribute to this score.</p>;
    return (
        <div className="divide-y divide-gray-100">
            {factors.map(f => (
                <article key={f.code} className="py-4 transition-colors hover:bg-gray-50/60 rounded-lg px-2">
                    <div className="flex flex-wrap items-start justify-between gap-3">
                        <div className="min-w-0 flex-1">
                            <p className="text-sm font-semibold text-gray-900">{f.reason}</p>
                            <div className="mt-1 flex items-center gap-2 text-xs text-gray-500">
                                <span className="rounded bg-gray-100 px-1.5 py-0.5 font-mono text-[11px] font-medium text-gray-700">{f.code}</span>
                                <span>&bull;</span>
                                <span className="capitalize">{f.category.toLowerCase()}</span>
                            </div>
                        </div>
                        <div className="text-right text-sm tabular-nums">
                            <strong className="text-gray-950 font-bold">{f.impact.toFixed(1)} pts</strong>
                            <p className={`text-xs font-semibold ${f.change > 0 ? "text-rose-700" : f.change < 0 ? "text-emerald-700" : "text-gray-400"}`}>
                                {delta(f.change)} change
                            </p>
                        </div>
                    </div>
                    <details className="mt-2.5 text-xs text-gray-600">
                        <summary className="cursor-pointer font-medium text-emerald-800 hover:text-emerald-900 select-none">
                            Inspect Evidence &amp; Thresholds
                        </summary>
                        <dl className="mt-2 grid gap-1.5 rounded-lg border border-gray-100 bg-gray-50/80 p-3">
                            <div className="flex justify-between"><dt className="text-gray-500">Observed Value:</dt><dd className="font-mono font-medium">{f.observedValue.toFixed(3)}</dd></div>
                            <div className="flex justify-between"><dt className="text-gray-500">Rule Threshold:</dt><dd className="font-mono font-medium">{f.threshold}</dd></div>
                            <div className="flex justify-between"><dt className="text-gray-500">Category Points:</dt><dd className="font-mono font-medium">{f.categoryPoints.toFixed(1)} / 100</dd></div>
                            <div className="flex justify-between"><dt className="text-gray-500">Observed At:</dt><dd>{date(f.occurredAt)}</dd></div>
                            <div className="flex justify-between break-all"><dt className="text-gray-500">Reference:</dt><dd className="font-mono text-[11px]">{f.evidenceReference}</dd></div>
                        </dl>
                    </details>
                </article>
            ))}
        </div>
    );
}

export function RiskHistoryChart({ history }: { history: Assessment[] }) {
    const ordered = [...history].reverse();
    const [selected, setSelected] = useState<string | null>(null);
    const current = ordered.find(a => a.id === selected) || ordered.at(-1);
    if (!current) return <p className="py-8 text-sm text-gray-500">No assessments recorded.</p>;
    return (
        <div className="space-y-4">
            <div className="flex h-44 items-end gap-1.5 overflow-x-auto rounded-xl border border-gray-200/80 bg-gray-50/40 p-4" role="group" aria-label="Risk score history">
                {ordered.map(a => {
                    const barColor =
                        a.totalRiskScore <= 20 ? "bg-emerald-600 hover:bg-emerald-700" :
                        a.totalRiskScore <= 40 ? "bg-sky-600 hover:bg-sky-700" :
                        a.totalRiskScore <= 60 ? "bg-amber-600 hover:bg-amber-700" :
                        a.totalRiskScore <= 80 ? "bg-orange-600 hover:bg-orange-700" :
                        "bg-rose-600 hover:bg-rose-700";
                    return (
                        <button
                            key={a.id}
                            type="button"
                            onClick={() => setSelected(a.id)}
                            title={`${date(a.calculatedAt)}: ${a.totalRiskScore}/100, ${a.riskLevel}`}
                            aria-label={`${date(a.calculatedAt)}: ${a.totalRiskScore}/100, ${a.riskLevel}`}
                            aria-pressed={current.id === a.id}
                            className={`group relative min-w-4 flex-1 rounded-t-md transition-all ${current.id === a.id ? `${barColor} ring-2 ring-emerald-500 ring-offset-2 scale-y-105` : "bg-gray-300 hover:bg-gray-400"}`}
                            style={{ height: `${Math.max(6, a.totalRiskScore)}%` }}
                        />
                    );
                })}
            </div>
            <div className="flex flex-wrap justify-between gap-2 text-xs font-medium text-gray-400">
                <time>{date(ordered[0].calculatedAt)}</time>
                <time>{date(ordered.at(-1)!.calculatedAt)}</time>
            </div>
            <div className="rounded-xl border border-gray-200 bg-white p-4 shadow-xs">
                <div className="flex items-center justify-between border-b border-gray-100 pb-3">
                    <RiskScoreBadge score={current.totalRiskScore} level={current.riskLevel} />
                    <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider">{current.action.replaceAll("_", " ")}</span>
                </div>
                <div className="mt-3 flex flex-wrap gap-4 text-xs text-gray-500">
                    <span>Calculated: <strong className="text-gray-700">{date(current.calculatedAt)}</strong></span>
                    <span>Score Change: <strong className="text-gray-700">{delta(current.scoreChange)}</strong></span>
                    <span>Trigger: <strong className="text-gray-700 break-all">{current.trigger}</strong></span>
                </div>
                <div className="mt-4 border-t border-gray-100 pt-3">
                    <RiskFactorList factors={current.topRiskFactors.filter(f => f.change !== 0)} />
                </div>
            </div>
        </div>
    );
}

export function RiskAlertCard({ alert }: { alert: RiskAlert }) {
    let reasons: string[];
    try { reasons = JSON.parse(alert.reason); } catch { reasons = [alert.reason]; }
    return (
        <article className="rounded-xl border border-gray-200/80 bg-white p-5 shadow-xs transition-shadow hover:shadow-md">
            <div className="flex flex-wrap items-center justify-between gap-3">
                <div className="flex items-center gap-2">
                    <ShieldAlert size={18} className={alert.severity === "CRITICAL" ? "text-rose-600" : alert.severity === "HIGH" ? "text-orange-600" : "text-amber-600"} />
                    <RiskLevelBadge level={alert.severity} />
                </div>
                <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${alert.clearedAt ? "bg-gray-100 text-gray-600" : "bg-rose-50 text-rose-700"}`}>
                    {alert.clearedAt ? "Cleared" : "Open Alert"}
                </span>
            </div>
            <ul className="mt-3.5 space-y-1.5 text-sm text-gray-700">
                {reasons.map((reason, i) => (
                    <li key={i} className="flex items-start gap-2">
                        <span className="mt-1 h-1.5 w-1.5 rounded-full bg-rose-500 shrink-0" />
                        <span>{reason}</span>
                    </li>
                ))}
            </ul>
            <div className="mt-5 flex flex-wrap items-center justify-between gap-3 border-t border-gray-100 pt-3 text-xs text-gray-400">
                <time>{date(alert.createdAt)}</time>
                <Link href={`/admin/risk/${alert.subjectId}`} className="inline-flex items-center gap-1 font-semibold text-emerald-800 hover:text-emerald-900">
                    Business Profile <ExternalLink size={13} />
                </Link>
            </div>
        </article>
    );
}

export function RiskReviewPanel({ review }: { review: RiskReview }) {
    const client = useQueryClient();
    const [decision, setDecision] = useState<ReviewStatus>("IN_REVIEW");
    const [action, setAction] = useState<RiskAction>("MANUAL_REVIEW");
    const [reason, setReason] = useState("");
    const [notes, setNotes] = useState("");
    const [evidence, setEvidence] = useState("");
    const [expiry, setExpiry] = useState("");
    const mutation = useMutation({
        mutationFn: (body: Decision) => riskService.decide(review.id, body),
        onSuccess: () => client.invalidateQueries({ queryKey: ["risk"] }),
    });
    const changeDecision = (value: ReviewStatus) => {
        setDecision(value);
        setAction(value === "CLEARED" ? "ALLOW" : value === "RESTRICTED" ? "TEMPORARY_HOLD" : "MANUAL_REVIEW");
    };

    return (
        <article className="rounded-xl border border-gray-200/90 bg-white p-5 shadow-xs">
            <div className="flex flex-wrap items-center justify-between gap-3">
                <h3 className="flex items-center gap-2 text-sm font-bold text-gray-900">
                    <ClipboardCheck size={18} className="text-emerald-700" />
                    Status: <span className="rounded bg-gray-100 px-2 py-0.5 font-mono text-xs">{review.status.replaceAll("_", " ")}</span>
                </h3>
                <Link className="text-xs font-semibold text-emerald-800 hover:underline" href={`/admin/risk/${review.subjectId}`}>
                    Inspect Business &rarr;
                </Link>
            </div>
            <p className="mt-2 text-xs text-gray-400">Case ID: {review.id} &bull; Created: {date(review.createdAt)}</p>
            <p className="mt-3 text-sm text-gray-800 font-medium">{review.reason || "Awaiting reviewer decision"}</p>
            {review.reviewerId && <p className="mt-1.5 text-xs text-gray-500">Reviewer: {review.reviewerId} &bull; {date(review.reviewedAt)}</p>}
            {review.notes && <p className="mt-2 rounded-lg bg-gray-50 p-3 text-sm text-gray-600 whitespace-pre-wrap">{review.notes}</p>}
            {review.restrictionUntil && <p className="mt-2 text-sm font-semibold text-amber-800">Restriction expires: {date(review.restrictionUntil)}</p>}

            {review.status !== "CLEARED" && (
                <form
                    className="mt-5 grid gap-3.5 border-t border-gray-100 pt-4"
                    onSubmit={e => {
                        e.preventDefault();
                        mutation.mutate({
                            decision,
                            action,
                            reason,
                            notes,
                            evidenceReferences: evidence.split("\n").map(s => s.trim()).filter(Boolean),
                            restrictionUntil: expiry && decision === "RESTRICTED" ? new Date(expiry).toISOString() : null,
                            expectedVersion: review.version,
                        });
                    }}
                >
                    <div className="grid gap-3 sm:grid-cols-2">
                        <label className="text-xs font-semibold text-gray-700">
                            Decision
                            <select className={fieldClass} value={decision} onChange={e => changeDecision(e.target.value as ReviewStatus)}>
                                {["IN_REVIEW", "CLEARED", "RESTRICTED", "ESCALATED"].map(s => <option key={s}>{s}</option>)}
                            </select>
                        </label>
                        <label className="text-xs font-semibold text-gray-700">
                            Action
                            <select className={fieldClass} value={action} onChange={e => setAction(e.target.value as RiskAction)}>
                                {(decision === "CLEARED" ? ["ALLOW", "MONITOR"] : decision === "RESTRICTED" ? ["TEMPORARY_HOLD"] : ["MANUAL_REVIEW", "VERIFY"]).map(s => <option key={s}>{s}</option>)}
                            </select>
                        </label>
                    </div>
                    {decision === "RESTRICTED" && (
                        <label className="text-xs font-semibold text-gray-700">
                            Restriction Expiry Date &amp; Time
                            <input type="datetime-local" required className={fieldClass} value={expiry} onChange={e => setExpiry(e.target.value)} />
                        </label>
                    )}
                    <label className="text-xs font-semibold text-gray-700">
                        Decision Reason
                        <textarea required maxLength={2000} rows={2} className={fieldClass} placeholder="Explain the decision..." value={reason} onChange={e => setReason(e.target.value)} />
                    </label>
                    <label className="text-xs font-semibold text-gray-700">
                        Internal Reviewer Notes
                        <textarea maxLength={4000} rows={2} className={fieldClass} placeholder="Confidential review notes..." value={notes} onChange={e => setNotes(e.target.value)} />
                    </label>
                    <label className="text-xs font-semibold text-gray-700">
                        Evidence References (one per line)
                        <textarea rows={2} className={fieldClass} placeholder="evidence:doc-123&#10;transaction:tx-456" value={evidence} onChange={e => setEvidence(e.target.value)} />
                    </label>
                    {mutation.error && <p role="alert" className="text-sm font-medium text-rose-700">{riskError(mutation.error)}</p>}
                    <button className={`${commandClass} justify-self-start`} disabled={mutation.isPending || !reason.trim()}>
                        <Check size={16} />{mutation.isPending ? "Recording..." : "Record Decision"}
                    </button>
                </form>
            )}
        </article>
    );
}
