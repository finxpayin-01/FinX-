# B13 Remediation Report

## Blocker

B13 — P1 — Risk inspection is not a stable, read-only assessment reference

## Root Cause

Prior to remediation, deal-level risk inspection was conflated with risk recalculation:
1. `RiskEngineService.getAssessmentForDeal(dealId)` delegated directly to `assessDealRisk(dealId)`. Every invocation of this ostensibly read-only method locked subjects (`db.lock`), invoked `RiskScoringService.recalculate()`, wrote rows to `risk_assessments`, appended historical records to `risk_score_history`, wrote rows to `risk_factors`, modified `risk_scores` rows, opened new `risk_reviews` and `risk_alerts`, logged audit events, and dispatched asynchronous events.
2. `assessDealRisk` manufactured an unsaved, in-memory `RiskAssessment` aggregate with an unsaved randomly generated UUID (`view.getId()`), presenting a transient object as though it were a durable assessment entity. Citing this transient ID as durable evidence in decision frameworks caused dangling foreign references.
3. The deal-level view collapsed buyer and vendor counterparties into a single aggregated score, obscuring their independent persisted identities (`RiskScore.id`, `RiskAssessment.id`, `RiskScoreHistory.assessmentId`).
4. Native `reviewRequired` flags (flagged by rules when score exceeded thresholds or manual review was needed) and configured active payout restrictions (`ReviewStatus.RESTRICTED` with active hold windows) were conflated or flattened.
5. Invoices associated with milestones had durable assessment records (`InvoiceAssessment` with `id` and `fingerprint`), but these identities and the document extraction provenance (such as `MANUAL_ENTRY`) risked being flattened to mere boolean statuses or incorrectly labeled as automated OCR/AI verified.

## Before

- Calling `getAssessmentForDeal` mutated database state, appending history, opening review cases, and creating audit logs on pure inspection.
- The returned aggregate `RiskAssessment` had an unsaved transient UUID never stored in `risk_assessments`.
- Buyer and vendor persisted source identities were merged and obscured.
- `reviewRequired` and configured payout restrictions were not distinguished.
- Invoice assessment identity and manual extraction provenance were not structured as authoritative evidence.

## After

- Strict architectural separation between pure read-only inspection and explicit recalculation commands.
- Creation of `RiskEvidenceReadService` providing `DealRiskEvidenceSnapshot` containing distinct, persisted `SubjectRiskEvidence` for buyer and vendor, and `InvoiceRiskEvidence` for milestone invoices.
- `getAssessmentForDeal(dealId)` is `@Transactional(readOnly = true)` and constructs an explicit compatibility view marked `isTransient = true` without performing any database mutations, calculations, or locking.
- `assessDealRisk(dealId)` remains the explicit, mutating command for recalculating deal risk.
- Repeated reads against unchanged data produce exactly 0 new risk history rows, 0 new review rows, 0 new risk events, 0 score mutations, 0 restriction mutations, 0 bank calls, 0 escrow mutations, and 0 ledger writes.

## Read-Only Risk Evidence Contract

- **Class**: `com.finx.risk.service.RiskEvidenceReadService`
- **Method (User-Authorized)**: `DealRiskEvidenceSnapshot getDealRiskEvidence(UUID dealId, UserPrincipal currentUser)`
- **Method (Internal Framework)**: `DealRiskEvidenceSnapshot getDealRiskEvidenceInternal(UUID dealId)`
- **Compatibility Read Method**: `RiskEngineService.getAssessmentForDeal(UUID dealId)`

## Explicit Recalculation Contract

- **Class**: `com.finx.risk.service.RiskEngineService`
- **Method**: `@Transactional public RiskAssessment assessDealRisk(UUID dealId)`

## Persisted Buyer Source Identity

- **Entity**: `com.finx.risk.entity.RiskScore` (table `risk_scores`), mapped to `com.finx.risk.entity.RiskAssessment` (table `risk_assessments`) and `com.finx.risk.entity.RiskScoreHistory` (table `risk_score_history`).
- **Repository**: `BusinessRiskRepository` (JPA `EntityManager`).
- **Persisted IDs**: `subjectId` (`RiskScore.id`), `persistedAssessmentId` (`RiskAssessment.id` / `RiskScoreHistory.assessmentId`), and active restriction ID (`RiskReview.id` where applicable).

