"use client";

import { useEffect, useState } from "react";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { BlockchainEscrow } from "@/components/escrow/BlockchainEscrow";
import { blockchainService, type ChainTransaction, formatToken } from "@/services/blockchain.service";
import { useAuth } from "@/components/auth/AuthProvider";
import { api } from "@/lib/api";

export default function BlockchainDashboard() {
    const { user } = useAuth();
    const [deals, setDeals] = useState<{id: string; title: string}[]>([]);
    const [selected, setSelected] = useState("");
    const [health, setHealth] = useState("");
    const [error, setError] = useState("");
    const [escrows, setEscrows] = useState<Record<string,string>[]>([]);
    const [transactions, setTransactions] = useState<ChainTransaction[]>([]);
    const [filter, setFilter] = useState("");
    useEffect(() => {
        void api.get("/deals").then(res => setDeals(res.data.data || [])).catch(() => setError("Could not load projects."));
        if (user?.role !== "ADMIN") return;
        let active = true;
        const load = () => void blockchainService.admin().then(res => {
            if (!active) return;
            setHealth(`${res.rpcHealth} | On-ramp: ${res.providerStatus}`);
            setEscrows(res.escrows); setTransactions(res.transactions || []);
        }).catch(() => { if (active) setError("Could not load network status."); });
        load(); const timer = setInterval(load, 10000);
        return () => { active = false; clearInterval(timer); };
    }, [user?.role]);
    return <DashboardLayout><div className="max-w-5xl space-y-4">
        <h1 className="text-2xl font-semibold">Blockchain Escrow</h1>
        {health && <p className="text-sm text-slate-600">{health.replaceAll("_", " ")}</p>}
        {error && <p role="alert" className="text-red-700">{error}</p>}
        <label className="block text-sm">Project<select className="mt-1 block w-full max-w-lg rounded border border-slate-300 p-2" value={selected} onChange={event => setSelected(event.target.value)}><option value="">Select project</option>{deals.map(deal => <option key={deal.id} value={deal.id}>{deal.title}</option>)}</select></label>
        {selected && <BlockchainEscrow key={selected} dealId={selected}/>}
        {user?.role === "ADMIN" && <>
            <section className="space-y-3 border-t py-4">
                <h2 className="text-lg font-semibold">Recent Escrows</h2>
                <div className="overflow-x-auto"><table className="w-full text-left text-sm"><thead><tr className="border-b"><th className="p-2">Project</th><th className="p-2">State</th><th className="p-2">Funded</th><th className="p-2">Reconciliation</th></tr></thead><tbody>
                    {escrows.map(e => <tr key={e.id} className="border-b"><td className="max-w-64 break-words p-2"><button className="text-left text-blue-700 underline" onClick={() => setSelected(e.deal_id)}>{deals.find(d => d.id === e.deal_id)?.title || e.deal_id}</button></td><td className="p-2">{e.status.replaceAll("_", " ")}</td><td className="p-2">{formatToken(e.funded_atomic, Number(e.token_decimals))} {e.token_symbol}</td><td className="p-2">{e.reconciliation_status.replaceAll("_", " ")}</td></tr>)}
                </tbody></table></div>
                {!escrows.length && <p className="text-sm text-slate-500">No blockchain escrows.</p>}
            </section>
            <section className="space-y-3 border-t py-4">
                <div className="flex flex-wrap items-center justify-between gap-3"><h2 className="text-lg font-semibold">Recent Transactions</h2><select aria-label="Transaction status" value={filter} onChange={e => setFilter(e.target.value)} className="rounded border p-2 text-sm"><option value="">All states</option>{["CREATED","SUBMITTED","PENDING","CONFIRMED","FAILED","REVERTED","REPLACED","PENDING_RECONCILIATION"].map(state => <option key={state} value={state}>{state.replaceAll("_", " ")}</option>)}</select></div>
                <div className="divide-y">{transactions.filter(tx => !filter || tx.status === filter).map(tx => <div key={tx.id} className="min-w-0 py-3 text-sm"><div className="flex flex-wrap justify-between gap-2"><button className="text-blue-700 underline" onClick={() => tx.deal_id && setSelected(tx.deal_id)}>{tx.operation} | {deals.find(d => d.id === tx.deal_id)?.title || tx.deal_id}</button><strong>{tx.status.replaceAll("_", " ")}</strong></div><p className="break-all font-mono text-xs">{tx.tx_hash || "Wallet authorization pending"}</p><p className="text-xs text-slate-500">{tx.confirmations} confirmations{tx.error_code && ` | ${tx.error_code}`}</p></div>)}</div>
                {!transactions.some(tx => !filter || tx.status === filter) && <p className="text-sm text-slate-500">No matching transactions.</p>}
            </section>
        </>}
    </div></DashboardLayout>;
}
