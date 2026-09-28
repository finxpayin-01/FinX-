import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { MyTrustScore } from "@/components/trust/MyTrustScore";

export default function MyTrustPage() {
  return <DashboardLayout><div className="mx-auto max-w-3xl space-y-6"><h1 className="text-2xl font-semibold">Your organization’s trust score</h1><MyTrustScore /></div></DashboardLayout>;
}
