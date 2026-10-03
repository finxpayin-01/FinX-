# Common EngineDecision Framework Report

## Scope

- Framework foundation only.
- No business engines implemented in this phase.
- No production money movement enabled.

## Preconditions

- B01–B13 containment and remediation: complete and verified (PASS).
- Restricted framework boundary commit `b605858` ("test: prove restricted framework safety boundary"): verified (PASS).
- Financial idempotency, ledger consistency, and locking containment: verified (PASS).

## DecisionStatus

The `DecisionStatus` enumeration strictly contains three exact evaluation outcomes:
1. `ALLOW` — The subject evaluated satisfies policy and evidence criteria for the specific engine.
2. `REVIEW` — Human or secondary review is required, or an evaluation failure/error occurred.
3. `BLOCK` — Evaluation failed or violated critical risk/rule constraints.

No financial lifecycle values (e.g. `PENDING`, `FAILED`, `EXECUTED`, `SETTLED`) are present in `DecisionStatus`. It represents evaluation results only.

## EngineType

The `EngineType` enumeration strictly defines the nine architectural decision engine types:
1. `RISK`
2. `TRUST`
3. `MILESTONE_RULES`
4. `APPROVAL`
5. `FRAUD`
6. `DOCUMENT`
7. `RECONCILIATION`
8. `DISPUTE`
9. `RELEASE`

## EngineDecision Schema

Every persisted `EngineDecision` record contains the following schema fields in `engine_decisions`:
- `id` (UUID, PK) — Unique surrogate identifier for the decision record.
- `organizationId` (UUID, NOT NULL) — The tenant/organization that owns the evaluation scope.
- `engineType` (VARCHAR(50), NOT NULL) — One of the 9 exact `EngineType` values.
- `subjectType` (VARCHAR(50), NOT NULL) — Canonical domain entity type (e.g. `DEAL`, `MILESTONE`, `INVOICE`, `ORGANIZATION`).
- `subjectId` (UUID, NOT NULL) — Identifier of the evaluated entity.
- `decision` (VARCHAR(20), NOT NULL) — `ALLOW`, `REVIEW`, or `BLOCK`.
- `evaluationOutcome` (VARCHAR(20), NOT NULL) — `SUCCESS` or `ERROR`.
- `errorCode` (VARCHAR(100)) — Required when `evaluationOutcome == ERROR`.
- `score` (NUMERIC(10, 4)) — Optional engine-specific numeric score (e.g. 0.0000 - 100.0000). Direction is engine-specific.
- `reasonCodesJson` (TEXT, NOT NULL, DEFAULT '[]') — JSON array of structured reason codes (e.g. `["LOW_RISK_APPROVED"]`).
- `explanation` (VARCHAR(1000)) — Safe diagnostic human-readable text; strictly excludes credentials, secrets, or account numbers.
- `evidenceReferencesJson` (TEXT, NOT NULL, DEFAULT '[]') — JSON array of immutable `EvidenceReference` records.
- `ruleVersion` (VARCHAR(100)) — Version tag or identifier of the rule set used.
- `policyVersionId` (UUID) — Optional reference to an `EnginePolicyVersion` record.
- `policyContentHash` (VARCHAR(64)) — SHA-256 hash of policy content used during evaluation.
- `sourceAssessmentId` (UUID) — Optional reference to a primary source assessment record (e.g. `InvoiceAssessment`).
- `evaluationId` (UUID, NOT NULL) — Logical evaluation run identifier; shared across decisions within one evaluation cycle.
- `correlationId` (UUID, NOT NULL) — Trace/correlation group ID for end-to-end evaluation tracking.
- `inputFingerprint` (VARCHAR(64)) — Deterministic SHA-256 hash of canonical evaluated input data.
- `inputRevision` (VARCHAR(100)) — Optional revision identifier of evaluated source document or entity.
- `schemaVersion` (VARCHAR(20), NOT NULL, DEFAULT '1.0') — Decision payload schema version.
- `engineImplementationVersion` (VARCHAR(50), NOT NULL, DEFAULT '1.0.0') — Version of evaluator code.
- `decisionFingerprint` (VARCHAR(64)) — Deterministic SHA-256 hash of the entire decision envelope for idempotent duplicate replay vs. conflict detection.
- `validUntil` (TIMESTAMP WITH TIME ZONE) — Expiry timestamp for evidence freshness; enforced by DB CHECK constraint `validUntil IS NULL OR validUntil >= evaluatedAt`.
- `evaluatedAt` (TIMESTAMP WITH TIME ZONE, NOT NULL) — Timestamp when the evaluation was executed.
- `createdAt` (TIMESTAMP WITH TIME ZONE, NOT NULL, DEFAULT NOW()) — Record persistence timestamp.

