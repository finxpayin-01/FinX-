# Reconciliation R3 Deterministic Matcher Report

## Scope

R3 deterministic reconciliation matcher only. Reconciles already-persisted `BankStatementImport` and `BankStatementLine` evidence against already-persisted internal financial effects (`PaymentTransaction`, `PaymentInstruction`, and `SettlementInstruction`) using deterministic matching rules without heuristic guessing or external network dependencies.

## Preconditions

- **Reconciliation R1**: `edf31d1` (PASS) — Durable schema, immutable line evidence, composite uniqueness constraints.
- **Reconciliation R2**: `5a34ee9` (PASS) — Deterministic bank statement pagination, ingestion, and SHA-256 evidence fingerprinting.
- **Reconciliation R2.5**: `6a20a1c` (PASS) — Provider account scope binding (`providerAccountId`) and transactional lineage.
- **Engine Decision Framework (7 / 8 Adapters)**: RISK, DOCUMENT, MILESTONE_RULES, APPROVAL, TRUST, DISPUTE, FRAUD (PASS).
- **Highest Prior Migration**: V27.

## Migration

**`V28__version_deterministic_reconciliation_runs.sql`**:
- Added `matcher_version VARCHAR(64)` and `statement_source_hash VARCHAR(64)` to `reconciliation_runs` table.
- Added check constraints:
  - `chk_reconciliation_runs_matcher_version`: `matcher_version IS NULL OR length(trim(matcher_version)) > 0`
  - `chk_reconciliation_runs_source_hash`: `statement_source_hash IS NULL OR statement_source_hash ~ '^[0-9a-f]{64}$'`
- Added composite index `idx_reconciliation_runs_import_matcher_created` on `(bank_statement_import_id, matcher_version, created_at)`.
- Added `reason_code VARCHAR(100)` to `reconciliation_items` table.
- Added check constraint `chk_reconciliation_items_reason_code`: `reason_code IS NULL OR length(trim(reason_code)) > 0`.
- Added composite index `idx_reconciliation_items_run_reason` on `(reconciliation_run_id, reason_code)`.
- V1 through V27 remain strictly unmodified.
- V29 is absent.

## Matcher Version

Exact constant:
```java
public static final String MATCHER_VERSION = "FINX_RECONCILIATION_MATCHER_V1";
```
Every R3 deterministic reconciliation run stores `matcher_version = 'FINX_RECONCILIATION_MATCHER_V1'`.

## Legacy Runs

- Historical reconciliation runs created prior to R3 contain `matcher_version = NULL` and `statement_source_hash = NULL`.
- Legacy runs are strictly **NONAUTHORITATIVE** and will be ignored by future decision engines as release evidence.
- No backfilling, mutation, or guessing of historical runs was performed.

## Import Integrity Verification

Before creating a reconciliation run or evaluating candidate effects, `DeterministicReconciliationMatcher` verifies:
1. `BankStatementImport` exists and has `status == COMPLETED`.
2. `provider` and `providerAccountId` are non-blank.
3. `windowStart` and `windowEnd` are non-null and `windowEnd >= windowStart`.
4. `sourceHash` is valid 64-hex lowercase SHA-256.
5. `currency` is valid 3-letter ISO code.
6. Database statement lines count strictly equals `statementImport.totalLines`.
7. Each statement line matches `importId`, `provider`, `providerAccountId`, and `currency`.
8. Each statement line's `transactionTime` falls within `[windowStart, windowEnd]`.

If any invariant fails, execution fails closed with `0` reconciliation runs or items created.

## Line Fingerprint Verification

Every persisted `BankStatementLine`'s fingerprint is recomputed using the canonical R2 algorithm via `BankStatementEvidenceFingerprint.computeLineFingerprint(line)`.
The recomputed fingerprint must match `line.lineFingerprint` with 100% exactness, detecting any post-ingestion tampering or bit rot.

## Source Hash Verification

The entire import's source hash is recomputed using the exact R2 canonical algorithm over provider, account, currency, balances, reference, and sorted line fingerprints:
```java
BankStatementEvidenceFingerprint.computeSourceHash(...)
```
The recomputed hash must match `statementImport.sourceHash` before reconciliation proceeds.

## Bounded Internal Candidate Queries

**Explicit Rule**: `findAll()` is strictly forbidden in R3.
- Inbound candidates are queried via `PaymentTransactionRepository.findInboundReconciliationCandidates(...)` bounded by:
  - `status == 'SUCCESS'`
  - `createdAt >= :windowStart AND createdAt <= :windowEnd`
  - `pi.provider == :provider`
  - `pi.providerAccountId == :providerAccountId OR pi.providerAccountId IS NULL`
