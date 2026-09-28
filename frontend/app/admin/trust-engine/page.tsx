"use client";

import { FormEvent, useEffect, useState } from "react";
import { useAuth } from "@/components/auth/AuthProvider";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { TrustDetails } from "@/components/trust/TrustDetails";
import { HistoryPoint, Organization, Review, TrustConfig, TrustEvent, trustError, trustService } from "@/services/trust.service";

const field = "w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm";
const button = "rounded-lg bg-teal-700 px-4 py-2 text-sm font-medium text-white disabled:opacity-40";
export default function AdminTrustEnginePage() {
  const { user, isLoading } = useAuth();
  const [query, setQuery] = useState("");
  const [organizations, setOrganizations] = useState<Organization[]>([]);
  const [selected, setSelected] = useState<Organization | null>(null);
  const [events, setEvents] = useState<TrustEvent[]>([]);
  const [eventPage, setEventPage] = useState(0);
  const [history, setHistory] = useState<HistoryPoint[]>([]);
  const [calculation, setCalculation] = useState<Record<string, unknown> | null>(null);
  const [reviews, setReviews] = useState<Review[]>([]);
  const [config, setConfig] = useState<TrustConfig | null>(null);
  const [rules, setRules] = useState("");
  const [reason, setReason] = useState("");
  const [configReason, setConfigReason] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    if (user?.role !== "ADMIN") return;
    let active = true;
    Promise.all([trustService.config(), trustService.reviews(), trustService.organizations("")]).then(([c, r, o]) => {
      if (active) { setConfig(c); setRules(JSON.stringify(c.rules, null, 2)); setReviews(r); setOrganizations(o); }
    }).catch(e => { if (active) setError(trustError(e)); });
    return () => { active = false; };
  }, [user]);
  useEffect(() => {
    if (!selected || user?.role !== "ADMIN") return;
    let active = true;
    Promise.all([trustService.events(selected.id, eventPage), trustService.history(selected.id, 180)]).then(([e, h]) => {
      if (active) { setEvents(e); setHistory(h); }
    }).catch(e => { if (active) setError(trustError(e)); });
    return () => { active = false; };
  }, [selected, eventPage, revision, user]);
  async function action(work: () => Promise<unknown>, message: string) {
    setBusy(true); setError(""); setNotice("");
    try { await work(); setNotice(message); setRevision(n => n + 1); setReviews(await trustService.reviews()); }
    catch (e) { setError(e instanceof SyntaxError ? "Scoring configuration must contain valid JSON." : trustError(e)); }
    finally { setBusy(false); }
  }
  function search(event: FormEvent) {
    event.preventDefault(); void action(async () => setOrganizations(await trustService.organizations(query)), "Organization search updated.");
  }
  if (isLoading) return <DashboardLayout><p role="status">Loading admin access…</p></DashboardLayout>;
  if (user?.role !== "ADMIN") return <DashboardLayout><p role="alert">Administrator access is required.</p></DashboardLayout>;
  return <DashboardLayout><div className="space-y-7">
    <div><p className="text-xs font-semibold tracking-widest text-teal-700">ADMINISTRATION</p><h1 className="mt-2 text-3xl font-semibold">Trust Engine</h1><p className="mt-2 text-sm text-slate-500">Inspect evidence, review unusual activity, and manage versioned scoring rules.</p></div>
    {error && <p role="alert" className="rounded-lg bg-red-50 p-4 text-sm text-red-800">{error}</p>}
    {notice && <p role="status" className="rounded-lg bg-teal-50 p-4 text-sm text-teal-900">{notice}</p>}
    <section className="rounded-2xl border bg-white p-6"><h2 className="mb-4 text-lg font-semibold">Find an organization</h2>
      <form onSubmit={search} className="flex gap-3"><input aria-label="Organization name" value={query} onChange={e => setQuery(e.target.value)} maxLength={100} placeholder="Search by organization name" className={field} /><button disabled={busy} className={button}>Search</button></form>
      <div className="mt-4 flex max-h-48 flex-wrap gap-2 overflow-auto">{organizations.map(o => <button onClick={() => { setSelected(o); setEventPage(0); setCalculation(null); }} key={o.id} className={`rounded-lg border px-3 py-2 text-left text-sm ${selected?.id === o.id ? "border-teal-700 bg-teal-50" : ""}`}><strong>{o.name}</strong><span className="block text-xs text-slate-500">{o.failures > 0 ? `${o.failures} calculation failures · retry pending` : o.dirty ? "Update pending" : "Calculated"}</span></button>)}</div>
      {!organizations.length && <p className="mt-4 text-sm text-slate-500">No organizations found. New accounts are provisioned by the background worker.</p>}
    </section>
    {selected && <>
      <section className="rounded-2xl border bg-white p-6"><h2 className="text-lg font-semibold">{selected.name}</h2><p className="mb-4 mt-1 text-xs text-slate-500">{selected.id}</p>
        <label className="mb-2 block text-sm" htmlFor="trust-action-reason">Reason for administrative action</label><textarea id="trust-action-reason" value={reason} onChange={e => setReason(e.target.value)} minLength={10} maxLength={1000} className={field} placeholder="Explain why this recalculation or review is needed (at least 10 characters)." />
        <div className="mt-3 flex flex-wrap gap-3"><button className={button} disabled={busy || reason.trim().length < 10} onClick={() => action(() => trustService.recalculate(selected.id, reason), "Scores recalculated and audit recorded.")}>Recalculate score</button><button className={button} disabled={busy || reason.trim().length < 10} onClick={() => action(() => trustService.review(selected.id, reason), "Manual review created.")}>Flag for manual review</button></div>
      </section>
      <TrustDetails key={`${selected.id}-${revision}`} organizationId={selected.id} />
      <section className="rounded-2xl border bg-white p-6"><h2 className="mb-4 text-lg font-semibold">Triggering events</h2><div className="overflow-x-auto"><table className="w-full text-left text-sm"><thead><tr>{["Event", "Role", "Source", "Occurred", "Processing"].map(h => <th key={h} className="p-2">{h}</th>)}</tr></thead><tbody>{events.map(e => <tr key={e.id} className="border-t"><td className="p-2">{e.eventType.replaceAll("_", " ")}<details className="mt-1 text-xs text-slate-500"><summary>Event context</summary><pre className="whitespace-pre-wrap">{JSON.stringify({ id: e.id, severity: e.severity, ...e.metadata }, null, 2)}</pre></details></td><td className="p-2">{e.role}</td><td className="p-2">{e.source}</td><td className="p-2">{new Date(e.occurredAt).toLocaleString()}</td><td className="p-2">{e.processed ? "Calculated" : "Pending"}</td></tr>)}</tbody></table></div>
        {!events.length && <p className="py-4 text-sm text-slate-500">No recorded events on this page.</p>}<div className="mt-4 flex gap-3"><button disabled={eventPage === 0} onClick={() => setEventPage(p => p - 1)} className="text-sm underline disabled:opacity-30">Previous</button><span className="text-sm">Page {eventPage + 1}</span><button disabled={events.length < 50} onClick={() => setEventPage(p => p + 1)} className="text-sm underline disabled:opacity-30">Next</button></div>
      </section>
      <section className="rounded-2xl border bg-white p-6"><h2 className="mb-4 text-lg font-semibold">Calculation audit</h2><select aria-label="Inspect calculation" className={field} defaultValue="" onChange={e => { if (e.target.value) void action(async () => setCalculation(await trustService.calculation(e.target.value)), "Calculation snapshot loaded."); }}><option value="">Select a daily calculation</option>{history.slice().reverse().map(h => <option value={h.id} key={h.id}>{new Date(h.calculatedAt).toLocaleString()} · {h.score} · configuration {h.configVersion}</option>)}</select>{calculation && <pre className="mt-4 max-h-96 overflow-auto rounded-lg bg-slate-50 p-4 text-xs">{JSON.stringify(calculation, null, 2)}</pre>}</section>
    </>}
    <section className="rounded-2xl border bg-white p-6"><h2 className="mb-2 text-lg font-semibold">Open manual reviews</h2><p className="mb-4 text-sm text-slate-500">Resolving a review records the decision. It does not directly change a score.</p>{reviews.length ? reviews.map(r => <div key={r.id} className="border-t py-4"><p className="text-sm font-medium">{r.reason}</p><p className="my-2 text-xs text-slate-500">Organization {r.organizationId} · {new Date(r.createdAt).toLocaleString()}</p><ReviewResolution disabled={busy} onResolve={resolution => action(() => trustService.resolve(r.id, resolution), "Review resolution recorded.")} /></div>) : <p className="text-sm text-slate-500">No open reviews.</p>}</section>
    <details className="rounded-2xl border bg-white p-6"><summary className="cursor-pointer text-lg font-semibold">Scoring configuration · version {config?.id ?? "…"}</summary><p className="my-4 text-sm text-slate-500">Changes create an immutable version and queue organizations for recalculation. Each role’s weights must sum to 1. Network and history weights are capped.</p>
      <label className="mb-2 block text-sm" htmlFor="trust-rules">Rules (JSON)</label><textarea id="trust-rules" spellCheck={false} className={`${field} min-h-96 font-mono text-xs`} value={rules} onChange={e => setRules(e.target.value)} />
      <label className="mb-2 mt-4 block text-sm" htmlFor="config-reason">Reason for rule change</label><textarea id="config-reason" className={field} minLength={10} maxLength={1000} value={configReason} onChange={e => setConfigReason(e.target.value)} />
      <button className={`${button} mt-3`} disabled={busy || !config || configReason.trim().length < 10} onClick={() => action(async () => { const next = await trustService.saveConfig(config!, JSON.parse(rules), configReason); setConfig(next); }, "Configuration version saved. Background recalculation is queued.")}>Save new configuration version</button>
    </details>
  </div></DashboardLayout>;
}
function ReviewResolution({ disabled, onResolve }: { disabled: boolean; onResolve: (reason: string) => Promise<void> }) {
  const [reason, setReason] = useState("");
  return <div className="flex flex-wrap gap-3"><input aria-label="Review resolution reason" placeholder="Resolution reason (at least 10 characters)" maxLength={1000} className={`${field} max-w-lg`} value={reason} onChange={e => setReason(e.target.value)} /><button disabled={disabled || reason.trim().length < 10} className={button} onClick={() => onResolve(reason)}>Resolve review</button></div>;
}