## Persisted Vendor Source Identity

- **Entity**: `com.finx.risk.entity.RiskScore` (table `risk_scores`), mapped to `com.finx.risk.entity.RiskAssessment` (table `risk_assessments`) and `com.finx.risk.entity.RiskScoreHistory` (table `risk_score_history`).
- **Repository**: `BusinessRiskRepository` (JPA `EntityManager`).
- **Persisted IDs**: `subjectId` (`RiskScore.id`), `persistedAssessmentId` (`RiskAssessment.id` / `RiskScoreHistory.assessmentId`), and active restriction ID (`RiskReview.id` where applicable).

## Transient Aggregate Identity

- The aggregate deal-level snapshot (`DealRiskEvidenceSnapshot`) explicitly sets `hasDurableAggregate = false`.
- The snapshot does NOT expose any fake deal-level assessment UUID.
- The compatibility view (`RiskAssessment`) returned by `getAssessmentForDeal` is annotated and flagged with `@Transient public boolean isTransient = true`, explicitly declaring that its ID is an in-memory view identifier rather than a durable persisted foreign key.

## Policy Semantics

- **Raw Risk Score**: Preserved directly from persisted `RiskScore.score` and `Assessment.totalRiskScore()`.
- **reviewRequired**: Preserved as a distinct boolean reflecting native rule triggers (`Assessment.reviewRequired()` or un-cleared open review state).
- **Effective Restriction**: Computed strictly from native persisted `RiskReview` records where `status == ReviewStatus.RESTRICTED` and `restrictionUntil != null && restrictionUntil.isAfter(Instant.now())`.
- **High Score with Cleared Review**: If a subject has a high score (e.g., 85) but the review has been cleared (`ReviewStatus.CLEARED`), `effectiveRestrictionActive` is reported as `false`. The read contract does not manufacture a synthetic `BLOCK`.
- **Low Score with Active Restriction**: If a subject has a low score (e.g., 25) but an active restriction review exists, `effectiveRestrictionActive` is reported as `true` with `activeRestrictionId` and `restrictionUntil` preserved.

## Freshness

- Each `RiskScore` records `nextRefreshAt`.
- If `nextRefreshAt.isBefore(Instant.now())`, `SubjectRiskEvidence.status()` is marked `EvidenceStatus.STALE`.
- The read contract NEVER silently recalculates or refreshes stale evidence during read; historical observation timestamps are preserved as-is.

## Missing Evidence

- If a counterparty has never been initialized or assessed, `status` is set to `EvidenceStatus.MISSING`.
- `persistedAssessmentId` is `null`, `score` is `null`, and `riskLevel` is `null`.
- Missing evidence is never defaulted to `score = 0`, `LOW`, `SAFE`, or `ALLOW`.

## Policy Version

- Preserved from `RiskAssessment.ruleVersion` or `Assessment.ruleVersion()` (e.g., `"rules-v1:BUYER:1.0"`).
- If unavailable or null in the database, it remains `null`. No synthetic version strings or arbitrary hashes are fabricated.

## Observation Timestamp

- Preserved from `RiskScore.calculatedAt` or `Assessment.calculatedAt()`.
- Never replaced with retrieval time (`Instant.now()`). Retrieval time is separately recorded in `snapshotAt`.

## Invoice Assessment Identity

- Authoritative invoice assessment ID is resolved from `InvoiceAssessment.id` (persisted in `invoice_assessments` table via `InvoiceAssessmentRepository`).

## Invoice Fingerprint

- Authoritative cryptographic matching fingerprint is resolved from `InvoiceAssessment.fingerprint`.
- Revision reference is preserved from `Invoice.revisionOf`.

## Invoice Provenance

- If `Invoice.extractionStatus == ExtractionStatus.MANUAL_ENTRY`, the read contract retains `manualProvenance = true` and `provenance = "MANUAL_ENTRY"`.
- Manual entry is never relabeled as automated, OCR-verified, or AI-verified.

