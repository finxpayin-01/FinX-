"use client";

import { useEffect, useState } from "react";
import { Invoice, invoiceService } from "@/services/invoice.service";

export function InvoiceEvidencePanel({ milestoneId, milestoneAmount }: { milestoneId: string; milestoneAmount: number }) {
    const [invoices, setInvoices] = useState<Invoice[]>([]);
    const [error, setError] = useState("");
    useEffect(() => { invoiceService.getMilestoneInvoices(milestoneId).then(setInvoices).catch(() => setError("Unable to load invoice verification.")); }, [milestoneId]);
    return <section className="rounded-xl border bg-white p-6 space-y-4" aria-label="Invoice verification">
        <h2 className="text-lg font-semibold">Invoice verification</h2>
        {error && <p role="alert">{error}</p>}
        {!invoices.length && !error && <p className="text-sm text-slate-500">No invoice submitted yet.</p>}
        {invoices.map(invoice => <article key={invoice.id} className="border rounded-lg p-4 space-y-3">
            <div className="flex justify-between"><strong>{invoice.invoiceNumber}</strong><span>{invoice.status.replaceAll("_", " ")}</span></div>
            <p>Invoice: {invoice.currency} {Number(invoice.totalAmount).toLocaleString()} · Milestone: {invoice.currency} {Number(milestoneAmount).toLocaleString()}</p>
            <p>Match score: <strong>{invoice.matchScore} / 100</strong> · {invoice.matchingStatus.replaceAll("_", " ")}</p>
            <p className="text-sm text-slate-500">{invoice.extractionStatus.replaceAll("_", " ")} · Basis: {invoice.comparisonBasis}</p>
            <ul className="space-y-1 text-sm">{invoice.checks.filter(c => c.status === "WARNING" || c.status === "FAIL").map(c => <li key={c.id}>{c.message}</li>)}</ul>
            {invoice.reviews.filter(r => r.reviewedAt).map(r => <p key={r.id} className="text-sm">{r.status.replaceAll("_", " ")}: {r.notes}</p>)}
            <button className="text-blue-700 text-sm underline" onClick={() => invoiceService.download(invoice).catch(() => setError("Document download failed."))}>Download invoice document</button>
        </article>)}
        <p className="text-xs text-slate-500">Invoice verification is separate from milestone approval and bank settlement authorization.</p>
    </section>;
}
