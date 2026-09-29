"use client";

import { useCallback, useEffect, useState } from "react";
import { useParams } from "next/navigation";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card, CardContent } from "@/components/ui/Card";
import { MilestoneStatusBadge } from "@/components/ui/MilestoneStatusBadge";
import { Milestone } from "@/types";
import { milestoneService } from "@/services/milestone.service";
import { invoiceService, Invoice } from "@/services/invoice.service";
import Link from "next/link";
import { ArrowLeft, Clock, UploadCloud, Play, Send, FileCheck, FileText, CheckCircle2, AlertTriangle, AlertCircle, RefreshCw, Eye } from "lucide-react";
import { Button } from "@/components/ui/Button";

export default function VendorMilestoneWorkspace() {
    const { projectId, milestoneId } = useParams();
    const [milestone, setMilestone] = useState<Milestone | null>(null);
    const [invoice, setInvoice] = useState<Invoice | null>(null);
    const [isStarting, setIsStarting] = useState(false);
    const [isSubmitting, setIsSubmitting] = useState(false);
    const [isUploadingInvoice, setIsUploadingInvoice] = useState(false);
    const [showUploadModal, setShowUploadModal] = useState(false);

    // Invoice form states
    const [invoiceNumber, setInvoiceNumber] = useState("");
    const [invoiceDate, setInvoiceDate] = useState(new Date().toISOString().split('T')[0]);
    const [invoiceAmount, setInvoiceAmount] = useState("");
    const [identity, setIdentity] = useState({ vendorLegalName: '', vendorPan: '', vendorGstin: '', buyerLegalName: '', buyerGstin: '', bankAccountLast4: '', bankIfsc: '' });
    const [currency, setCurrency] = useState('INR');
    const [selectedFile, setSelectedFile] = useState<File | null>(null);

    const fetchData = useCallback(() => {
        if (milestoneId) {
            milestoneService.getMilestone(milestoneId as string).then(m => {
                setMilestone(m);
                if (m && !invoiceAmount) {
                    setInvoiceAmount(String(m.amount));
                }
            });

            invoiceService.getMilestoneInvoices(milestoneId as string).then(invoices => {
                if (invoices && invoices.length > 0) {
                    setInvoice(invoices[0]);
                } else {
                    setInvoice(null);
                }
            }).catch(() => setInvoice(null));
        }
    }, [milestoneId, invoiceAmount]);

    useEffect(() => {
        fetchData();
    }, [fetchData]);

    const handleStart = async () => {
        setIsStarting(true);
        try {
            await milestoneService.startMilestone(milestoneId as string);
            await fetchData();
        } finally { setIsStarting(false); }
    };

    const handleDeliverableUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
        const file = e.target.files?.[0];
        if (!file) return;
        try {
            await milestoneService.submitDeliverable(milestoneId as string, {
                fileName: file.name,
                fileUrl: `https://storage.finx.local/deliverables/${file.name}`,
                description: `Deliverable document: ${file.name}`
            });
            alert('Deliverable uploaded successfully!');
            await fetchData();
        } catch (err: any) {
            alert('Upload failed: ' + (err.response?.data?.message || err.message));
        }
    };

    const handleInvoiceUpload = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!selectedFile || !invoiceNumber || !invoiceAmount) {
            alert("Please provide an invoice document, number and amount");
            return;
        }

        setIsUploadingInvoice(true);
        try {
            if (!selectedFile || selectedFile.size > 10 * 1024 * 1024) throw new Error('Select a PDF, PNG or JPEG up to 10 MB');
            const fileContentBase64 = await new Promise<string>((resolve, reject) => {
                const reader = new FileReader(); reader.onload = () => resolve(String(reader.result).split(',')[1]);
                reader.onerror = () => reject(new Error('Unable to read document')); reader.readAsDataURL(selectedFile);
            });
            const payload = {
                fileName: selectedFile.name, mimeType: selectedFile.type, fileContentBase64,
                invoiceNumber: invoiceNumber.trim(), invoiceDate,
                totalAmount: invoiceAmount, currency,
                ...(invoice && invoice.status !== 'APPROVED' ? { revisionOf: invoice.id } : {}),
                ...Object.fromEntries(Object.entries(identity).filter(([, value]) => value.trim()).map(([key,value]) => [key,value.trim()]))
            };

            const created = await invoiceService.uploadInvoice(milestoneId as string, payload);
            setInvoice(created);
            setShowUploadModal(false);
            alert("Invoice uploaded and verified against milestone context!");
            await fetchData();
        } catch (err: any) {
            alert("Invoice upload failed: " + (err.response?.data?.message || err.message));
        } finally {
            setIsUploadingInvoice(false);
        }
    };

    const handleSubmit = async () => {
        setIsSubmitting(true);
        try {
            await milestoneService.submitMilestone(milestoneId as string);
            alert('Milestone and invoice submitted to PM for final review!');
            await fetchData();
        } finally { setIsSubmitting(false); }
    };

    if (!milestone) return <DashboardLayout><div className="p-8">Loading workspace...</div></DashboardLayout>;

    return (
        <DashboardLayout>
            <div className="space-y-6">
                <div>
                    <Link href="/vendor" className="text-sm text-blue-600 flex items-center gap-1 mb-4 hover:underline">
                        <ArrowLeft size={16} /> Back to Contracts
                    </Link>
                    <div className="flex items-center justify-between">
                        <div>
                            <h1 className="text-2xl font-bold text-slate-900">{milestone.title}</h1>
                            <p className="text-sm text-slate-500 mt-1">{milestone.description}</p>
                        </div>
                        <MilestoneStatusBadge status={milestone.status} />
                    </div>
                </div>

                {milestone.status === 'PENDING' && (
                    <Card className="bg-blue-50 border-blue-200">
                        <CardContent className="p-6 text-center space-y-4">
                            <p className="text-blue-800 font-medium">This milestone has been funded and is ready for you to begin work.</p>
                            <Button onClick={handleStart} isLoading={isStarting} className="gap-2 bg-blue-600 hover:bg-blue-700">
                                <Play size={16} /> Start Working on Milestone
                            </Button>
                        </CardContent>
                    </Card>
                )}

                {/* INVOICE VERIFICATION SECTION */}
                <Card className="border-slate-200">
                    <CardContent className="p-6">
                        <div className="flex items-center justify-between mb-4 pb-2 border-b border-slate-100">
                            <div className="flex items-center gap-2">
                                <FileText className="text-blue-600" size={20} />
                                <h3 className="font-semibold text-slate-900">Milestone Tax Invoice</h3>
                            </div>
                            {invoice && (
                                <span className={`text-xs px-2.5 py-1 rounded-full font-bold uppercase ${
                                    invoice.status === 'APPROVED' || invoice.status === 'MATCHED' ? 'bg-emerald-100 text-emerald-800' :
                                    invoice.status === 'REVIEW_REQUIRED' || invoice.status === 'PARTIAL_MATCH' ? 'bg-amber-100 text-amber-800' :
                                    invoice.status === 'REJECTED' ? 'bg-red-100 text-red-800' : 'bg-slate-100 text-slate-800'
                                }`}>
                                    {invoice.status}
                                </span>
                            )}
                        </div>

                        {!invoice ? (
                            <div className="text-center py-6 border-2 border-dashed border-slate-200 rounded-lg">
                                <FileText className="mx-auto h-10 w-10 text-slate-400 mb-2" />
                                <p className="text-sm text-slate-700 font-medium">No invoice uploaded yet for this milestone</p>
                                <p className="text-xs text-slate-500 mb-4">Milestone settlement release requires a verified vendor tax invoice.</p>
                                <Button onClick={() => setShowUploadModal(true)} className="bg-blue-600 hover:bg-blue-700 text-white text-sm gap-2">
                                    <UploadCloud size={16} /> Upload Tax Invoice
                                </Button>
                            </div>
                        ) : (
                            <div className="space-y-4">
                                <div className="grid grid-cols-1 md:grid-cols-4 gap-4 p-4 bg-slate-50 rounded-lg border border-slate-200">
                                    <div>
                                        <span className="text-xs text-slate-500 block">Invoice Number</span>
                                        <span className="text-sm font-bold text-slate-900">{invoice.invoiceNumber}</span>
                                    </div>
                                    <div>
                                        <span className="text-xs text-slate-500 block">Invoice Date</span>
                                        <span className="text-sm font-semibold text-slate-800">{invoice.invoiceDate || 'N/A'}</span>
                                    </div>
                                    <div>
                                        <span className="text-xs text-slate-500 block">Billed Amount</span>
                                        <span className="text-sm font-bold text-slate-900">₹{invoice.totalAmount?.toLocaleString()} {invoice.currency}</span>
                                    </div>
                                    <div>
                                        <span className="text-xs text-slate-500 block">Match Score</span>
                                        <div className="flex items-center gap-2 mt-0.5">
                                            <span className={`text-base font-extrabold ${
                                                invoice.matchScore >= 90 ? 'text-emerald-600' :
                                                invoice.matchScore >= 75 ? 'text-amber-600' : 'text-red-600'
                                            }`}>
                                                {invoice.matchScore}/100
                                            </span>
                                            <span className="text-xs text-slate-500">({invoice.matchingStatus})</span>
                                        </div>
                                    </div>
                                </div>

                                {/* Duplicate Warning if any */}
                                {invoice.duplicateConfidence !== 'UNIQUE' && (
                                    <div className="p-3 bg-amber-50 border border-amber-200 rounded-md flex items-start gap-2 text-xs text-amber-800">
                                        <AlertTriangle size={16} className="text-amber-600 shrink-0 mt-0.5" />
                                        <div>
                                            <p className="font-semibold">Duplicate / Reused Document Signal Detected</p>
                                            <p>This invoice contains identifiers or document fingerprints matching an existing submission. A manual review by the Project Manager is required.</p>
                                        </div>
                                    </div>
                                )}

                                {/* Checks summary */}
                                <div className="space-y-2">
                                    <span className="text-xs font-semibold text-slate-700 block">Verification Evidence Checks</span>
                                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 text-xs">
                                        {invoice.checks?.map(c => (
                                            <div key={c.id} className="p-2 border rounded bg-white flex items-center justify-between">
                                                <div className="flex items-center gap-2">
                                                    {c.status === 'PASS' ? (
                                                        <CheckCircle2 size={14} className="text-emerald-500" />
                                                    ) : c.status === 'WARNING' ? (
                                                        <AlertTriangle size={14} className="text-amber-500" />
                                                    ) : (
                                                        <AlertCircle size={14} className="text-red-500" />
                                                    )}
                                                    <span className="text-slate-700 font-medium">{c.checkType.replace(/_/g, ' ')}</span>
                                                </div>
                                                <span className={`text-[10px] font-bold px-1.5 py-0.5 rounded ${
                                                    c.status === 'PASS' ? 'bg-emerald-50 text-emerald-700' :
                                                    c.status === 'WARNING' ? 'bg-amber-50 text-amber-700' : 'bg-red-50 text-red-700'
                                                }`}>
                                                    {c.status}
                                                </span>
                                            </div>
                                        ))}
                                    </div>
                                </div>

                                <div className="flex justify-between items-center pt-2">
                                    <button type="button" onClick={() => invoiceService.download(invoice).catch(() => alert("Document download failed"))}   className="text-xs text-blue-600 hover:underline flex items-center gap-1 font-medium">
                                        <Eye size={14} /> View Attached Invoice Document ({invoice.fileName})
                                    </button>
                                    <Button onClick={() => setShowUploadModal(true)} variant="outline" size="sm" className="text-xs gap-1">
                                        <RefreshCw size={12} /> Replace / Upload Revised Invoice
                                    </Button>
                                </div>
                            </div>
                        )}
                    </CardContent>
                </Card>

                {/* MODAL FOR INVOICE UPLOAD */}
                {showUploadModal && (
                    <div className="fixed inset-0 bg-slate-900/50 flex items-center justify-center p-4 z-50">
                        <div className="bg-white rounded-xl shadow-xl max-w-2xl w-full max-h-[90vh] overflow-y-auto p-6 space-y-4">
                            <h3 className="text-lg font-bold text-slate-900">Upload Milestone Tax Invoice</h3>
                            <p className="text-xs text-slate-600">Enter the fields shown on your invoice. Manual entry is active; FinX compares your declaration with verified business and milestone records. A reviewer must inspect the document.</p>
                            
                            <form onSubmit={handleInvoiceUpload} className="space-y-3">
                                <div className="grid grid-cols-2 gap-3">
                                    {Object.entries(identity).map(([key, value]) => <label key={key} className="text-xs font-semibold text-slate-700">{({ vendorLegalName: 'Vendor legal name', vendorPan: 'Vendor PAN', vendorGstin: 'Vendor GSTIN', buyerLegalName: 'Buyer legal name', buyerGstin: 'Buyer GSTIN', bankAccountLast4: 'Bank account last 4 (optional)', bankIfsc: 'IFSC (optional)' } as Record<string,string>)[key]}
                                        <input aria-label={key} value={value} onChange={e => setIdentity(old => ({ ...old, [key]: e.target.value }))} className="w-full border rounded p-2 mt-1" />
                                    </label>)}
                                    <label className="text-xs font-semibold">Currency<input aria-label="Currency" value={currency} onChange={e => setCurrency(e.target.value.toUpperCase())} maxLength={3} required className="w-full border rounded p-2 mt-1" /></label>
                                </div>
                                <div>
                                    <label className="text-xs font-semibold text-slate-700 block mb-1">Invoice Document (PDF, PNG, JPG)</label>
                                    <input
                                        type="file"
                                        required
                                        aria-label="Invoice document"
                                        accept=".pdf,.png,.jpg,.jpeg"
                                        onChange={e => setSelectedFile(e.target.files?.[0] || null)}
                                        className="w-full text-xs border border-slate-300 rounded p-2"
                                    />
                                </div>

                                <div className="grid grid-cols-2 gap-3">
                                    <div>
                                        <label className="text-xs font-semibold text-slate-700 block mb-1">Invoice Number</label>
                                        <input
                                            type="text"
                                            placeholder="e.g. INV-2026-001"
                                            aria-label="Invoice number" required
                                            value={invoiceNumber}
                                            onChange={e => setInvoiceNumber(e.target.value)}
                                            className="w-full text-xs border border-slate-300 rounded p-2"
                                        />
                                    </div>
                                    <div>
                                        <label className="text-xs font-semibold text-slate-700 block mb-1">Invoice Date</label>
                                        <input
                                            type="date"
                                            aria-label="Invoice date" required value={invoiceDate}
                                            onChange={e => setInvoiceDate(e.target.value)}
                                            className="w-full text-xs border border-slate-300 rounded p-2"
                                        />
                                    </div>
                                </div>

                                <div>
                                    <label className="text-xs font-semibold text-slate-700 block mb-1">Total Amount (₹)</label>
                                    <input
                                        type="number"
                                        step="0.01"
                                        aria-label="Invoice total" required value={invoiceAmount}
                                        onChange={e => setInvoiceAmount(e.target.value)}
                                        className="w-full text-xs border border-slate-300 rounded p-2"
                                        placeholder={`Expected: ₹${milestone.amount}`}
                                    />
                                    <span className="text-[10px] text-slate-500">Contracted escrow milestone amount: ₹{milestone.amount?.toLocaleString()} {milestone.currency}</span>
                                </div>

                                <div className="flex gap-2 justify-end pt-3">
                                    <Button type="button" variant="outline" onClick={() => setShowUploadModal(false)}>Cancel</Button>
                                    <Button type="submit" isLoading={isUploadingInvoice} className="bg-blue-600 hover:bg-blue-700 text-white">
                                        Upload & Verify
                                    </Button>
                                </div>
                            </form>
                        </div>
                    </div>
                )}

                {/* DELIVERABLE UPLOAD SECTION */}
                {['IN_PROGRESS', 'SUBMITTED', 'REJECTED'].includes(milestone.status) && (
                    <Card className="border-indigo-200">
                        <CardContent className="p-6">
                            <h3 className="font-semibold text-slate-900 mb-4">Supporting Work Deliverables</h3>
                            <div className="border-2 border-dashed border-slate-300 rounded-lg p-8 text-center hover:bg-slate-50 transition-colors">
                                <UploadCloud className="mx-auto h-12 w-12 text-slate-400 mb-3" />
                                <p className="text-sm text-slate-600">Drag and drop your deliverable archive here, or click to browse</p>
                                <p className="text-xs text-slate-500 mt-1 mb-4">ZIP, PDF, MP4 up to 500MB</p>
                                <label className="cursor-pointer bg-white border border-slate-300 text-slate-700 px-4 py-2 rounded-md hover:bg-slate-50 text-sm font-medium">
                                    Select File
                                    <input type="file" className="hidden" onChange={handleDeliverableUpload} />
                                </label>
                            </div>
                        </CardContent>
                    </Card>
                )}

                <h2 className="text-lg font-semibold text-slate-900 mt-8 mb-4">Uploaded Assets</h2>
                <Card>
                    <div className="divide-y divide-slate-100">
                        {(!milestone.deliverables || milestone.deliverables.length === 0) && (
                            <div className="p-4 text-center text-slate-500 text-sm">No deliverable files uploaded yet.</div>
                        )}
                        {milestone.deliverables?.map(d => (
                            <div key={d.id} className="p-4 flex items-center justify-between">
                                <div className="flex items-center gap-3">
                                    <FileCheck size={18} className="text-indigo-600" />
                                    <span className="font-medium text-sm text-slate-900">{d.fileName}</span>
                                </div>
                                <span className="text-xs text-slate-500">{new Date(d.submittedAt || d.uploadedAt || Date.now()).toLocaleString()}</span>
                            </div>
                        ))}
                    </div>
                </Card>

                {['IN_PROGRESS', 'SUBMITTED', 'REJECTED'].includes(milestone.status) && (
                    <div className="flex justify-end pt-4">
                        <Button onClick={handleSubmit} isLoading={isSubmitting} className="gap-2 bg-emerald-600 hover:bg-emerald-700">
                            <Send size={16} /> Submit Deliverables & Invoice for Review
                        </Button>
                    </div>
                )}
            </div>
        </DashboardLayout>
    );
}
