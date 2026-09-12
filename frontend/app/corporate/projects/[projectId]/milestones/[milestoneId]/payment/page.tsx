"use client";

import { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card, CardContent } from "@/components/ui/Card";
import { milestoneService } from "@/services/milestone.service";
import { paymentService, CreatePaymentOrderResponse, PaymentConfig } from "@/services/payment.service";
import { Milestone } from "@/types";
import { Button } from "@/components/ui/Button";
import { ArrowLeft, Wallet, ShieldCheck, CheckCircle2, AlertCircle, Loader2 } from "lucide-react";
import { useAuth } from "@/components/auth/AuthProvider";
import { loadRazorpay } from "@/lib/razorpay";

export default function FundMilestoneCheckout() {
    const { projectId, milestoneId } = useParams();
    const router = useRouter();
    const { user } = useAuth();

    const [milestone, setMilestone] = useState<Milestone | null>(null);
    const [paymentConfig, setPaymentConfig] = useState<PaymentConfig | null>(null);
    const [isLoading, setIsLoading] = useState(true);
    const [isProcessing, setIsProcessing] = useState(false);
    const [isVerifying, setIsVerifying] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [success, setSuccess] = useState(false);

    useEffect(() => {
        paymentService.getPaymentConfig()
            .then(setPaymentConfig)
            .catch((err) => {
                console.warn("Could not retrieve payment config:", err);
            });
    }, []);

    useEffect(() => {
        if (milestoneId) {
            setIsLoading(true);
            milestoneService.getMilestone(milestoneId as string)
                .then(setMilestone)
                .catch((err) => {
                    setError("Failed to load milestone details: " + (err.response?.data?.message || err.message));
                })
                .finally(() => setIsLoading(false));
        }
    }, [milestoneId]);

    const handlePayAndFund = async () => {
        if (!milestone) return;
        setError(null);
        setIsProcessing(true);

        try {
            // 1. Create payment order on backend
            const order: CreatePaymentOrderResponse = await paymentService.createPaymentOrder(
                projectId as string,
                milestoneId as string
            );

            // 2. Load Razorpay script
            const razorpayLoaded = await loadRazorpay();

            if (razorpayLoaded && typeof window !== 'undefined' && (window as any).Razorpay) {
                // 3. Open real Razorpay checkout
                const options = {
                    key: order.keyId,
                    amount: Math.round(Number(order.amount) * 100),
                    currency: order.currency || "INR",
                    name: "FINX Escrow Platform",
                    description: `Escrow funding for ${order.milestoneTitle || milestone.title}`,
                    order_id: order.orderId,
                    handler: async function (response: any) {
                        setIsVerifying(true);
                        try {
                            if (!response.razorpay_order_id || !response.razorpay_payment_id || !response.razorpay_signature) {
                                throw new Error("Razorpay did not return the payment verification details.");
                            }

                            await paymentService.verifyPayment({
                                paymentId: order.paymentId,
                                razorpayOrderId: response.razorpay_order_id,
                                razorpayPaymentId: response.razorpay_payment_id,
                                razorpaySignature: response.razorpay_signature
                            });
                            setSuccess(true);
                            setTimeout(() => {
                                router.push(`/corporate/projects/${projectId}`);
                            }, 2000);
                        } catch (verifyErr: any) {
                            setError("Payment verification error: " + (verifyErr.response?.data?.message || verifyErr.message));
                        } finally {
                            setIsVerifying(false);
                            setIsProcessing(false);
                        }
                    },
                    prefill: {
                        name: user?.name || user?.fullName || "Corporate Buyer",
                        email: user?.email || ""
                    },
                    theme: {
                        color: "#0f172a"
                    },
                    modal: {
                        ondismiss: function () {
                            setIsProcessing(false);
                        }
                    }
                };

                const rzp = new (window as any).Razorpay(options);
                rzp.on('payment.failed', function (resp: any) {
                    setError(`Payment failed: ${resp.error?.description || 'Transaction declined'}`);
                    setIsProcessing(false);
                });
                rzp.open();
            } else {
                setError("Razorpay Checkout could not be loaded. No payment was verified or credited to escrow.");
                setIsProcessing(false);
            }
        } catch (err: any) {
            setError(err.response?.data?.message || err.message || "Failed to initialize payment order");
            setIsProcessing(false);
            setIsVerifying(false);
        }
    };

    if (isLoading) {
        return (
            <DashboardLayout>
                <div className="flex items-center justify-center p-12 text-slate-500">
                    <Loader2 className="animate-spin mr-2" size={20} /> Loading milestone escrow details...
                </div>
            </DashboardLayout>
        );
    }

    return (
        <DashboardLayout>
            <div className="space-y-6 max-w-2xl mx-auto">
                <Button variant="ghost" onClick={() => router.back()} className="gap-2 -ml-3 text-slate-500 hover:text-slate-900">
                    <ArrowLeft size={16} /> Back to Project
                </Button>

                <div>
                    <div className="flex items-center gap-3">
                        <h1 className="text-2xl font-bold text-slate-900">
                            {paymentConfig?.sandboxMode || paymentConfig?.mode === "TEST"
                                ? "FINX Escrow Funding — Test Mode"
                                : "Milestone Escrow Funding"}
                        </h1>
                        {(paymentConfig?.sandboxMode || paymentConfig?.mode === "TEST") && (
                            <span className="px-2.5 py-0.5 rounded-full text-xs font-semibold bg-amber-100 text-amber-800 border border-amber-200">
                                Sandbox / Test Mode
                            </span>
                        )}
                    </div>
                    <p className="text-sm text-slate-500 mt-1">
                        {paymentConfig?.sandboxMode || paymentConfig?.mode === "TEST"
                            ? "Simulated escrow funding in Razorpay Test Sandbox. Real funds are not moved."
                            : "Lock fiat funds into FINX escrow for milestone completion."}
                    </p>
                </div>

                {/* Security / Mode Trust Notice */}
                {paymentConfig && !paymentConfig.configured ? (
                    <div className="p-4 bg-amber-50 border border-amber-200 rounded-xl flex items-start gap-3">
                        <AlertCircle size={20} className="text-amber-600 shrink-0 mt-0.5" />
                        <div>
                            <h4 className="text-sm font-semibold text-amber-900">Razorpay is not configured</h4>
                            <p className="text-xs text-amber-800 mt-1 leading-relaxed">
                                Payments cannot be initiated or verified because Razorpay credentials (RAZORPAY_KEY_ID / RAZORPAY_KEY_SECRET) are missing on the backend environment.
                            </p>
                        </div>
                    </div>
                ) : (
                    <div className="p-4 bg-emerald-50 border border-emerald-200 rounded-xl flex items-start gap-3">
                        <ShieldCheck size={20} className="text-emerald-600 shrink-0 mt-0.5" />
                        <div>
                            <h4 className="text-sm font-semibold text-emerald-900">
                                {paymentConfig?.sandboxMode || paymentConfig?.mode === "TEST"
                                    ? "FINX Escrow Funding — Test Mode"
                                    : "FINX Cryptographic Escrow Vault"}
                            </h4>
                            <p className="text-xs text-emerald-800 mt-1 leading-relaxed">
                                {paymentConfig?.sandboxMode || paymentConfig?.mode === "TEST"
                                    ? "This payment is processed in Razorpay Test Mode. Escrow and ledger balances are tracked in the FINX platform sandbox."
                                    : "Your payment is deposited into an immutable escrow account. Funds are safeguarded and released to the vendor only after you inspect and approve the completed milestone deliverables."}
                            </p>
                        </div>
                    </div>
                )}

                {error && (
                    <div className="p-4 bg-red-50 border border-red-200 rounded-xl flex items-center gap-3 text-red-700 text-sm">
                        <AlertCircle size={18} className="shrink-0" />
                        <span>{error}</span>
                    </div>
                )}

                {success && (
                    <div className="p-4 bg-emerald-50 border border-emerald-300 rounded-xl flex items-center gap-3 text-emerald-800 text-sm">
                        <CheckCircle2 size={20} className="text-emerald-600 shrink-0" />
                        <span className="font-semibold">Escrow Funded Successfully! Redirecting to project...</span>
                    </div>
                )}

                <Card>
                    <CardContent className="p-6 space-y-6">
                        <div className="flex items-center justify-between pb-4 border-b border-slate-100">
                            <div>
                                <h3 className="font-semibold text-slate-900">{milestone?.title || `Milestone ${milestoneId}`}</h3>
                                <p className="text-xs text-slate-500 mt-0.5">Project: {projectId}</p>
                            </div>
                            <div className="text-right">
                                <span className="text-2xl font-bold text-slate-900">
                                    ₹{milestone?.amount?.toLocaleString() || "0"}
                                </span>
                                <span className="text-xs text-slate-500 ml-1">{milestone?.currency || "INR"}</span>
                            </div>
                        </div>

                        <div className="space-y-3 text-sm text-slate-600">
                            <div className="flex justify-between">
                                <span>Corporate Account</span>
                                <span className="font-medium text-slate-900">{user?.name || user?.fullName || "Buyer"}</span>
                            </div>
                            <div className="flex justify-between">
                                <span>Platform Escrow Fee (0%)</span>
                                <span className="font-medium text-slate-900">₹0.00</span>
                            </div>
                            <div className="flex justify-between font-semibold text-slate-900 pt-3 border-t border-slate-100">
                                <span>Total Deposit Due</span>
                                <span>₹{milestone?.amount?.toLocaleString() || "0"} {milestone?.currency || "INR"}</span>
                            </div>
                        </div>

                        <div className="pt-2">
                            <Button
                                onClick={handlePayAndFund}
                                disabled={isProcessing || isVerifying || success || (paymentConfig !== null && !paymentConfig.configured)}
                                isLoading={isProcessing || isVerifying}
                                className="w-full gap-2 bg-slate-900 hover:bg-slate-800 text-white py-3 text-base shadow-sm"
                            >
                                <Wallet size={18} />
                                {isVerifying
                                    ? "Verifying Payment & Crediting Escrow..."
                                    : isProcessing
                                        ? "Connecting to Razorpay..."
                                        : paymentConfig !== null && !paymentConfig.configured
                                            ? "Gateway Not Configured"
                                            : `Pay ₹${milestone?.amount?.toLocaleString() || "0"} & Fund Escrow`}
                            </Button>
                        </div>
                    </CardContent>
                </Card>
            </div>
        </DashboardLayout>
    );
}
