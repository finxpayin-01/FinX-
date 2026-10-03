# Read-Only Domain Decision Adapters Report

## Scope

Implemented adapters:
- **RISK** (`com.finx.engine.adapter.RiskDecisionAdapter`)
- **DOCUMENT** (`com.finx.engine.adapter.DocumentDecisionAdapter`)

Not implemented (explicitly deferred):
- `TRUST`
- `MILESTONE_RULES`
- `APPROVAL`
- `FRAUD`
- `RECONCILIATION`
- `DISPUTE`
- `RELEASE`

Strict boundaries maintained:
- No `ReleaseInstruction` created
- No `BankingExecutionService` created
- No outbox, bank execution attempts, or approval workflow tables
- Production payout remains strictly **DISABLED**

## Preconditions

- Boundary commit `b605858`: PASS
- Common framework commit `c8137f8`: PASS
- Flyway V01–V25 clean sequential migrations: PASS
- PostgreSQL `finx_engine_framework_test` verified: PASS
- B01–B13 safety controls: PASS

## Risk Adapter

- **Class**: `com.finx.engine.adapter.RiskDecisionAdapter`
- **SPI Contract**: Implements `com.finx.engine.adapter.ReadOnlyDecisionAdapter`, which extends `com.finx.engine.evaluator.DecisionEvaluator`
- **Engine Type**: `EngineType.RISK`

## Risk Authoritative Source

- **Read Contract**: `com.finx.risk.service.RiskEvidenceReadService`
- **Persisted Entities**:
  - `com.finx.risk.entity.RiskScore` (table `risk_scores` via `BusinessRiskRepository`)
  - `com.finx.risk.entity.RiskAssessment` (table `risk_assessments`)
  - `com.finx.risk.entity.RiskReview` (table `risk_reviews`)
- **Strict Boundary**: The adapter consumes persisted B13 evidence only via `RiskEvidenceReadService`. It never invokes `RiskEngineService.getAssessmentForDeal` (preventing transient aggregate creation and recalculation side effects).

## Buyer / Vendor Separation

Risk decisions are strictly evaluated per organization. Buyer and vendor counterparties are never conflated into a single decision:
- **Buyer Decision**:
  - `organizationId` = `deal.getBuyerOrganizationId()`
  - `subjectType` = `"ORGANIZATION"`
  - `subjectId` = `deal.getBuyerOrganizationId()`
- **Vendor Decision**:
  - `organizationId` = `deal.getSellerOrganizationId()`
  - `subjectType` = `"ORGANIZATION"`
  - `subjectId` = `deal.getSellerOrganizationId()`
- Both decisions share `evaluationId` and `correlationId` when produced as part of a coordinated evaluation cycle, but maintain distinct logical identities under the `uk_engine_decisions_logical_identity` unique index.

## Risk Decision Mapping

Mapping directly reflects native effective policy semantics without simplistic score-threshold assumptions:
1. **Active Hard Restriction (`BLOCK`)**:
   - Condition: `evidence.effectiveRestrictionActive() == true` (active `RiskReview` with `status == ReviewStatus.RESTRICTED` and unexpired `restrictionUntil`).
   - Reason Code: `RISK_RESTRICTION_ACTIVE` (plus underlying assessment reason codes).
   - Explanation: `"Active risk restriction in effect until ..."`
2. **Missing Evidence (`REVIEW`)**:
   - Condition: `evidence == null || evidence.status() == EvidenceStatus.MISSING || evidence.subjectId() == null`.
   - Reason Code: `RISK_EVIDENCE_MISSING`.
   - Score: `null` (never defaulted to 0, LOW, or ALLOW).
   - Explanation: `"No persisted risk evidence found for organization ..."`
3. **Stale Evidence (`REVIEW`)**:
   - Condition: `evidence.status() == EvidenceStatus.STALE` (`nextRefreshAt.isBefore(Instant.now())`).
   - Reason Code: `RISK_EVIDENCE_STALE`.
   - Native score preserved; zero background refresh during read.
