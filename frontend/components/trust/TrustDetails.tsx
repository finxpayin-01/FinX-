"use client";

import { useEffect, useState } from "react";
import { useAuth } from "@/components/auth/AuthProvider";
import { Factor, HistoryPoint, TrustRole, TrustScore, trustError, trustService } from "@/services/trust.service";
import { SignalList, TrustScoreCard } from "./TrustScoreCard";

const labels: Record<Factor, string> = { PAYMENT: "Payment reliability", MILESTONE: "Milestone performance", DISPUTE: "Dispute behaviour", COMPLIANCE: "Contract compliance", EVIDENCE: "Evidence quality", HISTORY: "Transaction history", NETWORK: "Network reliability" };
export function TrustDetails({ organizationId }: { organizationId: string }) {
  const { user } = useAuth();
  const [scores, setScores] = useState<TrustScore[]>([]);
  const [role, setRole] = useState<TrustRole>("OVERALL");
  const [days, setDays] = useState(90);
  const [history, setHistory] = useState<HistoryPoint[]>([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    if (!user) return;
    let active = true;
    Promise.all([Promise.all((["OVERALL", "BUYER", "VENDOR"] as TrustRole[]).map(r => trustService.score(organizationId, r))), trustService.history(organizationId, days, role)])
      .then(([next, points]) => { if (active) { setScores(next); setHistory(points); setError(""); setLoading(false); } })
      .catch(e => { if (active) { setError(trustError(e)); setLoading(false); } });
    return () => { active = false; };
  }, [organizationId, days, role, user, attempt]);
  const reload = () => { setLoading(true); setAttempt(n => n + 1); };
  if (error) return <div role="alert" className="rounded-xl bg-amber-50 p-6">{error} <button onClick={reload} className="ml-3 underline">Retry</button></div>;
  if (loading || !scores.length) return <p role="status">Loading trust history…</p>;
  const score = scores.find(s => s.role === role)!;
  return <div className="space-y-6">
    <div className="flex flex-wrap justify-between gap-3"><div><h1 className="text-2xl font-semibold text-slate-900">Trust & reliability</h1><p className="mt-1 text-sm text-slate-500">Recorded behaviour. Clear explanations. Independent confidence.</p></div><button onClick={reload} className="rounded-lg border px-4 py-2 text-sm">Refresh</button></div>
    <div className="flex flex-wrap gap-2" aria-label="Score role">{scores.map(s => <button key={s.role} aria-pressed={role === s.role} onClick={() => { if (role !== s.role) { setLoading(true); setRole(s.role); } }} className={`rounded-lg border px-4 py-3 text-sm ${role === s.role ? "border-teal-700 bg-teal-50 text-teal-900" : "bg-white"}`}>{s.role === "OVERALL" ? "Organization" : s.role === "BUYER" ? "Buyer" : "Vendor"} · {s.trustLevel === "INSUFFICIENT_DATA" ? "Insufficient history" : s.overallScore.toFixed(1)}</button>)}</div>
    <div className="grid items-start gap-6 xl:grid-cols-2"><TrustScoreCard score={score} history={history} details={false} />
      <section className="rounded-2xl border bg-white p-6"><h2 className="mb-5 text-lg font-semibold">Factor breakdown</h2><div className="space-y-5">{(Object.keys(labels) as Factor[]).map(f => <div key={f}>
        <div className="mb-1 flex justify-between text-sm"><span>{labels[f]} <span className="text-slate-400">({(score.factors[f].weight * 100).toFixed(0)}%)</span></span><strong>{score.factors[f].score.toFixed(1)}</strong></div>
        <progress max={100} value={score.factors[f].score} aria-label={labels[f]} className="h-2 w-full accent-teal-600" /><p className="mt-1 text-xs text-slate-500">{score.factors[f].observations} observations{score.factors[f].observations === 0 ? " · neutral until evidence is available" : ""}</p>
      </div>)}</div></section></div>
    <section className="rounded-2xl border bg-white p-6"><div className="mb-5 flex flex-wrap items-center justify-between gap-3"><h2 className="text-lg font-semibold">Score history</h2><select aria-label="History period" className="rounded-lg border px-3 py-2 text-sm" value={days} onChange={e => { setLoading(true); setDays(Number(e.target.value)); }}><option value={30}>30 days</option><option value={90}>90 days</option><option value={180}>6 months</option></select></div>
      <TrustHistoryChart points={history} />
      <details className="mt-4 text-sm"><summary className="cursor-pointer font-medium">Calculation history and reasons</summary><div className="mt-4 max-h-72 overflow-auto"><table className="w-full text-left"><thead><tr><th className="p-2">Date</th><th className="p-2">Score</th><th className="p-2">Reason</th></tr></thead><tbody>{history.slice().reverse().map(p => <tr key={p.id} className="border-t"><td className="p-2">{new Date(p.calculatedAt).toLocaleDateString()}</td><td className="p-2">{p.level === "INSUFFICIENT_DATA" ? "Not established" : p.score}</td><td className="p-2">{p.reason}</td></tr>)}</tbody></table></div></details>
    </section>
    <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">{[["Completed transactions", score.statistics.completedDeals], ["Unique counterparties", score.statistics.uniqueCounterparties], ["Unresolved disputes", score.statistics.unresolvedDisputes], ["Qualified activity units", score.statistics.qualifiedBehaviourUnits]].map(([label, value]) => <div key={label} className="rounded-xl border bg-white p-5"><p className="text-xs text-slate-500">{label}</p><p className="mt-2 text-2xl font-semibold">{value ?? 0}</p></div>)}</div>
    <section className="grid gap-6 rounded-2xl border bg-white p-6 md:grid-cols-2"><SignalList title="All positive signals" signals={score.positiveSignals} positive /><SignalList title="All risk indicators" signals={score.negativeSignals} /></section>
    <p className="text-xs text-slate-500">{score.algorithmVersion} · Configuration {score.configVersion} · {score.dataPoints} recorded events. Scores support decisions and do not automatically block transactions. Counterparty identities and private documents are excluded.</p>
  </div>;
}

