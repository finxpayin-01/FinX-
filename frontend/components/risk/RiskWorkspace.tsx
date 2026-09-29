"use client";

import { useState, ReactNode } from "react";
import Link from "next/link";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, ArrowRight, RefreshCw, ShieldCheck, SlidersHorizontal } from "lucide-react";
import { useAuth } from "@/components/auth/AuthProvider";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { riskService, Page, Business } from "@/services/risk.service";
import { RiskScoreBadge, RiskLevelBadge, RiskAlertCard, RiskReviewPanel, levels, date, delta, fieldClass, commandClass, riskError } from "./RiskComponents";
import { RiskProfile } from "./RiskProfile";
import { RiskRules } from "./RiskRules";

type View = "overview" | "buyers" | "vendors" | "alerts" | "reviews" | "rules" | "profile";
const tabs: [View, string, string][] = [["overview", "Overview", "/admin/risk"], ["buyers", "Buyers", "/admin/risk/buyers"], ["vendors", "Vendors", "/admin/risk/vendors"], ["alerts", "Alerts", "/admin/risk/alerts"], ["reviews", "Reviews", "/admin/risk/reviews"], ["rules", "Rules", "/admin/risk/rules"]];

export function RiskWorkspace({ view, entityId }: { view: View; entityId?: string }) {
    const { user, isLoading } = useAuth();
    const client = useQueryClient();
    return <DashboardLayout><div className="min-w-0 space-y-6 text-gray-900">
        <header className="flex flex-wrap items-center justify-between gap-3"><div className="flex items-center gap-3"><ShieldCheck size={24} className="text-emerald-700" /><h1 className="text-2xl font-semibold">Business Risk</h1></div><button title="Refresh risk data" aria-label="Refresh risk data" className="rounded-md border border-gray-300 p-2 hover:bg-gray-50" onClick={() => client.invalidateQueries({ queryKey: ["risk"] })}><RefreshCw size={17} /></button></header>
        <nav aria-label="Risk navigation" className="flex gap-5 overflow-x-auto border-b border-gray-200">{tabs.map(([key, label, href]) => <Link key={key} href={href} aria-current={view === key ? "page" : undefined} className={`whitespace-nowrap border-b-2 pb-3 text-sm font-medium ${view === key ? "border-emerald-700 text-emerald-800" : "border-transparent text-gray-500 hover:text-gray-900"}`}>{label}</Link>)}</nav>
        {isLoading ? <Loading /> : user?.role !== "ADMIN" ? <p role="alert">Administrator access required.</p> : <>
            {view === "overview" && <Overview />}{(view === "buyers" || view === "vendors") && <Businesses kind={view} />}
            {view === "alerts" && <Alerts />}{view === "reviews" && <Reviews />}{view === "rules" && <RiskRules />}
            {view === "profile" && entityId && <RiskProfile id={entityId} />}
        </>}
    </div></DashboardLayout>;
}
export function Loading() { return <p role="status" className="py-10 text-sm text-gray-500">Loading risk records...</p>; }
export function ErrorState({ error, retry }: { error: unknown; retry: () => void }) { return <div role="alert" className="border-l-2 border-rose-600 py-3 pl-4"><p className="text-sm text-rose-800">{riskError(error)}</p><button onClick={retry} className="mt-2 text-sm font-medium underline">Retry</button></div>; }
export function Empty({ children }: { children: ReactNode }) { return <p className="py-10 text-center text-sm text-gray-500">{children}</p>; }
export function Pagination({ data, setPage }: { data: { page: number; size: number; total: number }; setPage: (page: number) => void }) {
    return <div className="flex items-center justify-between gap-3 border-t border-gray-200 py-4 text-sm text-gray-600"><p>{data.total.toLocaleString()} records / Page {data.page + 1}</p><div className="flex gap-2"><button type="button" aria-label="Previous page" title="Previous page" className="rounded-md border border-gray-300 p-2 disabled:opacity-30" disabled={data.page === 0} onClick={() => setPage(data.page - 1)}><ArrowLeft size={16} /></button><button type="button" aria-label="Next page" title="Next page" className="rounded-md border border-gray-300 p-2 disabled:opacity-30" disabled={(data.page + 1) * data.size >= data.total} onClick={() => setPage(data.page + 1)}><ArrowRight size={16} /></button></div></div>;
}
function Overview() {
    const q = useQuery({ queryKey: ["risk", "dashboard"], queryFn: riskService.dashboard, refetchInterval: 30000 });
    if (q.isPending) return <Loading />;
    if (q.error) return <ErrorState error={q.error} retry={() => q.refetch()} />;
    const d = q.data;
    return <div className="space-y-8"><section className="grid grid-cols-2 gap-x-6 gap-y-5 border-b border-gray-200 pb-6 md:grid-cols-4">{[["Monitored businesses", d.totalMonitoredBusinesses], ["Open alerts", d.openAlerts], ["Pending reviews", d.pendingReviews], ["Awaiting assessment", d.unassessedBusinesses]].map(([label, value]) => <div key={label}><p className="text-xs text-gray-500">{label}</p><p className="mt-2 text-2xl font-semibold tabular-nums">{value}</p></div>)}</section>
        <section className="grid grid-cols-2 gap-4 sm:grid-cols-5">{levels.map(level => <div key={level} className="border-l-2 border-gray-200 pl-3"><RiskLevelBadge level={level} /><p className="mt-2 text-xl tabular-nums">{d.levelCounts[level]}</p></div>)}</section>
        <section><h2 className="mb-3 text-base font-semibold">Recent score changes</h2>{!d.recentScoreChanges.length ? <Empty>No risk assessments recorded yet.</Empty> : <div className="overflow-x-auto"><table className="w-full text-left text-sm"><thead className="border-y border-gray-200 bg-gray-50 text-xs text-gray-500"><tr>{["Business", "Score", "Change", "Action", "Calculated"].map(h => <th className="px-3 py-3 font-medium" key={h}>{h}</th>)}</tr></thead><tbody className="divide-y divide-gray-100">{d.recentScoreChanges.map(a => <tr key={a.id}><td className="px-3 py-3"><Link className="text-emerald-800 underline-offset-2 hover:underline" href={`/admin/risk/${a.entityId}`}>{a.userType} / {a.entityId.slice(0, 8)}</Link></td><td className="px-3 py-3"><RiskScoreBadge score={a.totalRiskScore} level={a.riskLevel} /></td><td className="px-3 py-3 tabular-nums">{delta(a.scoreChange)}</td><td className="px-3 py-3 text-xs">{a.action.replaceAll("_", " ")}</td><td className="whitespace-nowrap px-3 py-3 text-xs text-gray-500">{date(a.calculatedAt)}</td></tr>)}</tbody></table></div>}</section>
        <section><h2 className="mb-3 text-base font-semibold">Recent high-risk activity</h2>{!d.recentHighRiskEvents.length ? <Empty>No high-risk activity recorded.</Empty> : <ul className="divide-y divide-gray-200">{d.recentHighRiskEvents.map(e => <li className="flex flex-wrap justify-between gap-2 py-3 text-sm" key={e.id}><Link className="text-emerald-800" href={`/admin/risk/${e.subjectId}`}>{e.eventType.replaceAll("_", " ")}</Link><time className="text-xs text-gray-500">{date(e.occurredAt)}</time></li>)}</ul>}</section>
        <p className="text-xs text-gray-500">Event queue: {d.pendingEvents} pending / <span className={d.failedEvents ? "text-rose-700" : ""}>{d.failedEvents} failed</span></p>
    </div>;
}
function Businesses({ kind }: { kind: string }) {
    const [filters, setFilters] = useState<Record<string, string>>({});
    const [page, setPage] = useState(0);
    const q = useQuery({ queryKey: ["risk", kind, filters, page], queryFn: () => riskService.businesses(kind, { ...filters, page }) });
    function apply(form: HTMLFormElement) {
        const values: Record<string, string> = {};
        new FormData(form).forEach((v, k) => { if (String(v)) values[k] = String(v); });
        if (values.from) values.from = new Date(`${values.from}T00:00:00`).toISOString();
        if (values.to) values.to = new Date(`${values.to}T23:59:59.999`).toISOString();
        setFilters(values); setPage(0);
    }
    return <section><form className="mb-5 grid grid-cols-2 items-end gap-3 lg:grid-cols-4" onSubmit={e => { e.preventDefault(); apply(e.currentTarget); }}>
        <label className="text-xs text-gray-500">Risk level<select name="riskLevel" className={fieldClass}><option value="">All levels</option>{levels.map(v => <option key={v}>{v}</option>)}</select></label>
        <label className="text-xs text-gray-500">Verification<select name="verificationStatus" className={fieldClass}><option value="">All statuses</option>{["NOT_STARTED", "IN_PROGRESS", "PENDING_REVIEW", "VERIFIED", "FAILED", "REJECTED", "EXPIRED"].map(v => <option key={v}>{v}</option>)}</select></label>
        <label className="text-xs text-gray-500">Minimum score<input type="number" name="minScore" min={0} max={100} className={fieldClass} /></label><label className="text-xs text-gray-500">Maximum score<input type="number" name="maxScore" min={0} max={100} className={fieldClass} /></label>
        <label className="text-xs text-gray-500">From<input type="date" name="from" className={fieldClass} /></label><label className="text-xs text-gray-500">To<input type="date" name="to" className={fieldClass} /></label>
        <label className="text-xs text-gray-500">Minimum volume (policy currency)<input type="number" name="minTransactionVolume" min={0} className={fieldClass} /></label>
        <label className="text-xs text-gray-500">Alerts<select name="openAlerts" className={fieldClass}><option value="">All businesses</option><option value="true">Open alerts</option><option value="false">No open alerts</option></select></label>
        <button className={`${commandClass} justify-self-start`}><SlidersHorizontal size={15} />Apply filters</button>
    </form>{q.isPending ? <Loading /> : q.error ? <ErrorState error={q.error} retry={() => q.refetch()} /> : <BusinessTable data={q.data} setPage={setPage} />}</section>;
}
function BusinessTable({ data, setPage }: { data: Page<Business>; setPage: (n: number) => void }) {
    return <>{!data.items.length ? <Empty>No businesses match these filters.</Empty> : <div className="overflow-x-auto"><table className="w-full text-left text-sm"><thead className="border-y border-gray-200 bg-gray-50 text-xs text-gray-500"><tr>{["Business", "Risk score", "Change", "Verification", "Volume", "Alerts"].map(h => <th key={h} className="px-3 py-3 font-medium">{h}</th>)}</tr></thead><tbody className="divide-y divide-gray-100">{data.items.map(b => <tr key={b.id} className="hover:bg-gray-50"><td className="min-w-44 px-3 py-4"><Link href={`/admin/risk/${b.id}`} className="font-medium text-emerald-800 hover:underline">{b.name}</Link><p className="mt-1 text-xs text-gray-500">{b.organizationId ? "Organization" : "Individual account"}</p></td><td className="px-3 py-4">{b.calculatedAt ? <RiskScoreBadge score={b.riskScore} level={b.riskLevel} /> : "Pending"}</td><td className="px-3 py-4 tabular-nums">{delta(b.scoreChange)}</td><td className="px-3 py-4 text-xs">{b.verificationStatus}</td><td className="whitespace-nowrap px-3 py-4 tabular-nums">{b.transactionVolume.toLocaleString()} {b.currency}</td><td className="px-3 py-4">{b.openAlerts}</td></tr>)}</tbody></table></div>}<Pagination data={data} setPage={setPage} /></>;
}
function Alerts() {
    const [open, setOpen] = useState(true); const [page, setPage] = useState(0);
    const q = useQuery({ queryKey: ["risk", "alerts", open, page], queryFn: () => riskService.alerts(open, page) });
    return <section><label className="mb-5 flex items-center gap-2 text-sm"><input type="checkbox" checked={open} onChange={e => { setOpen(e.target.checked); setPage(0); }} />Open alerts only</label>{q.isPending ? <Loading /> : q.error ? <ErrorState error={q.error} retry={() => q.refetch()} /> : <>{!q.data.items.length ? <Empty>No alerts found.</Empty> : <div className="grid gap-4 lg:grid-cols-2">{q.data.items.map(a => <RiskAlertCard key={a.id} alert={a} />)}</div>}<Pagination data={q.data} setPage={setPage} /></>}</section>;
}
function Reviews() {
    const [status, setStatus] = useState(""); const [page, setPage] = useState(0);
    const q = useQuery({ queryKey: ["risk", "reviews", status, page], queryFn: () => riskService.reviews(status, page) });
    return <section><label className="mb-5 block max-w-xs text-xs text-gray-500">Review status<select className={fieldClass} value={status} onChange={e => { setStatus(e.target.value); setPage(0); }}><option value="">All reviews</option>{["OPEN", "IN_REVIEW", "CLEARED", "RESTRICTED", "ESCALATED"].map(s => <option key={s}>{s}</option>)}</select></label>{q.isPending ? <Loading /> : q.error ? <ErrorState error={q.error} retry={() => q.refetch()} /> : <>{!q.data.items.length ? <Empty>No reviews found.</Empty> : <div className="grid items-start gap-4 xl:grid-cols-2">{q.data.items.map(r => <RiskReviewPanel key={`${r.id}:${r.version}`} review={r} />)}</div>}<Pagination data={q.data} setPage={setPage} /></>}</section>;
}
