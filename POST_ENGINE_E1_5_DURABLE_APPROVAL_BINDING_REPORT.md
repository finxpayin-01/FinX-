# FinX E1.5 Durable Milestone Approval Binding Report

## Reason For Corrective Phase

Prior to this phase, `ApprovalDecisionAdapter` explicitly documented:
> "No current schema source can prove a durable milestone approval binding. No ALLOW branch exists."

In addition, `ApprovalApplicabilityService` hard-coded:
```java
boolean milestoneUnbound = true;
```
As a result, `ApprovalDecisionAdapter` unconditionally returned `REVIEW` or `BLOCK`, but never `ALLOW`. Because `ReleaseDecisionEngine` requires all eight component engine adapters to return `ALLOW` in order to produce an overall `EngineDecision(RELEASE, ALLOW)`, the real release pipeline was structurally blocked from reaching `ALLOW`. Payout execution (Phase E2) would otherwise only have been testable via synthetic decision fixtures.

Phase E1.5 addresses this root cause by implementing immutable, durable milestone approval evidence that cryptographically binds the approver, authority, material milestone terms, and deliverable state at approval time.

---

## Migration

**`V30__create_milestone_approval_bindings.sql`**

- Table: `milestone_approval_bindings`
- Primary key: `id UUID PRIMARY KEY`
- Foreign keys with `ON DELETE RESTRICT`:
  - `organization_id` -> `organizations(id)`
  - `deal_id` -> `deals(id)`
  - `milestone_id` -> `milestones(id)`
  - `approved_by` -> `users(id)`
  - `project_manager_id` -> `users(id)` (nullable, populated when authority is `PROJECT_MANAGER`)
- Uniqueness constraint: `UNIQUE (milestone_id)` — strictly one authoritative binding per milestone lifecycle.
- Hash constraints: `milestone_fingerprint`, `deliverables_fingerprint`, and `approval_fingerprint` enforced as 64-character lowercase hexadecimal strings (`^[a-f0-9]{64}$`).
- Authority constraint: `approval_authority` limited to supported enum values (`BUYER`, `PROJECT_MANAGER`, `ADMIN`).
- Indexes:
  - `idx_mab_org_approved` on `(organization_id, approved_at DESC)`
  - `idx_mab_deal_approved` on `(deal_id, approved_at DESC)`
  - `idx_mab_milestone` on `(milestone_id)`
  - `idx_mab_approver_approved` on `(approved_by, approved_at DESC)`

Migrations `V1` through `V29` remain completely unchanged. Future migrations are shifted accordingly (`V31` for outbox, `V32` for attempts, `V33` for settlement observations).

---

## Binding Model

Entity: `com.finx.approval.entity.MilestoneApprovalBinding`
- Annotated `@Entity` and `@org.hibernate.annotations.Immutable`.
- All fields configured with `updatable = false` and `nullable = false` (except `projectManagerId`).
- Primary key generated in Java via `UUID.randomUUID()`.
- No business setters, update lifecycle, or deletion endpoints.

---

## Approval Authority

Enum: `com.finx.approval.entity.MilestoneApprovalAuthority`
- Exact values:
  - `BUYER`: Corporate buyer on the deal. Verified against `deal.getBuyerId()`.
  - `PROJECT_MANAGER`: Assigned PM on the deal. Verified against binding `projectManagerId` and current `deal.getProjectManagerId()`.
  - `ADMIN`: Platform administrator at approval time.
- Excluded values: `FINANCE`, `SELLER`, `AI`, `SYSTEM`.

---

## Milestone Fingerprint

Service: `com.finx.approval.service.MilestoneApprovalFingerprintService.computeMilestoneFingerprint`
- SHA-256 hash over canonical representation of material payment terms:
  - `milestone.id`
  - `milestone.dealId`
  - `sequence`
  - `amount.stripTrailingZeros().toPlainString()`
  - `currency.toUpperCase()`
  - `invoiceRequired`
  - `dueDate` (ISO-8601 instant)
  - `submissionDeadline` (ISO-8601 instant)
  - `status.name()`
- Omits non-financial display fields (`title`, `description`) and mutable system timestamps (`updatedAt`).

---

## Deliverables Fingerprint

Service: `com.finx.approval.service.MilestoneApprovalFingerprintService.computeDeliverablesFingerprint`
- SHA-256 hash over deterministic sequence of all milestone deliverables:
  - Sorted by `deliverable.id` to prevent collection-order indeterminism.
  - Per deliverable: `id`, `milestoneId`, `submittedBy`, `submittedAt`, `fileUrl`, `status`, `reviewedBy`, `reviewedAt`.
- Omits unstructured descriptions. Raw URLs are hashed but not exposed as unhashed evidence in decision adapter payloads.

---

## Approval Fingerprint

Service: `com.finx.approval.service.MilestoneApprovalFingerprintService.computeApprovalFingerprint`
- Canonical SHA-256 hash binding the approval context:
  - `organizationId`
  - `dealId`
  - `milestoneId`
  - `approvedBy`
  - `approvalAuthority`
  - `projectManagerId` (if present)
  - `milestoneFingerprint`
  - `deliverablesFingerprint`
  - `approvedAt` (ISO-8601 instant)

---

## Transactional Approval Creation

Integrated into `MilestoneService.approveMilestone`:
1. Pending deliverables transitioned to `APPROVED`, with `reviewedAt = approvalTime` and `reviewedBy = currentUser.id`.
2. Flushed to database.
3. Milestone transitioned to `APPROVED`.
4. Flushed to database.
5. Snapshot of post-approval persisted state reloaded.
6. For modern org-bound deals (`deal.getBuyerOrganizationId() != null`), `approvalBindingService.createBinding(...)` executed atomically in the same database transaction.
7. Any failure in binding creation rolls back the entire approval transaction.

