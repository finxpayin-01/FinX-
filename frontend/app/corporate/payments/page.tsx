"use client";

import { useEffect, useState } from "react";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card } from "@/components/ui/Card";
import { paymentService } from "@/services/payment.service";
import { Payment } from "@/types";
import { ShieldCheck, XCircle, Clock, RefreshCcw, HardHat, RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/Button";

const getPaymentBadge = (status: string) => {
    switch (status) {
        case 'PAYMENT_SUCCESS': return <span className="inline-flex items-center rounded-full bg-emerald-50 px-2 py-1 text-xs font-medium text-emerald-700 ring-1 ring-emerald-600/20"><ShieldCheck size={12} className="mr-1" /> Successful</span>;
        case 'ORDER_CREATED':
        case 'PAYMENT_PENDING':
        case 'PAYMENT_VERIFICATION_PENDING':
            return <span className="inline-flex items-center rounded-full bg-amber-50 px-2 py-1 text-xs font-medium text-amber-700 ring-1 ring-amber-600/20"><Clock size={12} className="mr-1" /> Pending</span>;
        case 'PAYMENT_FAILED':
        case 'CANCELLED':
            return <span className="inline-flex items-center rounded-full bg-red-50 px-2 py-1 text-xs font-medium text-red-700 ring-1 ring-red-600/20"><XCircle size={12} className="mr-1" /> Failed</span>;
        case 'REFUNDED':
            return <span className="inline-flex items-center rounded-full bg-slate-50 px-2 py-1 text-xs font-medium text-slate-700 ring-1 ring-slate-600/20"><RefreshCcw size={12} className="mr-1" /> Refunded</span>;
        default:
            return <span className="inline-flex items-center rounded-full bg-slate-50 px-2 py-1 text-xs font-medium text-slate-700 ring-1 ring-slate-600/20">{status}</span>;
    }
};

export default function BuyerPaymentsHistory() {
    const [payments, setPayments] = useState<Payment[]>([]);
    const [isLoading, setIsLoading] = useState(true);

    const fetchPayments = () => {
        setIsLoading(true);
        paymentService.getBuyerPayments()
            .then(setPayments)
            .finally(() => setIsLoading(false));
    };

    useEffect(() => {
        fetchPayments();
    }, []);

    return (
        <DashboardLayout>
            <div className="space-y-6">
                <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4">
                    <div>
                        <h1 className="text-2xl font-bold text-slate-900">Fiat Payment History</h1>
                        <p className="text-sm text-slate-500 mt-1">Historical Razorpay payment records.</p>
                    </div>
                    <Button variant="outline" size="sm" onClick={fetchPayments} disabled={isLoading} className="gap-2">
                        <RefreshCw size={14} className={isLoading ? "animate-spin" : ""} /> Refresh
                    </Button>
                </div>

                {/* Status Notice */}
                <div className="p-4 bg-emerald-50 border border-emerald-200 rounded-xl flex items-start gap-3">
                    <ShieldCheck size={20} className="text-emerald-600 shrink-0 mt-0.5" />
                    <div>
                        <h4 className="text-sm font-semibold text-emerald-900">Fiat Accounting</h4>
                        <p className="text-xs text-emerald-800 mt-1 leading-relaxed">
                            Fiat records and token settlement balances are separate. These records are not proof of blockchain funding or release.
                        </p>
                    </div>
                </div>

                <Card>
                    <div className="overflow-x-auto">
                        <table className="w-full text-left text-sm">
                            <thead className="bg-slate-50 border-b border-slate-200">
                                <tr>
                                    <th className="p-4 font-semibold text-slate-600">Payment Ref</th>
                                    <th className="p-4 font-semibold text-slate-600">Amount</th>
                                    <th className="p-4 font-semibold text-slate-600">Status</th>
                                    <th className="p-4 font-semibold text-slate-600">Razorpay Order</th>
                                    <th className="p-4 font-semibold text-slate-600">Date Initiated</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-100">
                                {isLoading && <tr><td colSpan={5} className="p-8 text-center text-slate-500">Loading ledgers...</td></tr>}
                                {!isLoading && payments.length === 0 && (
                                    <tr><td colSpan={5} className="p-8 text-center text-slate-500">No payment history found.</td></tr>
                                )}
                                {!isLoading && payments.map(p => (
                                    <tr key={p.id} className="hover:bg-slate-50 transition-colors">
                                        <td className="p-4 font-mono text-xs text-slate-500">{p.id}</td>
                                        <td className="p-4 font-medium text-slate-900">${p.amount.toLocaleString()} {p.currency}</td>
                                        <td className="p-4">{getPaymentBadge(p.status)}</td>
                                        <td className="p-4 font-mono text-xs text-slate-500">{p.razorpayOrderId || 'N/A'}</td>
                                        <td className="p-4 text-slate-600">{new Date(p.createdAt).toLocaleDateString()}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                </Card>
            </div>
        </DashboardLayout>
    );
}
