# Fraud Policy v1.0.1 Correction Report

## Reason For New Version

During analysis preceding F2 (read-only `FraudDecisionAdapter` implementation), an inspection of the actual Flyway schema and domain models revealed two critical inaccuracies in the `v1.0.0` policy metadata:
1. **S01 Source Reference Inaccuracy**: `v1.0.0` metadata cited `invoice_documents.content_hash`. However, Flyway migration `V22__invoice_document_security_and_history.sql` and `InvoiceDocument.java` only persist `invoice_id` and raw `content` (`BYTEA`). The canonical, persisted SHA-256 hash is located in `invoices.document_hash` (supported by `invoice_checks` of type `DOCUMENT_HASH_CHECK`).
2. **S05 Provider Reference Identity Inaccuracy**: `v1.0.0` metadata referenced `payment_transactions.provider_reference_id`, whereas the true persistence-level idempotency and uniqueness boundaries in `V23` are scoped across both `payment_transactions(provider, utr_number)` and `settlement_instructions(provider, utr_number)`.

Because policy records in FinX are **immutable governance artifacts**, `v1.0.0` cannot and must not be mutated or deleted. Therefore, a new policy version `1.0.1` was created and registered.

## v1.0.0 Preservation

- **Database Row**: Existing `v1.0.0` records remain untouched.
- **Record Modification**: NO update or deletion of `v1.0.0` has occurred.
- **Historical Status**: `v1.0.0` is preserved as historical immutable governance evidence.

## S01 Schema Correction

- **Old Metadata Reference**: `invoice_documents.content_hash` (non-existent column).
- **Corrected Canonical Source**: `invoices.document_hash`
- **Conceptual Rule**: `EXACT_DOCUMENT_HASH_DUPLICATE`
- **Reason Code**: `FRAUD_DUPLICATE_DOCUMENT_HASH`
- **Disposition**: `BLOCK`
- **Runtime Strategy for F2**: FraudDecisionAdapter will consume `Invoice.documentHash` and persisted `DOCUMENT_HASH_CHECK` evidence; it will not calculate or hash `BYTEA` blobs during evaluation.

## S05 Scoped Identity Correction

- **Rule**: `PROVIDER_CREDIT_IDENTITY_REUSE`
- **Namespaces**:
  - `payment_transactions(provider, utr_number)`
  - `settlement_instructions(provider, utr_number)`
- **Evaluation Properties**:
  - `evaluableFromCurrentReadModel = false`
  - `enforcedAtPersistenceBoundary = true`
- **Reason**: Rejected duplicate provider submissions do not produce durable transaction rows accessible to a read-only adapter. F2 will not fabricate false positive alerts when no duplicate row exists.

## S06 Source Clarification

- **Rule**: `BENEFICIARY_ACCOUNT_RECENTLY_MODIFIED`
- **Canonical Timestamps**: `organization_bank_accounts.created_at`, `organization_bank_accounts.updated_at`
- **Disposition**: `REVIEW`
- **Current Threshold**: `UNDEFINED` (no arbitrary thresholds invented)
- **Enabled**: `false` (`PRODUCT_POLICY_REQUIRED`)

## S07 Canonical Check Source

- **Rule**: `INVOICE_DATE_ANOMALY`
- **Source**: Persisted `invoice_checks` with `check_type = INVOICE_DATE_VALID`
- **Disposition**: `REVIEW`
- **Runtime Strategy for F2**: FraudDecisionAdapter consumes the persisted invoice check result without re-evaluating date logic.

## New Policy Identity

- **Engine Type**: `FRAUD`
- **Policy Key**: `FINX_FRAUD_DETERMINISTIC_BASE`
- **Version**: `1.0.1`
- **Scope**: `PLATFORM`
- **Subject**: `MILESTONE`
- **Owner**: `BUYER_ORGANIZATION`
- **Consume Derived Risk Score**: `false`
- **Schema Version**: `1.0`

## New Content Hash

- **Calculated SHA-256**: `d4f17cafa56ee686f80ec3665e2d5056f7b8ab7249ef097b4a3eacfaea292b94`
- **Old v1.0.0 Hash**: `c27e12a196da104c8ab61cd715dc24e274d4ecf2e6f80781bcc0218353482c9a`
- **Verification**: Deterministic UTF-8 JSON serialization matching SHA-256 digest.

## Activation

