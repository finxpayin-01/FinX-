# Milestone Rules + Approval Readiness Audit

## Executive Verdict

MILESTONE_RULES READY:
YES (READ-ONLY ADAPTER)

APPROVAL READ-ONLY ADAPTER READY:
YES

ENTERPRISE APPROVAL ENGINE READY:
NO

PRODUCTION PAYOUT READY:
NO

---

## Current Platform State

- **B01–B13 Safety Controls**: PASS (Full compliance maintained)
- **Restricted Framework Boundary**: PASS (Commit `b605858`)
- **Common EngineDecision Framework**: PASS (Commit `c8137f8`, 9 engine types, 3 decision statuses)
- **RISK Adapter**: PASS (Commit `60ad399`, buyer/vendor separation, zero side effects)
- **DOCUMENT Adapter**: PASS (Commit `60ad399`, assessment identity, manual entry provenance, review freshness)
- **Production Payout**: Strictly **DISABLED** (`SettlementAuthorizationService.authorizeAndInitiateSettlement` fails closed unconditionally via `AccessDeniedException`)
- **Active Test Suite**: 707 tests run, 0 failures, 0 errors, 1 skipped (BUILD SUCCESS)

---

## Part A — Milestone Rules Existing Sources & Detailed Audit

### Authoritative Source Inventory

| Concern | Authoritative Source | Persisted? | Freshness Available? | Read-Only? | Safe For Adapter? |
|---|---|---|---|---|---|
| Deal Canonical Value | `Deal.totalAmount` (`BigDecimal(15,2)`) | YES (`deals.total_amount`) | YES (`Deal.updatedAt`) | YES | YES |
| Deal Currency | `Deal.currency` (`String(10)`, canonical "INR") | YES (`deals.currency`) | YES | YES | YES |
| Milestone Amount | `Milestone.amount` (`BigDecimal(15,2)`) | YES (`milestones.amount`) | YES (`Milestone.updatedAt`) | YES | YES |
| Milestone Currency | `Milestone.currency` (`String(10)`, independent) | YES (`milestones.currency`) | YES | YES | YES |
| Sequence Ordering | `Milestone.sequence` (`int`, non-unique index) | YES (`milestones.sequence`) | N/A | YES | YES |
| Milestone Status | `Milestone.status` (`MilestoneStatus` enum) | YES (`milestones.status`) | YES (`updatedAt`) | YES | YES |
| Milestone Deadline | `Milestone.dueDate` & `submissionDeadline` (`Instant`) | YES (`milestones.submission_deadline`) | YES | YES | YES |
| Grace Period | ABSENT in domain & database | NO | NO | N/A | NO (Requires policy) |
| Agreement Selection | `DealAgreementRepository.findTopByDealIdOrderByVersionDesc` | YES (`deal_agreements`) | YES (`updatedAt`) | YES | YES |
| Agreement Version | `DealAgreement.version` (`int`) | YES (`deal_agreements.version`) | YES | YES | YES |
| Agreement SHA-256 | `DealAgreement.documentSha256` (`String(64)`) | YES (`deal_agreements.document_sha256`) | YES | YES | YES |
| Agreement Signatures | `DealAgreement.status == SIGNED` (buyer + seller accepted) | YES (`buyer_accepted_at`, `seller_accepted_at`) | YES | YES | YES |
| Deliverable Submissions | `Deliverable` (`deliverables` table) | YES (`deliverables`) | YES (`submitted_at`) | YES | YES |
| Deliverable Approval | `Deliverable.status == APPROVED` + `reviewedAt` + `reviewedBy` | YES (`deliverables.status`) | YES (`reviewed_at`) | YES | YES |
| Deliverable Hash/Revision | ABSENT in entity and schema | NO | NO | N/A | NO |
| Invoice Required Flag | `Milestone.invoiceRequired` (`boolean`) | YES (`milestones.invoice_required`) | YES | YES | YES |
| Latest Invoice Selection | `InvoiceRepository.findFirstByMilestoneIdOrderByCreatedAtDesc` | YES (`invoices`) | YES (`invoices.created_at`) | YES | YES |
| Invoice Assessment Freshness | `InvoiceAssessment.fingerprint` + `InvoiceReview.assessmentId` | YES (`invoice_assessments`, `invoice_reviews`) | YES (`createdAt`, `reviewedAt`) | YES | YES |
| Human Invoice Approval | `InvoiceReview` (`status == APPROVED`, `decision == APPROVE`) | YES (`invoice_reviews`) | YES (`reviewed_at`) | YES | YES |
| Open Disputes | `DisputeRepository.findByDealIdOrderByCreatedAtDesc` | YES (`disputes`) | YES (`status == OPEN`) | YES | YES |
| Confirmed Escrow Funding | `EscrowLedger` (`transactionType == FUND`) via `PaymentInstruction` | YES (`escrow_ledger.payment_instruction_id`) | YES | YES | YES |
| Available Balance | `EscrowAccount.balance` / `getAvailableBalance()` | YES (`escrow_accounts.balance`) | YES (`@Version version`) | YES | YES |
| Active Reservations | ABSENT in entity and schema | NO | NO | N/A | NO |
| Prior Release Prevention | `uk_escrow_ledger_milestone_type` (`milestone_id, transaction_type`) | YES (`escrow_ledger`) | YES | YES | YES |
| Partial Release Support | UNSUPPORTED (Schema permits 1 RELEASE entry per milestone) | NO | NO | N/A | NO |
| Dependency Graph | ABSENT (No DAG/prerequisite entities) | NO | NO | N/A | NO |
| Sequential Enforcement | NOT enforced (only sequence integer exists) | NO | NO | YES | NEEDS ADAPTER LOGIC |