## Evaluation Identity

Every evaluation execution is uniquely tracked by:
1. `evaluationId` (UUID): Identifies a single execution run across one or more engines.
2. `correlationId` (UUID): Identifies an end-to-end user request or workflow trace.
3. `organizationId` (UUID): Defines the tenant evaluation owner. Cannot be null.
4. Logical Decision Identity:
   `(evaluation_id, engine_type, subject_type, subject_id, organization_id)`
   Enforced by unique index `uk_engine_decisions_logical_identity`. Duplicate inserts of the same logical identity with identical content return the existing record (safe deterministic replay); conflicting inserts are rejected.

## Error Semantics

- Fail-closed invariant: Any evaluation failure or unhandled exception during engine evaluation produces `EvaluationOutcome.ERROR` and `DecisionStatus.REVIEW`.
- Evaluation error can NEVER produce `ALLOW`.
- Evaluation error can NEVER produce `BLOCK` automatically without policy evaluation.
- A non-blank `errorCode` is strictly required when `evaluationOutcome == ERROR`.
- Enforced at both the Java command builder level and the PostgreSQL database constraint `chk_engine_decisions_error_invariant`:
  `(evaluation_outcome != 'ERROR' OR (decision = 'REVIEW' AND error_code IS NOT NULL AND length(trim(error_code)) > 0))`.

## Evidence References

- Structure: `EvidenceReference` record containing:
  - `sourceType` (e.g. `RISK_ASSESSMENT`, `INVOICE_ASSESSMENT`, `BENEFICIARY_VERIFICATION`)
  - `sourceId` (UUID of persisted durable record)
  - `observedAt` (Instant of source observation)
  - `fingerprint` (SHA-256 hash of source content)
  - `provenance` (Source system / authority)
  - `revision` (Source document revision)
- Durability rule: Evidence references must refer ONLY to persisted, durable database records.
- Transient aggregate identities are never accepted as durable evidence IDs.
- Missing or unresolved evidence is represented as `REVIEW` with an appropriate reason code, never with fabricated IDs.
- Stored as references only: raw payload documents, credentials, and full banking details are never duplicated into `EngineDecision`.

## Input Fingerprint

- Stored as deterministic SHA-256 hash (`inputFingerprint`).
- Enables post-decision freshness detection to determine if underlying source data has been modified since evaluation.

## Freshness / validUntil

- An optional `validUntil` timestamp records evidence expiration.
- Enforced constraint: `validUntil >= evaluatedAt`.
- Crucial invariant: An unexpired `ALLOW` decision is NOT a permanent authorization. Real-time revalidation is always required prior to any state transition.

## Policy Version Model

- `EnginePolicyVersion` entity maps `engine_policy_versions` table.
- Stores immutable metadata for policy definitions:
  `id`, `organizationId`, `scope` (`PLATFORM` or `ORGANIZATION`), `engineType`, `policyKey`, `version`, `contentHash`, `schemaVersion`, `description`, `policyContent`, `createdBy`, `createdAt`, `activatedAt`.
- Policy versions are append-only. Updates and deletes are prohibited.

## Platform Policy Scope

- Where `scope = 'PLATFORM'`, `organizationId` MUST be NULL.
- Partial unique index `uk_engine_policy_platform`:
  `ON engine_policy_versions (engine_type, policy_key, version) WHERE organization_id IS NULL;`

## Tenant Policy Scope

- Where `scope = 'ORGANIZATION'`, `organizationId` MUST NOT be NULL.
- Partial unique index `uk_engine_policy_org`:
  `ON engine_policy_versions (organization_id, engine_type, policy_key, version) WHERE organization_id IS NOT NULL;`
- Supports tenant-specific overrides of platform baseline policies with unambiguous uniqueness.

## Immutability

- `EngineDecision` and `EnginePolicyVersion` entities are annotated with Hibernate `@Immutable`.
- Neither entity nor service exposes update, overwrite, or delete methods.
- If evidence or circumstances change, evaluators persist a NEW `EngineDecision` row under a new `evaluationId`. Previous records remain intact for complete audit history.

## Tenant Isolation