- Outbound candidates are queried via `SettlementInstructionRepository.findOutboundReconciliationCandidates(...)` bounded by:
  - `status == 'SETTLED'`
  - `COALESCE(settledAt, createdAt) >= :windowStart AND COALESCE(settledAt, createdAt) <= :windowEnd`
  - `provider == :provider`
  - `providerAccountId == :providerAccountId OR providerAccountId IS NULL`

## Inbound Matching

For each inbound `PaymentTransaction`:
1. Resolves parent `PaymentInstruction` via `paymentInstructionId`.
2. Validates internal lineage: provider equality, amount equality, currency equality, and `instruction.status == SUCCESS`.
3. If `providerAccountId == null`, classifies as `REQUIRES_REVIEW` with reason `INTERNAL_ACCOUNT_SCOPE_UNBOUND` (never matched).
4. If lineage is inconsistent, classifies as `REQUIRES_REVIEW` with reason `INTERNAL_LINEAGE_MISMATCH`.
5. Resolves reference against bank lines using deterministic priority.

## Outbound Matching

For each outbound `SettlementInstruction`:
1. Requires `status == SETTLED`.
2. Validates `settledAt` is non-null (null `settledAt` on `SETTLED` status fails as `INTERNAL_LINEAGE_MISMATCH`).
3. If `providerAccountId == null`, classifies as `REQUIRES_REVIEW` with reason `INTERNAL_ACCOUNT_SCOPE_UNBOUND` (never matched).
4. Resolves reference against bank lines using deterministic priority.

## Reference Priority

References are evaluated strictly in hierarchical order:

**Inbound Reference Priority**:
1. **UTR**: `PaymentTransaction.utrNumber` vs `BankStatementLine.utrNumber`
2. **Provider Reference**: `PaymentTransaction.providerReference` / `PaymentInstruction.providerInstructionId` vs `BankStatementLine.transactionReference`
3. **FinX Reference**: `PaymentTransaction.transactionReference` / `PaymentInstruction.instructionReference` vs `BankStatementLine.transactionReference`

**Outbound Reference Priority**:
1. **UTR**: `SettlementInstruction.utrNumber` vs `BankStatementLine.utrNumber`
2. **Provider Settlement ID**: `SettlementInstruction.providerSettlementId` vs `BankStatementLine.transactionReference`
3. **FinX Settlement Reference**: `SettlementInstruction.settlementReference` vs `BankStatementLine.transactionReference`

Rules at each priority level:
- Exactly 1 match: candidate associated.
- Greater than 1 match: `REFERENCE_AMBIGUOUS` (ambiguity does not fall through to weaker references).
- 0 matches: evaluate next priority level.

## Ambiguity Handling

- **Bank-side ambiguity**: If a reference matches multiple bank statement lines, it produces `DUPLICATE` with reason `REFERENCE_AMBIGUOUS`.
- **Internal-side conflict**: If multiple internal effects associate with the same bank statement line, all affected internal effects are marked `DUPLICATE` with reason `REFERENCE_AMBIGUOUS`, and the line remains unconsumed (no "first row wins").
- Processing order has zero impact on output determinism.

## Provider Matching

Internal provider must strictly match `BankStatementImport.provider` and `BankStatementLine.provider`. Any mismatch results in `STATUS_MISMATCH` with reason `INTERNAL_PROVIDER_MISMATCH`.

## Account Scope Matching

Internal `providerAccountId` must strictly match `BankStatementLine.providerAccountId`.
- `NULL` internal account scope produces `REQUIRES_REVIEW` with reason `INTERNAL_ACCOUNT_SCOPE_UNBOUND`.
- Explicit conflicting scope produces `STATUS_MISMATCH` with reason `ACCOUNT_SCOPE_MISMATCH`.

## Direction Matching

- Inbound (`PaymentTransaction`): expects bank line direction `CREDIT`.
- Outbound (`SettlementInstruction`): expects bank line direction `DEBIT`.
- Mismatch produces `STATUS_MISMATCH` with reason `DIRECTION_MISMATCH`.

## Currency Matching

Case-insensitive exact ISO 3-letter currency code match. No conversions or FX rates applied.
Mismatch produces `STATUS_MISMATCH` with reason `CURRENCY_MISMATCH`.

## Amount Matching

Exact numeric equality using `BigDecimal.compareTo() == 0`. No floating-point arithmetic or tolerance margins.
Mismatch produces `AMOUNT_MISMATCH` with reason `AMOUNT_MISMATCH`.

