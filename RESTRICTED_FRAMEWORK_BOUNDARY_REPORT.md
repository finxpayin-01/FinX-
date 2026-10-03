# Restricted Framework Boundary Verification

## Purpose

This report documents the architectural, security, and integration proof that the FinX repository is safe to begin implementation of a restricted, read-only, decision-recording common `EngineDecision` framework.

**Clarification**: This boundary verification proves the safety of introducing an internal, audit-isolated decision recording framework. It **DOES NOT** authorize, enable, or unblock production money movement or automated outbound bank payout execution. Production payouts remain strictly disabled.

---

## Completed Blockers

All thirteen restricted-framework blockers (B01–B13) are complete, verified, committed, and pushed on branch `feature/finx-engine-platform`:

- **B01 (PASS)**: Fail-closed containment on unconfirmed payments (`PaymentConfirmationContainmentTest`).
- **B02 (PASS)**: Webhook signature authentication and payload integrity (`BankingWebhookControllerTest`).
- **B03 (PASS)**: Escrow release idempotency and authorization controls (`EscrowReleaseContainmentTest`).
- **B04 (PASS)**: Settlement execution isolation; provider invocation disabled (`SettlementExecutionContainmentTest`).
- **B05 (PASS)**: Financial effect idempotency and unique constraints (`FinancialIdempotencyContainmentTest`, `PostgresFinancialIdentityTest`).
- **B06 (PASS)**: Source lineage and journal consistency (`PostgresFinancialConsistencyTest`).
- **B07 (PASS)**: Strict multi-tenant isolation and access boundaries (`TenantIsolationContainmentTest`).
- **B08 (PASS)**: Platform authority and role-escalation defense (`PlatformRoleProvisioningContainmentTest`).
- **B09 (PASS)**: Canonical verified beneficiary enforcement (`BeneficiaryVerificationContainmentTest`).
- **B10 (PASS)**: Approval freshness and staleness invalidation (`ApprovalFreshnessContainmentTest`).
- **B11 (PASS)**: Transaction locking and deadlock-free retries (`LockingAndRetryContainmentTest`, `PostgresLockingConcurrencyTest`).
- **B12 (PASS)**: Legacy payment rail containment; strict BANK mode (`LegacyBoundaryContainmentTest`).
- **B13 (PASS)**: Stable, read-only risk evidence without recalculation side effects (`RiskEvidenceReadContainmentTest`).

---

## Trusted Evidence Sources

The future restricted decision framework may consume only persisted, tenant-scoped, source-identifiable, read-only evidence. The audited trusted sources are:

1. **Risk Evidence**:
   - Contract: `com.finx.risk.service.RiskEvidenceReadService`
   - Data Structures: `DealRiskEvidenceSnapshot`, `SubjectRiskEvidence`, `InvoiceRiskEvidence`
   - Attributes: Tenant-scoped, buyer/vendor persisted references (`RiskScore.id`, `RiskAssessment.id`, `RiskScoreHistory.assessmentId`), active restrictions (`RiskReview.id`), explicit `isTransient` indicators.
2. **Approval Applicability Evidence**:
   - Contract: `com.finx.paymentorchestration.service.ApprovalApplicabilityService`
   - Data Structures: `ApprovalApplicabilityResult`, `InvoiceApplicabilityResult`, `AgreementApplicabilityResult`, `BeneficiaryApplicabilityResult`, `PmAssignmentApplicabilityResult`, `DisputeApplicabilityResult`, `RiskApplicabilityResult`
   - Attributes: Evaluates whether historical approvals have been rendered stale by subsequent material mutations.
3. **Beneficiary Eligibility Evidence**:
   - Contract: `com.finx.verification.service.VerificationEligibilityService`
   - Data Structure: `BeneficiaryEligibilityResult`
   - Attributes: Unambiguous canonical bank account with `BankVerificationStatus.VERIFIED`, matching organization ID, and persisted encrypted account credentials.
4. **Tenant Access & Context**:
   - Contract: `com.finx.deal.service.DealAccessService`
   - Attributes: Verification of organization membership, role authorization, and active member status.