function TrustHistoryChart({ points }: { points: HistoryPoint[] }) {
  const established = points.filter(p => p.level !== "INSUFFICIENT_DATA");
  if (!established.length) return <p className="py-12 text-center text-sm text-slate-500">No established score history yet. Each calculation will appear here as activity builds.</p>;
  const start = new Date(points[0].calculatedAt).getTime(), end = new Date(points[points.length - 1].calculatedAt).getTime();
  const x = (p: HistoryPoint) => 45 + (new Date(p.calculatedAt).getTime() - start) / Math.max(1, end - start) * 700;
  const y = (p: HistoryPoint) => 215 - p.score * 1.8;
  return <svg viewBox="0 0 800 255" role="img" aria-label="Historical trust scores from zero to one hundred" className="w-full">
    {[0, 40, 60, 75, 90, 100].map(v => <g key={v}><line x1="45" x2="760" y1={215 - v * 1.8} y2={215 - v * 1.8} stroke="#e2e8f0" /><text x="10" y={219 - v * 1.8} fontSize="12" fill="#64748b">{v}</text></g>)}
    <polyline points={established.map(p => `${x(p)},${y(p)}`).join(" ")} fill="none" stroke="#0f766e" strokeWidth="3" />
    {established.map(p => <circle key={p.id} cx={x(p)} cy={y(p)} r="4" fill="#0f766e"><title>{new Date(p.calculatedAt).toLocaleString()}: {p.score}. {p.reason}</title></circle>)}
    <text x="45" y="245" fontSize="12" fill="#64748b">{new Date(start).toLocaleDateString()}</text><text x="745" y="245" textAnchor="end" fontSize="12" fill="#64748b">{new Date(end).toLocaleDateString()}</text>
  </svg>;
}
