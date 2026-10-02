# B10 Remediation Report

## Blocker

B10 — Existing approvals can become stale without invalidation

## Root Cause

Prior to B10 remediation, approvals across multiple subsystems functioned as point-in-time status mutations without durable revision or fingerprint bindings, or were consumed downstream via naive status checks:
1. **Unbound Milestone Approvals**: Milestone approval (`MilestoneService.approveMilestone`) mutated milestone status to `MilestoneStatus.APPROVED` and deliverables to `APPROVED`. However, `Milestone` records contain no persistent approval revision, approval fingerprint, or snapshot of the agreed milestone state. Any post-approval change (such as deadline modifications, scope adjustments, or deliverable resubmissions) left the milestone in `APPROVED` status.
2. **Invoice Review Status vs. Freshness**: Although `InvoiceService` had implemented strong review freshness (checking that evidence matched the latest assessment fingerprint and that reviews bound to specific assessment IDs), downstream settlement authorization previously checked only `invoice.getStatus() == InvoiceStatus.APPROVED`. If an invoice was updated, modified after review, or superseded by a newer revision, an older review could be treated as current financial authorization.
3. **Agreement Changes**: Agreements in `AgreementService` are versioned, but downstream workflows did not verify that the latest version was fully `SIGNED`. If an agreement v1 was signed and v2 was subsequently created as an unsigned draft, an old signature could be treated as authorizing release against modified terms.
4. **Beneficiary Changes**: While B09 established single-eligible beneficiary verification rules, an approval granted under one verified bank account could remain in effect even after the account verification was revoked or became ambiguous with multiple accounts.
5. **Project Manager Reassignments**: Milestone deliverable reviews record `reviewedBy`. If the assigned Project Manager on a deal was removed or reassigned, approvals granted by the former PM continued to be treated as valid project approvals under the new PM assignment without revalidation.
6. **Dispute State Resets**: Resolving a dispute in `DisputeService` restored `deal.status` to `DealStatus.ACCEPTED` without checking revision-bound approval evidence, and active or reopened disputes were not guaranteed to invalidate pending release decisions.
7. **Risk / Policy Interventions**: An approval granted before a subsequent risk restriction (`ReviewStatus.RESTRICTED`) or hold could erroneously be treated as sufficient to proceed.

## Before

* **Milestone Approval**: Point-in-time status mutation. Milestones had no persistent approval fingerprint or revision. Legacy approved milestones lacked cryptographic or versioned proof of what state was approved.
* **Invoice Freshness**: Settlement gates checked only `invoice.getStatus() == InvoiceStatus.APPROVED` without verifying latest revision, assessment fingerprint binding, or absence of post-review edits.
* **Agreement Modification**: If agreement v1 was signed and agreement v2 was created as an unsigned DRAFT with altered terms, downstream checks did not fail closed on the unsigned draft.
* **Beneficiary Verification**: Stale approvals remained valid regardless of subsequent changes to the seller organization's bank accounts or verification status.
* **PM Reassignment**: Deliverable reviews by a former PM were treated as valid under a newly assigned PM.
* **Dispute State**: Resolving a dispute restored accepted state without ensuring evidence freshness; active disputes did not halt approval applicability across all gates.
* **Risk State**: Stale approvals could be considered valid despite newly introduced account restrictions or policy holds.

## After

* **Approval Applicability Boundary**: Created `ApprovalApplicabilityService` (`evaluateApprovalApplicability(UUID dealId, UUID milestoneId)`) as a dedicated, read-only contract. It evaluates milestone approval binding, invoice review freshness, agreement signature status, beneficiary eligibility, PM assignment validity, dispute state, and risk restrictions.
* **Milestone Historical Approval**: Historical milestone approvals remain completely visible and intact for audit and product display, but unbound milestone approvals (`milestoneUnbound = true`) are explicitly flagged as `UNBOUND_MILESTONE_APPROVAL` and fail closed for financial release authorization (`applicable = false`). Milestone modifications occurring after review are detected and reported.
* **Invoice Freshness**: Reuses `InvoiceService`'s review-to-assessment binding. Requires that:
  - An invoice exists for invoice-required milestones.
  - The latest invoice revision is `APPROVED`.
  - An `APPROVED` review exists and is bound to the latest assessment ID (`review.getAssessmentId().equals(latestAssessment.id)`).
  - The invoice has not been updated after the review decision was recorded (`!invoice.getUpdatedAt().isAfter(review.getReviewedAt())`).
  - Stale reviews, superseded assessments, and unapproved revisions fail closed (`invoiceApplicable = false`).