- `organizationId` is mandatory on every `EngineDecision`.
- `EngineDecisionService` query methods require non-null `organizationId`.
- No unscoped `findAll()` API is exposed in the service layer.
- Cross-tenant queries are blocked and return empty results.

## Duplicate Evaluation Protection

- Deterministic decision fingerprint is calculated via `EngineDecisionFingerprint.calculate(...)` using SHA-256.
- Database uniqueness index `uk_engine_decisions_logical_identity` prevents concurrent or duplicate inserts.
- Identical replay: Returns the existing entity safely without creating redundant rows.
- Conflicting content for the same logical evaluation: Throws `IllegalStateException`.
- Multi-threaded concurrent race test verified against real PostgreSQL: Exactly 1 record created.

## PostgreSQL Migration

- Migration file: `V25__engine_policy_versions_and_decisions.sql`
- Clean migration executed sequentially from V1 to V25: PASS.
- Verified on PostgreSQL 16:
  - 2 new tables created (`engine_policy_versions`, `engine_decisions`).
  - Total tables verified in PostgreSQL: 63.
  - Partial unique indexes verified in `pg_indexes`.
  - Check constraints verified in `pg_constraint`.

## PostgreSQL Verification

- Integration test `PostgresEngineFrameworkTest` executed directly against PostgreSQL container (`finx_engine_framework_test` on `finx_postgres:5432`).
- Flyway V25 schema and constraints verified: PASS.
- Concurrent duplicate evaluation race with `CyclicBarrier`: PASS (catalog count == 1).
- Tenant query isolation verified: PASS.
- Policy version scope uniqueness (platform vs. tenant) verified: PASS.
- Zero financial side effects verified: PASS.

## Architecture Boundary

- `com.finx.engine..` package has ZERO dependencies on:
  - `BankingProvider`
  - `SettlementAuthorizationService`
  - `PaymentOrchestrationService`
  - `LedgerService`
  - `EscrowService`
  - `RazorpayService`
  - `PaymentService`
  - Blockchain packages / Web3
  - REST Controllers
- Proven by `CommonEngineFrameworkArchitectureTest` using reflection on all framework classes.

## RELEASE Safety

- An `EngineDecision` with `engineType = RELEASE` and `decision = ALLOW` is strictly an evaluation recommendation.
- It does NOT initiate settlement, create payment transactions, post ledger journals, or transfer escrow funds.
- `BankingProvider.initiateSettlement calls = 0`.
- Settlement instruction delta = 0.
- Ledger journal delta = 0.
- Escrow ledger delta = 0.

## Existing Domain Systems Reused

- The common engine framework foundation provides the standardized decision recording and policy archival model.
- Authoritative business domain systems remain intact:
  - `RiskEvidenceReadService`
  - `ApprovalApplicabilityService`
  - `VerificationEligibilityService`
  - `InvoiceService` / `InvoiceAssessmentRepository`
- No domain evaluators or adapters were prematurely implemented in this phase.

## Deferred Engine Implementations

The nine business engine implementations remain deferred to subsequent phases:
1. `RISK` adapter (deferred to Phase 1)
2. `TRUST` (deferred)
3. `MILESTONE_RULES` (deferred)
4. `APPROVAL` (deferred)
5. `FRAUD` (deferred)
6. `DOCUMENT` (deferred)
7. `RECONCILIATION` (deferred; D03 nonauthoritative status preserved)
8. `DISPUTE` (deferred)
9. `RELEASE` (deferred; release decision evaluation logic, `ReleaseInstruction`, and `BankingExecutionService`)

## Deferred Production Banking

- Production payout remains STRICTLY DISABLED.
- Automated bank execution attempts remain UNREACHABLE.
- Production banking provider remains UNCONFIGURED.

## Files Modified

- `backend/src/test/java/com/finx/FlywayCleanMigrationTest.java` — Added assertions for V25 tables (`engine_policy_versions`, `engine_decisions`).

## Files Created

