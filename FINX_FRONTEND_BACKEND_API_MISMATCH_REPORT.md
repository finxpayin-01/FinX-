# FinX Frontend-Backend API Mismatch Report

**Audit Date:** 2026-10-18  
**Audit Scope:** Full FinX Stack (Next.js Turbopack Frontend + Spring Boot 3.4.3 Backend)  
**Authoritative Status:** AUDIT ONLY (No code modifications applied)

---

## Executive Overview

This report provides the exhaustive inventory of all API mismatches, URL prefix anomalies, missing endpoints, method discrepancies, and DTO contracts identified during the comprehensive functional audit of the FinX platform.

A primary systemic defect identified is the **Axios BaseURL Double-Prefix Hazard**: `frontend/lib/api.ts` configures Axios `baseURL` with `/api` (`http://localhost:8080/api`), whereas several frontend service files (`b2b.service.ts`, `app/admin/settlements/page.tsx`) explicitly call paths starting with `/api/v1/...`, resulting in runtime requests to `http://localhost:8080/api/api/v1/...` that fail with HTTP 500/404.

---

## API Mismatch Inventory

### 1. Double-Prefix Endpoint Failures (`/api/api/v1/...`)

| Frontend Endpoint in Source | Actual URL Generated | Target Backend Endpoint | HTTP Verb | Failure Mode | Impacted Routes | Recommended Correction |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `/api/v1/organizations/me` | `/api/api/v1/organizations/me` | `/api/v1/organizations/me` | GET | HTTP 500 (No mapping) | `/onboarding` | Change call in `b2b.service.ts` to `/v1/organizations/me` or `/organizations/me` |
| `/api/v1/admin/settlements` | `/api/api/v1/admin/settlements` | `/api/v1/admin/settlements` | GET | HTTP 500 (No mapping) | `/admin/settlements` | Change call in `app/admin/settlements/page.tsx` to `/v1/admin/settlements` or `/admin/settlements` |
| `/api/v1/reconciliation/runs` | `/api/api/v1/reconciliation/runs` | `/api/v1/reconciliation/runs` | GET | HTTP 500 (No mapping) | `/admin/reconciliation` | Change call in `b2b.service.ts` to `/v1/reconciliation/runs` |
| `/api/v1/admin/organizations` | `/api/api/v1/admin/organizations` | `/api/v1/admin/organizations` | GET | HTTP 500 (No mapping) | `/admin/organizations` | Change call in `b2b.service.ts` to `/v1/admin/organizations` |
| `/api/v1/organizations` | `/api/api/v1/organizations` | `/api/v1/organizations` | POST | HTTP 500 (No mapping) | Organization creation | Change call in `b2b.service.ts` to `/v1/organizations` |
| `/api/v1/organizations/${id}` | `/api/api/v1/organizations/${id}` | `/api/v1/organizations/{id}` | GET | HTTP 500 (No mapping) | Organization detail | Change call in `b2b.service.ts` to `/v1/organizations/${id}` |
| `/api/v1/organizations/${id}/members` | `/api/api/v1/organizations/${id}/members` | `/api/v1/organizations/{id}/members` | GET / POST | HTTP 500 (No mapping) | Org members | Change call in `b2b.service.ts` to `/v1/organizations/${id}/members` |
| `/api/v1/organizations/${id}/bank-accounts` | `/api/api/v1/organizations/${id}/bank-accounts` | `/api/v1/organizations/{id}/bank-accounts` | GET / POST | HTTP 500 (No mapping) | Org bank accounts | Change call in `b2b.service.ts` to `/v1/organizations/${id}/bank-accounts` |
| `/api/v1/kyb/${orgId}` | `/api/api/v1/kyb/${orgId}` | `/api/v1/kyb/{id}` | GET / POST | HTTP 500 (No mapping) | KYB business profile | Change call in `b2b.service.ts` to `/v1/kyb/${orgId}` |
| `/api/v1/kyc` | `/api/api/v1/kyc` | `/api/v1/kyc` | GET / POST | HTTP 500 (No mapping) | KYC profile | Change call in `b2b.service.ts` to `/v1/kyc` |
| `/api/v1/agreements/deals/${dealId}` | `/api/api/v1/agreements/deals/${dealId}` | `/api/v1/agreements/deals/{dealId}` | GET | HTTP 500 (No mapping) | Agreement viewer | Change call in `b2b.service.ts` to `/v1/agreements/deals/${dealId}` |
| `/api/v1/agreements/deals/${dealId}/sign` | `/api/api/v1/agreements/deals/${dealId}/sign` | `/api/v1/agreements/deals/{dealId}/sign` | POST | HTTP 500 (No mapping) | Dual-party sign | Change call in `b2b.service.ts` to `/v1/agreements/deals/${dealId}/sign` |
| `/api/v1/payments/instructions` | `/api/api/v1/payments/instructions` | `/api/v1/payments/instructions` | POST | HTTP 500 (No mapping) | Payment instruction | Change call in `b2b.service.ts` to `/v1/payments/instructions` |
| `/api/v1/reconciliation/trigger` | `/api/api/v1/reconciliation/trigger` | `/api/v1/reconciliation/trigger` | POST | HTTP 500 (No mapping) | Trigger recon run | Change call in `b2b.service.ts` to `/v1/reconciliation/trigger` |
| `/api/v1/admin/ledger/entries` | `/api/api/v1/admin/ledger/entries` | `/api/v1/admin/ledger/entries` | GET | HTTP 500 (No mapping) | Double-entry journal | Change call in `b2b.service.ts` to `/v1/admin/ledger/entries` |