---

### Detailed Milestone Rules Questions & Code Findings

1. **DEAL AMOUNT SOURCE**:
   - Canonical deal value is `Deal.totalAmount` (`@Column(name = "total_amount", nullable = false, precision = 15, scale = 2) private BigDecimal totalAmount;`), accessed via `deal.getTotalAmount()`.
2. **DEAL CURRENCY SOURCE**:
   - Canonical deal currency is `Deal.currency` (`@Column(name = "currency", nullable = false, length = 10) private String currency = "INR";`). `EscrowService.validateSupportedCurrency` strictly enforces that only `"INR"` is currently supported across the platform.
3. **MILESTONE AMOUNT SOURCE**:
   - `Milestone.amount` (`@Column(name = "amount", nullable = false, precision = 15, scale = 2) private BigDecimal amount;`).
4. **MILESTONE CURRENCY**:
   - Persisted independently on `Milestone` (`@Column(name = "currency", nullable = false, length = 10) private String currency = "INR";`). Defaults to `deal.getCurrency()` upon creation if omitted in request, but no database constraint enforces cross-table equality.
5. **MILESTONE SEQUENCE**:
   - Managed via `@Column(name = "sequence", nullable = false) private int sequence = 1;`. The table contains `@Index(name = "idx_milestones_sequence", columnList = "deal_id, sequence")`. This is a non-unique index. Uniqueness is not guaranteed at the schema level; order is determined by `ORDER BY sequence ASC`.
6. **MILESTONE STATUS MODEL**:
   - Defined in `MilestoneStatus.java`: `PENDING`, `IN_PROGRESS`, `SUBMITTED`, `UNDER_REVIEW`, `APPROVED`, `REJECTED`, `OVERDUE`, `COMPLETED`, `CANCELLED`.
7. **DEADLINE**:
   - Exact fields are `Milestone.dueDate` and `Milestone.submissionDeadline` (`Instant`). Stored as UTC timestamps. `getSubmissionDeadline()` defaults to `dueDate` if null.
8. **GRACE PERIOD**:
   - **ABSENT**. There is no grace period field, configuration, or calculation in the domain model or database schema.
9. **AGREEMENT**:
   - Latest agreement is resolved via `DealAgreementRepository.findTopByDealIdOrderByVersionDesc(dealId)`, selecting the highest version integer.
10. **AGREEMENT VERSION**:
    - **Persisted**: `DealAgreement.version` (`int`) in `deal_agreements.version`. Unique constraint exists on `(deal_id, version)`.
11. **AGREEMENT HASH**:
    - **Persisted**: `DealAgreement.documentSha256` (`String(64)`) in `deal_agreements.document_sha256`. Computed via SHA-256 over terms text upon creation.
12. **AGREEMENT SIGNATURE APPLICABILITY**:
    - Requires `DealAgreement.status == AgreementStatus.SIGNED`. This requires both `buyerAcceptedAt != null` and `sellerAcceptedAt != null`. Signer must have `ACTIVE` status with role `OWNER`, `ADMIN`, or `AUTHORIZED_SIGNATORY` in their respective organization.
13. **DELIVERABLE REQUIREMENT**:
    - Represented by rows in `deliverables` table (`com.finx.milestone.entity.Deliverable`) containing `milestoneId`, `submittedBy`, `fileName`, `fileUrl`, `submittedAt`, and `status`.
14. **DELIVERABLE APPROVAL**:
    - Deliverable review state is represented by `Deliverable.status == DeliverableStatus.APPROVED`, `Deliverable.reviewedAt` (`Instant`), and `Deliverable.reviewedBy` (`UUID`).
15. **DELIVERABLE REVISION/HASH**:
    - **ABSENT**. `Deliverable` contains only string file URLs and names; no private document byte hash, SHA-256 digest, or deliverable revision integer is stored.
16. **INVOICE REQUIRED**:
    - `Milestone.invoiceRequired` (`boolean`) in `milestones.invoice_required`, accessed via `milestone.isInvoiceRequired()`.
17. **CURRENT INVOICE**:
    - Selected via `InvoiceRepository.findFirstByMilestoneIdOrderByCreatedAtDesc(milestoneId)`.
18. **CURRENT INVOICE ASSESSMENT**:
    - Freshness is verified by requiring:
      1. Invoice status is `APPROVED`.
      2. Approved `InvoiceReview` exists.
      3. Latest `InvoiceAssessment` exists.
      4. `review.getAssessmentId().equals(latestAssessment.id)` (prevents superseded evidence).
      5. `!invoice.getUpdatedAt().isAfter(review.getReviewedAt())` (prevents post-review mutations).