* **Agreement Freshness**: Evaluates the latest agreement version on the deal (`findTopByDealIdOrderByVersionDesc`). If the latest version is not `AgreementStatus.SIGNED` (e.g. it is `DRAFT`, partially signed, or missing), agreement applicability fails closed (`agreementApplicable = false`).
* **Beneficiary Freshness**: Reuses B09 `VerificationEligibilityService.evaluateBeneficiaryEligibility(sellerOrgId)`. If the seller organization has 0 eligible accounts, >1 eligible accounts (ambiguous), revoked credentials, or masked-only accounts, beneficiary applicability fails closed (`beneficiaryApplicable = false`).
* **PM Assignment Freshness**: Inspects all approved deliverables on the milestone. If a deliverable was reviewed by a PM who is no longer the active PM on the deal (or if the deal PM has been removed), PM assignment applicability fails closed (`pmAssignmentApplicable = false`, `PM_ASSIGNMENT_CHANGED`).
* **Dispute Freshness**: Evaluates `disputeRepository` for active disputes on the deal or milestone. Any open dispute fails closed (`disputeApplicable = false`, `DISPUTE_OPEN_ON_DEAL` / `DISPUTE_OPEN_ON_MILESTONE`).
* **Risk / Policy Freshness**: Evaluates active `RiskReview` records for `ReviewStatus.RESTRICTED` where `restrictionUntil` is in the future. Any active restriction fails closed (`riskApplicable = false`, `RISK_RESTRICTION_ACTIVE`).
* **Execution Boundary**: `SettlementAuthorizationService.authorizeAndInitiateSettlement` remains unconditionally fail-closed under B04 (`AccessDeniedException`). Zero calls to `BankingProvider.initiateSettlement` are made.

## Approval Applicability Boundary

The read-only contract is encapsulated in:
- `com.finx.paymentorchestration.service.ApprovalApplicabilityService`
- Exposed via `SettlementAuthorizationService.evaluateApprovalApplicability(UUID dealId, UUID milestoneId)`

Returns an immutable `ApprovalApplicabilityResult`:
```java
public record ApprovalApplicabilityResult(
        boolean applicable,
        List<String> reasonCodes,
        Instant observedAt,
        UUID dealId,
        UUID milestoneId,
        boolean milestoneApproved,
        boolean milestoneUnbound,
        boolean invoiceApplicable,
        boolean agreementApplicable,
        boolean beneficiaryApplicable,
        boolean pmAssignmentApplicable,
        boolean disputeApplicable,
        boolean riskApplicable,
        UUID invoiceId,
        String invoiceFingerprint,
        UUID invoiceAssessmentId,
        UUID agreementId,
        Integer agreementVersion,
        String agreementSha256,
        UUID beneficiaryAccountId,
        boolean isSandboxBeneficiary,
        UUID assignedProjectManagerId
)
```

## Milestone Historical Approval

Historical milestone status (`APPROVED`, `COMPLETED`, etc.) remains untouched in the database and visible to product consumers. However, because current milestone entities lack durable revision/fingerprint bindings, `evaluateApprovalApplicability` marks `milestoneUnbound = true` and records reason code `UNBOUND_MILESTONE_APPROVAL`. Unbound milestone status alone does not produce a financial release `ALLOW`.

## Invoice Freshness

Reuses existing `InvoiceAssessment.fingerprint` and `InvoiceReview.assessmentId` relationships:
- If `milestone.isInvoiceRequired()` is true, checks `invoiceRepository.findFirstByMilestoneIdOrderByCreatedAtDesc(milestoneId)`.
- If an invoice review was bound to an older assessment ID, `evaluateInvoiceApplicability` returns `applicable = false`, reason `INVOICE_REVIEW_SUPERSEDED`.
- If the invoice was revised (new revision uploaded in `PROCESSING`), the review on the older revision is not active, returning `applicable = false`, reason `INVOICE_NOT_APPROVED`.
- If the invoice was modified after review (`updatedAt > reviewedAt`), returns `applicable = false`, reason `INVOICE_MODIFIED_AFTER_REVIEW`.

## Agreement Freshness

Evaluates `dealAgreementRepository.findTopByDealIdOrderByVersionDesc(dealId)`:
- If no agreement exists: `applicable = false`, reason `AGREEMENT_MISSING`.
- If latest version is in status `DRAFT`, `BUYER_SIGNED`, or `SELLER_SIGNED`: `applicable = false`, reason `AGREEMENT_NOT_SIGNED`.
- If latest version is `SIGNED`: `applicable = true`, exposing `agreementId`, `version`, and `documentSha256`.

