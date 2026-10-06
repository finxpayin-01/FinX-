# Reconciliation R2.5 Provider Account Scope Lineage Report

## Reason For Phase

Phase R3 (Deterministic Reconciliation Matcher) is required to implement rule **R05 — ACCOUNT SCOPE MATCHING**. Under R05, an internal financial instruction/effect can only match a bank statement line if both belong to the exact same provider account scope (`providerAccountId`).

Prior to Phase R2.5, durable bank statement imports (`bank_statement_imports.provider_account_id`) and statement lines (`bank_statement_lines.provider_account_id`) recorded the authoritative bank account scope (`providerAccountId`), but internal financial models (`PaymentInstruction`, `PaymentTransaction`, `SettlementInstruction`) lacked durable `provider_account_id` lineage.

Attempting to infer or guess account scope from `virtualAccountNumber` or provider configuration would violate financial truth. Therefore, Phase R2.5 binds internal financial instructions to the authoritative provider account scope before the R3 matcher is implemented.

## Preconditions

- **Reconciliation R1**: Commit `edf31d1` (PASS) — Durable bank statement schemas, constraints, and audit trails.
- **Reconciliation R2**: Commit `5a34ee9` (PASS) — Statement query/page/entry DTOs, pagination, cursor safety, ingestion, and fingerprinting.
- **B01–B13 Baseline**: Verified PASS.
- **Engine Decision Framework (7 / 8 Adapters)**: RISK, DOCUMENT, MILESTONE_RULES, APPROVAL, TRUST, DISPUTE, FRAUD (PASS).

## Semantic Distinction

- **`providerAccountId`**: The stable platform/escrow nodal account identifier or token at the partner bank (e.g. `MOCK_ESCROW_ACCOUNT`, `ESCROW_NODAL_POOL_001`). This is the authoritative account scope across bank statement evidence and internal ledger bindings.
- **`virtualAccountNumber`**: Dynamic payer-specific routing number allocated for inbound payment collection (e.g. `FINX1234567890`).

> [!IMPORTANT]
> `virtualAccountNumber` is NEVER treated as a fallback or synonym for `providerAccountId`. They represent fundamentally distinct banking concepts.

## Migration

**`V27__bind_internal_effect_provider_account_scope.sql`**:
- Added nullable `provider_account_id VARCHAR(128)` to `payment_instructions`.
- Added check constraint `chk_pi_provider_account_id`: `provider_account_id IS NULL OR length(trim(provider_account_id)) > 0`.
- Added composite index `idx_payment_instructions_provider_account_created` on `(provider, provider_account_id, created_at)`.
- Added nullable `provider_account_id VARCHAR(128)` to `settlement_instructions`.
- Added check constraint `chk_si_provider_account_id`: `provider_account_id IS NULL OR length(trim(provider_account_id)) > 0`.
- Added composite index `idx_settlement_instructions_provider_account_created` on `(provider, provider_account_id, created_at)`.
- Migrations V1 through V26 remain strictly unmodified.
- Migration V28 is absent.

## Payment Instruction Binding

In `PaymentOrchestrationService.createPaymentInstruction(...)`:
- Receives `PaymentInstructionResult` from `BankingProvider.createPaymentInstruction(...)`.
- Enforces fail-closed validation: `bankResult.providerAccountId()` must be non-null, non-blank, and `<= 128` characters.
- If missing or blank, throws `IllegalStateException` without writing to `payment_instructions`, ledger, escrow, or `payment_transactions`.
- Persists `instruction.setProviderAccountId(bankResult.providerAccountId())`.

## Payment Transaction Lineage

`PaymentTransaction` does NOT duplicate `provider_account_id`. Lineage is strictly resolved via the mandatory foreign key:
`PaymentTransaction -> payment_instruction_id -> PaymentInstruction -> provider_account_id`

Real PostgreSQL join tests prove that `PaymentTransaction -> PaymentInstruction -> providerAccountId` resolves with 100% precision without denormalizing account columns onto `payment_transactions`.

## Settlement Instruction Binding

- `SettlementInstruction` entity maps `@Column(name = "provider_account_id", length = 128)`.
- Getter and setter methods allow setting and reading the account scope.
- B04 containment remains active: production payout execution is disabled and fails closed with 0 calls to `BankingProvider.initiateSettlement`.

## Banking Provider Result Contract

- Extended `BankingDtos.PaymentInstructionResult` record with `String providerAccountId`.
- Extended `BankingDtos.SettlementResult` record with `String providerAccountId`.
- Backward-compatible constructors preserved for non-breaking API evolution.

## Mock Provider Account Scope

`MockBankingProvider` uses `public static final String MOCK_ESCROW_ACCOUNT = "MOCK_ESCROW_ACCOUNT"` consistently across:
- `fetchStatementPage(query)`
- `PaymentInstructionResult.providerAccountId()`
- `SettlementResult.providerAccountId()`