19. **HUMAN INVOICE APPROVAL**:
    - Entity `InvoiceReview` (table `invoice_reviews`) with `status == InvoiceReviewStatus.APPROVED` and `decision == ReviewDecision.APPROVE`. Reviewer must be the assigned Project Manager or an Admin (`InvoiceService.verifyReviewer`).
20. **DISPUTE**:
    - Detected via `DisputeRepository.findByDealIdOrderByCreatedAtDesc(dealId)` filtering for `status == DisputeStatus.OPEN`. Also checked at milestone level via `milestoneId.equals(d.getMilestoneId())`.
21. **ESCROW CONFIRMED FUNDING**:
    - Confirmed funding is established via `EscrowLedger` entries with `transactionType == TransactionType.FUND` and non-null `paymentInstructionId` referencing a `PaymentInstruction` with status `SUCCESS`. Unique constraint `uk_escrow_ledger_milestone_type` guarantees that a milestone cannot be funded more than once.
22. **AVAILABLE BALANCE**:
    - `EscrowAccount.balance` (`BigDecimal(15,2)`), accessed via `getAvailableBalance()`.
23. **PENDING RESERVATIONS**:
    - **ABSENT**. No reservation model, column, or reservation table exists in the system.
24. **PREVIOUS RELEASE**:
    - Enforced by database unique constraint `uk_escrow_ledger_milestone_type` on `(milestone_id, transaction_type)`. If an `EscrowLedger` row with `milestone_id` and `transaction_type == RELEASE` exists, duplicate release is strictly rejected.
25. **PARTIAL RELEASE**:
    - **UNSUPPORTED**. System only supports releasing the full milestone amount in a single operation. The unique constraint prevents multiple release slices.
26. **DEPENDENCY GRAPH**:
    - **ABSENT**. No directed acyclic graph (DAG) or milestone prerequisite table exists.
27. **SEQUENTIAL DEPENDENCY**:
    - Currently **NOT enforced in production code**. Milestones can be approved and settled out of sequence; only an integer sequence tag exists.
28. **STATE VERSION**:
    - A deterministic composite fingerprint can be constructed today from:
      `deal.id + ":" + deal.updatedAt + ":" + milestone.id + ":" + milestone.updatedAt + ":" + milestone.amount + ":" + milestone.currency + ":" + agreement.version + ":" + agreement.documentSha256 + ":" + (invoiceAssessment != null ? invoiceAssessment.fingerprint : "NO_INVOICE")`.
29. **MATERIAL WRITERS**:
    - Every operation that can invalidate milestone eligibility:
      1. `MilestoneService.updateMilestoneDeadline`: changes submission deadline, updates `milestone.updatedAt`.
      2. `MilestoneService.rejectMilestone`: transitions milestone to `REJECTED`.
      3. `AgreementService.createOrUpdateDraft`: creates a new draft version or updates terms, moving agreement status to `DRAFT`.
      4. `InvoiceService.uploadInvoice`: vendor uploads revised invoice, causing latest revision to be `PROCESSING`.
      5. `InvoiceService.recalculateInvoiceMatching`: recalculates match, creating a new `InvoiceAssessment` with a new fingerprint, superseding existing review.
      6. `InvoiceService.submitReviewDecision`: reviewer rejects invoice or requests information.
      7. `VerificationService` bank account modifications: seller bank account revoked or multiple accounts verified (ambiguity).
      8. `ProjectManagerInvitationService`: deal PM removed or reassigned.
      9. `DisputeService.createDispute`: opens dispute on deal or milestone, moving deal to `DISPUTED`.
      10. `RiskActionService.payoutReviews`: introduces active `RESTRICTED` review on buyer or seller.
      11. Escrow fund debit / refund: lowers balance below milestone amount.
30. **READ-ONLY EVALUATION POSSIBILITY**:
    - **YES**. All authoritative facts can be retrieved using read-only JPA queries without triggering side effects or database mutations.

---

## Part B — Milestone Rules Rule-by-Rule Target Classification