## Bank Finality

- `FINAL_SUCCESS`: required for `MATCHED` confirmation.
- `PENDING`: produces `REQUIRES_REVIEW` with reason `BANK_FINALITY_PENDING`.
- `UNKNOWN`: produces `REQUIRES_REVIEW` with reason `BANK_FINALITY_UNKNOWN`.
- `FAILED`: produces `STATUS_MISMATCH` with reason `BANK_FINALITY_FAILED`.
- `REVERSED`: produces `STATUS_MISMATCH` with reason `BANK_FINALITY_REVERSED`.

## One-To-One Consumption

- Each bank line can only be consumed once per reconciliation run.
- Maintained via in-memory set and enforced by database schema unique constraints.

## Missing External

Authoritative internal transactions (`PaymentTransaction` SUCCESS or `SettlementInstruction` SETTLED) with valid provider/account binding that find no matching bank line produce `MISSING_EXTERNAL` with reason `MISSING_EXTERNAL`.

## Missing Internal

Unconsumed bank lines after matching:
- `FINAL_SUCCESS` unconsumed lines produce `MISSING_INTERNAL` with reason `UNCLAIMED_FINAL_BANK_LINE`.
- Non-final unconsumed lines (`PENDING`, `UNKNOWN`, `FAILED`, `REVERSED`) produce `REQUIRES_REVIEW` with reason `UNCLAIMED_NONFINAL_BANK_LINE`.

## Statement Balance Control

1. If `openingBalance` or `closingBalance` is null: creates run-level `REQUIRES_REVIEW` with reason `STATEMENT_BALANCE_MISSING`.
2. If any statement line is not `FINAL_SUCCESS`: creates run-level `REQUIRES_REVIEW` with reason `STATEMENT_BALANCE_NONFINAL_EVIDENCE`.
3. If all lines are `FINAL_SUCCESS`: computes `expectedClosing = openingBalance + sum(CREDIT) - sum(DEBIT)`. If `expectedClosing.compareTo(closingBalance) != 0`, creates `REQUIRES_REVIEW` with reason `STATEMENT_BALANCE_MISMATCH`.

## Run Semantics

`COMPLETED != clean`:
- `ReconciliationRunStatus.COMPLETED` means only that the deterministic matcher executed to completion without fatal abort.
- A run is `clean = true` if and only if:
  1. `run.status == COMPLETED`
  2. `run.mismatchCount == 0`
  3. `run.matcherVersion == 'FINX_RECONCILIATION_MATCHER_V1'`

## Machine-Readable Reason Codes

`ReconciliationReasonCode` enum values:
- `MATCHED_CONFIRMED`
- `INTERNAL_ACCOUNT_SCOPE_UNBOUND`
- `INTERNAL_PROVIDER_MISMATCH`
- `ACCOUNT_SCOPE_MISMATCH`
- `INTERNAL_LINEAGE_MISMATCH`
- `REFERENCE_AMBIGUOUS`
- `MISSING_EXTERNAL`
- `AMOUNT_MISMATCH`
- `CURRENCY_MISMATCH`
- `DIRECTION_MISMATCH`
- `BANK_FINALITY_PENDING`
- `BANK_FINALITY_UNKNOWN`
- `BANK_FINALITY_FAILED`
- `BANK_FINALITY_REVERSED`
- `UNCLAIMED_FINAL_BANK_LINE`
- `UNCLAIMED_NONFINAL_BANK_LINE`
- `STATEMENT_BALANCE_MISSING`
- `STATEMENT_BALANCE_NONFINAL_EVIDENCE`
- `STATEMENT_BALANCE_MISMATCH`

## R01-R08 Status

- **R01 (Settlement Amount Comparison)**: FIXED IN R3
- **R02 (Missing Outbound Settlement Detection)**: FIXED IN R3
- **R03 (Currency Comparison)**: FIXED IN R3
- **R04 (Direction Comparison)**: FIXED IN R3
- **R05 (Provider/Account-Scope Comparison)**: FIXED IN R3
- **R06 (Bank Finality Comparison)**: FIXED IN R3
- **R07 (One-to-One Bank Line Consumption)**: FIXED IN R3
- **R08 (Bounded Internal Candidate Queries)**: FIXED IN R3

## Other Reconciliation Defects