5. **Contractual Agreement State**:
   - Contract: `com.finx.agreement.repository.DealAgreementRepository`
   - Attributes: Latest signed agreement version (`findTopByDealIdOrderByVersionDesc`), immutable terms SHA-256 fingerprint (`documentSha256`), and status (`AgreementStatus.SIGNED`).
6. **Invoice & Extraction Evidence**:
   - Contract: `com.finx.invoice.repository.InvoiceRepository`, `InvoiceAssessmentRepository`
   - Attributes: Persisted invoice assessment IDs, SHA-256 document fingerprints, and explicit extraction provenance (`ExtractionStatus.MANUAL_ENTRY` vs automated).

---

## Explicitly Excluded Sources

The future framework **MUST NOT** treat any of the following non-authoritative or unvalidated inputs as authoritative financial facts:

1. **Reconciliation MATCHED State (D03)**:
   - Untracked bank statements and unrepaired reconciliation algorithms cannot serve as eligibility gates or release attestations.
2. **Organization Performance Metrics (D06)**:
   - Global or broken metric computations cannot be ingested as trust evidence.
3. **Unbound Historical Milestone Approvals**:
   - A milestone status of `APPROVED` alone without durable fingerprint binding or revision tracking is treated as historical status, not present financial authorization.
4. **Masked-Only or Ambiguous Beneficiary Evidence**:
   - Accounts lacking encrypted account numbers or organizations with multiple ambiguous verified accounts fail closed.
5. **Unreconciled / Mixed Legacy Funding**:
   - Legacy Razorpay funding cannot be commingled or treated identically with bank transfer lineage.
6. **Transient Risk Aggregate IDs**:
   - In-memory aggregate deal risk identifiers (`hasDurableAggregate = false`, `isTransient = true`) must never be cited as durable foreign keys.
7. **Manual Extraction Misrepresentation**:
   - Manual invoice entries must never be labeled as automated OCR or AI-verified evidence.
8. **Missing or Stale Evidence**:
   - Data absence must fail closed (status `MISSING`, `null` score/IDs) and must never default to `ALLOW`, `SAFE`, or `LOW_RISK`.

---

## No-Execution Boundary

Proven by reflection and dependency analysis in `RestrictedFrameworkArchitectureTest`:
- Framework-facing evidence services (`RiskEvidenceReadService`, `ApprovalApplicabilityService`, `VerificationEligibilityService`, `DealAccessService`) declare **zero** fields, **zero** constructor injections, and **zero** method return types of prohibited executing components.
- Prohibited dependencies strictly excluded from read paths:
  - `BankingProvider`
  - `SettlementAuthorizationService`
  - `PaymentOrchestrationService`
  - `LedgerService`
  - `EscrowService`
  - `RazorpayService`
  - `PaymentService`

---

## BankingProvider Boundary

- **Calls to `BankingProvider.initiateSettlement`**: Exactly **0**.
- Read operations (`getDealRiskEvidence`, `evaluateApprovalApplicability`, `evaluateBeneficiaryEligibility`) do not trigger outbound bank communications, test webhook dispatches, or account balance inquiries.
- Production money movement remains disabled in `VerificationEligibilityService.validateProductionMoneyMovement()` and `SettlementAuthorizationService`.

---

## Settlement Command Boundary

- Evidence retrieval executes zero commands on settlement orchestration:
  - `authorizeAndInitiateSettlement`: 0 calls
  - `authorizeSettlement`: 0 calls
  - Settlement instruction generation: 0 rows created
  - Payment instruction generation: 0 rows created
- Settlement instruction delta from reads: **0**.

---

## Ledger Mutation Boundary

- Evidence retrieval invokes zero ledger mutating methods:
  - `LedgerService.postJournalEntry`: 0 calls
  - Repository saves to `journal_entries`: 0
  - Repository saves to `journal_lines`: 0
- Journal entry delta from reads: **0**.

---

## Escrow Mutation Boundary

- Evidence retrieval performs pure entity lookups without side effects:
  - `EscrowService.fundEscrow`: 0 calls
  - `EscrowService.releaseEscrow`: 0 calls
  - `EscrowLedger` row delta: **0**
  - `EscrowAccount` balance delta: **0**

---

## Legacy Razorpay Boundary

- Evidence read adapters have zero imports or runtime calls to `RazorpayService` or `PaymentService` legacy order creation.
- Strict `BANK` payment rail mode is preserved.
- Legacy records are labeled with explicit legacy provenance.

