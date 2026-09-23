# FINX — Architecture Overview

## 1. System Mission & Legal Demarcation
FinX is a centralized B2B milestone-payment, verification, reconciliation, and trust infrastructure platform.

> **CRITICAL LEGAL NOTICE**:
> FinX itself does NOT hold a banking license, nor does it act as a regulated escrow agent. All physical fund custody, nodal/escrow banking, and statutory payment settlements are executed via external, appropriately licensed banking partners (e.g., scheduled commercial banks or licensed payment aggregators) through standardized API integrations and virtual accounts. FinX provides the trust, verification, agreement execution, double-entry ledger bookkeeping, and milestone verification software layer.

---

## 2. High-Level Architecture

```
[ B2B Corporate Buyer ]           [ B2B Vendor / Seller ]
           │                                 │
           ▼                                 ▼
┌───────────────────────────────────────────────────────────┐
│              Next.js 14 Web Application                   │
│   (Tailwind CSS, TypeScript, RBAC Navigation, Top Banner) │
└─────────────────────────────┬─────────────────────────────┘
                              │ HTTPS / JWT Bearer
                              ▼
┌───────────────────────────────────────────────────────────┐
│              FinX Spring Boot 3 Backend                   │
│ ┌──────────────────────┐   ┌────────────────────────────┐ │
│ │ Auth & RBAC (JWT)    │   │ Organization & Member Mgmt │ │
│ ├──────────────────────┤   ├────────────────────────────┤ │
│ │ KYC & KYB Engine     │   │ Bank Account Verification  │ │
│ ├──────────────────────┤   ├────────────────────────────┤ │
│ │ Deal & Milestones    │   │ Versioned Dual Agreements  │ │
│ ├──────────────────────┤   ├────────────────────────────┤ │
│ │ Payment Orchestration│   │ 10-Gate Settlement Engine  │ │
│ ├──────────────────────┤   ├────────────────────────────┤ │
│ │ Double-Entry Ledger  │   │ Automated Reconciliation   │ │
│ ├──────────────────────┤   ├────────────────────────────┤ │
│ │ Risk Engine          │   │ Append-Only Audit Logging  │ │
│ └──────────────────────┘   └────────────────────────────┘ │
└──────────────┬─────────────────────────────┬──────────────┘
               │                             │
               ▼                             ▼
┌───────────────────────────────┐ ┌─────────────────────────┐
│ Banking Abstraction Layer     │ │ Database & Cache Layer  │
│ (BankingProvider Interface)   │ │                         │
│ ┌───────────────────────────┐ │ │ PostgreSQL / Supabase   │
│ │ MockBankingProvider       │ │ │ (Flyway Migrations V1-15│
│ │ (Deterministic Sandbox)   │ │ │ Redis (Sessions/Queues) │
│ ├───────────────────────────┤ │ └─────────────────────────┘
│ │ BankAProvider / Partner   │ │
│ │ (Production HTTP Client)  │ │
│ └───────────────────────────┘ │
└──────────────┬────────────────┘
               │ Secure MTLS / Webhooks
               ▼
┌───────────────────────────────┐
│ Licensed Bank / Partner       │
│ (Nodal Account, Escrow, NEFT/ │
│  RTGS/IMPS Virtual Accounts)  │
└───────────────────────────────┘
```

---

## 3. Core Component Responsibilities

| Component | Responsibility |
| :--- | :--- |
| **Organization Service** | Multi-tenant B2B entity management, business structure hierarchy, role-based membership (`OWNER`, `ADMIN`, `FINANCE`, `PROJECT_MANAGER`, `AUTHORIZED_SIGNATORY`, `MEMBER`), and masked bank account registry. |
| **KYC / KYB Verification** | Corporate registry verification (PAN, GSTIN, CIN) and director KYC with fuzzy name matching and penny-drop bank account validation. |
| **Deal & Agreement Engine** | Versioned B2B contracts with SHA-256 document hashing, mutual digital signing audit trails, and strict milestone invariants (`sum(milestones) == deal.totalAmount`). |
| **Payment Orchestration** | Virtual Account (VAN) and dynamic IFSC generation for escrow deposits; webhook signature verification; idempotent state transitions. |
| **Double-Entry Ledger** | Immutable, balanced financial bookkeeping (`Total Debits == Total Credits`) using `BigDecimal` / `NUMERIC(19,4)` tracking escrow liabilities, nodal pool assets, and fee revenues. |
| **Settlement Authorization Gate** | 10-step server-side validation matrix preventing premature, fraudulent, or duplicate disbursements to counterparty bank accounts. |
| **Reconciliation Engine** | Automated batch comparison between internal transactions and external bank settlement statements, identifying matched, missing, or mismatched records. |
| **Risk Engine** | Real-time multi-signal risk evaluation (entity mismatch, velocity anomalies, banking detail shifts) that gates automated payouts. |
