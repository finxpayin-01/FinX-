# Milestone Rules + Approval Decision Adapters Report

## Scope

Implemented:
- `MILESTONE_RULES` read-only EngineDecision adapter (`com.finx.engine.adapter.MilestoneRulesDecisionAdapter`)
- `APPROVAL` read-only EngineDecision adapter (`com.finx.engine.adapter.ApprovalDecisionAdapter`)
- Shared canonical evidence and scalar reading utilities (`MilestoneDecisionEvidenceReader`, `DecisionEvidence`)
- Read-only test fixtures and architecture validation

No financial execution, no enterprise approval workflow, no schema migrations, and no payout execution.

## Preconditions

- B01-B13: PASS
- Restricted boundary: PASS (`b605858`)
- Common EngineDecision framework: PASS (`c8137f8`)
- RISK + DOCUMENT read-only adapters: PASS (`60ad399`)
- Milestone + Approval readiness audit: PASS (`a3c14b6`)

## MilestoneRulesDecisionAdapter

- Class: `com.finx.engine.adapter.MilestoneRulesDecisionAdapter`
- Implements: `ReadOnlyDecisionAdapter` (and `DecisionEvaluator`)
- Engine Type: `EngineType.MILESTONE_RULES`
- Rule Version: `MILESTONE_RULES_V1`
- Dependencies:
  - `MilestoneDecisionEvidenceReader`: Reader for Deal, Milestone, Deliverables, Agreement, Invoices, Disputes, and Escrow Account evidence
  - `MilestoneRepository`: Read-only deal milestone sequence and allocation queries
  - `EscrowAccountRepository`: Read-only deal escrow balance lookup
  - `EscrowLedgerRepository`: Read-only historical RELEASE transaction check
- Prohibited Dependencies: Zero references or constructor parameters for `BankingProvider`, `SettlementAuthorizationService`, `PaymentOrchestrationService`, `LedgerService`, `EscrowService`, `RazorpayService`, `PaymentService`, or controllers.

## Evaluation Owner

- `organizationId`: `deal.buyerOrganizationId` (UUID)
- Strict validation: If `deal.buyerOrganizationId` is `null` or does not exist in `OrganizationRepository`, the evaluation fails closed with `UnauthorizedException`.
- The buyer user's UUID is never substituted for the organization ID.

## Subject

- `subjectType`: `DecisionSubjectType.MILESTONE`
- `subjectId`: `milestone.id` (UUID)

## Implemented Milestone Rules

1. **C1. Currency Equality**: Verifies `deal.currency.equalsIgnoreCase(milestone.currency)`. Mismatch produces `BLOCK` with `MILESTONE_CURRENCY_MISMATCH`. Also verifies supported currency is INR (`MILESTONE_CURRENCY_UNSUPPORTED`).
2. **C2. Positive Milestone Amount**: Verifies `milestone.amount != null && milestone.amount.compareTo(BigDecimal.ZERO) > 0`. Invalid amount produces `BLOCK` with `MILESTONE_AMOUNT_INVALID`.
3. **C3. Milestone <= Deal Total**: Verifies `milestone.amount.compareTo(deal.totalAmount) <= 0`. Exceeding deal produces `BLOCK` with `MILESTONE_AMOUNT_EXCEEDS_DEAL`.
4. **C4. Total Milestone Allocation**: Calculates sum of all non-`CANCELLED` milestones for the deal. If sum != `deal.totalAmount`, produces `BLOCK` with `MILESTONE_ALLOCATION_MISMATCH`.
5. **C5. Current Signed Agreement**: Fetches latest agreement by version. Missing agreement produces `REVIEW` (`AGREEMENT_MISSING`). Non-`SIGNED` agreement produces `REVIEW` (`AGREEMENT_NOT_SIGNED`).
6. **C6. Deliverables Verification**: Checks deliverable submissions for milestone. Missing deliverables produces `REVIEW` (`DELIVERABLES_MISSING`). Non-`APPROVED` deliverables produce `REVIEW` (`DELIVERABLES_PENDING_APPROVAL`).
7. **C7. Deadline Semantics**: If deadline has passed and completion evidence is absent, produces `REVIEW` (`DEADLINE_EXCEEDED_REVIEW`).
8. **C8. Invoice Requirement**: If `milestone.invoiceRequired == true` and no invoice exists, produces `REVIEW` (`INVOICE_EVIDENCE_MISSING`).
9. **C9. Invoice Freshness**: Required invoice must be approved, with matching assessment and review timestamps. Stale/superseded invoice produces `REVIEW` (`INVOICE_EVIDENCE_STALE`).
10. **C10. Human Invoice Approval**: Verifies `InvoiceReview.status == APPROVED` and `decision == APPROVE` with reviewer ID and timestamp.
11. **C11. Active Dispute Check**: Checks for open disputes on deal or milestone. Open dispute produces `BLOCK` (`MILESTONE_ACTIVE_DISPUTE`).
12. **C12. Confirmed Escrow Funds**: Reads confirmed balance in `EscrowAccount`. Missing account produces `REVIEW` (`ESCROW_ACCOUNT_MISSING`). Balance < `milestone.amount` produces `REVIEW` (`INSUFFICIENT_CONFIRMED_FUNDS`).
13. **C13. Prior Release Check**: Queries `EscrowLedger` for `(milestone_id, TransactionType.RELEASE)`. Prior release produces `BLOCK` (`MILESTONE_ALREADY_RELEASED`).
14. **C14. Milestone Status Check**: `REJECTED` produces `BLOCK` (`MILESTONE_STATUS_REJECTED`). `CANCELLED` produces `BLOCK` (`MILESTONE_STATUS_CANCELLED`).
15. **C15. Sequence Prerequisite Check**: All prior non-cancelled milestones in the deal must have status `COMPLETED`. Incomplete prior milestone produces `REVIEW` (`SEQUENCE_PREREQUISITE_UNMET`).