---

## Blockchain Boundary

- No active blockchain or Web3 executor exists in the backend Maven lifecycle.
- Evidence read services contain zero imports or references to blockchain/smart-contract classes.

---

## Tenant Boundary

- Evidence reads strictly enforce B07 tenant scoping:
  - Organization members can only read evidence for deals belonging to their organization.
  - Cross-tenant requests throw `UnauthorizedException`.
  - Inactive or suspended members are rejected.
  - Organization ID cannot be substituted with User ID.

---

## Source ID Integrity

Proven in `RestrictedFrameworkSourceReferenceTest`:
- Every durable ID exposed by read snapshots resolves to an existing persisted database entity:
  - Buyer persisted assessment ID -> Matches database `RiskAssessment`
  - Vendor persisted assessment ID -> Matches database `RiskAssessment`
  - Invoice persisted assessment ID -> Matches database `InvoiceAssessment`
  - Beneficiary bank account ID -> Matches database `OrganizationBankAccount`
  - Agreement ID & version -> Matches database `DealAgreement`
- No transient UUID is ever cited as durable.

---

## Provenance Preservation

Evidence contracts retain clear, unflattened provenance:
- Bank funding lineage vs. legacy payment rail
- Manual invoice entry (`ExtractionStatus.MANUAL_ENTRY`) vs. automated OCR
- Buyer subject evidence vs. Vendor subject evidence
- Persisted assessment ID vs. transient in-memory view
- Fully verified bank account vs. masked-only account
- Bound, currently fresh approval vs. historical unbound approval

---

## Missing Evidence Semantics

Negative trust verification proves:
- When risk scores or assessments are missing, evidence status is explicitly `MISSING`, score is `null`, and assessment ID is `null`.
- The system **never** fabricates default `ALLOW` decisions or default `LOW_RISK` scores for missing data.

---

## Freshness Semantics

Approval freshness evaluation enforces:
- Material modifications to milestones after deliverable review invalidate applicability (`MILESTONE_MODIFIED_AFTER_APPROVAL`).
- New invoice revisions or superseded assessments invalidate applicability (`INVOICE_NOT_APPROVED`, `INVOICE_REVIEW_SUPERSEDED`).
- Agreement draft modifications or unsigned newer versions invalidate applicability (`AGREEMENT_NOT_SIGNED`).
- Beneficiary account changes or multiple ambiguous accounts invalidate applicability (`BENEFICIARY_INELIGIBLE`).
- Historical milestone approvals in current schema are correctly recognized as unbound (`UNBOUND_MILESTONE_APPROVAL`).

---

## PostgreSQL Verification

Database-backed regression tests were executed against a real PostgreSQL 16 container (`finx_postgres`, database `finx_b06_test`):
- `FlywayCleanMigrationTest`: Migrations V1 through V24 applied cleanly with state `SUCCESS`.
- `PostgresFinancialIdentityTest`: Idempotency constraints and unique indexes verified against concurrent duplicates.
- `PostgresFinancialConsistencyTest`: Journal line balance and ledger transaction integrity verified under real Postgres transactions.
- `PostgresLockingConcurrencyTest`: Pessimistic locking and serialization behavior verified without deadlocks.

---

## Architecture Tests

Implemented in `backend/src/test/java/com/finx/security/RestrictedFrameworkArchitectureTest.java`:
- Test 1: Evidence read services do not declare prohibited mutating fields (PASS).
- Test 2: Evidence read service constructors do not accept prohibited components (PASS).
- Test 3: Evidence read service methods do not return prohibited components (PASS).
- Test 4: `RiskEvidenceReadService` does not depend on `ReconciliationService` (PASS).
- Test 5: `ApprovalApplicabilityService` does not depend on `ReconciliationService` (PASS).
- Test 6: `VerificationEligibilityService` fails closed on production money movement (PASS).
- Test 7: `SettlementAuthorizationService` payout execution remains disabled (PASS).
- Test 8: No blockchain executor referenced by evidence services (PASS).

---

## Integration Side-Effect Tests

