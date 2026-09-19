"use client";

import { useCallback, useEffect, useState } from "react";
import { Wallet, RefreshCw, ArrowUpRight, ShieldCheck, AlertCircle } from "lucide-react";
import { blockchainService as service, BrowserWalletSigner, ChainEscrow, ChainOperation, formatToken } from "@/services/blockchain.service";
import { paymentService } from "@/services/payment.service";
import { milestoneService } from "@/services/milestone.service";
import { useAuth } from "@/components/auth/AuthProvider";
import { Button } from "@/components/ui/Button";
import type { Milestone } from "@/types";

const states = ["Pending", "Funded", "Submitted", "Approved on-chain", "Released", "Disputed", "Refunded"];
function message(error: unknown): string {
    const e = error as { response?: { data?: { message?: string } }; message?: string };
    return e.response?.data?.message || e.message || "Request failed";
}
export function BlockchainEscrow({ dealId }: { dealId: string }) {
    const { user } = useAuth();
    const [data, setData] = useState<ChainEscrow>();
    const [rail, setRail] = useState<string>();
    const [milestones, setMilestones] = useState<Milestone[]>([]);
    const [amounts, setAmounts] = useState<Record<string,string>>({});
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState("");
    const [notice, setNotice] = useState("");
    const [reason, setReason] = useState("");
    const [checkoutUrl, setCheckoutUrl] = useState("");
    const [recovery, setRecovery] = useState<Record<string,string>>({});
    const buyer = user?.role === "BUYER" || user?.role === "CORPORATE";
    const vendor = user?.role === "SELLER" || user?.role === "VENDOR";
    const admin = user?.role === "ADMIN";
    const refresh = useCallback(async () => {
        const config = await paymentService.getPaymentConfig();
        setRail(config.rail || "RAZORPAY");
        if (config.rail !== "BLOCKCHAIN") return;
        const [escrow, schedule] = await Promise.all([service.get(dealId), milestoneService.getProjectMilestones(dealId)]);
        setData(escrow); setMilestones(schedule);
    }, [dealId]);
    useEffect(() => {
        let active = true;
        const load = () => { if (active) void refresh().catch(e => { if (active) setError(message(e)); }); };
        load(); const timer = setInterval(load, 10000);
        return () => { active = false; clearInterval(timer); };
    }, [refresh]);
    const run = async (action: () => Promise<void>) => {
        setBusy(true); setError(""); setNotice("");
        try { await action(); await refresh(); } catch (e) { setError(message(e)); } finally { setBusy(false); }
    };
    const execute = (operation: ChainOperation, milestoneId?: string) => run(async () => {
        const tx = await service.intent(dealId, operation, milestoneId, reason || undefined);
        if (operation.startsWith("RESOLVE_")) {
            setNotice(`Council authorization pending: ${tx.from_address}. Intent ${tx.id}.`);
            return;
        }
        if (tx.tx_hash) { setNotice(`Transaction ${tx.status.toLowerCase()}: ${tx.tx_hash}`); return; }
        const saved = localStorage.getItem(`finx-chain-tx:${tx.id}`);
        const hash = saved || await new BrowserWalletSigner().send(tx);
        localStorage.setItem(`finx-chain-tx:${tx.id}`, hash);
        await service.attach(tx.id, hash);
        setNotice("Transaction submitted. Blockchain confirmation pending.");
    });
    const button = (label: string, operation: ChainOperation, milestone?: string) =>
        <Button key={operation} variant="outline" disabled={busy || !data?.config.configured} onClick={() => execute(operation, milestone)} className="gap-2"><ArrowUpRight size={15}/>{label}</Button>;
    if (rail === "RAZORPAY") return null;
    const e = data?.escrow;
    const symbol = data?.config.symbol || "Token";
    const amount = (value: string) => formatToken(value, data?.config.decimals || 0);
    return <section className="min-w-0 space-y-5 border-t border-slate-200 py-6" aria-label="Blockchain escrow">
        <div className="flex flex-wrap items-center justify-between gap-3">
            <h2 className="flex items-center gap-2 text-xl font-semibold"><ShieldCheck size={22}/>Blockchain Escrow</h2>
            <button aria-label="Refresh escrow" title="Refresh escrow" disabled={busy} onClick={() => run(refresh)} className="p-2"><RefreshCw size={18}/></button>
        </div>
        {error && <p role="alert" className="flex items-start gap-2 break-words text-sm text-red-700"><AlertCircle size={18} className="shrink-0"/>{error}</p>}
        {notice && <p role="status" className="break-all text-sm text-emerald-800">{notice}</p>}
        {!data ? <p className="text-sm text-slate-500">Loading escrow...</p> : <>
            <div className="flex flex-wrap gap-x-6 gap-y-2 text-sm">
                <span>{data.config.network}</span><span>{symbol}</span>
                {data.config.testnet && <strong className="text-amber-800">Test tokens only</strong>}
                <span>{e?.status.replaceAll("_", " ") || "Not created"}</span>
                {e && <span>{e.reconciliation_status.replaceAll("_", " ")}</span>}
            </div>
            {!data.config.configured && <p className="text-sm text-amber-800">Blockchain service is not configured. Funding is unavailable.</p>}
            {(buyer || vendor) && <Button variant="outline" className="gap-2" disabled={busy || !data.config.configured} onClick={() => run(async () => {
                const signer = new BrowserWalletSigner(); const address = await signer.account();
                const challenge = await service.challenge(address);
                if (!challenge.verified && challenge.message) await service.verify(await signer.signMessage(challenge.message));
                setNotice(`Wallet verified: ${address}`);
            })}><Wallet size={16}/>Verify Wallet</Button>}
            {!e && buyer && <div className="space-y-3">
                <h3 className="font-medium">Token settlement amounts</h3>
                <div className="grid gap-3 sm:grid-cols-2">{milestones.map(m => <label key={m.id} className="min-w-0 space-y-1 text-sm">
                    <span className="block break-words">{m.title} ({m.currency} {m.amount} commercial value)</span>
                    <input aria-label={`${m.title} token amount`} inputMode="decimal" placeholder={`Amount in ${symbol}`} value={amounts[m.id] || ""} onChange={event => setAmounts({ ...amounts, [m.id]: event.target.value })} className="w-full rounded border border-slate-300 p-2"/>
                </label>)}</div>
                <Button disabled={busy || !data.config.configured || !milestones.length} onClick={() => run(async () => {
                    await service.create(dealId, milestones.map(m => ({ milestoneId: m.id, amount: amounts[m.id] || "" })));
                    setNotice("Escrow terms created. Operator deployment pending.");
                })}>Create Escrow</Button>
            </div>}
            {e && <>
                <dl className="grid grid-cols-2 gap-x-4 gap-y-3 border-y border-slate-200 py-4 sm:grid-cols-3">
                    {[["Total",e.total_atomic],["Funded",e.funded_atomic],["Locked",(BigInt(e.funded_atomic)-BigInt(e.released_atomic)-BigInt(e.refunded_atomic)).toString()],["Released",e.released_atomic],["Refunded",e.refunded_atomic],["Unfunded",(BigInt(e.total_atomic)-BigInt(e.funded_atomic)).toString()]].map(([label,value]) => <div key={label} className="min-w-0"><dt className="text-xs text-slate-500">{label}</dt><dd className="break-words text-sm font-semibold">{amount(value)} {symbol}</dd></div>)}
                </dl>
                <div className="space-y-1 break-all text-xs text-slate-600"><p>Buyer: {e.buyer_address}</p><p>Vendor: {e.vendor_address}</p><p>Contract: {e.contract_address || "Deployment pending"}</p></div>
                <div className="flex flex-wrap gap-2">
                    {admin && !e.contract_address && button("Deploy Escrow", "CREATE")}
                    {(buyer || vendor) && e.status === "CREATED" && button("Accept Terms", "ACCEPT")}
                    {buyer && e.status === "AWAITING_FUNDING" && <>
                        <Button variant="outline" disabled={busy} onClick={() => run(async () => { const intent = await service.funding(dealId); setNotice(`Funding destination: ${intent.destination_wallet}. ${intent.provider.replaceAll("_", " ")}.`); const url = intent.checkout_url || ""; setCheckoutUrl(url.startsWith("https://buy.onramper.dev/?") ? url : ""); })}>Funding Intent</Button>
                        {button("Authorize Token Amount", "ALLOWANCE")}{button("Fund Escrow", "FUND")}
                    </>}
                    {(buyer || vendor) && ["CREATED", "AWAITING_FUNDING"].includes(e.status) && button("Cancel Escrow", "CANCEL")}
                </div>
                {checkoutUrl && <a href={checkoutUrl} target="_blank" rel="noopener noreferrer" className="text-sm text-blue-700 underline">Open Onramper sandbox (no token settlement)</a>}
                <div className="divide-y divide-slate-200">{data.allocations?.map(a => <div key={a.milestone_id} className="space-y-2 py-3">
                    <div className="flex flex-wrap justify-between gap-2 text-sm"><strong>{milestones.find(m => m.id === a.milestone_id)?.title || a.milestone_id}</strong><span>{amount(a.amount_atomic)} {symbol} · {states[Number(a.chain_status)]}</span></div>
                    <div className="flex flex-wrap gap-2">
                        {vendor && a.chain_status === "1" && button("Submit On-chain", "SUBMIT", a.milestone_id)}
                        {buyer && a.chain_status === "2" && button("Approve On-chain", "APPROVE", a.milestone_id)}
                        {(buyer || vendor) && a.chain_status === "3" && e.status !== "DISPUTED" && button("Release", "RELEASE", a.milestone_id)}
                        {(buyer || vendor) && ["1","2","3"].includes(a.chain_status) && button("Raise Dispute", "DISPUTE", a.milestone_id)}
                        {vendor && ["1","2","3"].includes(a.chain_status) && button("Refund", "REFUND", a.milestone_id)}
                        {admin && a.chain_status === "5" && <>{button("Resolve to Vendor", "RESOLVE_VENDOR", a.milestone_id)}{button("Resolve to Buyer", "RESOLVE_BUYER", a.milestone_id)}</>}
                    </div>
                </div>)}</div>
                {(buyer || vendor || admin) && <label className="block text-sm">Dispute / refund / resolution reason<textarea maxLength={1000} value={reason} onChange={event => setReason(event.target.value)} className="mt-1 block w-full rounded border border-slate-300 p-2"/></label>}
                <h3 className="font-medium">Transactions</h3>
                <div className="space-y-3">{data.transactions?.length === 0 && <p className="text-sm text-slate-500">No transactions yet.</p>}{data.transactions?.map(tx => <div key={tx.id} className="min-w-0 border-b border-slate-200 pb-3 text-sm">
                    <div className="flex flex-wrap justify-between gap-2"><span>{tx.operation}</span><strong>{tx.status.replaceAll("_"," ")}</strong></div>
                    {tx.tx_hash && (data.config.explorer ? <a className="block break-all text-blue-700" target="_blank" rel="noopener noreferrer" href={`${data.config.explorer}/tx/${tx.tx_hash}`}>{tx.tx_hash}</a> : <span className="block break-all font-mono text-xs">{tx.tx_hash}</span>)}
                    <span className="text-xs text-slate-500">{tx.confirmations} confirmations{tx.error_code ? ` · ${tx.error_code}` : ""}</span>
                    {admin && tx.operation.startsWith("RESOLVE_") && <details className="mt-2 text-xs"><summary>Council transaction</summary><p className="break-all">Council: {tx.from_address}</p><p className="break-all">Target: {tx.to_address}</p><p className="break-all font-mono">{tx.calldata}</p></details>}
                    {tx.tx_hash && ["PENDING","SUBMITTED"].includes(tx.status) && tx.actor_id === user?.id && <details className="mt-2"><summary>Replacement transaction</summary><div className="mt-2 flex flex-wrap gap-2"><input aria-label="Replacement transaction hash" placeholder="0x replacement hash" value={recovery[tx.id] || ""} onChange={event => setRecovery({...recovery,[tx.id]:event.target.value})} className="min-w-0 flex-1 rounded border p-2"/><Button variant="outline" disabled={busy} onClick={() => run(async()=>{await service.replace(tx.id,recovery[tx.id] || "");})}>Track Replacement</Button></div></details>}
                    {!tx.tx_hash && tx.actor_id === user?.id && <div className="mt-2 flex flex-wrap gap-2"><input aria-label="Recover submitted transaction hash" placeholder="0x transaction hash" value={recovery[tx.id] || ""} onChange={event => setRecovery({ ...recovery, [tx.id]: event.target.value })} className="min-w-0 flex-1 rounded border p-2"/><Button variant="outline" disabled={busy} onClick={() => run(async () => { await service.attach(tx.id, recovery[tx.id] || ""); setNotice("Transaction attached. Verification pending."); })}>Track Transaction</Button></div>}
                </div>)}</div>
            </>}
        </>}
    </section>;
}
