"use client";

import React, { useEffect, useState, Suspense } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useAuth } from "@/components/auth/AuthProvider";
import { authService } from "@/services/auth.service";
import { ShieldCheck, AlertCircle, RefreshCw } from "lucide-react";
import Link from "next/link";
import { Button } from "@/components/ui/Button";

function GoogleCallbackContent() {
    const router = useRouter();
    const searchParams = useSearchParams();
    const { setSession } = useAuth();

    const [status, setStatus] = useState<"processing" | "error" | "success">("processing");
    const [errorMessage, setErrorMessage] = useState<string | null>(null);

    useEffect(() => {
        const processOAuth = async () => {
            const error = searchParams.get("error");
            const errorDescription = searchParams.get("error_description");

            if (error) {
                setStatus("error");
                setErrorMessage(errorDescription || error || "Google authentication was cancelled or failed.");
                return;
            }

            // 1. Extract tokens from URL hash fragment (secure: #accessToken=...) or searchParams (fallback)
            let accessToken = searchParams.get("accessToken");
            let refreshToken = searchParams.get("refreshToken");
            let role = searchParams.get("role") || "BUYER";
            const code = searchParams.get("code");

            if (typeof window !== "undefined" && window.location.hash) {
                const hash = window.location.hash.startsWith("#") ? window.location.hash.substring(1) : window.location.hash;
                const hashParams = new URLSearchParams(hash);
                if (hashParams.get("accessToken")) {
                    accessToken = hashParams.get("accessToken");
                }
                if (hashParams.get("refreshToken")) {
                    refreshToken = hashParams.get("refreshToken");
                }
                if (hashParams.get("role")) {
                    role = hashParams.get("role")!;
                }
                // Immediately scrub sensitive tokens from the browser URL history and address bar
                window.history.replaceState(null, "", window.location.pathname);
            } else if (accessToken) {
                // If tokens arrived in query params, scrub them immediately
                window.history.replaceState(null, "", window.location.pathname);
            }

            if (accessToken && refreshToken) {
                try {
                    localStorage.setItem("accessToken", accessToken);
                    localStorage.setItem("refreshToken", refreshToken);

                    // Fetch user details from /api/auth/me
                    const user = await authService.getCurrentUser();
                    setSession(accessToken, refreshToken, user);
                    setStatus("success");

                    // Redirect to role dashboard
                    if (user.role === "ADMIN") router.push("/admin");
                    else if (user.role === "FINANCE") router.push("/finance");
                    else if (user.role === "PROJECT_MANAGER") router.push("/project-manager");
                    else if (user.role === "SELLER" || user.role === "VENDOR") router.push("/vendor");
                    else router.push("/corporate");
                } catch (e: any) {
                    setStatus("error");
                    setErrorMessage(e.message || "Failed to load user profile after Google sign-in.");
                }
                return;
            }

            if (code) {
                try {
                    const response = await authService.googleLogin(code);
                    setSession(response.accessToken, response.refreshToken, response.user);
                    setStatus("success");

                    if (response.user.role === "ADMIN") router.push("/admin");
                    else if (response.user.role === "SELLER" || response.user.role === "VENDOR") router.push("/vendor");
                    else router.push("/corporate");
                } catch (e: any) {
                    setStatus("error");
                    setErrorMessage(e.message || "Failed to exchange Google authentication code.");
                }
                return;
            }

            setStatus("error");
            setErrorMessage("No authentication tokens or authorization code received from Google.");
        };

        processOAuth();
    }, [searchParams, router, setSession]);

    return (
        <div className="min-h-screen bg-slate-50 flex items-center justify-center p-6">
            <div className="max-w-md w-full bg-white rounded-2xl p-8 border border-slate-200 shadow-sm text-center">
                <div className="flex justify-center mb-6">
                    <div className="bg-blue-600 p-2 rounded-lg text-white">
                        <ShieldCheck size={28} />
                    </div>
                </div>

                {status === "processing" && (
                    <div className="space-y-4">
                        <RefreshCw size={32} className="animate-spin text-blue-600 mx-auto" />
                        <h2 className="text-xl font-semibold text-slate-900">Verifying Google Account</h2>
                        <p className="text-sm text-slate-500">
                            Connecting with FINX authentication services...
                        </p>
                    </div>
                )}

                {status === "success" && (
                    <div className="space-y-4">
                        <div className="h-10 w-10 rounded-full bg-emerald-100 text-emerald-600 flex items-center justify-center mx-auto">
                            ✓
                        </div>
                        <h2 className="text-xl font-semibold text-slate-900">Sign-in Successful</h2>
                        <p className="text-sm text-slate-500">Redirecting to your workspace...</p>
                    </div>
                )}

                {status === "error" && (
                    <div className="space-y-4">
                        <div className="h-12 w-12 rounded-full bg-red-100 text-red-600 flex items-center justify-center mx-auto">
                            <AlertCircle size={28} />
                        </div>
                        <h2 className="text-xl font-semibold text-slate-900">Google Authentication Failed</h2>
                        <p className="text-sm text-red-600 bg-red-50 p-3 rounded-md border border-red-100">
                            {errorMessage}
                        </p>
                        <div className="pt-4">
                            <Link href="/login">
                                <Button className="w-full">Return to Sign In</Button>
                            </Link>
                        </div>
                    </div>
                )}
            </div>
        </div>
    );
}

export default function GoogleCallbackPage() {
    return (
        <Suspense fallback={
            <div className="min-h-screen bg-slate-50 flex items-center justify-center">
                <RefreshCw size={32} className="animate-spin text-blue-600" />
            </div>
        }>
            <GoogleCallbackContent />
        </Suspense>
    );
}
