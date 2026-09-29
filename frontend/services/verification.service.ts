import { api } from "@/lib/api";

export interface KycResponse {
    id: string;
    userId: string;
    fullName: string;
    panNumberMasked: string;
    status: "NOT_STARTED" | "CONSENT_REQUIRED" | "IN_PROGRESS" | "PENDING_PROVIDER" | "PENDING_REVIEW" | "VERIFIED" | "FAILED" | "REJECTED" | "EXPIRED";
    provider: string;
    providerReference?: string;
    panStatus: string;
    identityStatus: string;
    nameMatchStatus: string;
    nameMatchScore: number;
    rejectionReason?: string;
    verifiedAt?: string;
    isSandbox: boolean;
}

export interface KybResponse {
    id: string;
    organizationId: string;
    legalName: string;
    tradeName?: string;
    businessPanMasked: string;
    gstin?: string;
    cinOrLlpin?: string;
    udyamNumber?: string;
    status: "NOT_STARTED" | "CONSENT_REQUIRED" | "IN_PROGRESS" | "PENDING_PROVIDER" | "PENDING_REVIEW" | "VERIFIED" | "FAILED" | "REJECTED" | "EXPIRED";
    provider: string;
    providerReference?: string;
    panStatus: string;
    gstStatus: string;
    registrationStatus: string;
    nameMatchStatus: string;
    nameMatchScore: number;
    rejectionReason?: string;
    verifiedAt?: string;
    isSandbox: boolean;
}

export interface FinxCheckItem {
    code: string;
    name: string;
    status: string;
    detail: string;
    required: boolean;
}

export interface FinxOrganizationVerificationResponse {
    organizationId: string;
    legalName: string;
    entityType: string;
    overallStatus: string;
    kybVerified: boolean;
    canActivateDeal: boolean;
    canSignAgreement: boolean;
    canFundRealTransaction: boolean;
    canReceiveRealSettlement: boolean;
    moneyMovementStatus: string;
    checks: FinxCheckItem[];
    badge: string;
    isSandbox: boolean;
}

export interface BankAccountResponse {
    id: string;
    organizationId: string;
    accountHolderName: string;
    accountNumberMasked: string;
    ifscCode: string;
    bankName: string;
    branchName?: string;
    accountType: string;
    isPrimary: boolean;
    verificationStatus: "NOT_STARTED" | "IN_PROGRESS" | "VERIFIED" | "FAILED";
    verificationReference?: string;
    nameMatchScore?: number;
    nameMatchStatus?: string;
    registeredNameReturned?: string;
    verifiedAt?: string;
}

export interface RepresentativeResponse {
    id: string;
    organizationId: string;
    userId: string;
    userName: string;
    userEmail: string;
    relationshipType: string;
    authorityStatus: "PENDING" | "VERIFIED" | "REJECTED" | "REVOKED";
    kycVerified: boolean;
    verifiedAt?: string;
}

export interface VerificationEventDto {
    id: string;
    subjectType: string;
    subjectId: string;
    verificationType: string;
    provider: string;
    oldStatus: string;
    newStatus: string;
    reason: string;
    actorId: string;
    timestamp: string;
}

export interface AdminVerificationSummary {
    pendingKyc: KycResponse[];
    pendingKyb: KybResponse[];
    manualReviews: KybResponse[];
    bankMismatches: BankAccountResponse[];
    recentEvents: VerificationEventDto[];
}

export const verificationService = {
    // Consent
    recordConsent: async (data: { verificationType: string; organizationId?: string; purpose: string; consentVersion?: string }) => {
        const res = await api.post("/v1/kyc/consent", data);
        return res.data?.data || res.data;
    },

    // KYC
    startKyc: async () => {
        const res = await api.post("/v1/kyc/start");
        return res.data?.data || res.data;
    },

    getKycStatus: async (): Promise<KycResponse> => {
        const res = await api.get("/v1/kyc/status");
        return res.data?.data || res.data;
    },

    verifyPan: async (data: { panNumber: string; fullName: string; dateOfBirth?: string }) => {
        const res = await api.post("/v1/kyc/pan/verify", data);
        return res.data?.data || res.data;
    },

    startIdentitySession: async (data: { redirectUrl?: string; callbackUrl?: string }) => {
        const res = await api.post("/v1/kyc/identity/start", data);
        return res.data?.data || res.data;
    },

    getIdentityStatus: async (sessionId: string): Promise<KycResponse> => {
        const res = await api.get(`/v1/kyc/identity/${sessionId}/status`);
        return res.data?.data || res.data;
    },

    // KYB
    startKyb: async (orgId: string): Promise<KybResponse> => {
        const res = await api.post(`/v1/kyb/${orgId}/start`);
        return res.data?.data || res.data;
    },

    getKybStatus: async (orgId: string): Promise<KybResponse> => {
        const res = await api.get(`/v1/kyb/${orgId}/status`);
        return res.data?.data || res.data;
    },

    verifyBusinessPan: async (orgId: string, businessPan: string): Promise<KybResponse> => {
        const res = await api.post(`/v1/kyb/${orgId}/pan/verify`, { businessPan });
        return res.data?.data || res.data;
    },

    verifyGstin: async (orgId: string, gstin: string): Promise<KybResponse> => {
        const res = await api.post(`/v1/kyb/${orgId}/gstin/verify`, { gstin });
        return res.data?.data || res.data;
    },

    verifyRegistration: async (orgId: string, data: { cinOrLlpin?: string; udyamNumber?: string }): Promise<KybResponse> => {
        const res = await api.post(`/v1/kyb/${orgId}/registration/verify`, data);
        return res.data?.data || res.data;
    },

    // Organization Verification
    getOrganizationVerification: async (orgId: string): Promise<FinxOrganizationVerificationResponse> => {
        const res = await api.get(`/v1/organizations/${orgId}/verification`);
        return res.data?.data || res.data;
    },

    // Representatives
    addRepresentative: async (orgId: string, data: { userId: string; relationshipType: string; documentRef?: string; notes?: string }): Promise<RepresentativeResponse> => {
        const res = await api.post(`/v1/organizations/${orgId}/representatives`, data);
        return res.data?.data || res.data;
    },

    getRepresentatives: async (orgId: string): Promise<RepresentativeResponse[]> => {
        const res = await api.get(`/v1/organizations/${orgId}/representatives`);
        return res.data?.data || res.data;
    },

    // Bank Accounts
    addBankAccount: async (orgId: string, data: { accountHolderName: string; accountNumber: string; ifscCode: string; bankName: string; branchName?: string; accountType?: string; isPrimary: boolean }): Promise<BankAccountResponse> => {
        const res = await api.post(`/v1/organizations/${orgId}/bank-accounts`, data);
        return res.data?.data || res.data;
    },

    getBankAccounts: async (orgId: string): Promise<BankAccountResponse[]> => {
        const res = await api.get(`/v1/organizations/${orgId}/bank-accounts`);
        return res.data?.data || res.data;
    },

    verifyBankAccount: async (orgId: string, accountId: string): Promise<BankAccountResponse> => {
        const res = await api.post(`/v1/organizations/${orgId}/bank-accounts/${accountId}/verify`);
        return res.data?.data || res.data;
    },

    // Admin Review
    getAdminVerificationSummary: async (): Promise<AdminVerificationSummary> => {
        const res = await api.get("/v1/admin/verifications");
        return res.data?.data || res.data;
    },

    reviewVerification: async (id: string, action: string, reason: string, notes?: string) => {
        const res = await api.post(`/v1/admin/verifications/${id}/review`, { action, reason, notes });
        return res.data?.data || res.data;
    }
};