| Rule | Current Source | Current Enforcement | Readiness | Missing Work | Migration Needed? |
|---|---|---|---|---|---|
| **A. Deal/Milestone Currency Equality** | `Deal.currency`, `Milestone.currency` | Checked at escrow fund time; not enforced at milestone creation | **READY NOW** | Add comparison in decision adapter logic | NONE |
| **B. Positive Milestone Amount** | `Milestone.amount` | DTO `@DecimalMin("0.01")` | **READY NOW** | Add `amount.compareTo(ZERO) > 0` check in adapter | NONE |
| **C. Milestone Amount <= Deal Amount** | `Milestone.amount`, `Deal.totalAmount` | Checked at milestone creation | **READY NOW** | Add `milestone.amount <= deal.totalAmount` in adapter | NONE |
| **D. Sum of Milestones == Deal Total** | All deal milestones in `milestoneRepository` | Only upper bound checked at creation; exact equality not checked | **READY NOW** | Compute sum of non-cancelled milestones in adapter | NONE |
| **E. Signed Current Agreement Required** | `DealAgreementRepository` | Fully checked in `ApprovalApplicabilityService` | **READY NOW** | Reuse `evaluateAgreementApplicability` | NONE |
| **F. Deliverables Submitted** | `DeliverableRepository` | Checked in `MilestoneService` | **READY NOW** | Verify non-empty deliverables in adapter | NONE |
| **G. Deliverables Currently Approved** | `Deliverable.status` | Set upon milestone approval | **READY NOW** | Verify all deliverables are `APPROVED` in adapter | NONE |
| **H. Deadline / Grace Policy** | `Milestone.dueDate`, `submissionDeadline` | Overdue flags exist; grace policy absent | **DEFER** | Product specification required for late/grace rules | NONE (for now) |
| **I. Invoice Required / Current** | `Milestone.invoiceRequired`, `InvoiceRepository` | Fully checked in `ApprovalApplicabilityService` | **READY NOW** | Reuse `evaluateInvoiceApplicability` | NONE |
| **J. Invoice Assessment Current** | `InvoiceAssessmentRepository` | Fully checked in `ApprovalApplicabilityService` | **READY NOW** | Reuse `evaluateInvoiceApplicability` | NONE |
| **K. Invoice Human Approval Current** | `InvoiceReviewRepository` | Fully checked in `ApprovalApplicabilityService` | **READY NOW** | Reuse `evaluateInvoiceApplicability` | NONE |
| **L. No Active Dispute** | `DisputeRepository` | Fully checked in `ApprovalApplicabilityService` | **READY NOW** | Reuse `evaluateDisputeApplicability` | NONE |
| **M. Sufficient Confirmed Escrow Funds** | `EscrowAccount.balance`, `Milestone.amount` | Checked in `EscrowService` before release | **READY NOW** | Add `account.balance >= milestone.amount` in adapter | NONE |
| **N. Sufficient Funds Minus Reservations** | Reservation model is absent | Absent | **SCHEMA CHANGE FIRST** / **DEFER** | Implement reservation entity and ledger schema | FUTURE REQUIRED |
| **O. Prior Release Blocks Full Release** | `EscrowLedger` unique constraint | Checked in `EscrowService` & unique constraint | **READY NOW** | Check `findByMilestoneIdAndTransactionType` in adapter | NONE |
| **P. Sequence / Prerequisite Rule** | `Milestone.sequence` | Absent in milestone service | **SMALL CODE FIX FIRST** | Add check in adapter: milestones with lower sequence must be `COMPLETED` | NONE |
| **Q. Dependency Graph Rule** | Graph model is absent | Absent | **SCHEMA CHANGE FIRST** | Requires DAG table (`milestone_dependencies`) | FUTURE REQUIRED |
| **R. Beneficiary Readiness** | `VerificationEligibilityService` | Fully checked in B09/B10 | **READY NOW** | Reuse `evaluateBeneficiaryApplicability` | NONE |
| **S. Risk Decision Dependency** | `RiskDecisionAdapter` / `BusinessRiskRepository` | Fully checked in B10/B13 | **READY NOW** | Inspect `EngineDecision(RISK)` or `evaluateRiskApplicability` | NONE |
| **T. Fraud Decision Dependency** | FRAUD engine is deferred | Absent | **DEFER** | Implement fraud policy and signal engine first | NONE (for now) |

---

## Part C — Milestone Rules Proposed Decision Mapping

> [!NOTE]
> **PROPOSED — NOT IMPLEMENTED**. These mappings define the target behavior for a future `MilestoneRulesDecisionAdapter`.

### Proposed Semantics

1. **Contractual Violations (`BLOCK`)**:
   - `MILESTONE_CURRENCY_MISMATCH`: Milestone currency does not match canonical deal currency.
   - `MILESTONE_AMOUNT_INVALID`: Milestone amount is null or <= 0.
   - `MILESTONE_AMOUNT_EXCEEDS_DEAL`: Milestone amount exceeds total deal value.
   - `MILESTONE_ALLOCATION_MISMATCH`: Sum of non-cancelled contractual milestones does not equal approved deal value.
   - `MILESTONE_ALREADY_RELEASED`: Prior `RELEASE` transaction already exists in `escrow_ledger`.
   - `MILESTONE_STATUS_REJECTED`: Milestone has been rejected.
   - `MILESTONE_STATUS_CANCELLED`: Milestone has been cancelled.
   - `MILESTONE_ACTIVE_DISPUTE`: Active open dispute exists on the deal or milestone (hard prohibition per current product semantics).

2. **Missing Mandatory Evidence (`REVIEW`)**:
   - `DELIVERABLES_MISSING`: Milestone requires deliverables but none have been submitted.
   - `INVOICE_EVIDENCE_MISSING`: Milestone requires an invoice, but no invoice is present.
   - `AGREEMENT_MISSING`: Deal has no agreement record.
   - `ESCROW_ACCOUNT_MISSING`: Deal has no escrow account.

3. **Stale Evidence / Incomplete Prerequisites (`REVIEW`)**:
   - `DELIVERABLES_PENDING_APPROVAL`: Deliverables have been submitted but are not yet fully approved.
   - `INVOICE_EVIDENCE_STALE`: Latest invoice is not approved, review is superseded, or invoice was edited post-review.
   - `AGREEMENT_NOT_SIGNED`: Latest agreement version is in `DRAFT`, `PENDING_BUYER`, or `PENDING_SELLER` status.
   - `INSUFFICIENT_CONFIRMED_FUNDS`: Escrow balance is less than required milestone amount.
   - `SEQUENCE_PREREQUISITE_UNMET`: Prior milestone in sequence is not yet completed.
   - `DEADLINE_EXCEEDED_REVIEW`: Milestone deadline has passed without approved submission (review required due to absence of grace policy).