Tests verify that `providerAccountId != virtualAccountNumber`.

## Historical Rows

- Historical rows with `provider_account_id IS NULL` remain `NULL`.
- No automatic backfilling, heuristic mapping, or guessing from UTR/virtualAccountNumber was performed.

## R3 Fail-Closed Contract

Documented specification for upcoming Phase R3:
- **Inbound matching**: `PaymentTransaction.paymentInstruction.providerAccountId == BankStatementLine.providerAccountId`.
- **Outbound matching**: `SettlementInstruction.providerAccountId == BankStatementLine.providerAccountId`.
- If internal `providerAccountId` is `NULL`, the matcher will classify as `UNBOUND / REQUIRES_REVIEW`, never `MATCHED`.

## Database Verification

Real PostgreSQL verification against `finx_postgres` container on `127.0.0.1:5432`:
- Clean sequential migration V1 -> V27 validated.
- Check constraints verify blank `provider_account_id` is rejected.
- Composite indexes verified on `pg_indexes`.
- `PaymentTransaction -> PaymentInstruction -> providerAccountId` join proven.

## B04 Containment

- `SettlementAuthorizationService.authorizeAndInitiateSettlement` fails closed before bank dispatch.
- Production `BankingProvider.initiateSettlement` calls: **0**.
- Production payout execution: **DISABLED**.

## Reconciliation Runtime

- Legacy `ReconciliationService`: UNCHANGED / NONAUTHORITATIVE.
- `DeterministicReconciliationMatcher`: NOT IMPLEMENTED.
- `ReconciliationDecisionAdapter`: NOT IMPLEMENTED.
- `ReleaseDecisionEngine`: NOT IMPLEMENTED.

## Financial Side Effects

- Zero unauthorized mutations to ledger, escrow, or payment tables.
- Total financial side effects: **0**.

## Files Created

1. `backend/src/main/resources/db/migration/V27__bind_internal_effect_provider_account_scope.sql`
2. `backend/src/test/java/com/finx/reconciliation/PostgresProviderAccountScopeLineageTest.java`
3. `backend/src/test/java/com/finx/paymentorchestration/PaymentProviderAccountBindingTest.java`
4. `backend/src/test/java/com/finx/reconciliation/ProviderAccountScopeLineageArchitectureTest.java`
5. `RECONCILIATION_R2_5_ACCOUNT_SCOPE_LINEAGE_REPORT.md`

## Files Modified

1. `backend/src/main/java/com/finx/banking/dto/BankingDtos.java`
2. `backend/src/main/java/com/finx/banking/provider/mock/MockBankingProvider.java`
3. `backend/src/main/java/com/finx/paymentorchestration/entity/PaymentInstruction.java`
4. `backend/src/main/java/com/finx/paymentorchestration/entity/SettlementInstruction.java`
5. `backend/src/main/java/com/finx/paymentorchestration/service/PaymentOrchestrationService.java`
6. `backend/src/test/java/com/finx/FlywayCleanMigrationTest.java`
7. `backend/src/test/java/com/finx/reconciliation/PostgresBankStatementIngestionTest.java`
8. `backend/src/test/java/com/finx/reconciliation/PostgresDurableBankStatementSchemaTest.java`
9. `backend/src/test/java/com/finx/security/FinancialIdempotencyContainmentTest.java`
10. `backend/src/test/java/com/finx/security/TenantIsolationContainmentTest.java`
11. `backend/src/test/java/com/finx/security/PostgresFinancialConsistencyTest.java`

## Tests Added

- `PostgresProviderAccountScopeLineageTest`: 12 test cases verifying V27 DDL, constraints, legacy null compatibility, shared account scopes, composite indexes, and relational join lineage to `PaymentTransaction`.
- `PaymentProviderAccountBindingTest`: 5 test cases verifying MockBankingProvider contract, fail-closed validation, 0 side effects on invalid scope, SettlementInstruction support, and VA distinction.
- `ProviderAccountScopeLineageArchitectureTest`: 4 test cases verifying field mapping, lack of duplicate columns, and absence of matcher/adapter classes.

## Tests Run

- Targeted R2.5 Suite: 22 tests (100% PASS)
- Financial & Containment Suites: 104 tests (100% PASS)
- Engine Decision Suites (7 / 8 adapters): (100% PASS)
- Full Backend Suite: 989 tests (100% PASS, 0 failures, 0 errors, 1 skipped)

## Full Suite

```text
[INFO] Tests run: 989, Failures: 0, Errors: 0, Skipped: 1
[INFO] BUILD SUCCESS
```

## R2.5 Status

**PASS**

## Ready For R3

**YES**