- Registered via `EnginePolicyVersionService.registerPolicyVersion(...)`.
- `activatedAt` timestamp is set automatically upon active platform policy registration.
- Duplicate registration attempts are idempotent (no duplicates created).
- Hash mismatch for version `1.0.1` triggers `IllegalStateException`.

## PostgreSQL Verification

- **Real PostgreSQL**: PASS
- **Database Product**: PostgreSQL 16.15 (running in container `finx_postgres` on port 5432)
- **PostgresFraudPolicyTest**: PASS
  - Tests run: 1
  - Failures: 0
  - Errors: 0
  - Skipped: 0
- **Policy Row Count for v1.0.1**: 1
- **Content Hash Verified in PostgreSQL**: `d4f17cafa56ee686f80ec3665e2d5056f7b8ab7249ef097b4a3eacfaea292b94`
- **Flyway Clean Migration (V1 -> V25)**: PASS (FlywayCleanMigrationTest executed 25 migrations, created 63 tables, 0 skipped, 0 failures)
- **PostgresEngineFrameworkTest**: PASS (5 tests run, 0 skipped, 0 failures)
- **Idempotent Replay on PostgreSQL**: Verified (COUNT = 1 after replay)
- **Conflicting Hash on PostgreSQL**: Verified blocked (`IllegalStateException` thrown)

## Database Migration

- **New Migration**: NONE
- **Highest Migration**: V25 (`V25__create_engine_policy_versions.sql`)
- **V25 Diff**: NONE
- **V26 Status**: ABSENT

## Fraud Decisions Created

- **EngineDecision rows created**: 0
- **Fraud Decision evaluations performed**: 0

## Financial Side Effects

- **Ledger Entries**: 0
- **Bank Transfers / Payouts**: 0
- **Production Payout State**: DISABLED

## Files Modified

1. `backend/src/main/java/com/finx/engine/policy/fraud/FraudPolicyDefinition.java`
2. `backend/src/main/java/com/finx/engine/policy/fraud/FraudPolicyRegistrar.java`
3. `backend/src/test/java/com/finx/engine/policy/fraud/FraudPolicyDefinitionTest.java`
4. `backend/src/test/java/com/finx/engine/policy/fraud/FraudPolicyRegistrationTest.java`
5. `backend/src/test/java/com/finx/engine/policy/fraud/FraudPolicyBootstrapSpringTest.java`
6. `backend/src/test/java/com/finx/engine/policy/fraud/PostgresFraudPolicyTest.java`
7. `FRAUD_POLICY_V101_CORRECTION_REPORT.md`

## Files Created

- None (report updated in place)

## Tests Run

- `PostgresFraudPolicyTest` (1 test — PASS, Skipped: 0)
- `FlywayCleanMigrationTest` (1 test — PASS, Skipped: 0)
- `PostgresEngineFrameworkTest` (5 tests — PASS, Skipped: 0)
- `FraudPolicyDefinitionTest` (8 tests — PASS)
- `FraudPolicyRegistrationTest` (5 tests — PASS)
- `FraudPolicyRegistrationArchitectureTest` (2 tests — PASS)
- `FraudPolicyBootstrapSpringTest` (1 test — PASS)
- `EnginePolicyVersionServiceTest` (11 tests — PASS)
- `CommonEngineFrameworkArchitectureTest` (13 tests — PASS)
- `RiskDecisionAdapterTest` (19 tests — PASS)
- `DocumentDecisionAdapterTest` (19 tests — PASS)
- `MilestoneRulesDecisionAdapterTest` (20 tests — PASS)
- `ApprovalDecisionAdapterTest` (20 tests — PASS)
- `TrustDecisionAdapterTest` (20 tests — PASS)
- `DisputeDecisionAdapterTest` (20 tests — PASS)
- `ReadOnlyDecisionAdapterArchitectureTest` (14 tests — PASS)
- `RestrictedFrameworkArchitectureTest` (8 tests — PASS)
- `RestrictedFrameworkBoundaryContainmentTest` (9 tests — PASS)
- `FinancialIdempotencyContainmentTest` (20 tests — PASS)
- `SettlementExecutionContainmentTest` (18 tests — PASS)
- `LegacyBoundaryContainmentTest` (19 tests — PASS)

## Full Suite

- **Total Tests Run**: 825
- **Failures**: 0
- **Errors**: 0
- **PostgresFraudPolicyTest Skipped**: 0
- **Build Status**: BUILD SUCCESS

## Ready For F2

YES