4. **Contractual & Factual Compliance (`ALLOW`)**:
   - Condition: All amounts and currencies match, agreement is signed, deliverables are approved, invoice requirements (if applicable) are fresh and approved, no disputes exist, escrow funds are confirmed, and no prior release exists.
   - Reason Code: `MILESTONE_CONTRACT_COMPLIANT`.

---

## Part D — Approval Audit & Existing Sources

### Authoritative Approval Sources

| Concern | Authoritative Source | Persisted? | Exact Role / Entity |
|---|---|---|---|
| Milestone Approver | `MilestoneService.approveMilestone` | Status on `milestones`, Actor on `deliverables` & `audit_logs` | Buyer (`deal.buyerId`), Assigned PM (`deal.projectManagerId`), or Admin (`Role.ADMIN`) |
| Milestone Rejector | `MilestoneService.rejectMilestone` | Status on `milestones`, Actor on `deliverables` & `audit_logs` | Buyer (`deal.buyerId`), Assigned PM (`deal.projectManagerId`), or Admin (`Role.ADMIN`) |
| Invoice Reviewer | `InvoiceService.submitReviewDecision` | `InvoiceReview` (`reviewer_id`, `decision`, `status`) | Assigned PM (`deal.projectManagerId`) or Admin (`Role.ADMIN`) only (Buyer CANNOT review invoices) |
| Risk Reviewer | `RiskReview` / `RiskActionService` | `RiskReview` (`reviewer_id`, `status == RESTRICTED/CLEARED`) | Risk Officer / Admin |
| Approval Applicability | `ApprovalApplicabilityService` | Read-only evaluation record | Evaluates 7 dimensions of freshness across deal lifecycle |

### Approval Audit Detailed Findings

1. **MILESTONE APPROVER ROLES**:
   - Corporate Buyer (`deal.buyerId == currentUser.id`), Assigned Project Manager (`deal.projectManagerId == currentUser.id`), or Platform Admin (`Role.ADMIN`).
2. **MILESTONE REJECT ROLES**:
   - Exactly identical to approver roles: Corporate Buyer, Assigned PM, or Admin.
3. **ASSIGNED PM CHECK**:
   - Explicitly validated: `currentUser.getRole() == Role.PROJECT_MANAGER && deal.getProjectManagerId() != null && deal.getProjectManagerId().equals(currentUser.getId())`.
4. **BUYER AUTHORITY**:
   - Verified via direct user identity: `deal.getBuyerId().equals(currentUser.getId())`. Does not inspect organization role membership in `deal.buyerOrganizationId`.
5. **ADMIN OVERRIDE**:
   - **EXISTS**. Admin can approve or reject any milestone without being a party or PM. No mandatory justification code is currently required for milestone approval.
6. **FINANCE AUTHORITY**:
   - **DOES NOT EXIST** for milestone approval. `Role.FINANCE` callers are rejected with `UnauthorizedException`.
7. **APPROVAL RECORD**:
   - **NO separate domain entity** exists for milestone approval. It mutates `milestones.status` and `deliverables.status`.
8. **APPROVER ID**:
   - Not stored on `Milestone`. Persisted on `Deliverable.reviewedBy` and in `audit_logs.user_id`.
9. **APPROVAL TIMESTAMP**:
   - Not stored as a distinct immutable approval field on `Milestone`. Persisted on `Deliverable.reviewedAt` and in `audit_logs.created_at`.
10. **APPROVAL REVISION**:
    - **ABSENT** on `Milestone`.
11. **APPROVAL FINGERPRINT**:
    - **ABSENT** on `Milestone`.
12. **INVOICE REVIEW RECORD**:
    - **EXISTS**: Entity `InvoiceReview` in `invoice_reviews` table.
13. **INVOICE REVIEW ACTOR**:
    - **Persisted**: `InvoiceReview.reviewerId`.
14. **INVOICE REVIEW REVISION**:
    - Bound to `InvoiceAssessment.id` via `InvoiceReview.assessmentId`, and tracked via `Invoice.revisionOf` / `Invoice.rowVersion`.
15. **INVOICE REVIEW FINGERPRINT**:
    - **Persisted**: Stored in `InvoiceAssessment.fingerprint`.
16. **RISK REVIEW**:
    - **EXISTS**: Persisted in `risk_reviews` table.
17. **APPROVAL APPLICABILITY**:
    - Evaluated comprehensively by `ApprovalApplicabilityService` across 7 freshness dimensions.
18. **BENEFICIARY CHANGE INVALIDATION**:
    - **YES**: Invalidation occurs via `evaluateBeneficiaryApplicability`.
19. **AGREEMENT CHANGE INVALIDATION**:
    - **YES**: If a new draft version is added, latest agreement is not `SIGNED`, invalidating applicability.
20. **INVOICE CHANGE INVALIDATION**:
    - **YES**: If invoice is modified post-review or superseded, applicability fails closed.