## Deferred Milestone Rules

The following are explicitly outside the scope of `MilestoneRulesDecisionAdapter`:
- Active reservation subtraction
- Partial / progressive release
- Arbitrary dependency DAG modeling
- Configurable grace periods and late penalties
- Trust and Fraud engine integration

## Milestone Decision Mapping

- **BLOCK**: `MILESTONE_CURRENCY_MISMATCH`, `MILESTONE_AMOUNT_INVALID`, `MILESTONE_AMOUNT_EXCEEDS_DEAL`, `MILESTONE_ALLOCATION_MISMATCH`, `MILESTONE_ACTIVE_DISPUTE`, `MILESTONE_ALREADY_RELEASED`, `MILESTONE_STATUS_REJECTED`, `MILESTONE_STATUS_CANCELLED`
- **REVIEW**: `AGREEMENT_MISSING`, `AGREEMENT_NOT_SIGNED`, `DELIVERABLES_MISSING`, `DELIVERABLES_PENDING_APPROVAL`, `DEADLINE_EXCEEDED_REVIEW`, `INVOICE_EVIDENCE_MISSING`, `INVOICE_EVIDENCE_STALE`, `ESCROW_ACCOUNT_MISSING`, `INSUFFICIENT_CONFIRMED_FUNDS`, `SEQUENCE_PREREQUISITE_UNMET`, `MILESTONE_CURRENCY_UNSUPPORTED`
- **ALLOW**: `MILESTONE_CONTRACT_COMPLIANT`

## Sequence Rule

The adapter queries all milestones for `deal.id` ordered by sequence ascending. Any milestone with `sequence < current.sequence` that is not `CANCELLED` must be in `COMPLETED` status. If any prior milestone is `PENDING`, `IN_PROGRESS`, or `APPROVED` (not yet completed), the adapter returns `REVIEW` with `SEQUENCE_PREREQUISITE_UNMET`.

## Deadline Semantics

In accordance with the audit findings, no explicit grace-period policy or schema field currently exists. An expired deadline without completed deliverable evidence yields `REVIEW` (`DEADLINE_EXCEEDED_REVIEW`) to prompt human review rather than an unrecoverable `BLOCK`.

## Milestone Evidence References

All evidence references point to immutable/persisted entities:
- `DEAL`: `deal.id`, observed at `deal.updatedAt`
- `MILESTONE`: `milestone.id`, observed at `milestone.updatedAt`
- `AGREEMENT`: `agreement.id`, with `documentSha256`, version, and status
- `DELIVERABLE`: Each deliverable's `id`, with `status`, `reviewedBy`, and `reviewedAt`
- `INVOICE`: `invoice.id`, with extraction status provenance
- `INVOICE_ASSESSMENT`: `assessment.id`, with assessment fingerprint
- `INVOICE_REVIEW`: `review.id`, with review decision
- `DISPUTE`: `dispute.id`, for any open dispute
- `ESCROW_ACCOUNT`: `account.id`, with balance and version
- `ESCROW_LEDGER`: Prior release entry `id` if present

## Milestone Input Fingerprint

Canonical SHA-256 fingerprint generated from scalar inputs:
- Deal: id, updatedAt, totalAmount, currency, buyerOrgId, sellerOrgId
- Milestone: id, updatedAt, amount, currency, sequence, status, invoiceRequired, deadline
- Agreement: id, version, sha256, status
- Deliverables: sorted by id (id, status, reviewedAt, reviewedBy, submittedAt)
- Invoice / Review / Assessment: sorted identifiers, fingerprints, and statuses
- Disputes: sorted open dispute IDs
- Escrow Account: id, balance, version
- Escrow Ledger: prior release transaction ID

