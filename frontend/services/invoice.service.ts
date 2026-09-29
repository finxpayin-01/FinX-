import { api } from "@/lib/api";
import { ApiResponse } from "@/types";

export type InvoiceStatus = 'UPLOADED' | 'PROCESSING' | 'MATCHED' | 'PARTIAL_MATCH' | 'REVIEW_REQUIRED' | 'REJECTED' | 'APPROVED';
export type ExtractionStatus = 'PENDING' | 'EXTRACTED' | 'FAILED' | 'MANUAL_ENTRY';
export type MatchingStatus = 'PENDING' | 'STRONG_MATCH' | 'MATCH_WITH_WARNINGS' | 'REVIEW_REQUIRED' | 'MAJOR_MISMATCH';
export type DuplicateConfidence = 'UNIQUE' | 'POSSIBLE_DUPLICATE' | 'LIKELY_DUPLICATE' | 'EXACT_DUPLICATE';
export type CheckStatus = 'PASS' | 'WARNING' | 'FAIL' | 'NOT_APPLICABLE';

export interface InvoiceCheck {
    id: string;
    checkType: string;
    status: CheckStatus;
    reasonCode: string;
    message: string;
    weight: number;
    scoreEarned: number;
    details?: string;
    createdAt: string;
}

export interface InvoiceReview {
    id: string;
    invoiceId: string;
    reviewerId?: string;
    status: 'OPEN' | 'IN_REVIEW' | 'APPROVED' | 'REJECTED' | 'NEEDS_INFORMATION';
    decision?: 'APPROVE' | 'REJECT' | 'REQUEST_INFORMATION';
    decisionReason?: string;
    notes?: string;
    evidenceReferences?: string;
    reviewedAt?: string;
    createdAt: string;
}

export interface InvoiceExtractedField {
    id: string;
    fieldName: string;
    rawValue: string;
    normalizedValue: string;
    confidence: number;
}

export interface Invoice {
    id: string;
    dealId: string;
    milestoneId: string;
    vendorOrganizationId: string;
    buyerOrganizationId: string;
    invoiceNumber: string;
    normalizedInvoiceNumber: string;
    invoiceDate?: string;
    dueDate?: string;
    subtotal?: number;
    taxAmount?: number;
    totalAmount: number;
    currency: string;
    comparisonBasis: string;
    vendorLegalName?: string;
    vendorPan?: string;
    vendorGstin?: string;
    buyerLegalName?: string;
    buyerGstin?: string;
    bankAccountLast4?: string;
    bankIfsc?: string;
    fileName: string;
    fileUrl: string;
    fileSizeBytes?: number;
    mimeType?: string;
    documentHash: string;
    status: InvoiceStatus;
    extractionStatus: ExtractionStatus;
    matchingStatus: MatchingStatus;
    duplicateConfidence: DuplicateConfidence;
    matchScore: number;
    policyVersion: string;
    rejectionReason?: string;
    createdBy: string;
    createdAt: string;
    updatedAt: string;
    checks: InvoiceCheck[];
    reviews: InvoiceReview[];
    extractedFields: InvoiceExtractedField[];
}

export interface InvoiceUploadPayload {
    fileName: string;
    fileUrl?: string;
    revisionOf?: string;
    fileContentBase64?: string;
    mimeType?: string;
    fileSizeBytes?: number;
    invoiceNumber?: string;
    invoiceDate?: string;
    dueDate?: string;
    subtotal?: number;
    taxAmount?: number;
    totalAmount?: number | string;
    currency?: string;
    comparisonBasis?: string;
    vendorLegalName?: string;
    vendorPan?: string;
    vendorGstin?: string;
    buyerLegalName?: string;
    buyerGstin?: string;
    bankAccountLast4?: string;
    bankIfsc?: string;
}

export interface ReviewDecisionPayload {
    decision: 'APPROVE' | 'REJECT' | 'REQUEST_INFORMATION';
    decisionReason?: string;
    notes?: string;
    evidenceReferences?: string;
}