21. **PM REASSIGNMENT INVALIDATION**:
    - **YES**: If PM who approved deliverable is removed or reassigned, applicability fails closed (`PM_ASSIGNMENT_CHANGED`).
22. **DISPUTE CHANGE INVALIDATION**:
    - **YES**: Any open dispute fails closed (`DISPUTE_OPEN_ON_DEAL` / `DISPUTE_OPEN_ON_MILESTONE`).
23. **RISK CHANGE INVALIDATION**:
    - **YES**: Any active `RESTRICTED` review fails closed (`RISK_RESTRICTION_ACTIVE`).
24. **DEADLINE CHANGE INVALIDATION**:
    - Modifying deadline updates `milestone.updatedAt`, which is detected as `MILESTONE_MODIFIED_AFTER_APPROVAL` if deliverable was already reviewed.
25. **MULTI-STAGE APPROVAL**:
    - **ABSENT**. No stages exist.
26. **QUORUM**:
    - **ABSENT**. No M-of-N signature support exists.
27. **SEPARATION OF DUTIES**:
    - **ABSENT**. No restriction preventing the same individual from performing multiple conflicting roles.
28. **REQUESTER CANNOT APPROVE OWN RELEASE**:
    - **ABSENT**. No check enforces `requester != approver`.
29. **DELEGATION**:
    - **ABSENT**. No delegation mechanics exist.
30. **APPROVAL EXPIRY**:
    - **ABSENT**. Approvals do not expire on a time horizon.

---

## Part E — Approval Current Safe Boundary & Read-Only Adapter Feasibility

Can `EngineType.APPROVAL` decision be implemented **NOW** as a **READ-ONLY ADAPTER** over `ApprovalApplicabilityService`?

### Feasibility Verdict: YES (Safe Read-Only Adapter)

- `ApprovalApplicabilityService` provides an established, tested, read-only contract (`evaluateApprovalApplicability(dealId, milestoneId)`).
- **Critical Invariant**: In the current schema, `Milestone` records lack durable approval revision and fingerprint bindings (`milestoneUnbound = true`).
- Therefore, when evaluated by the adapter:
  - If a milestone is marked `APPROVED` historically, the adapter returns:
    - `decision = DecisionStatus.REVIEW`
    - `reasonCode = "APPROVAL_UNBOUND_MILESTONE"`
    - `explanation = "Milestone approval is historical status only without durable revision or fingerprint binding; cannot serve as current financial authorization"`
  - If a milestone is unapproved:
    - `decision = DecisionStatus.REVIEW`
    - `reasonCode = "APPROVAL_PENDING"`
  - If a milestone is rejected or an active restriction/dispute exists:
    - `decision = DecisionStatus.BLOCK`
- **Zero False Readiness**:
  - The adapter will **NEVER** return `ALLOW` for unbound historical milestone status.
  - An `ALLOW` outcome is strictly reserved for fully bound, verified state.
  - This perfectly matches B10 containment and prevents premature payout execution while integrating the domain cleanly into the common engine framework.

### Proposed Approval Decision Mapping (PROPOSED ONLY)

1. **Hard Rejections / Active Prohibitions (`BLOCK`)**:
   - `APPROVAL_MILESTONE_REJECTED`: Milestone status is `REJECTED`.
   - `APPROVAL_RISK_RESTRICTION_ACTIVE`: Counterparty has an active risk restriction.
   - `APPROVAL_DISPUTE_ACTIVE`: Deal or milestone has an open dispute.
   - `APPROVAL_INVOICE_REJECTED`: Latest invoice was rejected during review.

2. **Unbound / Pending / Stale Evidence (`REVIEW`)**:
   - `APPROVAL_UNBOUND_MILESTONE`: Milestone approval lacks durable revision/fingerprint binding (Current Schema Default).
   - `APPROVAL_PENDING`: Milestone has not yet been approved.
   - `APPROVAL_MODIFIED_AFTER_DECISION`: Milestone attributes were edited after deliverable approval.
   - `APPROVAL_INVOICE_PENDING`: Invoice required but review is pending or superseded.
   - `APPROVAL_AGREEMENT_NOT_SIGNED`: Latest agreement version is not signed.
   - `APPROVAL_BENEFICIARY_INELIGIBLE`: Seller bank account is not verified, ambiguous, or ineligible.
   - `APPROVAL_PM_ASSIGNMENT_CHANGED`: Approving PM is no longer actively assigned to deal.

3. **Fully Verified & Bound Approval (`ALLOW`)**:
   - Condition: Milestone is approved, bound to revision/fingerprint, agreement signed, invoice approved and fresh, beneficiary eligible, PM assignment active, and zero disputes/restrictions.
   - Reason Code: `APPROVAL_VERIFIED_AND_APPLICABLE`.

---

## Part F — Enterprise Approval Gap Analysis

