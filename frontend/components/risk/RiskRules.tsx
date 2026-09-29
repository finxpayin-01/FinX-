"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Save } from "lucide-react";
import { PolicyRecord, riskService } from "@/services/risk.service";
import { commandClass, fieldClass, riskError } from "./RiskComponents";
import { Loading, ErrorState } from "./RiskWorkspace";

export function RiskRules() {
    const q = useQuery({ queryKey: ["risk", "rules"], queryFn: riskService.rules });
    const [type, setType] = useState("BUYER");
    if (q.isPending) return <Loading />;
    if (q.error) return <ErrorState error={q.error} retry={() => q.refetch()} />;
    const record = q.data.find(r => r.userType === type)!;
    return <section><div className="mb-5 flex gap-5" role="tablist" aria-label="Risk policy type">{["BUYER", "VENDOR"].map(t => <button key={t} role="tab" aria-selected={type === t} className={`border-b-2 pb-2 text-sm font-medium ${type === t ? "border-emerald-700 text-emerald-800" : "border-transparent text-gray-500"}`} onClick={() => setType(t)}>{t}</button>)}</div><PolicyEditor key={`${record.id}:${record.version}`} record={record} /></section>;
}
function PolicyEditor({ record }: { record: PolicyRecord }) {
    const [draft, setDraft] = useState<PolicyRecord>(() => structuredClone(record));
    const [reason, setReason] = useState("");
    const client = useQueryClient();
    const mutation = useMutation({ mutationFn: () => riskService.updatePolicy(draft, reason), onSuccess: () => client.invalidateQueries({ queryKey: ["risk"] }) });
    const total = Object.values(draft.policy.weights).reduce((a, b) => a + b, 0);
    function weight(key: string, value: number) { setDraft(d => ({ ...d, policy: { ...d.policy, weights: { ...d.policy.weights, [key]: value } } })); }
    return <form className="space-y-7" onSubmit={e => { e.preventDefault(); mutation.mutate(); }}>
        <div className="flex flex-wrap justify-between gap-3"><h2 className="font-semibold">Category weights</h2><p className={`text-sm tabular-nums ${total === 100 ? "text-gray-500" : "text-rose-700"}`}>Total {total}% / Version {record.version}</p></div>
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">{Object.entries(draft.policy.weights).map(([key, value]) => <label key={key} className="text-xs text-gray-500">{key}<input type="number" min={0} max={100} required value={value} onChange={e => weight(key, Number(e.target.value))} className={fieldClass} /></label>)}</div>
        <section className="grid gap-4 border-y border-gray-200 py-5 sm:grid-cols-2 lg:grid-cols-4">{([['windowDays', 'Observation window (days)'], ['velocityHours', 'Velocity window (hours)'], ['minimumBaselineProjects', 'Minimum baseline samples'], ['highValueAmount', 'High-value payout threshold']] as const).map(([key, label]) => <label key={key} className="text-xs text-gray-500">{label}<input type="number" required min={1} className={fieldClass} value={draft.policy[key]} onChange={e => setDraft(d => ({ ...d, policy: { ...d.policy, [key]: Number(e.target.value) } }))} /></label>)}
            <label className="text-xs text-gray-500">Policy currency<input className={fieldClass} required pattern="[A-Z]{3}" maxLength={3} value={draft.policy.currency} onChange={e => setDraft(d => ({ ...d, policy: { ...d.policy, currency: e.target.value.toUpperCase() } }))} /></label>
            <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={draft.policy.reviewHighValuePayouts} onChange={e => setDraft(d => ({ ...d, policy: { ...d.policy, reviewHighValuePayouts: e.target.checked } }))} />Review high-value payouts</label>
            <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={draft.policy.holdCriticalPayouts} onChange={e => setDraft(d => ({ ...d, policy: { ...d.policy, holdCriticalPayouts: e.target.checked } }))} />Hold critical-risk payouts for review</label>
        </section>
        <section><h2 className="mb-4 font-semibold">Rule thresholds</h2><div className="divide-y divide-gray-200">{draft.policy.rules.map((r, index) => <div key={r.code} className="grid items-center gap-3 py-4 sm:grid-cols-[1fr_100px_100px_60px]">
            <div className="min-w-0"><p className="text-sm font-medium">{r.reason}</p><p className="mt-1 break-all text-xs text-gray-500">{r.code} / {r.category}</p></div>
            <label className="text-xs text-gray-500">Threshold<input aria-label={`${r.code} threshold`} type="number" min={0.0001} step="any" required className={fieldClass} value={r.threshold} onChange={e => setDraft(d => ({ ...d, policy: { ...d.policy, rules: d.policy.rules.map((rule, i) => i === index ? { ...rule, threshold: Number(e.target.value) } : rule) } }))} /></label>
            <label className="text-xs text-gray-500">Points<input aria-label={`${r.code} points`} type="number" min={0} max={100} required className={fieldClass} value={r.points} onChange={e => setDraft(d => ({ ...d, policy: { ...d.policy, rules: d.policy.rules.map((rule, i) => i === index ? { ...rule, points: Number(e.target.value) } : rule) } }))} /></label>
            <label className="flex items-center gap-2 text-xs text-gray-500"><input aria-label={`${r.code} enabled`} type="checkbox" checked={r.enabled} onChange={e => setDraft(d => ({ ...d, policy: { ...d.policy, rules: d.policy.rules.map((rule, i) => i === index ? { ...rule, enabled: e.target.checked } : rule) } }))} />On</label>
        </div>)}</div></section>
        <label className="block text-sm text-gray-600">Change reason<textarea className={`${fieldClass} mt-2`} required maxLength={2000} value={reason} onChange={e => setReason(e.target.value)} /></label>
        {mutation.error && <p role="alert" className="text-sm text-rose-700">{riskError(mutation.error)}</p>}
        <button type="submit" className={commandClass} disabled={total !== 100 || !reason.trim() || mutation.isPending}><Save size={16} />{mutation.isPending ? "Saving..." : "Save policy"}</button>
    </form>;
}
