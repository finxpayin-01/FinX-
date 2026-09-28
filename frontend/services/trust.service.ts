import { api } from "@/lib/api";

export type TrustRole = "OVERALL" | "BUYER" | "VENDOR";
export type Factor = "PAYMENT" | "MILESTONE" | "DISPUTE" | "COMPLIANCE" | "EVIDENCE" | "HISTORY" | "NETWORK";
export interface TrustScore {
  organizationId: string; role: TrustRole; overallScore: number; confidenceScore: number;
  trustLevel: string; factors: Record<Factor, { score: number; weight: number; observations: number; effectiveEvidence: number }>;
  positiveSignals: string[]; negativeSignals: string[]; statistics: Record<string, number>;
  explanation: string; warning: string | null; algorithmVersion: string; configVersion: number;
  dataPoints: number; lastCalculatedAt: string | null;
}
export interface HistoryPoint { id: string; score: number; confidence: number; level: string; calculatedAt: string; reason: string; configVersion: number }
export interface Organization { id: string; name: string; dirty: boolean; failures: number; lastCalculatedAt: string | null }
export interface TrustEvent { id: string; eventType: string; role: TrustRole; occurredAt: string; severity: number; source: string; processed: boolean; metadata: Record<string, unknown> }
export interface TrustConfig { id: number; rules: Record<string, unknown>; reason: string; createdAt: string }
export interface Review { id: string; organizationId: string; reason: string; status: string; createdAt: string }
async function get<T>(path: string, params?: Record<string, unknown>): Promise<T> {
  return (await api.get<{ data: T }>(path, { params })).data.data;
}
export const trustService = {
  mine: () => get<{ organizationId: string }>("/trust/mine"),
  score: (id: string, role: TrustRole = "OVERALL") => get<TrustScore>(`/trust/${id}${role === "OVERALL" ? "" : role === "BUYER" ? "/buyer-score" : "/vendor-score"}`),
  history: (id: string, days = 90, role: TrustRole = "OVERALL") => get<HistoryPoint[]>(`/trust/${id}/history`, { days, role }),
  organizations: (query: string, page = 0) => get<Organization[]>("/admin/trust/organizations", { query, page }),
  events: (id: string, page = 0) => get<TrustEvent[]>(`/admin/trust/${id}/events`, { page }),
  config: () => get<TrustConfig>("/admin/trust/config"),
  saveConfig: async (config: TrustConfig, rules: Record<string, unknown>, reason: string) =>
    (await api.put<{ data: TrustConfig }>("/admin/trust/config", { rules, expectedVersion: config.id, reason })).data.data,
  recalculate: async (id: string, reason: string) =>
    (await api.post<{ data: TrustScore }>(`/admin/trust/${id}/recalculate`, { reason })).data.data,
  reviews: () => get<Review[]>("/admin/trust/reviews"),
  review: async (id: string, reason: string) => (await api.post(`/admin/trust/${id}/reviews`, { reason })).data.data,
  resolve: async (id: string, reason: string) => (await api.post(`/admin/trust/reviews/${id}/resolve`, { reason })).data.data,
  calculation: (id: string) => get<Record<string, unknown>>(`/admin/trust/calculations/${id}`),
};

export function trustError(error: unknown): string {
  const e = error as { response?: { status?: number; data?: { message?: string } } };
  if (e.response?.status === 403) return "You do not have access to this organization’s trust details.";
  return e.response?.data?.message || "Trust data could not be loaded. Please try again.";
}