| Feature | Exists? | Needed for Initial Read-Only Adapter? | Needed Before Payout Restoration? |
|---|---|---|---|
| **Approval Workflow Instance** (`approval_workflows`) | NO | NO | YES |
| **Approval Stages** (Ordered / Parallel) | NO | NO | YES |
| **Quorum Policies** (M-of-N signers) | NO | NO | DEFERRED / OPTIONAL |
| **Organization-Role Matrix** | PARTIAL (User-level) | NO | YES |
| **Approval Delegation** | NO | NO | DEFERRED |
| **Approval Expiry / Timeouts** | NO | NO | YES |
| **Separation of Duties** (Requester != Approver) | NO | NO | YES |
| **Admin Override Justification** | NO | NO | YES |
| **Approval Action History** (`approval_actions`) | NO (Only `audit_logs`) | NO | YES |
| **Milestone Revision Binding** | NO (Invoice has it) | NO (Maps to REVIEW) | YES |
| **Beneficiary Binding** | YES (via B09/B10) | YES | YES |
| **Agreement Version Binding** | YES (via B10) | YES | YES |
| **Invoice Assessment Binding** | YES (via B10) | YES | YES |
| **Amount / Currency Binding** | PARTIAL | YES | YES |
| **Policy Version Binding** | PARTIAL | YES | YES |
| **Membership Revocation Invalidation** | PARTIAL | NO | YES |
| **PM Reassignment Invalidation** | YES (via B10) | YES | YES |

---

## Part G — Read-Only Side Effect Audit

Verification of read paths across `ApprovalApplicabilityService`, `MilestoneRepository`, `DeliverableRepository`, `DealAgreementRepository`, `InvoiceAssessmentRepository`, and `DisputeRepository`:

- **Banking Provider API Calls**: **0**
- **Ledger Journal Writes**: **0**
- **Escrow Account / Ledger Writes**: **0**
- **Settlement Instruction Writes**: **0**
- **Risk Score / Factor Recalculations**: **0**
- **Invoice Re-extractions / Status Mutations**: **0**

All inspected query paths use read-only Spring transactions (`@Transactional(readOnly = true)`) and pure JPA read queries.

---

## Part H — Tenant / Authority Audit

