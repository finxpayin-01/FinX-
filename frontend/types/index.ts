export type Role = "CORPORATE" | "VENDOR" | "PROJECT_MANAGER" | "ADMIN" | "FINANCE" | "BUYER" | "SELLER";

export interface User {
  id: string;
  uid?: string;
  email: string;
  fullName?: string;
  name?: string;
  role: Role;
  status?: string;
  createdAt?: string;
}

export interface SellerSummary {
  id: string;
  uid?: string;
  name: string;
  email: string;
  role: Role;
  status?: string;
}

export interface GoogleOAuthConfig {
  configured: boolean;
  clientId?: string;
  authUrl?: string;
  redirectUri?: string;
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  tokenType?: string;
  expiresIn?: number;
  user: User;
}

export interface ApiResponse<T> {
  success: boolean;
  message: string;
  data: T;
}

export interface RefreshTokenResponse {
  accessToken: string;
  refreshToken: string;
}

export interface ApiError {
  message: string;
  code?: string;
  details?: unknown;
}

export type MilestoneStatus =
  | "PENDING"
  | "IN_PROGRESS"
  | "SUBMITTED"
  | "UNDER_REVIEW"
  | "APPROVED"
  | "REJECTED"
  | "RELEASE_PENDING"
  | "RELEASED"
  | "COMPLETED"
  | "CANCELLED";

export interface Project {
  id: string;
  title: string;
  description?: string;
  status: 'DRAFT' | 'ACTIVE' | 'COMPLETED' | 'DISPUTED';
  amount: number;
}

export type DealStatus = 'DRAFT' | 'PENDING_ACCEPTANCE' | 'ACTIVE' | 'COMPLETED' | 'CANCELLED' | 'DISPUTED';

export interface Deal {
  id: string;
  projectId?: string;
  title: string;
  description: string;
  buyerId: string;
  sellerId: string;
  totalAmount: number;
  currency: string;
  status: DealStatus;
  createdAt: string;
  updatedAt: string;
}

export interface DealResponse {
  id: string;
  projectId?: string;
  title: string;
  description: string;
  buyerId: string;
  sellerId: string;
  totalAmount: number;
  currency: string;
  status: DealStatus;
  createdAt: string;
  updatedAt: string;
}
export interface Deliverable {
  id: string;
  milestoneId: string;
  fileName: string;
  fileUrl: string;
  description?: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED';
  submittedBy?: string;
  submittedAt?: string;
  uploadedAt?: string;
  reviewedAt?: string;
  reviewedBy?: string;
  rejectionReason?: string;
}

export interface Milestone {
  id: string;
  dealId?: string;
  projectId: string;
  title: string;
  description: string;
  sequence?: number;
  amount: number;
  currency: string;
  dueDate?: string;
  status: MilestoneStatus;
  deliverables?: Deliverable[];
  createdAt: string;
  updatedAt: string;
}

export type PaymentStatus =
  | "PENDING"
  | "SUCCESS"
  | "FAILED"
  | "REFUNDED"
  | "NOT_FUNDED"
  | "ORDER_CREATED"
  | "PAYMENT_PENDING"
  | "PAYMENT_PROCESSING"
  | "PAYMENT_SUCCESS"
  | "PAYMENT_FAILED"
  | "PAYMENT_VERIFICATION_PENDING"
  | "CANCELLED";

export interface Payment {
  id: string;
  dealId?: string;
  projectId: string;
  milestoneId: string;
  buyerId?: string;
  amount: number;
  currency: string;
  provider?: string;
  status: PaymentStatus;
  razorpayOrderId?: string;
  razorpayPaymentId?: string;
  createdAt: string;
  updatedAt: string;
}

export interface EscrowAccount {
  id: string;
  dealId: string;
  balance: number;
  currency: string;
  status: 'ACTIVE' | 'FROZEN' | 'CLOSED';
  createdAt: string;
  updatedAt: string;
}

export interface EscrowLedger {
  id: string;
  escrowAccountId: string;
  paymentId?: string;
  milestoneId?: string;
  transactionType: 'FUND' | 'RELEASE' | 'REFUND' | 'HOLD';
  amount: number;
  balanceAfter: number;
  description?: string;
  createdAt: string;
}

export interface EscrowTransaction {
  id: string;
  milestoneId: string;
  amount: number;
  status: 'HELD' | 'RELEASED' | 'REFUNDED';
}

export interface Invoice {
  id: string;
  paymentId: string;
  amount: number;
  date: string;
}

export interface Dispute {
  id: string;
  dealId?: string;
  projectId: string;
  milestoneId?: string;
  raisedBy?: string;
  reason: string;
  status: 'OPEN' | 'UNDER_REVIEW' | 'RESOLVED' | 'REJECTED';
  resolutionNotes?: string;
  resolvedAt?: string;
  createdAt?: string;
}

export interface AuditLog {
  id: string;
  action: string;
  timestamp: string;
  userId: string;
}
