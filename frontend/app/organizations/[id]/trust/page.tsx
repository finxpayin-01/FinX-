import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { TrustDetails } from "@/components/trust/TrustDetails";

export default async function OrganizationTrustPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <DashboardLayout><TrustDetails organizationId={id} /></DashboardLayout>;
}