---

### 2. Missing Backend Endpoints (Endpoint Not Present)

#### Mismatch A: `/finance/dashboard`
- **Frontend Source File:** `frontend/services/day6.service.ts` (Line 20), consumed by `frontend/app/finance/page.tsx`
- **Frontend Method:** `financeService.getDashboard()`
- **URL Called:** `http://localhost:8080/api/finance/dashboard`
- **HTTP Verb:** `GET`
- **Backend Controller:** None. `FinanceController.java` provides `/api/v1/finance/summary` and `/api/v1/finance/ledger`.
- **Backend Endpoint Present?:** NO (`ENDPOINT_MISSING`)
- **Expected DTO:** Object containing `totalInflowVolume`, `totalSettledVolume`, `pendingSettlementsCount`, `totalReconciliationRuns`.
- **Actual Response:** HTTP 500 / 404 Not Found.
- **Recommended Correction:** Refactor `day6.service.ts` to call `/v1/finance/summary` and update `app/finance/page.tsx` to read the unified summary response.

#### Mismatch B: `/finance/transactions`
- **Frontend Source File:** `frontend/services/day6.service.ts` (Line 24), consumed by `frontend/app/finance/payments/page.tsx`, `frontend/app/finance/transactions/page.tsx`, `frontend/app/finance/reports/page.tsx`
- **Frontend Method:** `financeService.getTransactions()`
- **URL Called:** `http://localhost:8080/api/finance/transactions`
- **HTTP Verb:** `GET`
- **Backend Controller:** None. `FinanceProjectController.java` provides `/api/finance/projects` with embedded transaction ledgers, and `AdminB2bController.java` provides `/api/v1/admin/ledger/entries`.
- **Backend Endpoint Present?:** NO (`ENDPOINT_MISSING`)
- **Actual Response:** HTTP 500 / 404 Not Found.
- **Recommended Correction:** Update `financeService.getTransactions()` to call `/finance/projects` or `/v1/admin/ledger/entries`.

#### Mismatch C: `/api/v1/finance/overview`
- **Frontend Source File:** `frontend/services/b2b.service.ts` (Line 167)
- **Frontend Method:** `b2bService.getFinanceOverview()`
- **URL Called:** `http://localhost:8080/api/api/v1/finance/overview`
- **HTTP Verb:** `GET`
- **Backend Controller:** `FinanceController.java`
- **Backend Endpoint Present?:** NO (`ENDPOINT_MISSING`). Controller defines `/summary`.
- **Recommended Correction:** Change endpoint to `/v1/finance/summary`.

#### Mismatch D: `/api/v1/settlements/${settlementId}` and `/api/v1/settlements/milestones/${milestoneId}`
- **Frontend Source File:** `frontend/services/b2b.service.ts` (Lines 123, 128)
- **Frontend Method:** `b2bService.getSettlementById()`, `b2bService.getSettlementByMilestone()`
- **URL Called:** `http://localhost:8080/api/api/v1/settlements/{id}`
- **HTTP Verb:** `GET`
- **Backend Controller:** `PaymentOrchestrationController.java`
- **Backend Endpoint Present?:** NO (`ENDPOINT_MISSING`). Controller only exposes `@GetMapping("/settlements/deal/{dealId}")`.
- **Recommended Correction:** Add `@GetMapping("/settlements/{id}")` and `@GetMapping("/settlements/milestone/{milestoneId}")` to `PaymentOrchestrationController.java`, or route deal settlements via dealId.

---

### 3. Path & Parameter Discrepancies (`WRONG_PATH`)

