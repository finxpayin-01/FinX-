import { api } from "@/lib/api";
import {
    Organization,
    CreateOrgRequest,
    OrgMemberResponse,
    AddBankAccountRequest,
    OrgBankAccountResponse,
    SubmitKybRequest,
    KybProfileResponse,
    SubmitKycRequest,
    KycProfileResponse,
    DealAgreementResponse,
    SignAgreementRequest,
    PaymentInstructionResponse,
    GeneratePaymentInstructionRequest,
    SettlementResponse,
    PayoutAuthorizationRequest,
    ReconciliationRunResponse,
    ReconciliationItemResponse,
    RiskAssessmentResponse,
} from "@/types/b2b";
import { ApiResponse } from "@/types";

export const b2bService = {
    // Organizations
    createOrganization: async (payload: CreateOrgRequest): Promise<Organization> => {
        const res = await api.post<ApiResponse<Organization>>("/api/v1/organizations", payload);
        return res.data.data;
    },

    getMyOrganizations: async (): Promise<Organization[]> => {
        const res = await api.get<ApiResponse<Organization[]>>("/api/v1/organizations/me");
        return res.data.data;
    },

    getOrganizationById: async (id: string): Promise<Organization> => {
        const res = await api.get<ApiResponse<Organization>>(`/api/v1/organizations/${id}`);
        return res.data.data;
    },

    addOrgMember: async (orgId: string, email: string, role: string): Promise<OrgMemberResponse> => {
        const res = await api.post<ApiResponse<OrgMemberResponse>>(`/api/v1/organizations/${orgId}/members`, { email, role });
        return res.data.data;
    },

    listOrgMembers: async (orgId: string): Promise<OrgMemberResponse[]> => {
        const res = await api.get<ApiResponse<OrgMemberResponse[]>>(`/api/v1/organizations/${orgId}/members`);
        return res.data.data;
    },

    // Bank Accounts
    addBankAccount: async (orgId: string, payload: AddBankAccountRequest): Promise<OrgBankAccountResponse> => {
        const res = await api.post<ApiResponse<OrgBankAccountResponse>>(`/api/v1/organizations/${orgId}/bank-accounts`, payload);
        return res.data.data;
    },

    listBankAccounts: async (orgId: string): Promise<OrgBankAccountResponse[]> => {
        const res = await api.get<ApiResponse<OrgBankAccountResponse[]>>(`/api/v1/organizations/${orgId}/bank-accounts`);
        return res.data.data;
    },

    verifyBankAccountPennyDrop: async (bankAccountId: string): Promise<OrgBankAccountResponse> => {
        const res = await api.post<ApiResponse<OrgBankAccountResponse>>(`/api/v1/bank-accounts/${bankAccountId}/verify-penny-drop`, {});
        return res.data.data;
    },

    // KYB
    submitKyb: async (orgId: string, payload: SubmitKybRequest): Promise<KybProfileResponse> => {
        const res = await api.post<ApiResponse<KybProfileResponse>>(`/api/v1/kyb/${orgId}`, payload);
        return res.data.data;
    },

    getKybProfile: async (orgId: string): Promise<KybProfileResponse> => {
        const res = await api.get<ApiResponse<KybProfileResponse>>(`/api/v1/kyb/${orgId}`);
        return res.data.data;
    },

    // KYC
    submitKyc: async (payload: SubmitKycRequest): Promise<KycProfileResponse> => {
        const res = await api.post<ApiResponse<KycProfileResponse>>("/api/v1/kyc", payload);
        return res.data.data;
    },

    getKycProfile: async (): Promise<KycProfileResponse> => {
        const res = await api.get<ApiResponse<KycProfileResponse>>("/api/v1/kyc");
        return res.data.data;
    },

    // Deal Agreements
    getDealAgreement: async (dealId: string): Promise<DealAgreementResponse> => {
        const res = await api.get<ApiResponse<DealAgreementResponse>>(`/api/v1/agreements/deals/${dealId}`);
        return res.data.data;
    },

    signAgreement: async (dealId: string, payload: SignAgreementRequest): Promise<DealAgreementResponse> => {
        const res = await api.post<ApiResponse<DealAgreementResponse>>(`/api/v1/agreements/deals/${dealId}/sign`, payload);
        return res.data.data;
    },

    // Bank Payment Orchestration
    generatePaymentInstruction: async (payload: GeneratePaymentInstructionRequest): Promise<PaymentInstructionResponse> => {
        const res = await api.post<ApiResponse<PaymentInstructionResponse>>("/api/v1/payments/instructions", payload);
        return res.data.data;
    },

    getPaymentInstruction: async (instructionId: string): Promise<PaymentInstructionResponse> => {
        const res = await api.get<ApiResponse<PaymentInstructionResponse>>(`/api/v1/payments/instructions/${instructionId}`);
        return res.data.data;
    },

    simulateBankPaymentConfirmation: async (instructionId: string): Promise<PaymentInstructionResponse> => {
        const res = await api.post<ApiResponse<PaymentInstructionResponse>>(`/api/v1/payments/instructions/${instructionId}/simulate-confirmation`, {});
        return res.data.data;
    },

    // Settlements & Payout Authorization
    authorizeSettlement: async (payload: PayoutAuthorizationRequest): Promise<SettlementResponse> => {
        const res = await api.post<ApiResponse<SettlementResponse>>("/api/v1/settlements/authorize", payload);
        return res.data.data;
    },

    getSettlementById: async (settlementId: string): Promise<SettlementResponse> => {
        const res = await api.get<ApiResponse<SettlementResponse>>(`/api/v1/settlements/${settlementId}`);
        return res.data.data;
    },

    getMilestoneSettlement: async (milestoneId: string): Promise<SettlementResponse> => {
        const res = await api.get<ApiResponse<SettlementResponse>>(`/api/v1/settlements/milestones/${milestoneId}`);
        return res.data.data;
    },

    // Reconciliation
    triggerReconciliation: async (): Promise<ReconciliationRunResponse> => {
        const res = await api.post<ApiResponse<ReconciliationRunResponse>>("/api/v1/reconciliation/trigger", {});
        return res.data.data;
    },

    listReconciliationRuns: async (): Promise<ReconciliationRunResponse[]> => {
        const res = await api.get<ApiResponse<ReconciliationRunResponse[]>>("/api/v1/reconciliation/runs");
        return res.data.data;
    },

    getReconciliationItems: async (runId: string): Promise<ReconciliationItemResponse[]> => {
        const res = await api.get<ApiResponse<ReconciliationItemResponse[]>>(`/api/v1/reconciliation/runs/${runId}/items`);
        return res.data.data;
    },

    // Risk
    getRiskAssessment: async (entityType: string, entityId: string): Promise<RiskAssessmentResponse> => {
        const res = await api.get<ApiResponse<RiskAssessmentResponse>>(`/api/v1/risk/assessments/${entityType}/${entityId}`);
        return res.data.data;
    },

    // Admin
    getAdminOrganizations: async (): Promise<Organization[]> => {
        const res = await api.get<ApiResponse<Organization[]>>("/api/v1/admin/organizations");
        return res.data.data;
    },

    getAdminLedgerEntries: async (): Promise<any[]> => {
        const res = await api.get<ApiResponse<any[]>>("/api/v1/admin/ledger/entries");
        return res.data.data;
    },

    // Finance
    getFinanceOverview: async (): Promise<any> => {
        const res = await api.get<ApiResponse<any>>("/api/v1/finance/overview");
        return res.data.data;
    },
};