4. **Review Required (`REVIEW`)**:
   - Condition: `Boolean.TRUE.equals(evidence.reviewRequired()) || (evidence.reviewStatus() != null && evidence.reviewStatus() != ReviewStatus.CLEARED)`.
   - Reason Code: `RISK_REVIEW_REQUIRED`.
   - Explanation: `"Manual risk review is required (reviewStatus: ...)"`
5. **Policy Clear / Eligible (`ALLOW`)**:
   - Condition: `!evidence.effectiveRestrictionActive() && !Boolean.TRUE.equals(evidence.reviewRequired()) && (reviewStatus == null || reviewStatus == ReviewStatus.CLEARED) && evidence.status() == EvidenceStatus.PRESENT`.
   - Reason Code: `RISK_POLICY_CLEAR`.
   - Explanation: `"Risk policy clear (score: ..., level: ...)"`

### Edge Cases Verified
- **High Risk Score Edge Case**: High score (e.g. 85, HIGH) + cleared review (`ReviewStatus.CLEARED`, `reviewRequired == false`, `effectiveRestrictionActive == false`) does **NOT** blindly become `BLOCK`; it maps to `ALLOW`.
- **Active Restriction Edge Case**: Lower score (e.g. 20, LOW) + active restriction (`effectiveRestrictionActive == true`) preserves `BLOCK`.

## Native Risk Semantics Preserved

- **Raw Score**: Preserved directly in `EngineDecision.score` as `BigDecimal.valueOf(evidence.score())`. Score direction is unchanged. Buyer and vendor scores are never averaged.
- **reviewRequired**: Preserved as distinct trigger for `REVIEW` status.
- **Effective Restriction**: Preserved from native `RiskReview` records.
- **Policy Version**: Preserved from `RiskAssessment.ruleVersion` (null if absent; no synthetic version strings fabricated).
- **Observed At**: Preserved from `RiskScore.calculatedAt` or `RiskAssessment.createdAt`.

## Risk Missing / Stale Handling

- Missing counterparty risk maps to `DecisionStatus.REVIEW` with `reasonCode = "RISK_EVIDENCE_MISSING"`, `score = null`, and empty evidence references.
- Stale counterparty risk maps to `DecisionStatus.REVIEW` with `reasonCode = "RISK_EVIDENCE_STALE"`. The adapter performs zero auto-recalculation or background refresh.

## Risk Evidence References

Durable references cite only persisted database rows:
- `RISK_SCORE`: `sourceId = evidence.subjectId()` (points to `risk_scores.id`)
- `RISK_ASSESSMENT`: `sourceId = evidence.persistedAssessmentId()` (points to `risk_assessments.id`)
- `RISK_RESTRICTION`: `sourceId = evidence.activeRestrictionId()` (points to `risk_reviews.id`)
- Transient aggregate `RiskAssessment` IDs are strictly excluded.

---

## Document Adapter

- **Class**: `com.finx.engine.adapter.DocumentDecisionAdapter`
- **SPI Contract**: Implements `com.finx.engine.adapter.ReadOnlyDecisionAdapter`
- **Engine Type**: `EngineType.DOCUMENT`

## Invoice Assessment Source

- **Entity**: `com.finx.invoice.entity.InvoiceAssessment` (table `invoice_assessments` via `InvoiceAssessmentRepository`)
- **Invoice Entity**: `com.finx.invoice.entity.Invoice` (table `invoices` via `InvoiceRepository`)
- **Review Entity**: `com.finx.invoice.entity.InvoiceReview` (table `invoice_reviews` via `InvoiceReviewRepository`)
- **Inspection Rules**: Consumes latest persisted `InvoiceAssessment`. Pure read-only; performs zero invoice mutations, zero OCR processing, and zero external AI provider calls.

## Document Decision Mapping

1. **Hard Prohibitions (`BLOCK`)**:
   - `InvoiceStatus.REJECTED` -> `DOCUMENT_REJECTED`
   - `DuplicateConfidence.EXACT_DUPLICATE` -> `DOCUMENT_EXACT_DUPLICATE`
   - `MatchingStatus.MAJOR_MISMATCH` -> `DOCUMENT_MAJOR_MISMATCH`
