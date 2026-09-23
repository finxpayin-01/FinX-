export type EntityType =
    | "PROPRIETORSHIP"
    | "PARTNERSHIP"
    | "LLP"
    | "PRIVATE_LIMITED"
    | "PUBLIC_LIMITED"
    | "OTHER";

export type OrgRole =
    | "OWNER"
    | "ADMIN"
    | "FINANCE"
    | "PROJECT_MANAGER"
    | "AUTHORIZED_SIGNATORY"
    | "MEMBER";

export type VerificationStatus =
    | "NOT_STARTED"
    | "IN_PROGRESS"
    | "PENDING_REVIEW"
    | "VERIFIED"
    | "FAILED"
    | "REJECTED"
    | "EXPIRED";

export type AgreementStatus =
    | "DRAFT"
    | "PENDING_BUYER"
    | "PENDING_SELLER"
    | "SIGNED"
    | "REJECTED"
    | "EXPIRED";

export type PaymentInstructionStatus =
    | "CREATED"
    | "SUBMITTED"
    | "PROCESSING"
    | "SUCCESS"
    | "FAILED"
    | "CANCELLED"
    | "REVERSED";

export type SettlementStatus =
    | "PENDING"
    | "PROCESSING"
    | "SETTLED"
    | "FAILED"
    | "REVERSED";

export type ReconciliationItemStatus =
    | "MATCHED"
    | "MISSING_INTERNAL"
    | "MISSING_EXTERNAL"
    | "AMOUNT_MISMATCH"
    | "STATUS_MISMATCH"
    | "DUPLICATE"
    | "REQUIRES_REVIEW";

export type RiskLevel = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";

export interface Organization {
    id: string;
    name: string;
    legalName: string;
    entityType: EntityType;
    cin?: string;
    pan: string;
    gstin?: string;
    registeredAddress?: string;
    kybStatus: VerificationStatus;
    verified: boolean;
    trustScore: number;
    completedDealsCount: number;
    completedMilestonesCount: number;
    disputeCount: number;
    createdAt: string;
}

export interface CreateOrgRequest {
    name: string;
    legalName: string;
    entityType: EntityType;
    cin?: string;
    pan: string;
    gstin?: string;
    registeredAddress?: string;
}

export interface OrgMemberResponse {
    id: string;
    userId: string;
    userName: string;
    userEmail: string;
    role: OrgRole;
    active: boolean;
    joinedAt: string;
}

export interface OrgBankAccountResponse {
    id: string;
    accountHolderName: string;
    accountNumberMasked: string;
    ifscCode: string;
    bankName: string;
    branchName?: string;
    accountType: string;
    verificationStatus: VerificationStatus;
    verified: boolean;
    pennyDropRef?: string;
    verifiedAt?: string;
    isPrimary: boolean;
    createdAt: string;
}

export interface AddBankAccountRequest {
    accountHolderName: string;
    accountNumber: string;
    ifscCode: string;
    bankName: string;
    branchName?: string;
    accountType?: string;
}

export interface KybProfileResponse {
    id: string;
    organizationId: string;
    organizationName: string;
    legalName: string;
    businessPan: string;
    gstin?: string;
    cin?: string;
    status: VerificationStatus;
    providerReference?: string;
    verifiedAt?: string;
    rejectionReason?: string;
}

export interface SubmitKybRequest {
    legalName: string;
    entityType: EntityType;
    businessPan: string;
    gstin?: string;
    cin?: string;
    registeredAddress: string;
    authorizedSignatoryName: string;
    authorizedSignatoryPan: string;
}

export interface KycProfileResponse {
    id: string;
    userId: string;
    fullName: string;
    panMasked: string;
    status: VerificationStatus;
    nameMatchScore: number;
    verifiedAt?: string;
}

export interface SubmitKycRequest {
    fullName: string;
    pan: string;
    dateOfBirth?: string;
    idDocumentType?: string;
    idDocumentNumber?: string;
}

export interface DealAgreementResponse {
    id: string;
    dealId: string;
    version: number;
    status: AgreementStatus;
    buyerOrgId: string;
    sellerOrgId: string;
    buyerAccepted: boolean;
    buyerAcceptedAt?: string;
    sellerAccepted: boolean;
    sellerAcceptedAt?: string;
    termsContent: string;
    documentHashSha256: string;
    totalAmount: number;
    currency: string;
    createdAt: string;
    signedAt?: string;
}

export interface SignAgreementRequest {
    accept: boolean;
    signatoryName: string;
    notes?: string;
}

export interface PaymentInstructionResponse {
    id: string;
    instructionReference: string;
    dealId: string;
    milestoneId?: string;
    amount: number;
    currency: string;
    provider: string;
    status: PaymentInstructionStatus;
    virtualAccountNumber: string;
    ifscCode: string;
    beneficiaryName: string;
    providerPaymentRef?: string;
    confirmedAt?: string;
    createdAt: string;
}

export interface GeneratePaymentInstructionRequest {
    dealId: string;
    milestoneId?: string;
    idempotencyKey?: string;
}

export interface SettlementResponse {
    id: string;
    settlementReference: string;
    dealId: string;
    milestoneId: string;
    amount: number;
    currency: string;
    provider: string;
    utrNumber?: string;
    status: SettlementStatus;
    authorizedAt?: string;
    settledAt?: string;
    failureReason?: string;
}

export interface PayoutAuthorizationRequest {
    milestoneId: string;
    comment?: string;
    idempotencyKey?: string;
}

export interface ReconciliationRunResponse {
    id: string;
    runReference: string;
    provider: string;
    periodStart: string;
    periodEnd: string;
    status: string;
    totalInternalTransactions: number;
    totalBankTransactions: number;
    matchedCount: number;
    mismatchCount: number;
    totalSettledAmount: number;
    executedAt: string;
    completedAt?: string;
}

export interface ReconciliationItemResponse {
    id: string;
    runId: string;
    internalTxReference?: string;
    bankTxReference?: string;
    dealId?: string;
    milestoneId?: string;
    internalAmount?: number;
    bankAmount?: number;
    status: ReconciliationItemStatus;
    notes?: string;
    resolved: boolean;
    createdAt: string;
}

export interface RiskAssessmentResponse {
    id: string;
    entityType: string;
    entityId: string;
    riskLevel: RiskLevel;
    riskScore: number;
    cleared: boolean;
    summary?: string;
    signals: RiskSignalResponse[];
    assessedAt: string;
    clearedAt?: string;
}

export interface RiskSignalResponse {
    id: string;
    signalType: string;
    severity: RiskLevel;
    description: string;
    createdAt: string;
}