## Tenant Scope

- User-facing deal evidence inspection via `RiskEvidenceReadService.getDealRiskEvidence` invokes `DealAccessService.requireDealAccess(deal, currentUser)`.
- Enforces B07 multi-tenant boundary: active membership in buyer/seller organization, assigned PM, or platform ADMIN/FINANCE role is required. Unrelated tenants and inactive/suspended memberships are rejected with `UnauthorizedException`.

## Side-Effect Verification

Repeated calls to `RiskEvidenceReadService` and `RiskEngineService.getAssessmentForDeal` against unchanged deal data produce:
- `history writes`: 0
- `review writes`: 0
- `risk-event writes`: 0
- `score/state mutations`: 0
- `bank calls`: 0 (`BankingProvider.initiateSettlement = 0`)
- `ledger writes`: 0
- `escrow writes`: 0

## Interaction With B07

`RiskEvidenceReadService` delegates tenant boundary enforcement directly to `DealAccessService`, guaranteeing that risk evidence inspection cannot be exploited for cross-tenant IDOR.

## Interaction With B10

B13 provides the durable evidence reference layer required by B10 approval freshness. Stale risk evidence is surfaced as `EvidenceStatus.STALE` without silent background refresh, ensuring stale evidence cannot masquerade as current financial clearance.

## Interaction With B11

`RiskEvidenceReadService` is strictly `@Transactional(readOnly = true)`. It executes zero `REQUIRES_NEW` mutating actions, acquires zero pessimistic write locks, and causes zero transaction lock inversions.

## Interaction With B12

Maintains provenance separation between automated and manual sources. Does not conflate legacy or manual evidence with verified authoritative sources.

## Interaction With B05/B06

Read inspection performs zero financial effect identity generation, zero journal postings, zero balance ledger modifications, and zero escrow mutations.

## Files Modified

- `backend/src/main/java/com/finx/risk/entity/RiskAssessment.java`
- `backend/src/main/java/com/finx/risk/service/RiskEngineService.java`

## Files Created

- `backend/src/main/java/com/finx/risk/dto/RiskEvidenceDtos.java`
- `backend/src/main/java/com/finx/risk/service/RiskEvidenceReadService.java`
- `backend/src/test/java/com/finx/security/RiskEvidenceReadContainmentTest.java`
- `B13_REMEDIATION_REPORT.md`

## Database Migration

NO. No database migration was introduced. Existing PostgreSQL schema and tables (`risk_scores`, `risk_assessments`, `risk_score_history`, `risk_reviews`, `invoices`, `invoice_assessments`) fully support the model.

## API Compatibility

- `RiskEngineService.assessDealRisk(dealId)` remains as the explicit mutating recalculation command for callers requiring recalculation.
- `RiskEngineService.getAssessmentForDeal(dealId)` is now purely read-only, serving existing callers (e.g. `AdminB2bController`) without mutating the database.
- `RiskEvidenceReadService` provides the new structured read contract (`DealRiskEvidenceSnapshot`) for framework use.

## Tests Added

- `com.finx.security.RiskEvidenceReadContainmentTest` (28 tests covering all 27 containment and verification criteria).

## Tests Run

- Focused test suite: `RiskEvidenceReadContainmentTest` (28/28 PASS).
- Prior blocker regression suite: B01-B12 containment tests (265/265 PASS).
- Full backend suite: `mvnw.cmd test` (621 tests, 0 failures, 0 errors, 1 skipped).

## Test Results

- `RiskEvidenceReadContainmentTest`: PASS
- Full Suite: PASS (621 tests run, 0 failures, 0 errors)

## Known Limitations

- Existing scoring rules and metrics logic are reused and not rewritten.
- No new Trust, Fraud, or Release engine created (out of scope for B13).
- Missing and stale evidence remain unresolved rather than guessed.
- Policy version is reported as null when absent; no synthetic version strings are generated.
- Bank execution remains disabled (`BankingProvider.initiateSettlement calls = 0`).

## B13 Status

PASS

## Restricted Framework Blocker Set Complete

YES
