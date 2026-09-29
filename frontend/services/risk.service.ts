import { api } from "@/lib/api";

export type RiskLevel = "LOW" | "MODERATE" | "ELEVATED" | "HIGH" | "CRITICAL";
export type ReviewStatus = "OPEN" | "IN_REVIEW" | "CLEARED" | "RESTRICTED" | "ESCALATED";
export type RiskAction = "ALLOW" | "MONITOR" | "VERIFY" | "MANUAL_REVIEW" | "TEMPORARY_HOLD";
export interface Factor { code: string; category: string; observedValue: number; threshold: number; categoryPoints: number; impact: number; change: number; reason: string; evidenceReference: string; occurredAt: string; }
export interface Assessment { id: string; entityId: string; userId: string; organizationId: string | null; userType: "BUYER" | "VENDOR"; totalRiskScore: number; riskLevel: RiskLevel; previousScore: number | null; scoreChange: number; factorScores: Record<string, number>; topRiskFactors: Factor[]; triggeredRules: string[]; reasonCodes: string[]; humanReadableReasons: string[]; calculatedAt: string; ruleVersion: string; reviewRequired: boolean; reviewStatus: ReviewStatus; action: RiskAction; trigger: string; unavailableSignals: string[]; roundingAdjustment: number; }
export interface Business { id: string; userId: string; organizationId: string | null; userType: string; name: string; riskScore: number; riskLevel: RiskLevel; verificationStatus: string; transactionVolume: number; currency: string; calculatedAt: string | null; scoreChange: number; openAlerts: number; }
export interface Page<T> { items: T[]; total: number; page: number; size: number; }
export interface RiskAlert { id: string; subjectId: string; assessmentId: string; severity: RiskLevel; reason: string; createdAt: string; clearedAt: string | null; }
export interface RiskReview { id: string; subjectId: string; assessmentId: string; alertId: string; status: ReviewStatus; action: RiskAction; reviewerId: string | null; reviewedAt: string | null; restrictionUntil: string | null; reason: string | null; notes: string | null; evidenceReferences: string; createdAt: string; version: number; }
export interface RiskEvent { id: string; subjectId: string; eventType: string; sourceKey: string; occurredAt: string; processedAt: string | null; attempts: number; deadLetter: boolean; }
export interface Dashboard { totalMonitoredBusinesses: number; unassessedBusinesses: number; levelCounts: Record<RiskLevel, number>; openAlerts: number; pendingReviews: number; pendingEvents: number; failedEvents: number; recentScoreChanges: Assessment[]; recentHighRiskEvents: RiskEvent[]; }
export interface Rule { code: string; category: string; metric: string; threshold: number; points: number; enabled: boolean; reason: string; }
export interface Policy { weights: Record<string, number>; rules: Rule[]; windowDays: number; velocityHours: number; minimumBaselineProjects: number; highValueAmount: number; reviewHighValuePayouts: boolean; holdCriticalPayouts: boolean; currency: string; }
export interface PolicyRecord { id: string; userType: string; version: number; policy: Policy; updatedAt: string; }
export type EvidenceRow = Record<string, string | number | null>;
export interface Profile { subject: Business; assessment: Assessment | null; history: Page<Assessment>; reviews: RiskReview[]; alerts: RiskAlert[]; events: RiskEvent[]; payments: EvidenceRow[]; legacyPayments: EvidenceRow[]; milestones: EvidenceRow[]; disputes: EvidenceRow[]; verification: EvidenceRow[]; businessVerification: EvidenceRow[]; reviewHistory: EvidenceRow[]; securityEvents?: EvidenceRow[]; bankAccountChanges?: EvidenceRow[]; }
export interface Decision { decision: ReviewStatus; action: RiskAction; reason: string; notes: string; evidenceReferences: string[]; restrictionUntil: string | null; expectedVersion: number; }

const base = "/admin/risk";
async function get<T>(path: string, params?: Record<string, string | number | boolean>) {
    return (await api.get<{ data: T }>(base + path, { params })).data.data;
}
export const riskService = {
    dashboard: () => get<Dashboard>("/dashboard"),
    businesses: (kind: string, params: Record<string, string | number | boolean>) => get<Page<Business>>(`/${kind}`, params),
    profile: (id: string) => get<Profile>(`/${id}`),
    history: (id: string, page = 0) => get<Page<Assessment>>(`/${id}/history`, { page, size: 50 }),
    alerts: (open: boolean, page: number) => get<Page<RiskAlert>>("/alerts", { open, page }),
    reviews: (status: string, page: number) => get<Page<RiskReview>>("/reviews", { ...(status ? { status } : {}), page }),
    rules: () => get<PolicyRecord[]>("/rules"),
    recalculate: async (id: string) => (await api.post<{ data: Assessment }>(`${base}/${id}/recalculate`)).data.data,
    decide: async (id: string, decision: Decision) => (await api.post<{ data: RiskReview }>(`${base}/reviews/${id}/decision`, decision)).data.data,
    updatePolicy: async (record: PolicyRecord, reason: string) => (await api.patch<{ data: PolicyRecord }>(`${base}/rules/${record.id}`, { expectedVersion: record.version, policy: record.policy, reason })).data.data,
};