export interface InvoiceDashboardData {
    totalInvoicesProcessed: number;
    strongMatches: number;
    reviewRequired: number;
    majorMismatches: number;
    duplicatesDetected: number;
    pendingReviews: number;
    recentInvoices: Invoice[];
}

export const invoiceService = {
    download: async (invoice: Invoice) => {
        const res = await api.get(`/invoices/${invoice.id}/document`, { responseType: 'blob' });
        const url = URL.createObjectURL(res.data);
        const link = document.createElement('a'); link.href = url; link.download = invoice.fileName;
        link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
    },
    openReview: async (invoiceId: string): Promise<Invoice> => {
        const res = await api.post<ApiResponse<Invoice>>(`/invoices/${invoiceId}/reviews`, {});
        return res.data.data;
    },
    uploadInvoice: async (milestoneId: string, payload: InvoiceUploadPayload): Promise<Invoice> => {
        const res = await api.post<ApiResponse<Invoice> | Invoice>(`/milestones/${milestoneId}/invoices`, payload);
        return (res.data as ApiResponse<Invoice>)?.data || (res.data as Invoice);
    },

    getInvoice: async (invoiceId: string): Promise<Invoice> => {
        const res = await api.get<ApiResponse<Invoice> | Invoice>(`/invoices/${invoiceId}`);
        return (res.data as ApiResponse<Invoice>)?.data || (res.data as Invoice);
    },

    getInvoiceChecks: async (invoiceId: string): Promise<InvoiceCheck[]> => {
        const res = await api.get<ApiResponse<InvoiceCheck[]> | InvoiceCheck[]>(`/invoices/${invoiceId}/checks`);
        return (res.data as ApiResponse<InvoiceCheck[]>)?.data || (res.data as InvoiceCheck[]) || [];
    },

    getInvoiceReviews: async (invoiceId: string): Promise<InvoiceReview[]> => {
        const res = await api.get<ApiResponse<InvoiceReview[]> | InvoiceReview[]>(`/invoices/${invoiceId}/reviews`);
        return (res.data as ApiResponse<InvoiceReview[]>)?.data || (res.data as InvoiceReview[]) || [];
    },

    getMilestoneInvoices: async (milestoneId: string): Promise<Invoice[]> => {
        const res = await api.get<ApiResponse<Invoice[]> | Invoice[]>(`/milestones/${milestoneId}/invoices`);
        return (res.data as ApiResponse<Invoice[]>)?.data || (res.data as Invoice[]) || [];
    },

    getDealInvoices: async (dealId: string): Promise<Invoice[]> => {
        const res = await api.get<ApiResponse<Invoice[]> | Invoice[]>(`/deals/${dealId}/invoices`);
        return (res.data as ApiResponse<Invoice[]>)?.data || (res.data as Invoice[]) || [];
    },

    recalculateMatching: async (invoiceId: string): Promise<Invoice> => {
        const res = await api.post<ApiResponse<Invoice> | Invoice>(`/invoices/${invoiceId}/recalculate`, {});
        return (res.data as ApiResponse<Invoice>)?.data || (res.data as Invoice);
    },

    submitReviewDecision: async (invoiceId: string, reviewId: string, payload: ReviewDecisionPayload): Promise<Invoice> => {
        const res = await api.post<ApiResponse<Invoice> | Invoice>(`/invoices/${invoiceId}/reviews/${reviewId}/decision`, payload);
        return (res.data as ApiResponse<Invoice>)?.data || (res.data as Invoice);
    },

    getAdminInvoices: async (page = 0): Promise<Invoice[]> => {
        const res = await api.get<ApiResponse<Invoice[]> | Invoice[]>(`/admin/invoices?page=${page}&size=20`);
        return (res.data as ApiResponse<Invoice[]>)?.data || (res.data as Invoice[]) || [];
    },

    getAdminDashboard: async (): Promise<InvoiceDashboardData> => {
        const res = await api.get<ApiResponse<InvoiceDashboardData> | InvoiceDashboardData>("/admin/invoices/dashboard");
        return (res.data as ApiResponse<InvoiceDashboardData>)?.data || (res.data as InvoiceDashboardData);
    }
};