Implemented in `backend/src/test/java/com/finx/security/RestrictedFrameworkBoundaryContainmentTest.java`:
- Test 1: Evidence reads produce exactly zero side-effects across risk history, reviews, events, journal entries, escrow ledger, and settlement instructions (PASS).
- Test 2: Missing evidence fails closed with null values, never defaulting to SAFE or ALLOW (PASS).
- Test 3: Subsequent material changes invalidate historical milestone approval (PASS).
- Test 4: Masked-only bank account without verified presence fails closed (PASS).
- Test 4b: Ambiguous multiple verified bank accounts fail closed (PASS).
- Test 5: Manual invoice provenance remains explicitly manual, not relabeled as OCR/AI (PASS).
- Test 6: Non-authoritative reconciliation cannot act as a financial gate (PASS).
- Test 7: Cross-tenant evidence access is denied (PASS).
- Test 8: Inactive organization membership is denied evidence access (PASS).

Implemented in `backend/src/test/java/com/finx/security/RestrictedFrameworkSourceReferenceTest.java`:
- Tests 1–9: Durable source IDs exist for buyer, vendor, invoice, beneficiary, agreement; missing sources remain missing; transient aggregates are marked non-durable (PASS).

---

## Regression Results

All critical blocker regression containment test suites passed:
- `PaymentConfirmationContainmentTest`: PASS
- `BankingWebhookControllerTest`: PASS
- `EscrowReleaseContainmentTest`: PASS
- `SettlementExecutionContainmentTest`: PASS
- `LegacyBoundaryContainmentTest`: PASS
- `TenantIsolationContainmentTest`: PASS
- `PlatformRoleProvisioningContainmentTest`: PASS
- `BeneficiaryVerificationContainmentTest`: PASS
- `ApprovalFreshnessContainmentTest`: PASS
- `LockingAndRetryContainmentTest`: PASS
- `FinancialIdempotencyContainmentTest`: PASS
- `RiskEvidenceReadContainmentTest`: PASS
- `RestrictedFrameworkBoundaryContainmentTest`: PASS
- `RestrictedFrameworkArchitectureTest`: PASS
- `RestrictedFrameworkSourceReferenceTest`: PASS

---

## Full Suite

Executed command: `.\mvnw.cmd test`
- **Total Tests Run**: 647
- **Failures**: 0
- **Errors**: 0
- **Skipped**: 1 (pre-existing mock integration test)
- **Result**: BUILD SUCCESS
- **Execution Time**: 07:02 min

---

## Database Migration

- **Database Migration**: **NONE**
- No Flyway migration (V25 or otherwise) was introduced.
- Existing Flyway migrations V1–V24 remain unmodified and verified.

---

## Deferred Work

The following deferred roadmap items remain excluded and are **not** prerequisites for the restricted common framework:
- **D01**: Real webhook signature verification for production banking providers.
- **D02**: Production ledger partitioning and archival.
- **D03**: Full reconciliation matching repair and ledger statement synchronization.
- **D04**: Escrow auto-sweep and banking pool account balancing.
- **D05**: Production banking provider integration and multi-rail failover.
- **D06**: Multi-tenant organization performance analytics computation.
- **D07**: Advanced OCR document processing models.
- **D08**: Real-time compliance screening feeds.
- **F01**: Durable outbox and event streaming broker.
- **F02**: Asynchronous workflow orchestration engine.

These items relate to production money movement, external bank integrations, or background workers, none of which are required for internal, read-only decision recording.

---

## Production Money Movement Status

Production money movement remains **STRICTLY BLOCKED** and **DISABLED**.

Prior to any future consideration of live payout execution:
1. **B04 Durable Execution**: Robust two-phase settlement execution and payout state machine must be completed.
2. **B09 Beneficiary Snapshot**: Immutable binding of beneficiary snapshot at authorization time must be established.
3. **B10 Persistent Approval Fingerprint**: Revision-tracked, durable milestone approval fingerprints must be added to the schema.
4. **D03 Reconciliation Attestation**: Authoritative bank statement reconciliation must be proven.
5. **D05 Bank Provider Adapter**: Certified production banking provider adapter must be configured.

---

## Boundary Verdict

- **RESTRICTED FRAMEWORK SAFE TO START**: **YES**
- **PRODUCTION MONEY MOVEMENT SAFE**: **NO**
