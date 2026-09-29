"use client";

import { useCallback, useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card, CardContent } from "@/components/ui/Card";
import { MilestoneStatusBadge } from "@/components/ui/MilestoneStatusBadge";
import { milestoneService } from "@/services/milestone.service";
import { invoiceService, Invoice, InvoiceCheck } from "@/services/invoice.service";
import { Milestone } from "@/types";
import { Button } from "@/components/ui/Button";
import Link from "next/link";
import { ArrowLeft, CheckCircle, XCircle, FileCheck, RefreshCw, FileText, AlertTriangle, AlertCircle, CheckCircle2, ShieldCheck, HelpCircle } from "lucide-react";

export default function MilestoneReviewDetail() {
    const { milestoneId } = useParams();
    const router = useRouter();
    const [milestone, setMilestone] = useState<Milestone | null>(null);
    const [invoice, setInvoice] = useState<Invoice | null>(null);
    const [isLoading, setIsLoading] = useState(true);

    const [isRejectModalOpen, setIsRejectModalOpen] = useState(false);
    const [isChangeModalOpen, setIsChangeModalOpen] = useState(false);
    const [isInvoiceRejectModalOpen, setIsInvoiceRejectModalOpen] = useState(false);
    const [isInvoiceInfoModalOpen, setIsInvoiceInfoModalOpen] = useState(false);

    const [reason, setReason] = useState("");
    const [invoiceNotes, setInvoiceNotes] = useState("");
    const [isActionLoading, setIsActionLoading] = useState(false);

    const fetchData = useCallback(() => {
        if (milestoneId) {
            Promise.all([
                milestoneService.getMilestone(milestoneId as string),
                invoiceService.getMilestoneInvoices(milestoneId as string).catch(() => [])
            ]).then(([m, invoices]) => {
                setMilestone(m);
                if (invoices && invoices.length > 0) {
                    setInvoice(invoices[0]);
                } else {
                    setInvoice(null);
                }
            }).finally(() => setIsLoading(false));
        }
    }, [milestoneId]);

    useEffect(() => {
        fetchData();
    }, [fetchData]);

    const handleApproveMilestone = async () => {
        if (invoice && (invoice.status === 'REVIEW_REQUIRED' || invoice.status === 'REJECTED')) {
            alert("Warning: This milestone has an unresolved invoice (" + invoice.status + "). Please approve or resolve the invoice review first.");
            return;
        }
        try {
            await milestoneService.approveMilestone(milestoneId as string);
            alert("Milestone globally authorized! Escrow is now permitted.");
            router.push('/project-manager');
        } catch (err: any) {
            alert("Failed to approve milestone: " + (err.response?.data?.message || err.message));
        }
    };

    const handleRejectMilestone = async () => {
        if (!reason.trim()) {
            alert("Rejection reason is absolutely legally required.");
            return;
        }
        try {
            await milestoneService.rejectMilestone(milestoneId as string, reason);
            alert("Milestone rejected.");
            router.push('/project-manager');
        } catch (err) {
            alert("Failed to reject milestone.");
        }
    };

    const handleRequestMilestoneChanges = async () => {
        if (!reason.trim()) {
            alert("Change request instructions are required.");
            return;
        }
        try {
            await milestoneService.requestChanges(milestoneId as string, reason);
            alert("Changes requested from vendor.");
            router.push('/project-manager');
        } catch (err) {
            alert("Failed to request changes.");
        }
    };

    // Invoice Review Actions
    const handleApproveInvoice = async () => {
        if (!invoice) return;
        const current = invoice.reviews?.some(r => r.status === 'OPEN' || r.status === 'IN_REVIEW') ? invoice : await invoiceService.openReview(invoice.id);
        const review = current.reviews?.find(r => r.status === 'OPEN' || r.status === 'IN_REVIEW');
        if (!review) {
            alert("No pending review record found for this invoice.");
            return;
        }
        setIsActionLoading(true);
        try {
            await invoiceService.submitReviewDecision(invoice.id, review.id, {
                decision: 'APPROVE',
                decisionReason: 'APPROVED_BY_PM',
                notes: 'All milestone deliverables and financial calculations approved by PM'
            });
            alert("Invoice approved successfully!");
            await fetchData();
        } catch (err: any) {
            alert("Invoice approval failed: " + (err.response?.data?.message || err.message));
        } finally {
            setIsActionLoading(false);
        }
    };

    const handleRejectInvoice = async () => {
        if (!invoice) return;
        if (!invoiceNotes.trim()) {
            alert("Please provide specific rejection reasons for the vendor.");
            return;
        }
        const current = invoice.reviews?.some(r => r.status === 'OPEN' || r.status === 'IN_REVIEW') ? invoice : await invoiceService.openReview(invoice.id);
        const review = current.reviews?.find(r => r.status === 'OPEN' || r.status === 'IN_REVIEW');
        if (!review) return;

        setIsActionLoading(true);
        try {
            await invoiceService.submitReviewDecision(invoice.id, review.id, {
                decision: 'REJECT',
                decisionReason: 'REJECTED_DISCREPANCY',
                notes: invoiceNotes.trim()
            });
            alert("Invoice rejected. Vendor notified to submit revision.");
            setIsInvoiceRejectModalOpen(false);
            setInvoiceNotes("");
            await fetchData();
        } catch (err: any) {
            alert("Invoice rejection failed: " + (err.response?.data?.message || err.message));
        } finally {
            setIsActionLoading(false);
        }
    };

    const handleRequestInvoiceInfo = async () => {
        if (!invoice) return;
        if (!invoiceNotes.trim()) {
            alert("Please specify the additional information requested.");
            return;
        }
        const current = invoice.reviews?.some(r => r.status === 'OPEN' || r.status === 'IN_REVIEW') ? invoice : await invoiceService.openReview(invoice.id);
        const review = current.reviews?.find(r => r.status === 'OPEN' || r.status === 'IN_REVIEW');
        if (!review) return;

        setIsActionLoading(true);
        try {
            await invoiceService.submitReviewDecision(invoice.id, review.id, {
                decision: 'REQUEST_INFORMATION',
                decisionReason: 'ADDITIONAL_DOCS_NEEDED',
                notes: invoiceNotes.trim()
            });
            alert("Information request sent to vendor.");
            setIsInvoiceInfoModalOpen(false);
            setInvoiceNotes("");
            await fetchData();
        } catch (err: any) {
            alert("Request failed: " + (err.response?.data?.message || err.message));
        } finally {
            setIsActionLoading(false);
        }
    };

    if (!milestone) return <DashboardLayout><div className="p-8">Loading arbitration data...</div></DashboardLayout>;

    return (
        <DashboardLayout>
            <div className="space-y-6 max-w-4xl mx-auto">
                <div>
                    <Link href="/project-manager" className="text-sm text-blue-600 flex items-center gap-1 mb-4 hover:underline">
                        <ArrowLeft size={16} /> Back to Audit Queue
                    </Link>
                    <div className="flex items-center justify-between">
                        <div>
                            <h1 className="text-2xl font-bold text-slate-900">Arbitration Review & Verification</h1>
                            <p className="text-sm text-slate-500 mt-1">Inspect deliverables and tax invoice compliance against SLA contracts.</p>
                        </div>
                        <MilestoneStatusBadge status={milestone.status} />
                    </div>
                </div>

                <Card>
                    <CardContent className="p-8">
                        <div className="mb-6">
                            <h2 className="text-xl font-bold text-slate-900 mb-2">{milestone.title}</h2>
                            <p className="text-slate-600">{milestone.description}</p>
                            <div className="mt-4 p-4 bg-slate-50 rounded border border-slate-200 flex justify-between">
                                <span className="text-slate-600 font-medium">Contract Escrow Allocation</span>
                                <span className="font-bold text-blue-900">₹{milestone.amount.toLocaleString()} {milestone.currency}</span>
                            </div>
                        </div>

                        {/* INVOICE VERIFICATION PANEL */}
                        <div className="mb-8 border border-slate-200 rounded-lg p-6 bg-slate-50/50">
                            <div className="flex items-center justify-between mb-4">
                                <div className="flex items-center gap-2">
                                    <FileText className="text-indigo-600" size={22} />
                                    <div>
                                        <h3 className="font-bold text-slate-900 text-base">Invoice Verification Engine</h3>
                                        <p className="text-xs text-slate-500">Automated 14-point cross-contract verification against FinX KYB and deal limits.</p>
                                    </div>
                                </div>
                                {invoice && (
                                    <div className="flex items-center gap-3">
                                        <div className="text-right">
                                            <span className="text-[10px] text-slate-500 uppercase tracking-wide block">Match Score</span>
                                            <span className={`text-xl font-black ${
                                                invoice.matchScore >= 90 ? 'text-emerald-600' :
                                                invoice.matchScore >= 75 ? 'text-amber-600' : 'text-red-600'
                                            }`}>
                                                {invoice.matchScore} / 100
                                            </span>
                                        </div>
                                        <span className={`text-xs px-2.5 py-1 rounded-md font-bold uppercase ${
                                            invoice.status === 'APPROVED' || invoice.status === 'MATCHED' ? 'bg-emerald-100 text-emerald-800' :
                                            invoice.status === 'REVIEW_REQUIRED' || invoice.status === 'PARTIAL_MATCH' ? 'bg-amber-100 text-amber-800' :
                                            invoice.status === 'REJECTED' ? 'bg-red-100 text-red-800' : 'bg-slate-100 text-slate-800'
                                        }`}>
                                            {invoice.status}
                                        </span>
                                    </div>
                                )}
                            </div>

                            {!invoice ? (
                                <div className="p-4 bg-white border border-slate-200 rounded text-center text-sm text-slate-600">
                                    <p className="font-medium text-amber-800">No invoice has been submitted by the vendor for this milestone.</p>
                                    <p className="text-xs text-slate-500 mt-1">Milestone settlement release gate requires an approved invoice.</p>
                                </div>
                            ) : (
                                <div className="space-y-4">
                                    {/* Invoice Summary Header */}
                                    <div className="grid grid-cols-2 md:grid-cols-4 gap-3 p-3 bg-white border border-slate-200 rounded text-xs">
                                        <div>
                                            <span className="text-slate-500 block">Invoice #</span>
                                            <span className="font-bold text-slate-900">{invoice.invoiceNumber}</span>
                                        </div>
                                        <div>
                                            <span className="text-slate-500 block">Billed Total</span>
                                            <span className="font-bold text-slate-900">₹{invoice.totalAmount?.toLocaleString()} {invoice.currency}</span>
                                        </div>
                                        <div>
                                            <span className="text-slate-500 block">Issuance Date</span>
                                            <span className="font-semibold text-slate-800">{invoice.invoiceDate || 'N/A'}</span>
                                        </div>
                                        <div>
                                            <span className="text-slate-500 block">Document Vault</span>
                                            <button type="button" onClick={() => invoiceService.download(invoice).catch(() => alert("Document download failed"))}   className="text-blue-600 font-medium hover:underline flex items-center gap-1">
                                                <FileText size={12} /> View File
                                            </button>
                                        </div>
                                    </div>

                                    {/* Duplicate Warning */}
                                    {invoice.duplicateConfidence !== 'UNIQUE' && (
                                        <div className="p-3 bg-red-50 border border-red-200 rounded-md flex items-start gap-2 text-xs text-red-800">
                                            <AlertTriangle size={16} className="text-red-600 shrink-0 mt-0.5" />
                                            <div>
                                                <span className="font-bold">Duplicate Document / Invoice Number Detected: </span>
                                                <span>Confidence: {invoice.duplicateConfidence}. This invoice hash or number exists on another milestone. Detailed review required before approval.</span>
                                            </div>
                                        </div>
                                    )}

                                    {/* 14 Checks Grid */}
                                    <div className="space-y-1.5">
                                        <span className="text-xs font-semibold text-slate-700 block">Explainable Verification Checks</span>
                                        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 text-xs">
                                            {invoice.checks?.map(c => (
                                                <div key={c.id} className="p-2.5 border rounded bg-white flex flex-col justify-between">
                                                    <div className="flex items-center justify-between mb-1">
                                                        <span className="font-bold text-slate-800">{c.checkType.replace(/_/g, ' ')}</span>
                                                        <span className={`text-[10px] font-bold px-1.5 py-0.5 rounded ${
                                                            c.status === 'PASS' ? 'bg-emerald-50 text-emerald-700 border border-emerald-200' :
                                                            c.status === 'WARNING' ? 'bg-amber-50 text-amber-700 border border-amber-200' :
                                                            c.status === 'FAIL' ? 'bg-red-50 text-red-700 border border-red-200' :
                                                            'bg-slate-50 text-slate-600 border border-slate-200'
                                                        }`}>
                                                            {c.status}
                                                        </span>
                                                    </div>
                                                    <p className="text-[11px] text-slate-600">{c.message}</p>
                                                    {c.details && <span className="text-[10px] text-slate-400 mt-1 italic">{c.details}</span>}
                                                </div>
                                            ))}
                                        </div>
                                    </div>

                                    {/* PM Invoice Actions */}
                                    {invoice.status !== 'APPROVED' && invoice.status !== 'REJECTED' && (
                                        <div className="flex gap-2 pt-3 border-t border-slate-200">
                                            <Button
                                                onClick={handleApproveInvoice}
                                                isLoading={isActionLoading}
                                                className="bg-emerald-600 hover:bg-emerald-700 text-white text-xs h-9 gap-1 flex-1"
                                            >
                                                <CheckCircle size={14} /> Approve Invoice
                                            </Button>
                                            <Button
                                                onClick={() => setIsInvoiceInfoModalOpen(true)}
                                                variant="outline"
                                                className="border-amber-300 text-amber-700 hover:bg-amber-50 text-xs h-9 gap-1 flex-1"
                                            >
                                                <HelpCircle size={14} /> Request Information
                                            </Button>
                                            <Button
                                                onClick={() => setIsInvoiceRejectModalOpen(true)}
                                                className="bg-red-600 hover:bg-red-700 text-white text-xs h-9 gap-1 flex-1"
                                            >
                                                <XCircle size={14} /> Reject Invoice
                                            </Button>
                                        </div>
                                    )}
                                </div>
                            )}
                        </div>

                        {/* SUBMITTED DELIVERABLES */}
                        <h3 className="font-semibold text-slate-900 mb-4 border-b pb-2">Submitted S3 Deliverable Assets</h3>
                        {(!milestone.deliverables || milestone.deliverables.length === 0) ? (
                            <p className="text-slate-500 italic mb-8">No files were legally attached to this submission payload.</p>
                        ) : (
                            <div className="space-y-3 mb-8">
                                {milestone.deliverables.map(d => (
                                    <div key={d.id} className="p-4 border border-slate-200 rounded flex justify-between items-center text-sm">
                                        <div className="flex items-center gap-3 text-slate-700">
                                            <FileCheck size={18} className="text-indigo-600" />
                                            <span className="font-medium">{d.fileName}</span>
                                        </div>
                                        <a href={d.fileUrl} target="_blank" className="font-medium text-blue-600 hover:text-blue-800 underline">
                                            Vault Source
                                        </a>
                                    </div>
                                ))}
                            </div>
                        )}

                        {/* MILESTONE APPROVAL BAR */}
                        {milestone.status === 'SUBMITTED' ? (
                            <div className="flex gap-4 pt-4 border-t border-slate-200">
                                <Button onClick={handleApproveMilestone} className="flex-1 bg-emerald-600 hover:bg-emerald-700 h-12 text-white gap-2">
                                    <CheckCircle size={20} /> Authorize Deliverables
                                </Button>
                                <Button onClick={() => setIsChangeModalOpen(true)} variant="outline" className="flex-1 border-amber-200 bg-amber-50 text-amber-700 hover:bg-amber-100 h-12 gap-2">
                                    <RefreshCw size={20} /> Request Changes
                                </Button>
                                <Button onClick={() => setIsRejectModalOpen(true)} className="flex-1 bg-red-600 hover:bg-red-700 text-white h-12 gap-2">
                                    <XCircle size={20} /> Force Reject
                                </Button>
                            </div>
                        ) : (
                            <div className="mt-6 p-4 bg-slate-50 border border-slate-200 rounded text-center">
                                <p className="text-slate-600">This milestone has already transacted past the PM arbitration layer.</p>
                            </div>
                        )}
                    </CardContent>
                </Card>

                {/* MODAL: Request Deliverable Changes */}
                {isChangeModalOpen && (
                    <div className="fixed inset-0 bg-slate-900/50 flex items-center justify-center p-4 z-50">
                        <div className="bg-white rounded-xl shadow-xl max-w-md w-full overflow-hidden p-6">
                            <h3 className="text-lg font-bold text-slate-900 mb-2">Request Revisions</h3>
                            <p className="text-sm text-slate-600 mb-4">The vendor will be notified to revise their upload.</p>
                            <textarea
                                className="w-full border border-slate-300 rounded p-3 min-h-[100px] mb-4 text-sm bg-white text-slate-900 placeholder:text-slate-400 focus:ring-blue-500 focus:border-blue-500"
                                placeholder="Enter specific required changes..."
                                value={reason}
                                onChange={e => setReason(e.target.value)}
                            ></textarea>
                            <div className="flex gap-3">
                                <Button onClick={() => setIsChangeModalOpen(false)} variant="outline" className="flex-1">Cancel</Button>
                                <Button onClick={handleRequestMilestoneChanges} className="flex-1 bg-amber-600 text-white">Send Request</Button>
                            </div>
                        </div>
                    </div>
                )}

                {/* MODAL: Reject Milestone */}
                {isRejectModalOpen && (
                    <div className="fixed inset-0 bg-slate-900/50 flex items-center justify-center p-4 z-50">
                        <div className="bg-white rounded-xl shadow-xl max-w-md w-full overflow-hidden p-6">
                            <h3 className="text-lg font-bold text-red-600 mb-2">Final Rejection</h3>
                            <p className="text-sm text-slate-600 mb-4">This terminates the current delivery trajectory. Legal comments required.</p>
                            <textarea
                                className="w-full border border-slate-300 rounded p-3 min-h-[100px] mb-4 text-sm bg-white text-slate-900 placeholder:text-slate-400 focus:ring-blue-500 focus:border-blue-500"
                                placeholder="State explicit rejection reasons..."
                                value={reason}
                                onChange={e => setReason(e.target.value)}
                            ></textarea>
                            <div className="flex gap-3">
                                <Button onClick={() => setIsRejectModalOpen(false)} variant="outline" className="flex-1">Cancel</Button>
                                <Button onClick={handleRejectMilestone} className="flex-1 bg-red-600 text-white">Commit Rejection</Button>
                            </div>
                        </div>
                    </div>
                )}

                {/* MODAL: Request Invoice Info */}
                {isInvoiceInfoModalOpen && (
                    <div className="fixed inset-0 bg-slate-900/50 flex items-center justify-center p-4 z-50">
                        <div className="bg-white rounded-xl shadow-xl max-w-md w-full overflow-hidden p-6">
                            <h3 className="text-lg font-bold text-slate-900 mb-2">Request Invoice Information</h3>
                            <p className="text-xs text-slate-600 mb-4">Specify details required from the vendor to resolve the invoice discrepancy.</p>
                            <textarea
                                className="w-full border border-slate-300 rounded p-3 min-h-[100px] mb-4 text-sm"
                                placeholder="e.g. Please clarify line item tax breakdown or purchase order reference..."
                                value={invoiceNotes}
                                onChange={e => setInvoiceNotes(e.target.value)}
                            ></textarea>
                            <div className="flex gap-3">
                                <Button onClick={() => setIsInvoiceInfoModalOpen(false)} variant="outline" className="flex-1">Cancel</Button>
                                <Button onClick={handleRequestInvoiceInfo} className="flex-1 bg-amber-600 text-white">Send Request</Button>
                            </div>
                        </div>
                    </div>
                )}

                {/* MODAL: Reject Invoice */}
                {isInvoiceRejectModalOpen && (
                    <div className="fixed inset-0 bg-slate-900/50 flex items-center justify-center p-4 z-50">
                        <div className="bg-white rounded-xl shadow-xl max-w-md w-full overflow-hidden p-6">
                            <h3 className="text-lg font-bold text-red-600 mb-2">Reject Invoice</h3>
                            <p className="text-xs text-slate-600 mb-4">State the discrepancy that prevents accepting this invoice.</p>
                            <textarea
                                className="w-full border border-slate-300 rounded p-3 min-h-[100px] mb-4 text-sm"
                                placeholder="e.g. Invoice amount differs from contracted milestone escrow amount..."
                                value={invoiceNotes}
                                onChange={e => setInvoiceNotes(e.target.value)}
                            ></textarea>
                            <div className="flex gap-3">
                                <Button onClick={() => setIsInvoiceRejectModalOpen(false)} variant="outline" className="flex-1">Cancel</Button>
                                <Button onClick={handleRejectInvoice} className="flex-1 bg-red-600 text-white">Reject Invoice</Button>
                            </div>
                        </div>
                    </div>
                )}
            </div>
        </DashboardLayout>
    );
}
