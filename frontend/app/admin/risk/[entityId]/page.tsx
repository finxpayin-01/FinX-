"use client";

import { useParams } from "next/navigation";
import { RiskWorkspace } from "@/components/risk/RiskWorkspace";

export default function Page() {
    const { entityId } = useParams<{ entityId: string }>();
    return <RiskWorkspace view="profile" entityId={entityId} />;
}