## Beneficiary Freshness

Delegates directly to B09 `VerificationEligibilityService.evaluateBeneficiaryEligibility(sellerOrganizationId)`:
- Exactly 1 eligible verified account: `beneficiaryApplicable = true`.
- 0 eligible accounts: `beneficiaryApplicable = false`, reason `No eligible verified bank accounts found`.
- >1 eligible accounts: `beneficiaryApplicable = false`, reason `Ambiguous beneficiary: multiple eligible verified bank accounts found`.
- Revoked / masked-only verification: `beneficiaryApplicable = false`.

## PM Assignment Freshness

Evaluates whether the PM who reviewed and approved milestone deliverables (`Deliverable.reviewedBy`) remains the active PM on the deal (`deal.getProjectManagerId()`):
- If the PM was removed (`deal.getProjectManagerId() == null`): `pmAssignmentApplicable = false`.
- If the deal was reassigned to a different PM (`!deal.getProjectManagerId().equals(reviewedBy)`): `pmAssignmentApplicable = false`.
- If reviewed by the buyer directly or current assigned PM: `pmAssignmentApplicable = true`.

## Dispute Freshness

Queries `disputeRepository` for open disputes on the deal or milestone:
- Open dispute exists: `disputeApplicable = false`.
- All disputes resolved: `disputeApplicable = true`.

## Risk / Policy Freshness

Queries `businessRiskRepository` for active `RiskReview` records in `ReviewStatus.RESTRICTED` where `restrictionUntil > Instant.now()`:
- Active restriction found: `riskApplicable = false`.
- No active restrictions: `riskApplicable = true`.

## Legacy Approval Handling

No guessed fingerprint, version number, or synthetic backfill was introduced for legacy approvals. Unbound historical approvals are explicitly classified as `UNBOUND_MILESTONE_APPROVAL` / not authoritative for financial release.

## Financial Execution Boundary

`SettlementAuthorizationService.authorizeAndInitiateSettlement` remains unconditionally fail-closed under B04 (`AccessDeniedException`). `BankingProvider.initiateSettlement` calls strictly remain zero.

## Interaction With B01

B01 (Strict Webhook Replay and State Mutation Protection) remains completely intact. Payment confirmation and webhooks enforce signatures and deduplication. All B01 tests pass.

## Interaction With B02

B02 (Escrow and Banking Webhook Signature Verification) remains completely intact. Strict signature checks and replay defenses remain enforced. All B02 tests pass.

## Interaction With B03

B03 (Escrow Auto-Release and Premature Transition Prevention) remains completely intact. Escrow release cannot be triggered automatically or prematurely. All B03 tests pass.

## Interaction With B04

B04 (Unchecked Settlement Execution Fail-Closed) remains completely intact. `SettlementAuthorizationService.authorizeAndInitiateSettlement` fails closed unconditionally throwing `AccessDeniedException`. `BankingProvider.initiateSettlement` calls remain at zero. All B04 tests pass.

## Interaction With B12

B12 (Legacy Endpoint and Open Parameter Security) remains completely intact. Legacy paths are neutralized and reject open parameter hijacking. All B12 tests pass.

## Interaction With B07

B07 (Tenant and Organization Isolation) remains completely intact. Active membership verification and organization access control rules are reused and reinforced. All B07 tests pass.

## Interaction With B08

B08 (Role Escalation and Authority Partitioning) remains completely intact. Public registration cannot grant elevated platform or finance authority. All B08 tests pass.

## Interaction With B09

B09 (Beneficiary and Verification Authority Binding) remains completely intact. Beneficiary eligibility boundary is reused by `ApprovalApplicabilityService`, preserving single-beneficiary enforcement, masked-only rejection, and sandbox provenance tracking. All B09 tests pass.

## Files Modified