## ApprovalDecisionAdapter

- Class: `com.finx.engine.adapter.ApprovalDecisionAdapter`
- Implements: `ReadOnlyDecisionAdapter` (and `DecisionEvaluator`)
- Engine Type: `EngineType.APPROVAL`
- Rule Version: `APPROVAL_V1`
- Dependencies:
  - `MilestoneDecisionEvidenceReader`: Scoped reading of deal and milestone evidence
  - `ApprovalApplicabilityService`: B10 read-only evaluation of 7 freshness dimensions
  - `OrganizationBankAccountRepository`: Read-only verified beneficiary account lookup
  - `BusinessRiskRepository`: Read-only active risk restriction review lookup

## ApprovalApplicabilityService Reuse

The adapter directly leverages `ApprovalApplicabilityService.evaluateApprovalApplicability(dealId, milestoneId)`, preserving full B10 compliance across:
1. Milestone Approval Status & Unbound evaluation
2. Invoice Review Applicability
3. Agreement Signature Applicability
4. Beneficiary Bank Account Verification Applicability
5. Dispute Applicability
6. Risk Restriction Applicability
7. Project Manager Assignment Applicability

## Critical Unbound Approval Rule

In the current schema, historical `MilestoneStatus.APPROVED` records lack a durable revision or cryptographic fingerprint binding. Therefore, `ApprovalApplicabilityService` marks `milestoneUnbound = true`.

The `ApprovalDecisionAdapter` enforces that unbound approvals **MUST NOT** produce `DecisionStatus.ALLOW`. Instead, they return:
- Decision: `DecisionStatus.REVIEW`
- Reason: `APPROVAL_UNBOUND_MILESTONE`
- Explanation: `"Historical milestone approval is not bound to a durable revision/fingerprint and cannot act as current financial authorization."`

## Approval BLOCK Mapping

BLOCK is mapped strictly for active, evidenced prohibitions:
- `APPROVAL_MILESTONE_REJECTED`: Milestone status is `REJECTED`
- `APPROVAL_RISK_RESTRICTION_ACTIVE`: Active `RESTRICTED` risk review on buyer/seller organization
- `APPROVAL_DISPUTE_ACTIVE`: Open dispute exists on deal or milestone
- `APPROVAL_INVOICE_REJECTED`: Latest applicable invoice was explicitly rejected

## Approval REVIEW Mapping

REVIEW is mapped for stale, pending, or unbound approval states:
- `APPROVAL_UNBOUND_MILESTONE`: Historical approval lacks durable revision/fingerprint binding
- `APPROVAL_PENDING`: Milestone has not yet been approved
- `APPROVAL_MODIFIED_AFTER_DECISION`: Milestone was edited after approval
- `APPROVAL_INVOICE_PENDING`: Invoice review is pending, stale, or superseded
- `APPROVAL_AGREEMENT_NOT_SIGNED`: Latest agreement version is not signed
- `APPROVAL_BENEFICIARY_INELIGIBLE`: Beneficiary bank account is not verified or mismatched
- `APPROVAL_PM_ASSIGNMENT_CHANGED`: Project Manager assignment changed after review
- `APPROVAL_RISK_SCOPE_UNRESOLVED`: Risk review organization mapping cannot be verified

## Approval ALLOW Mapping

- Reachable under CURRENT schema: **NO**
- In the current schema without enterprise approval migrations, `milestoneUnbound` is always `true`. Consequently, `ALLOW` is unreachable for historical milestone approvals, preventing any premature financial authorization.

## Enterprise Approval Features

The following enterprise features remain **DEFERRED** and NOT IMPLEMENTED:
- `approval_workflows`, `approval_steps`, `approval_actions` tables
- Ordered and parallel approval stages
- Multi-party quorum and consensus thresholds
- Approval delegation and proxy authority
- Automatic approval expiration
- Separation of duties enforcement (requester != approver)
- Admin override workflows with justification audit trails

## Approval Evidence References

Durable evidence references are captured from persisted database records:
- `MILESTONE`: `milestone.id`
- `DELIVERABLE`: Reviewed deliverable `id` with reviewer identity
- `AGREEMENT`: `agreement.id` with version and hash
- `INVOICE_ASSESSMENT`: `assessment.id` with fingerprint
- `INVOICE_REVIEW`: `review.id` with decision
- `ORGANIZATION_BANK_ACCOUNT`: Verified bank account `id`
- `DISPUTE`: Active dispute `id`
- `RISK_REVIEW`: Active restriction review `id`

