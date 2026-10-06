# Fraud Decision Adapter Report

## Scope

F2 only.

Implemented read-only [FraudDecisionAdapter](file:///C:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/backend/src/main/java/com/finx/engine/adapter/FraudDecisionAdapter.java) and supporting [FraudDecisionEvidenceReader](file:///C:/Users/91863/OneDrive/Documents/from%20scratch/FinX-/backend/src/main/java/com/finx/engine/adapter/FraudDecisionEvidenceReader.java) evaluating canonical persisted evidence against Platform Fraud Policy v1.0.1.

## Preconditions

- Policy Key: `FINX_FRAUD_DETERMINISTIC_BASE`
- Policy Version: `1.0.1`
- Schema Version: `1.0`
- Content Hash: `d4f17cafa56ee686f80ec3665e2d5056f7b8ab7249ef097b4a3eacfaea292b94`
- F1.1 PostgreSQL verification commit: `416f43e` (PASS, 0 skipped on real PostgreSQL 16.15)
- Working tree: Clean

## FraudDecisionAdapter

- Class: `com.finx.engine.adapter.FraudDecisionAdapter`
- Interface: `com.finx.engine.adapter.ReadOnlyDecisionAdapter`
- Engine Type: `EngineType.FRAUD`
- Annotations: `@Component`, `@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)`

## Subject / Tenant Contract

- Subject Type: `DecisionSubjectType.MILESTONE` (`"MILESTONE"`)
- Subject ID: `milestone.getId()`
- Owner Organization ID: `deal.getBuyerOrganizationId()`
- Scope Invariants Enforced:
  - `context.subjectType().equals("MILESTONE")`
  - `context.subjectId().equals(milestone.getId())`
  - `milestone.getDealId().equals(deal.getId())`
  - `context.organizationId().equals(deal.getBuyerOrganizationId())`
  - `invoice.getBuyerOrganizationId().equals(deal.getBuyerOrganizationId())`
  - `invoice.getVendorOrganizationId().equals(deal.getSellerOrganizationId())`
- Cross-tenant and mismatched context strictly rejected with `UnauthorizedException` before evaluation.

## Policy Resolution

- Resolved via `EnginePolicyVersionService.getPlatformPolicy(EngineType.FRAUD, "FINX_FRAUD_DETERMINISTIC_BASE", "1.0.1")`.
- Arbitrary version fallback and legacy v1.0.0 resolution prohibited.
- Automatic inline registration/repair during evaluation prohibited.

## Policy Failure Semantics

Policy validation occurs before evaluating any domain evidence:
- Missing policy row: `DecisionStatus.REVIEW` + `EvaluationOutcome.ERROR` + `errorCode: "FRAUD_POLICY_UNAVAILABLE"`
- Inactive policy (`activatedAt == null`): `DecisionStatus.REVIEW` + `EvaluationOutcome.ERROR` + `errorCode: "FRAUD_POLICY_INACTIVE"`
- Content / Hash integrity mismatch: `DecisionStatus.REVIEW` + `EvaluationOutcome.ERROR` + `errorCode: "FRAUD_POLICY_INTEGRITY_FAILURE"`
- Fail-closed guarantees ensure no policy anomaly can ever produce `DecisionStatus.ALLOW` or `DecisionStatus.BLOCK`.

## Policy Binding

Every successful `RecordEngineDecisionCommand` includes:
- `policyVersionId`: ID of registered and activated `EnginePolicyVersion` row
- `policyContentHash`: `d4f17cafa56ee686f80ec3665e2d5056f7b8ab7249ef097b4a3eacfaea292b94`
- `ruleVersion`: `"FINX_FRAUD_DETERMINISTIC_BASE:1.0.1"`
- `schemaVersion`: `"1.0"`
- `engineImplementationVersion`: `"1.0.0"`

## Numeric Fraud Score

- `score`: `null`
- Strictly zero numeric, probability, percentile, risk-band, or confidence score calculation.

## S01 Document Hash Duplicate

- Reason Code: `FRAUD_DUPLICATE_DOCUMENT_HASH`
- Disposition: `DecisionStatus.BLOCK`
- Primary Evidence: `invoices.document_hash`
- Canonical Persisted Checks: `InvoiceCheck` with `checkType == DOCUMENT_HASH_CHECK` and `status == FAIL` / `reasonCode == "DOCUMENT_HASH_DUPLICATE"`
- Duplicate Candidate Discovery: `InvoiceRepository.findByDocumentHash(documentHash)` excluding current invoice.
- Zero `BYTEA` hashing or file recalculation.

## S02 Vendor Invoice Number Duplicate

- Reason Code: `FRAUD_DUPLICATE_INVOICE_NUMBER`
- Disposition: `DecisionStatus.BLOCK`
- Canonical Source: Scoped query `InvoiceRepository.findByVendorOrganizationIdAndNormalizedInvoiceNumber(vendorOrganizationId, normalizedInvoiceNumber)`
- Exclusions: Current invoice ID and all legitimate ancestor invoice IDs in the revision chain.
- No cross-vendor false positives.

## Revision Handling

- Safe revision chain traversal following `revisionOf` foreign keys.
- Cycle detection using visited ID sets.
- Strict scope verification on all ancestor records (must match same deal, milestone, and vendor organization).
- If revision lineage is corrupt, evaluation fails safely into `DecisionStatus.REVIEW` (`FRAUD_MISSING_INVOICE_EVIDENCE`).

## S03 Tax Identity Mismatch

- Reason Code: `FRAUD_TAX_IDENTITY_MISMATCH`
- Disposition: `DecisionStatus.BLOCK`
- Source: Persisted `InvoiceCheck` records with `checkType == GSTIN_MATCH` or `checkType == PAN_MATCH` and `status == FAIL`.
- Privacy Protection: Raw PAN and GSTIN text values are never copied into explanations, reasons, or JSON payloads.

## S04 Beneficiary Name Mismatch

- Reason Code: `FRAUD_BENEFICIARY_NAME_MISMATCH`
- Disposition: `DecisionStatus.BLOCK`
- Source: Payout bank verification evidence via `VerificationEligibilityService.evaluateBeneficiaryEligibility(sellerOrgId)` and `OrganizationBankAccountRepository`.
- Trigger: If there is no eligible beneficiary and verified seller bank evidence proves explicit `nameMatchStatus == "MISMATCH"`.
- Clean eligible beneficiary with unrelated historical mismatched account does not trigger S04 BLOCK.

## S05 Provider Reference Reuse

- Explicitly NOT evaluated from current read model (`evaluableFromCurrentReadModel = false`, `enforcedAtPersistenceBoundary = true`).
- Reason `FRAUD_PROVIDER_REFERENCE_REUSE` is never emitted at runtime.
- Zero dependency on `PaymentTransactionRepository` or `SettlementInstructionRepository` in `FraudDecisionAdapter`.

## S06 Recent Beneficiary Modification

- Explicitly DISABLED (`enabled = false`, `currentThreshold = "UNDEFINED"`, `policyState = "PRODUCT_POLICY_REQUIRED"`).
- Reason `FRAUD_BENEFICIARY_ACCOUNT_RECENTLY_MODIFIED` is never emitted at runtime.
- Zero arbitrary timestamp lookback windows invented.

## S07 Invoice Date Anomaly

- Reason Code: `FRAUD_INVOICE_DATE_ANOMALY`
- Disposition: `DecisionStatus.REVIEW`
- Source: Persisted `InvoiceCheck` with `checkType == INVOICE_DATE_VALID` and `status == WARNING`/`FAIL` or `reasonCode == "INVOICE_DATE_ANOMALY"`.
- Zero date recalculation or `LocalDate.now()` comparison in adapter.

## Missing Evidence Semantics

- Missing required invoice (`milestone.isInvoiceRequired() == true` with no invoice): `DecisionStatus.REVIEW` (`FRAUD_MISSING_INVOICE_EVIDENCE`)
- Missing invoice when not required: Continues cleanly to beneficiary evaluation.
- Empty or materially absent `InvoiceCheck` records on submitted invoice: `DecisionStatus.REVIEW` (`FRAUD_MISSING_INVOICE_EVIDENCE`)
- Unassigned or ambiguous beneficiary (0 eligible or >1 eligible): `DecisionStatus.REVIEW` (`FRAUD_BENEFICIARY_UNASSIGNED`)

## Decision Precedence

- Deterministic precedence: `BLOCK` > `REVIEW` > `ALLOW`
- `ALLOW` Reason: `FRAUD_EVALUATION_PASSED`

## Risk / Fraud Boundary

- `FraudDecisionAdapter` has zero dependency on `RiskDecisionAdapter`, `RiskScoringService`, `RiskEvidenceReadService`, `RiskAssessment`, `RiskScore`, or `RiskRuleEngine`.
- Derived `RiskScore` is NOT consumed as fraud evidence (`consumeDerivedRiskScore = false`).

## Evidence References

All evidence references point strictly to persisted, durable domain source IDs:
- `ENGINE_POLICY_VERSION`: Policy row ID + content hash
- `DEAL`: Deal ID + updated timestamp
- `MILESTONE`: Milestone ID + updated timestamp
- `INVOICE`: Invoice ID + updated timestamp + document hash
- `INVOICE_CHECK`: Check ID + check type + status
- `INVOICE_DUPLICATE_CANDIDATE`: Candidate invoice ID
- `ORGANIZATION_BANK_ACCOUNT`: Bank account ID + verification status

## Input Fingerprint

Deterministic SHA-256 hash over canonical JSON structure including:
- Policy ID, policy key, version, and content hash
- Deal ID, buyer org ID, seller org ID, updated timestamp
- Milestone ID, deal ID, updated timestamp, invoiceRequired flag
- Invoice ID, revisionOf, status, documentHash (if present)
- Sorted InvoiceCheck list (ID, checkType, status, reasonCode, createdAt)
- Sorted duplicate candidate IDs
- Selected/eligible beneficiary bank account ID and verification metadata

## Tenant Isolation

- Enforced through strict scope binding to buyer organization and milestone deal linkage.
- Caller-scoped endpoint `evaluateForCaller` validates caller permissions via `DealAccessService.requireDealAccess(...)`.
- Cross-tenant requests fail closed immediately with `UnauthorizedException`.

## Side Effects

- Bank partner calls: 0
- Bank statement / feed calls: 0
- Ledger writes: 0
- Escrow writes: 0
- Settlement writes: 0
- Payment transaction writes: 0
- Risk scoring / assessment writes: 0
- Invoice / check writes: 0
- Invoice matching engine invocations: 0

## Database Migration

- Database Migration: NONE
- Highest Migration: V25
- V26: ABSENT

## RELEASE Decisions Created

- Automatic RELEASE decisions created: 0

## Files Created

- `backend/src/main/java/com/finx/engine/adapter/FraudDecisionEvidenceReader.java`
- `backend/src/main/java/com/finx/engine/adapter/FraudDecisionAdapter.java`
- `backend/src/test/java/com/finx/engine/FraudDecisionAdapterTest.java`
- `backend/src/test/java/com/finx/engine/FraudDecisionAdapterArchitectureTest.java`
- `backend/src/test/java/com/finx/engine/FraudDecisionPersistenceTest.java`
- `FRAUD_DECISION_ADAPTER_REPORT.md`

## Files Modified

- `backend/src/main/java/com/finx/engine/adapter/DecisionEvidence.java` (added `computeFingerprint()` and `getSortedReferences()`)
- `backend/src/test/java/com/finx/engine/ReadOnlyDecisionAdapterArchitectureTest.java` (registered fraud adapter classes)

## Tests Added

- `FraudDecisionAdapterTest` (54 required test scenarios across policy, S01-S07, missing evidence, ALLOW, determinism, tenant isolation, zero side effects)
- `FraudDecisionAdapterArchitectureTest` (boundary isolation against banking, payment, risk, reconciliation, invoice matching)
- `FraudDecisionPersistenceTest` (decision recording, policy binding, idempotency, conflict detection, zero side effects)

## Tests Run

- `FraudDecisionAdapterTest`: PASS (54/54)
- `FraudDecisionAdapterArchitectureTest`: PASS (3/3)
- `FraudDecisionPersistenceTest`: PASS (2/2)
- `PostgresFraudPolicyTest`: PASS (1/1, 0 skipped on real PostgreSQL 16.15)
- `PostgresEngineFrameworkTest`: PASS (5/5, 0 skipped on real PostgreSQL 16.15)
- `FlywayCleanMigrationTest`: PASS (V1-V25 clean migration on PostgreSQL 16.15)
- `RiskDecisionAdapterTest`: PASS (16/16)
- `DocumentDecisionAdapterTest`: PASS (16/16)
- `MilestoneRulesDecisionAdapterTest`: PASS (10/10)
- `ApprovalDecisionAdapterTest`: PASS (6/6)
- `TrustDecisionAdapterTest`: PASS (20/20)
- `DisputeDecisionAdapterTest`: PASS (12/12)
- Full Maven Test Suite: PASS (884 tests run, 0 failures, 0 errors, 1 skipped)

## PostgreSQL

- Engine Framework on Postgres: PASS (0 skipped)
- Fraud Policy v1.0.1 on Postgres: PASS (0 skipped)
- Clean Flyway Migration (V1-V25) on Postgres: PASS (0 skipped)

## Full Suite

- Status: BUILD SUCCESS
- Tests Run: 884
- Failures: 0
- Errors: 0

## Adapter Status

- Status: PASS

## Engine Coverage

- RISK: IMPLEMENTED
- DOCUMENT: IMPLEMENTED
- MILESTONE_RULES: IMPLEMENTED
- APPROVAL: IMPLEMENTED READ-ONLY
- TRUST: IMPLEMENTED
- DISPUTE: IMPLEMENTED
- FRAUD: IMPLEMENTED
- RECONCILIATION: NOT READY

Coverage: 7 / 8 prerequisite component adapters

## Production Payout

- Status: DISABLED

## Ready For Reconciliation Remediation

- Status: YES