#### Mismatch E: Bank Account Penny Drop Verification
- **Frontend Source File:** `frontend/services/b2b.service.ts` (Line 63)
- **Frontend Method:** `b2bService.verifyPennyDrop(bankAccountId)`
- **URL Called:** `http://localhost:8080/api/api/v1/bank-accounts/${bankAccountId}/verify-penny-drop`
- **HTTP Verb:** `POST`
- **Backend Controller:** `OrganizationController.java` (Line 104)
- **Actual Backend Endpoint:** `POST /api/v1/organizations/{id}/bank-accounts/{accountId}/verify`
- **Backend Endpoint Present?:** Path mismatch (`WRONG_PATH`)
- **Role Required:** `BUYER`, `SELLER`, `ADMIN`
- **DTO Mismatch:** Backend requires both `organizationId` (`id`) and `accountId` as path variables; frontend omitted `organizationId`.
- **Recommended Correction:** Update `b2bService.verifyPennyDrop(orgId, bankAccountId)` to call `/v1/organizations/${orgId}/bank-accounts/${bankAccountId}/verify`.

#### Mismatch F: Payment Instruction Confirmation
- **Frontend Source File:** `frontend/services/b2b.service.ts` (Line 112)
- **Frontend Method:** `b2bService.simulatePaymentConfirmation(instructionId)`
- **URL Called:** `http://localhost:8080/api/v1/payments/instructions/${instructionId}/simulate-confirmation`
- **HTTP Verb:** `POST`
- **Backend Controller:** `PaymentOrchestrationController.java` (Line 45)
- **Actual Backend Endpoint:** `POST /api/v1/payments/instructions/{id}/confirm`
- **Backend Endpoint Present?:** Path mismatch (`WRONG_PATH`)
- **Recommended Correction:** Update `b2bService.simulatePaymentConfirmation(instructionId)` to target `/v1/payments/instructions/${instructionId}/confirm`.

---

### 4. Deprecated / Gated Endpoints (`AUTH_MISMATCH / PIPELINE_DEPRECATION`)

#### Mismatch G: Direct Settlement Payout Authorization
- **Frontend Source File:** `frontend/services/b2b.service.ts` (Line 118)
- **Frontend Method:** `b2bService.authorizeSettlement(payload)`
- **URL Called:** `http://localhost:8080/api/v1/settlements/authorize`
- **HTTP Verb:** `POST`
- **Backend Controller:** `PaymentOrchestrationController.java` (Line 70)
- **Backend Behavior:** Throws `AccessDeniedException("Settlement payout execution is disabled pending settlement orchestration redesign")` -> HTTP 403 Forbidden.
- **Authoritative Engine Pipeline:** Release instructions must be managed via `AdminReleaseManagementController.java` (`/api/v1/finance/release-operations`).
- **Recommended Correction:** Remove direct settlement button and rewire payout release UI to the certified `ReleaseExecutionPlatform` endpoints (`/api/v1/finance/release-operations/summary`, `hold`, `resume`, `cancel`).

#### Mismatch H: Direct Escrow Release
- **Frontend Source File:** Legacy deal/milestone buttons
- **Frontend Method:** `escrowService.releaseEscrow(milestoneId)`
- **URL Called:** `http://localhost:8080/api/escrow/{milestoneId}/release`
- **HTTP Verb:** `POST`
- **Backend Controller:** `EscrowController.java` (Line 55)
- **Backend Behavior:** Throws `AccessDeniedException("Direct escrow release is disabled pending settlement orchestration")` -> HTTP 403 Forbidden.
- **Recommended Correction:** Remove direct escrow release buttons; milestone approval transitions release instructions into `ReleaseExecutionPlatform`.

---

### 5. DTO & Field Mismatches

| Endpoint | Frontend Expected Field | Backend Model Field | Classification | Impact |
| :--- | :--- | :--- | :--- | :--- |
| `POST /api/deals` | `sellerId` (string UUID) | `sellerId` (UUID) | COMPATIBLE | Passes validation |
| `GET /api/deals/sellers` | `SellerSummary.id` | `UserSummaryResponse.id`, `name`, `email` | COMPATIBLE | Successfully populates seller dropdowns |
| `GET /api/finance/summary` | `stats.escrowFunds`, `stats.pendingFunds` | `totalInflowVolume`, `totalSettledVolume`, `pendingSettlementsCount` | DTO_MISMATCH | Stats card rendered with 0 if field mappings are not normalized |
| `GET /api/v1/finance/release-operations/summary` | Not currently consumed by frontend | `ReleaseOperationalSummaryDto` (queued, held, dispatching, pendingSettlement, unknownReconRequired, manualReview, settled) | MISSING_CONSUMPTION | Frontend admin/settlements does not visualize certified pipeline states |

---

## Mismatch Summary Counts

- **ENDPOINT_MISSING:** 4
- **WRONG_PATH:** 2
- **DOUBLE_PREFIX_HAZARD (`/api/api/v1`):** 15
- **DTO_MISMATCH:** 2
- **PIPELINE_DEPRECATED (Gated 403):** 2
- **Total API Integration Corrections Required:** 25