2. **Missing Assessment (`REVIEW`)**:
   - No `InvoiceAssessment` found -> `DOCUMENT_ASSESSMENT_MISSING`
3. **Superseded Revision (`REVIEW`)**:
   - Newer invoice exists for milestone or revised invoice exists -> `DOCUMENT_SUPERSEDED_REVISION` (never `ALLOW`)
4. **Human Review Required / Pending (`REVIEW`)**:
   - `InvoiceStatus.REVIEW_REQUIRED`, `PARTIAL_MATCH`, `MatchingStatus.REVIEW_REQUIRED`, `MATCH_WITH_WARNINGS`, or open `InvoiceReview` -> `DOCUMENT_REVIEW_REQUIRED` / `DOCUMENT_REVIEW_PENDING`
5. **B10 Approval Freshness Verification**:
   - If `invoice.getStatus() == InvoiceStatus.APPROVED`:
     - Must have an `APPROVED` `InvoiceReview` (`DOCUMENT_REVIEW_NOT_APPROVED` if missing)
     - Approved review must be bound to the latest `InvoiceAssessment` (`DOCUMENT_REVIEW_SUPERSEDED` if unlinked)
     - Invoice must not have been modified after the review was recorded (`DOCUMENT_MODIFIED_AFTER_REVIEW` if modified)
6. **Verified Current Approved Assessment (`ALLOW`)**:
   - Current applicable assessment, not superseded, B10 verified -> `DOCUMENT_APPROVED` (score preserved from `invoice.matchScore`).

## Invoice Assessment Identity

- Authoritative identity resolved from `InvoiceAssessment.id`.
- Cited in `EngineDecision.sourceAssessmentId` and in `EvidenceReference("INVOICE_ASSESSMENT", assessment.id, ...)`.

## Fingerprint / Revision

- Deterministic input fingerprint preserved from `InvoiceAssessment.fingerprint`.
- Revision reference preserved from `Invoice.revisionOf` / `rowVersion`.

## Manual Provenance

- If `invoice.getExtractionStatus() == ExtractionStatus.MANUAL_ENTRY`, provenance is strictly preserved as `"MANUAL_ENTRY"`.
- Manual entry is never relabeled as `"OCR"`, `"AI"`, `"ML VERIFIED"`, or `"DOCUMENT INTELLIGENCE VERIFIED"`.
- Manual entry alone does not automatically cause `BLOCK`; it allows review or verified approval while preserving full provenance visibility.

## Human Review Distinction

- Automated matching is strictly separated from human review state.
- Automated `STRONG_MATCH` is not treated as final release authorization.
- Human review state (`InvoiceReview`) is independently inspected and required for `ALLOW` under B10 freshness rules.

## Missing / Superseded Handling

- Missing assessment produces `REVIEW` with reason `DOCUMENT_ASSESSMENT_MISSING`.
- Superseded revision produces `REVIEW` with reason `DOCUMENT_SUPERSEDED_REVISION` and is never allowed to produce `ALLOW`.

## Input Fingerprints

- **Risk**: Deterministic SHA-256 hash of `subjectId|persistedAssessmentId|score|riskLevel|effectiveRestrictionActive|reviewRequired|observedAt|ruleVersion`.
- **Document**: Deterministic SHA-256 hash derived from `assessment.fingerprint` (or `invoiceId|documentHash|status` fallback).

## Tenant Isolation

- In both adapters and the evaluation coordinator, `organizationId` is strictly enforced:
  - Buyer Risk: `deal.getBuyerOrganizationId()`
  - Vendor Risk: `deal.getSellerOrganizationId()`
  - Document Invoice: `deal.getBuyerOrganizationId()`
- Evaluated decisions are persisted through `EngineDecisionService.recordDecision`, which enforces tenant-scoped lookups.
- Tests confirm: queries scoped to `buyerOrgId` return buyer decisions, while queries scoped to an unrelated `orgC` return 0 decisions.

## Common Decision Persistence