- `backend/src/main/resources/db/migration/V25__engine_policy_versions_and_decisions.sql`
- `backend/src/main/java/com/finx/engine/model/DecisionStatus.java`
- `backend/src/main/java/com/finx/engine/model/EngineType.java`
- `backend/src/main/java/com/finx/engine/model/EvaluationOutcome.java`
- `backend/src/main/java/com/finx/engine/model/PolicyScope.java`
- `backend/src/main/java/com/finx/engine/model/DecisionSubjectType.java`
- `backend/src/main/java/com/finx/engine/model/EvidenceReference.java`
- `backend/src/main/java/com/finx/engine/model/EngineDecisionFingerprint.java`
- `backend/src/main/java/com/finx/engine/model/RecordEngineDecisionCommand.java`
- `backend/src/main/java/com/finx/engine/model/EvaluationContext.java`
- `backend/src/main/java/com/finx/engine/evaluator/DecisionEvaluator.java`
- `backend/src/main/java/com/finx/engine/entity/EnginePolicyVersion.java`
- `backend/src/main/java/com/finx/engine/entity/EngineDecision.java`
- `backend/src/main/java/com/finx/engine/repository/EnginePolicyVersionRepository.java`
- `backend/src/main/java/com/finx/engine/repository/EngineDecisionRepository.java`
- `backend/src/main/java/com/finx/engine/service/EnginePolicyVersionService.java`
- `backend/src/main/java/com/finx/engine/service/EngineDecisionService.java`
- `backend/src/test/java/com/finx/engine/CommonEngineFrameworkArchitectureTest.java`
- `backend/src/test/java/com/finx/engine/EngineDecisionModelTest.java`
- `backend/src/test/java/com/finx/engine/EngineDecisionServiceTest.java`
- `backend/src/test/java/com/finx/engine/EnginePolicyVersionServiceTest.java`
- `backend/src/test/java/com/finx/engine/PostgresEngineFrameworkTest.java`
- `COMMON_ENGINE_FRAMEWORK_REPORT.md`

## Database Migration

YES — `V25__engine_policy_versions_and_decisions.sql`

## Tests Added

1. `CommonEngineFrameworkArchitectureTest` (5 tests):
   - Proves zero dependencies on banking, execution, settlement, ledger, escrow, razorpay, blockchain, controllers.
2. `EngineDecisionModelTest` (6 tests):
   - Verifies exact `DecisionStatus` values (`ALLOW`, `REVIEW`, `BLOCK`).
   - Verifies exact 9 `EngineType` values.
   - Verifies error fail-closed invariant (`ERROR` requires `REVIEW` + non-blank error code).
   - Verifies `validUntil` >= `evaluatedAt`.
   - Verifies policy version platform/org scoping and content hash validation.
   - Verifies deterministic SHA-256 fingerprinting.
3. `EngineDecisionServiceTest` (5 tests):
   - Verifies tenant isolation and scoped read queries.
   - Verifies immutability and historical retention.
   - Verifies duplicate replay vs. conflicting evaluation detection.
   - Verifies durable evidence references.
   - Verifies `RELEASE` `ALLOW` produces zero banking or ledger calls.
4. `EnginePolicyVersionServiceTest` (1 test):
   - Verifies platform and organization policy registration, immutability, scope override precedence, and collision detection.
5. `PostgresEngineFrameworkTest` (5 tests):
   - Verifies V25 tables, partial indexes, and check constraints in PostgreSQL catalog.
   - Verifies concurrent duplicate evaluation race produces exactly 1 record in PostgreSQL.
   - Verifies PostgreSQL tenant query isolation.
   - Verifies PostgreSQL policy version scope uniqueness.
   - Verifies PostgreSQL `RELEASE` `ALLOW` produces zero banking or ledger writes.
6. `FlywayCleanMigrationTest` (updated):
   - Verifies clean migration sequence V1 -> V25.

## Tests Run

- Focused unit and migration suite: 18 tests (0 failures, 0 errors).
- PostgreSQL engine framework suite: 5 tests (0 failures, 0 errors).
- Boundary, financial, and security regression suite: 309 tests (0 failures, 0 errors).
- Full repository test suite (`.\mvnw.cmd test`): 669 tests (0 failures, 0 errors).

## Test Results

- Total tests executed: 669
- Failures: 0
- Errors: 0
- Skipped: 1 (normal pre-existing conditional test)
- Status: BUILD SUCCESS

## Known Limitations

- `EngineDecision` records are evaluation recommendations and evidence envelopes, NOT authorization or money movement instructions.
- No `ReleaseInstruction` exists in this phase.
- No `BankingExecutionService` exists in this phase.
- No generic outbox exists in this phase.
- Reconciliation decisions remain nonauthoritative (D03).
- Trust scoring engine remains deferred.
- No live banking partner is configured.
- Production payout remains strictly disabled.
- The nine business engine implementations remain deferred.

## Framework Foundation Status

PASS

## Ready For Read-Only Domain Adapters

YES
