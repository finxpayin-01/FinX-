"use client";

import { useEffect, useState, ReactNode } from "react";
import { paymentService } from "@/services/payment.service";
import BlockchainDashboard from "./BlockchainDashboard";
import { DashboardLayout } from "@/components/layout/DashboardLayout";

export function EscrowRailView({ children }: { children: ReactNode }) {
    const [rail, setRail] = useState("");
    useEffect(() => { void paymentService.getPaymentConfig().then(c => setRail(c.rail || "RAZORPAY")).catch(() => setRail("ERROR")); }, []);
    if (rail === "BLOCKCHAIN") return <BlockchainDashboard/>;
    if (rail === "RAZORPAY") return children;
    return <DashboardLayout><p role="status">{rail === "ERROR" ? "Payment configuration is unavailable." : "Loading escrow..."}</p></DashboardLayout>;
}
