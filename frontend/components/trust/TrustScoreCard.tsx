"use client";

import Link from "next/link";
import { ShieldCheck, ArrowUpRight, CheckCircle2, AlertTriangle } from "lucide-react";
import { HistoryPoint, TrustScore } from "@/services/trust.service";

export function TrustScoreCard({ score, history = [], details = true }: { score: TrustScore; history?: HistoryPoint[]; details?: boolean }) {
  const insufficient = score.trustLevel === "INSUFFICIENT_DATA";
  const comparable = history.length > 1 && history[0].level !== "INSUFFICIENT_DATA" && !insufficient;
  const change = comparable ? score.overallScore - history[0].score : null;
  return <section aria-label={`${score.role.toLowerCase()} trust score`} className="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
    <div className="flex items-start justify-between gap-4">
      <div><p className="text-xs font-semibold tracking-widest text-slate-500">{score.role} TRUST SCORE</p>
        <p className="mt-3 text-4xl font-semibold tracking-tight text-slate-900">{insufficient ? "Not established" : score.overallScore.toFixed(1)}{!insufficient && <span className="text-lg text-slate-400"> / 100</span>}</p>
        <p className={`mt-3 inline-block rounded-full px-3 py-1 text-xs font-semibold ${score.warning ? "bg-amber-50 text-amber-800" : "bg-slate-100 text-slate-700"}`}>{score.trustLevel.replaceAll("_", " ")}</p>
      </div><ShieldCheck className="text-teal-600" size={32} />
    </div>
    <div className="my-5"><div className="mb-2 flex justify-between text-sm"><span>Confidence in available history</span><strong>{score.confidenceScore.toFixed(0)}%</strong></div>
      <progress aria-label="Confidence" max={100} value={score.confidenceScore} className="h-2 w-full accent-teal-600" />
    </div>
    <p className="text-sm leading-6 text-slate-600">{score.explanation}</p>
    {change !== null && <p className="mt-3 text-sm font-medium text-teal-700">{change >= 0 ? "+" : ""}{change.toFixed(1)} since {new Date(history[0].calculatedAt).toLocaleDateString()}</p>}
    {score.warning && <p className="mt-4 rounded-lg bg-amber-50 p-3 text-sm text-amber-900">{score.warning}</p>}
    <div className="mt-6 grid gap-5 sm:grid-cols-2">
      <SignalList title="Strengths" signals={score.positiveSignals.slice(0, 3)} positive />
      <SignalList title="Risk indicators" signals={score.negativeSignals.slice(0, 3)} />
    </div>
    <div className="mt-6 flex flex-wrap items-center justify-between gap-3 border-t border-slate-100 pt-4 text-xs text-slate-500">
      <span>{score.lastCalculatedAt ? `Updated ${new Date(score.lastCalculatedAt).toLocaleString()}` : "Awaiting first calculation"}</span>
      {details && <Link className="flex items-center gap-1 font-semibold text-teal-700" href={`/organizations/${score.organizationId}/trust`}>View Trust Details <ArrowUpRight size={15} /></Link>}
    </div>
  </section>;
}

export function SignalList({ title, signals, positive = false }: { title: string; signals: string[]; positive?: boolean }) {
  return <div><h3 className="mb-3 text-sm font-semibold text-slate-900">{title}</h3>
    {signals.length ? <ul className="space-y-3">{signals.map((signal) => <li key={signal} className="flex gap-2 text-sm leading-5 text-slate-600">
      {positive ? <CheckCircle2 size={16} className="mt-0.5 shrink-0 text-teal-600" /> : <AlertTriangle size={16} className="mt-0.5 shrink-0 text-amber-600" />}{signal}</li>)}</ul>
      : <p className="text-sm text-slate-500">No recorded {positive ? "strengths" : "risk indicators"} yet.</p>}
  </div>;
}
