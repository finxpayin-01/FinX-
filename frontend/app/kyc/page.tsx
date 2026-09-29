"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card, CardContent } from "@/components/ui/Card";
import { Button } from "@/components/ui/Button";
import { ShieldCheck, ArrowRight } from "lucide-react";

export default function KycPage() {
    const router = useRouter();

    useEffect(() => {
        router.replace("/onboarding");
    }, [router]);

    return (
        <DashboardLayout>
            <div className="max-w-xl mx-auto py-12">
                <Card>
                    <CardContent className="p-8 text-center space-y-4">
                        <div className="mx-auto w-12 h-12 bg-blue-100 text-blue-600 rounded-full flex items-center justify-center">
                            <ShieldCheck size={28} />
                        </div>
                        <h2 className="text-xl font-bold text-slate-900">Redirecting to FinX Verification Portal...</h2>
                        <p className="text-sm text-slate-500">
                            Centralized onboarding for individual KYC and business KYB.
                        </p>
                        <Button onClick={() => router.push("/onboarding")} className="mt-2">
                            Go to Onboarding Now <ArrowRight size={16} className="ml-1" />
                        </Button>
                    </CardContent>
                </Card>
            </div>
        </DashboardLayout>
    );
}