Transient in-memory applicability objects are never referenced as durable IDs.

## Approval Input Fingerprint

Deterministic SHA-256 fingerprint generated from:
- Deal ID and organization boundaries
- Milestone ID, status, and update timestamp
- Deliverable review state and reviewer IDs
- Agreement ID, version, and document hash
- Invoice assessment ID and fingerprint
- Invoice review ID, decision, and reviewer ID
- Beneficiary bank account ID and verification timestamp
- Project Manager ID
- Active dispute IDs
- Active risk restriction IDs

## Tenant Isolation

Both adapters enforce strict tenant isolation:
- Queries are scoped to `deal.buyerOrganizationId`.
- Evaluation fails closed if the caller does not belong to the organization or if cross-tenant deal/milestone access is attempted (`UnauthorizedException`).
- `EngineDecision` records are persisted with `organizationId = deal.buyerOrganizationId`.

## Side Effects

- Bank calls (`BankingProvider`): **0**
- Ledger writes (`EscrowLedger` / `JournalEntry`): **0**
- Escrow writes (`EscrowAccount`): **0**
- Settlement writes (`SettlementInstruction`): **0**
- Approval mutations (`Milestone` / `InvoiceReview`): **0**
- Financial side effects: **0**

## RELEASE Decisions Created

- `EngineType.RELEASE` decisions generated: **0**

## Database Migration

- Migrations created: **NONE**
- V26 created: **NO**
- V1-V25 modified: **NO**

## Files Modified

- `backend/src/test/java/com/finx/engine/ReadOnlyDecisionAdapterArchitectureTest.java`
- `backend/src/test/java/com/finx/FlywayCleanMigrationTest.java`

## Files Created

- `backend/src/main/java/com/finx/engine/adapter/MilestoneRulesDecisionAdapter.java`
- `backend/src/main/java/com/finx/engine/adapter/ApprovalDecisionAdapter.java`
- `backend/src/main/java/com/finx/engine/adapter/MilestoneDecisionEvidenceReader.java`
- `backend/src/main/java/com/finx/engine/adapter/DecisionEvidence.java`
- `backend/src/test/java/com/finx/engine/MilestoneRulesDecisionAdapterTest.java`
- `backend/src/test/java/com/finx/engine/ApprovalDecisionAdapterTest.java`
- `backend/src/test/java/com/finx/engine/MilestoneAdapterFixture.java`
- `backend/src/test/java/com/finx/engine/MilestoneApprovalDecisionPersistenceTest.java`
- `MILESTONE_APPROVAL_DECISION_ADAPTERS_REPORT.md`

## Tests Added

- `MilestoneRulesDecisionAdapterTest`: 44 test cases (contractual rules C1-C15, currency, amounts, allocation, agreement, deliverables, invoice, dispute, escrow, release, status, sequence, fingerprint determinism, tenant isolation)
- `ApprovalDecisionAdapterTest`: 20 test cases (unbound milestone review invariant, pending, rejected, dispute, risk, stale invoice, unsigned agreement, beneficiary, PM assignment, fingerprint determinism, tenant isolation)
- `ReadOnlyDecisionAdapterArchitectureTest`: 4 architectural tests verifying zero financial/banking dependencies across all adapters
- `MilestoneApprovalDecisionPersistenceTest`: 3 Spring Boot integration tests verifying idempotent persistence, shared evaluation context, real durable references, caller auth, and zero side effects

## Tests Run

- Focused adapter tests: 71 tests, 0 failures, 0 errors
- Architecture tests: 4 tests, 0 failures, 0 errors
- Full test suite: 774 tests executed

## Test Results

- Failures: 0
- Errors: 0
- Status: **PASS / BUILD SUCCESS**

## Production Payout

- Status: **DISABLED** (Fail closed in `SettlementAuthorizationService`)

## Adapter Status

- `MILESTONE_RULES`: **PASS**
- `APPROVAL`: **PASS**

## Engine Adapter Coverage After This Phase

- `RISK`: IMPLEMENTED
- `DOCUMENT`: IMPLEMENTED
- `MILESTONE_RULES`: IMPLEMENTED
- `APPROVAL`: IMPLEMENTED READ-ONLY
- `TRUST`: NOT IMPLEMENTED
- `FRAUD`: NOT IMPLEMENTED
- `RECONCILIATION`: NOT IMPLEMENTED
- `DISPUTE`: NOT IMPLEMENTED
- `RELEASE`: NOT IMPLEMENTED

**Total Coverage**: 4 / 8 prerequisite component adapters implemented (RISK, DOCUMENT, MILESTONE_RULES, APPROVAL).

## Ready For Remaining Engine Readiness Audit

**YES**