---

## Lock Order

**Canonical Order: `Deal -> Milestone`**
- Step 1: Read milestone `dealId` without write lock (`milestoneRepository.findDealIdById(milestoneId)`).
- Step 2: Acquire pessimistic write lock on Deal (`dealRepository.findByIdForUpdate(dealId)`).
- Step 3: Acquire pessimistic write lock on Milestone (`milestoneRepository.findByIdForUpdate(milestoneId)`).
- Step 4: Re-verify `deal.getId().equals(milestone.getDealId())` under lock.
- Eliminates prior lock inversion pattern (`Milestone -> Deal -> Milestone`). Verified by `PostgresLockingConcurrencyTest` and `LockingAndRetryContainmentTest`.

---

## ApprovalApplicability

`ApprovalApplicabilityService` now invokes `MilestoneApprovalBindingService.evaluateCurrentBinding(...)`:
- Hard-coded `milestoneUnbound = true` removed.
- `milestoneUnbound = !binding.current()`.
- Captures granular reason codes when binding is invalid:
  - `APPROVAL_BINDING_MISSING`
  - `APPROVAL_BINDING_MILESTONE_CHANGED`
  - `APPROVAL_BINDING_DELIVERABLES_CHANGED`
  - `APPROVAL_BINDING_AUTHORITY_CHANGED`
- `overallApplicable` evaluates to `true` only when:
  - Milestone status is `APPROVED`
  - Binding is present, current, and scope-coherent
  - All existing gates pass (snapshot coherence, invoice, agreement, beneficiary, PM assignment, dispute, risk)

---

## Approval Engine

- **Before**: `REVIEW` or `BLOCK` only; `APPROVAL_UNBOUND_MILESTONE` unconditionally added; no `ALLOW` branch.
- **After**:
  - `BLOCK`: Unresolved disputes, rejected milestones, or explicit policy blocks.
  - `REVIEW`: Missing or stale approval binding, legacy unbound deal, pending invoice/agreement/beneficiary/PM verification.
  - `ALLOW`: Reached when all approval applicability conditions are confirmed valid and the durable binding is current (`APPROVAL_CURRENT_BINDING_CONFIRMED`).
  - Score: `null` (no numeric score for approval decisions).
  - Evidence: Durable `MILESTONE_APPROVAL_BINDING` reference and input recorded in `EngineDecision`.

---

## Release Engine

- Precedence and 8-component all-`ALLOW` requirement remain untouched.
- `ReleaseDecisionEngine` can now legitimately produce `EngineDecision(RELEASE, ALLOW)` without fabricated component fixtures.
- Zero automatic release instructions created.
- Zero bank calls.

---

## Legacy Approval

- For deals without `buyerOrganizationId`, `approveMilestone` retains historical workflow compatibility, setting status `APPROVED` and logging an audit event.
- No authoritative financial binding is created.
- `ApprovalDecisionAdapter` evaluates legacy approvals as `REVIEW` (`APPROVAL_BUYER_ORGANIZATION_UNBOUND`). Status-only approval is strictly non-authoritative for financial release.

---

## Financial Side Effects

- Bank calls: **0**
- Escrow deductions / release writes: **0**
- Ledger journal entries posted: **0**
- ReleaseInstruction writes: **0**
- ReleaseReservation writes: **0**

---

## Bank Calls

**0** (Strictly verified across unit and PostgreSQL tests).

---

## PostgreSQL

- Disposable database: `finx_approval_binding_test` on `finx_postgres` (PostgreSQL 16.15).
- Test class: `com.finx.approval.PostgresMilestoneApprovalBindingTest`
- Tests run: 15, Failures: 0, Errors: 0, **Skipped: 0** — **PASS**
- Concurrency test: `com.finx.security.PostgresLockingConcurrencyTest` — **PASS**

---

## Flyway

- Sequential clean migration test: `com.finx.FlywayCleanMigrationTest`
- Migrations: `V1` through `V30` applied sequentially — **PASS**
- Tables created: 68 tables including `milestone_approval_bindings`
- `V31`: Absent

---

## E1 Regression

- `ReleasePersistenceEntityMappingTest`: **PASS**
- `ReleasePersistenceFoundationArchitectureTest`: **PASS**
- `ReleaseRepositoryTest`: **PASS**
- `PostgresReleasePersistenceFoundationTest`: **PASS**

---

## Engine Regression

All 9 engine platform tests:
1. `RiskDecisionAdapterTest`: **PASS**
2. `TrustDecisionAdapterTest`: **PASS**
3. `MilestoneRulesDecisionAdapterTest`: **PASS**
4. `ApprovalDecisionAdapterTest`: **PASS**
5. `FraudDecisionAdapterTest`: **PASS**
6. `DocumentDecisionAdapterTest`: **PASS**
7. `ReconciliationDecisionAdapterTest`: **PASS**
8. `DisputeDecisionAdapterTest`: **PASS**
9. `ReleaseDecisionEngineTest`: **PASS**
**9 / 9 PASS** (237 tests)

---

## Ready For E2

**YES** — The approval engine adapter can now legitimately yield `ALLOW` backed by immutable, durable approval evidence, clearing the prerequisite for Phase E2 (Release Instruction Creation, Platform Authorization, and Atomic Fund Reservation).