- **Evaluation Owner Resolution**:
  - `MILESTONE_RULES`: Owned by `deal.getBuyerOrganizationId()` (governs release of buyer-funded escrow).
    - `subjectType` = `"MILESTONE"`
    - `subjectId` = `milestone.getId()`
    - `organizationId` = `deal.getBuyerOrganizationId()`
  - `APPROVAL`: Owned by `deal.getBuyerOrganizationId()` (commercial approval authority resides with buyer / buyer's appointed PM).
    - `subjectType` = `"MILESTONE"`
    - `subjectId` = `milestone.getId()`
    - `organizationId` = `deal.getBuyerOrganizationId()`
- **Access Control**:
  - Validated using `DealAccessService.canUserAccessDeal`.
  - Stored decisions are strictly isolated under tenant-scoped unique index `uk_engine_decisions_logical_identity`.

---

## Part I — Engine Dependency Audit

Neither the proposed `MILESTONE_RULES` adapter nor the proposed `APPROVAL` adapter will have dependencies on:
- `BankingProvider`
- `LedgerService` write methods
- `EscrowService` write methods (`fundEscrow`, `releaseEscrow`)
- `RazorpayService` / on-ramp
- `SettlementAuthorizationService` execution methods

Both adapters interact exclusively with read-only query contracts:
- `MilestoneRules`: Reads deal, milestone, agreement, deliverable, invoice, dispute, and escrow balance projections.
- `Approval`: Delegates to `ApprovalApplicabilityService` and reads underlying review repositories.

---

## Part J — Release Dependency Readiness

| Engine Type | Implementation Status | Readiness for Release Engine |
|---|---|---|
| **RISK** | **IMPLEMENTED ADAPTER** (`RiskDecisionAdapter`) | READY |
| **DOCUMENT** | **IMPLEMENTED ADAPTER** (`DocumentDecisionAdapter`) | READY |
| **MILESTONE_RULES** | **READY TO IMPLEMENT** (Audit complete; read-only adapter feasible) | PENDING IMPLEMENTATION |
| **APPROVAL** | **READY TO IMPLEMENT (READ-ONLY)** (Audit complete; read-only adapter feasible) | PENDING IMPLEMENTATION |
| **TRUST** | **DEFERRED / AUDIT NEEDED** (D06 metrics unverified) | NOT READY |
| **FRAUD** | **DEFERRED / AUDIT NEEDED** (Dedicated fraud policies needed) | NOT READY |
| **RECONCILIATION** | **DEFERRED / AUDIT NEEDED** (Bank statement feed needed) | NOT READY |
| **DISPUTE** | **READY TO IMPLEMENT** (Read-only dispute queries established) | READY FOR SPECIFICATION |
| **RELEASE** | **BLOCKED / DEFERRED** (Requires all prerequisite adapters & settlement separation) | NOT READY |

---

## Part K — Recommended Next Implementation

**Option A (Recommended)**:
Implement `MILESTONE_RULES` and read-only `APPROVAL` decision adapters together in the next phase.

**Rationale**:
1. Both adapters evaluate against the exact same domain subject (`Milestone` within a `Deal`).
2. Both share `deal.getBuyerOrganizationId()` as their tenant evaluation owner.
3. `ApprovalApplicabilityService` already coordinates evidence across both milestones and approvals.
4. Implementing both read-only adapters together brings 4 of the 8 required decision engines (`RISK`, `DOCUMENT`, `MILESTONE_RULES`, `APPROVAL`) into the common `EngineDecision` framework without changing any database schemas or weakening B01–B13 safety gates.

---

## Part L — Files Inspected

- `backend/src/main/java/com/finx/milestone/entity/Milestone.java`
- `backend/src/main/java/com/finx/milestone/entity/MilestoneStatus.java`
- `backend/src/main/java/com/finx/milestone/entity/Deliverable.java`
- `backend/src/main/java/com/finx/milestone/entity/DeliverableStatus.java`
- `backend/src/main/java/com/finx/milestone/repository/MilestoneRepository.java`
- `backend/src/main/java/com/finx/milestone/repository/DeliverableRepository.java`
- `backend/src/main/java/com/finx/milestone/service/MilestoneService.java`
- `backend/src/main/java/com/finx/deal/entity/Deal.java`
- `backend/src/main/java/com/finx/deal/entity/DealStatus.java`
- `backend/src/main/java/com/finx/deal/service/DealAccessService.java`
- `backend/src/main/java/com/finx/deal/service/DealService.java`
- `backend/src/main/java/com/finx/agreement/entity/DealAgreement.java`
- `backend/src/main/java/com/finx/agreement/enums/AgreementStatus.java`
- `backend/src/main/java/com/finx/agreement/repository/DealAgreementRepository.java`
- `backend/src/main/java/com/finx/agreement/service/AgreementService.java`
- `backend/src/main/java/com/finx/invoice/entity/Invoice.java`
- `backend/src/main/java/com/finx/invoice/entity/InvoiceAssessment.java`
- `backend/src/main/java/com/finx/invoice/entity/InvoiceReview.java`
- `backend/src/main/java/com/finx/invoice/service/InvoiceService.java`
- `backend/src/main/java/com/finx/paymentorchestration/service/ApprovalApplicabilityService.java`
- `backend/src/main/java/com/finx/paymentorchestration/service/SettlementAuthorizationService.java`
- `backend/src/main/java/com/finx/paymentorchestration/entity/PaymentInstruction.java`
- `backend/src/main/java/com/finx/paymentorchestration/entity/PaymentTransaction.java`
- `backend/src/main/java/com/finx/dispute/entity/Dispute.java`
- `backend/src/main/java/com/finx/dispute/entity/DisputeStatus.java`
- `backend/src/main/java/com/finx/dispute/service/DisputeService.java`
- `backend/src/main/java/com/finx/escrow/entity/EscrowAccount.java`
- `backend/src/main/java/com/finx/escrow/entity/EscrowLedger.java`
- `backend/src/main/java/com/finx/escrow/entity/TransactionType.java`
- `backend/src/main/java/com/finx/escrow/service/EscrowService.java`
- `backend/src/main/java/com/finx/projectmanager/entity/ProjectManagerInvitation.java`
- `backend/src/main/java/com/finx/projectmanager/service/ProjectManagerInvitationService.java`
- `backend/src/main/java/com/finx/org/enums/OrgEnums.java`

---

## Part M — Tests Run & Test Results

### 1. Focused Containment & Framework Regression
- `ApprovalFreshnessContainmentTest`: 22/22 PASS
- `RiskEvidenceReadContainmentTest`: 28/28 PASS
- `BeneficiaryVerificationContainmentTest`: 31/31 PASS
- `TenantIsolationContainmentTest`: 40/40 PASS
- `LockingAndRetryContainmentTest`: 22/22 PASS
- `RestrictedFrameworkArchitectureTest`: 8/8 PASS
- `RestrictedFrameworkBoundaryContainmentTest`: 9/9 PASS
- `CommonEngineFrameworkArchitectureTest`: 5/5 PASS
- `ReadOnlyDecisionAdapterArchitectureTest`: 4/4 PASS
- `RiskDecisionAdapterTest`: 16/16 PASS
- `DocumentDecisionAdapterTest`: 15/15 PASS
- `ReadOnlyDomainDecisionPersistenceTest`: 3/3 PASS
- **Subtotal**: **203/203 PASS** (0 failures, 0 errors)

### 2. Full Repository Test Suite (`.\mvnw.cmd test`)
- **Total Tests Run**: **707**
- **Failures**: **0**
- **Errors**: **0**
- **Skipped**: **1**
- **Build Status**: **BUILD SUCCESS**

---

## Database Migration

- Current Audit Database Migration: **NONE** (No schema changes, no V26 migration added).
- Initial `MILESTONE_RULES` + `APPROVAL` Adapters: **NONE REQUIRED** (Can reuse existing V25 `engine_decisions` table and domain read sources).
- Future Enterprise Approval Engine: **FUTURE REQUIRED** (Will require `approval_workflows`, `approval_steps`, `approval_actions` tables when multi-stage workflow is built).

---

## Production Money Movement

- **DISABLED**: `SettlementAuthorizationService.authorizeAndInitiateSettlement` fails closed unconditionally throwing `AccessDeniedException`.
- External bank settlement calls: **0**.

---

## Final Verdict

READY FOR NEXT IMPLEMENTATION:
YES

NEXT EXACT PHASE:
Implement read-only MILESTONE_RULES and APPROVAL decision adapters within the common EngineDecision framework.