- Coordinated execution via `ReadOnlyDomainDecisionCoordinator`:
  - Produces `DomainEvaluationResult(evaluationId, correlationId, buyerRiskCommand, vendorRiskCommand, documentCommand)`
  - Persists exactly 3 `EngineDecision` rows in `engine_decisions`
  - All 3 rows share the same `evaluationId` and `correlationId`
  - All 3 rows have distinct logical identities `(evaluation_id, engine_type, subject_type, subject_id, organization_id)`
  - Deterministic replay tested: re-evaluating with unchanged data returns existing records without duplicate key errors or row count explosion.

## Financial Side Effects

- BankingProvider initiateSettlement calls: **0**
- Journal entries posted: **0**
- Escrow mutations: **0**
- Settlement instructions created: **0**
- Risk history writes: **0**
- Risk review writes: **0**
- Invoice writes: **0**

## RELEASE Decisions Created

- `EngineType.RELEASE` row count delta: **0**

## Database Migration

- **NONE** (No V26 migration added; reuses existing V25 framework tables `engine_decisions` and `engine_policy_versions`).

## Files Modified

- `backend/src/main/java/com/finx/risk/service/RiskEvidenceReadService.java`: Added `getSubjectRiskEvidence` read-only delegate.

## Files Created

- `backend/src/main/java/com/finx/engine/adapter/ReadOnlyDecisionAdapter.java`
- `backend/src/main/java/com/finx/engine/adapter/RiskDecisionAdapter.java`
- `backend/src/main/java/com/finx/engine/adapter/DocumentDecisionAdapter.java`
- `backend/src/main/java/com/finx/engine/adapter/DomainEvaluationResult.java`
- `backend/src/main/java/com/finx/engine/adapter/ReadOnlyDomainDecisionCoordinator.java`
- `backend/src/test/java/com/finx/engine/ReadOnlyDecisionAdapterArchitectureTest.java`
- `backend/src/test/java/com/finx/engine/RiskDecisionAdapterTest.java`
- `backend/src/test/java/com/finx/engine/DocumentDecisionAdapterTest.java`
- `backend/src/test/java/com/finx/engine/ReadOnlyDomainDecisionPersistenceTest.java`
- `READ_ONLY_DOMAIN_DECISION_ADAPTERS_REPORT.md`

## Tests Added

- `ReadOnlyDecisionAdapterArchitectureTest`: 4 tests (verifies zero dependencies on banking, ledger, escrow, settlement, razorpay, reconciliation, blockchain, or controllers).
- `RiskDecisionAdapterTest`: 16 tests (verifies buyer/vendor separation, durable references, score preservation, high-score + cleared review, active restriction, missing/stale evidence, zero side effects).
- `DocumentDecisionAdapterTest`: 15 tests (verifies assessment ID retention, manual provenance fidelity, missing/superseded/stale review, zero side effects).
- `ReadOnlyDomainDecisionPersistenceTest`: 3 tests (verifies coordinated persistence, deterministic replay, and tenant isolation).

## Tests Run

- Adapter test suite: 38/38 PASS
- Framework & Boundary regression: 48/48 PASS
- B13, B10 & Financial regression: 124/124 PASS
- Security regression: 159/159 PASS
- Full Maven test suite (`.\mvnw.cmd test`): **707/707 PASS** (0 failures, 0 errors, 1 skipped)

## Deferred Adapters

- **TRUST**: Deferred because `OrganizationService.getVerifiedBusinessProfile` performance metrics remain excluded under D06 and no trust decision policy is implemented.
- **MILESTONE_RULES**: Deferred because deterministic contractual milestone rules (amounts, signed revision, deliverables, deadlines, sequence) require separate rule specifications.
- **APPROVAL**: Deferred because enterprise approval workflow semantics (quorum, stages, delegable authorities) are not yet implemented.
- **FRAUD**: Deferred because dedicated fraud policies require separate signal evaluation.
- **RECONCILIATION**: Deferred because reconciliation remains non-authoritative under D03.
- **DISPUTE**: Deferred because dispute evidence and version models are scheduled for later phases.
- **RELEASE**: Deferred because RELEASE aggregation depends on all required current component engine decisions.

## Production Payout

**DISABLED**

## Adapter Phase Status

**PASS**

## Ready For Next Engine Phase

**YES**