- **R09 (Missing Statement Schema)**: FIXED R1/R2
- **R10 (Missing Run Provenance)**: FIXED R1
- **R11 (Ingestion Idempotency)**: FIXED R2
- **R12 (Unbound Account Lineage)**: FIXED R1/R2/R2.5
- **R13 (Discrepancy Text Parsing)**: FIXED IN R3 (Machine-readable `reason_code` column and `ReconciliationReasonCode` enum)
- **R14 (Missing Balance Control)**: FIXED IN R3 (Formal opening/closing balance verification & nonfinal guards)
- **R15 (Semantic Run Clearance)**: Deterministic path FIXED semantically: `COMPLETED != clean`. Legacy runs remain nonauthoritative.
- **R16 (Exception Resolution Lifecycle)**: NOT FIXED (Deferred to Phase R4)

## Financial Side Effects

- Only `ReconciliationRun` and `ReconciliationItem` records are written.
- Mutations to `PaymentInstruction`, `PaymentTransaction`, `SettlementInstruction`, `JournalEntry`, `EscrowLedger`, `EscrowAccount`, `Deal`, `Milestone`, `BankStatementImport`, `BankStatementLine`: **0**.

## Bank Calls

- `BankingProvider.fetchStatementPage` calls: **0**
- `BankingProvider.fetchTransactions` calls: **0**
- `BankingProvider.initiateSettlement` calls: **0**
- `BankingProvider.createPaymentInstruction` calls: **0**

## Reconciliation EngineDecision

**NOT IMPLEMENTED** (Deferred to R5).

## RELEASE

**NOT IMPLEMENTED** (ReleaseDecisionEngine remains separate).

## PostgreSQL

Real PostgreSQL verification against `finx_postgres` container:
- Flyway clean & migrate V1 -> V28 verified.
- Schema, constraints, indexes, legacy compatibility verified on PostgreSQL.

## Files Created

1. `backend/src/main/resources/db/migration/V28__version_deterministic_reconciliation_runs.sql`
2. `backend/src/main/java/com/finx/reconciliation/enums/ReconciliationReasonCode.java`
3. `backend/src/main/java/com/finx/reconciliation/dto/DeterministicReconciliationResult.java`
4. `backend/src/main/java/com/finx/reconciliation/service/DeterministicReconciliationMatcher.java`
5. `backend/src/test/java/com/finx/reconciliation/DeterministicReconciliationMatcherTest.java`
6. `backend/src/test/java/com/finx/reconciliation/DeterministicReconciliationMatcherArchitectureTest.java`
7. `backend/src/test/java/com/finx/reconciliation/PostgresDeterministicReconciliationMatcherTest.java`
8. `RECONCILIATION_R3_DETERMINISTIC_MATCHER_REPORT.md`

## Files Modified

1. `backend/src/main/java/com/finx/reconciliation/entity/ReconciliationRun.java`
2. `backend/src/main/java/com/finx/reconciliation/entity/ReconciliationItem.java`
3. `backend/src/main/java/com/finx/reconciliation/service/BankStatementEvidenceFingerprint.java`
4. `backend/src/main/java/com/finx/paymentorchestration/repository/PaymentTransactionRepository.java`
5. `backend/src/main/java/com/finx/paymentorchestration/repository/SettlementInstructionRepository.java`
6. `backend/src/test/java/com/finx/FlywayCleanMigrationTest.java`

## Tests Added

- `DeterministicReconciliationMatcherTest`: 29 test cases covering import integrity, line fingerprints, source hash, inbound/outbound matching, direction/currency/amount validation, bank finality, reference hierarchy & ambiguity, unclaimed lines, and balance control.
- `DeterministicReconciliationMatcherArchitectureTest`: 2 test cases asserting forbidden service dependencies and verifying bounded queries without `findAll()`.
- `PostgresDeterministicReconciliationMatcherTest`: 3 test cases verifying PostgreSQL metadata, Flyway V28 columns, check constraints, legacy compatibility, and end-to-end matching with 0 financial side effects.

## Tests Run

- R3 Matcher & Architecture Suite: 31 tests (100% PASS)
- Legacy Reconciliation Suite: 3 tests (100% PASS)
- R2 / R2.5 Regression Suites: 98 tests (100% PASS)
- Engine Decision Suites (7 adapters): 185 tests (100% PASS)
- Financial & Containment Suites: 215 tests (100% PASS)
- Framework Regression Suites: 53 tests (100% PASS)
- Full Backend Suite: 1023 tests (100% PASS, 0 failures, 0 errors)

## Full Suite

```text
[INFO] Tests run: 1023, Failures: 0, Errors: 0, Skipped: 85
[INFO] BUILD SUCCESS
[INFO] Total time:  01:36 min
```

## R3 Status

**PASS**

## Ready For R4

**YES**