- [SettlementAuthorizationService.java](file:///c:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/backend/src/main/java/com/finx/paymentorchestration/service/SettlementAuthorizationService.java)

## Files Created

- [ApprovalApplicabilityService.java](file:///c:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/backend/src/main/java/com/finx/paymentorchestration/service/ApprovalApplicabilityService.java)
- [ApprovalFreshnessContainmentTest.java](file:///c:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/backend/src/test/java/com/finx/security/ApprovalFreshnessContainmentTest.java)
- [B10_REMEDIATION_REPORT.md](file:///c:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/B10_REMEDIATION_REPORT.md)

## Database Migration

NONE

## API Compatibility

Historical milestone, agreement, invoice, and review statuses remain readable via existing endpoints. Read-only financial applicability evaluations fail closed when approvals are unbound, stale, or superseded.

## Tests Added

`com.finx.security.ApprovalFreshnessContainmentTest`:
1. `test01_milestoneMaterialChange_invalidatesApplicability`: Modification after deliverable review invalidates approval applicability.
2. `test02_deadlineDateChange_marksApprovalStale`: Deadline change after review marks approval stale.
3. `test03_agreementModification_invalidatesAgreementApplicability`: Agreement draft update invalidates agreement applicability.
4. `test04_agreementResignReplacement_newVersionSupersedesOldVersion`: New unsigned version supersedes signed version.
5. `test05_invoiceNewRevision_makesPreviousApprovalStale`: New invoice revision makes previous approval stale.
6. `test06_invoiceFingerprintChange_reviewBoundToOlderAssessmentFailsFreshness`: Review bound to older assessment fails freshness check.
7. `test07_beneficiaryVerificationChange_failsApprovalApplicability`: Invalidated beneficiary verification fails approval applicability.
8. `test08_beneficiaryAccountAmbiguity_failsClosed`: Multiple verified beneficiary accounts fail closed as ambiguous.
9. `test09_pmReassignment_priorPmApprovalNotTreatedAsCurrentPmApproval`: Approval by former PM is not treated as current PM approval.
10. `test10_disputeReopened_invalidatesPriorApprovalApplicability`: Open dispute invalidates prior approval applicability.
11. `test11_disputeMateriallyChanged_dealDisputeInvalidatesApprovals`: Deal-level dispute invalidates milestone approval applicability.
12. `test12_riskRestrictionIntroduced_overridesPriorApproval`: Active risk restriction overrides prior approval.
13. `test13_unchangedInvoiceReview_remainsApplicable`: Unchanged current invoice review remains applicable when fingerprint matches.
14. `test14_staleInvoiceReview_fails`: Invoice modified after review was recorded fails freshness.
15. `test15_historicalMilestoneApproval_remainsReadable`: Historical milestone approval remains readable without modification.
16. `test16_historicalAgreementSignature_remainsReadable`: Historical agreement signature remains readable without mutation.
17. `test17_historicalDisputeResolution_remainsReadable`: Historical dispute resolution remains readable.
18. `test18_oldApprovalNotDeleted_whenMarkedNonCurrent`: Old approval is not deleted when marked non-current.
19. `test19_milestoneStatusApprovedAlone_mustNotProduceFinancialAllow`: Milestone status `APPROVED` alone must not produce financial `ALLOW`.
20. `test20_settlementAuthorization_remainsFailClosedUnderB04`: Settlement authorization remains fail-closed under B04.
21. `test21_zeroBankingExecutionCalls_duringApplicabilityEvaluation`: Zero banking execution calls occur during applicability evaluation.
22. `test22_bankingProvider_initiateSettlementCallsStrictlyZero`: `BankingProvider.initiateSettlement` calls strictly remain zero.

## Tests Run

- `ApprovalFreshnessContainmentTest` (22 tests, 0 failures, 0 errors)
- Blocker regression test suite:
  - `PaymentConfirmationContainmentTest` (B01)
  - `BankingWebhookControllerTest` (B02)
  - `EscrowReleaseContainmentTest` (B03)
  - `SettlementExecutionContainmentTest` (B04)
  - `LegacyBoundaryContainmentTest` (B12)
  - `TenantIsolationContainmentTest` (B07)
  - `PlatformRoleProvisioningContainmentTest` (B08)
  - `BeneficiaryVerificationContainmentTest` (B09)
  - `ApprovalFreshnessContainmentTest` (B10)
  - Total: 195 tests, 0 failures, 0 errors
- Domain tests:
  - `MilestoneServiceTest` (8 tests)
  - `SettlementAuthorizationServiceTest` (14 tests)
  - `ProjectManagerInvitationServiceTest` (7 tests)
  - Total: 29 tests, 0 failures, 0 errors
- Full backend suite:
  - `.\mvnw.cmd test`

## Test Results

Exact:
- Tests run: 526
- Failures: 0
- Errors: 0
- Skipped: 1
- **BUILD SUCCESS**

## Known Limitations

- Persistent approval revision and approval fingerprint columns on `milestones` and persistent approval tables are still required before payout restoration.
- Persistent beneficiary snapshot/fingerprint is still required before payout restoration.
- B11 will address locking and race condition containment across financial writers.
- B13 will address stable risk assessment read identity.
- B05/B06 will subsequently address financial effect identity and accounting lineage.
- Payout execution remains unconditionally disabled by B04.

## B10 Status

PASS

## Ready for B11

YES
