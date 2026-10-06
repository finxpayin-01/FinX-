# Reconciliation R1 Durable Schema Report

## Scope

Phase R1 established durable bank statement reconciliation evidence infrastructure only:
- Flyway database migration `V26__durable_bank_statement_reconciliation.sql`
- Durable evidence tables: `bank_statement_imports` and `bank_statement_lines`
- Additive extensions to `reconciliation_runs` and `reconciliation_items`
- Java persistence entities and strict enum types
- Spring Data JPA repositories with scoped finder queries
- Real PostgreSQL schema and constraint validation suite (`PostgresDurableBankStatementSchemaTest`)
- JPA mapping, immutability, and architecture boundary containment tests

**Explicitly NOT Implemented in R1**:
- `BankStatementIngestionService` (Deferred to R2)
- `BankingProvider.fetchTransactions` changes (Deferred to R2)
- Pagination / cursor provider logic (Deferred to R2)
- `DeterministicReconciliationMatcher` (Deferred to R3)
- `ReconciliationDecisionAdapter` (Deferred to R5)
- `ReconciliationExceptionService` (Deferred to R4)
- `ReleaseDecisionEngine` (Deferred to Release phase)
- Banking execution, settlement execution, ledger mutation, or production payout

## Preconditions

- Fraud Policy F1.1 & Fraud Adapter F2: Commit `0c91877` PASS
- Engine Decision Adapter Coverage prior to R1: 7 / 8 (`RISK`, `DOCUMENT`, `MILESTONE_RULES`, `APPROVAL`, `TRUST`, `DISPUTE`, `FRAUD`)
- Existing highest Flyway migration before R1: `V25`
- Working tree: Clean

## Migration

**Filename**: `backend/src/main/resources/db/migration/V26__durable_bank_statement_reconciliation.sql`
- Additive Flyway migration establishing immutable statement storage and relational audit references.
- No modifications made to historical migrations `V1`–`V25`.

## bank_statement_imports

Table capturing immutable batch metadata and execution window bounds:

| Column | Type | Nullable | Description & Constraints |
|---|---|---|---|
| `id` | `UUID` | NO | Primary Key (Application-generated UUID) |
| `provider` | `VARCHAR(64)` | NO | External banking provider identifier (e.g., `YES_BANK`) |
| `provider_account_id` | `VARCHAR(128)` | NO | Stable bank/provider account scope identifier/token (not raw account) |
| `window_start` | `TIMESTAMPTZ` | NO | Statement feed window start timestamp |
| `window_end` | `TIMESTAMPTZ` | NO | Statement feed window end timestamp (`chk_bsi_window`: `window_end >= window_start`) |
| `source_hash` | `VARCHAR(64)` | NO | SHA-256 hash of canonical raw statement feed (`chk_bsi_source_hash`: `^[0-9a-f]{64}$`) |
| `opening_balance` | `NUMERIC(19,4)` | YES | Account opening balance for statement window |
| `closing_balance` | `NUMERIC(19,4)` | YES | Account closing balance for statement window |
| `currency` | `VARCHAR(3)` | NO | ISO 4217 uppercase code (`chk_bsi_currency`: `^[A-Z]{3}$`) |
| `total_lines` | `INTEGER` | NO | Total lines count (`chk_bsi_total_lines`: `total_lines >= 0`) |
| `status` | `VARCHAR(32)` | NO | Import lifecycle status (`chk_bsi_status`: `RECEIVED`, `PROCESSING`, `COMPLETED`, `FAILED`) |
| `imported_by` | `UUID` | YES | Platform operator reference (`REFERENCES users(id)`) |
| `created_at` | `TIMESTAMPTZ` | NO | Record creation timestamp |
| `completed_at` | `TIMESTAMPTZ` | YES | Processing completion timestamp |
| `provider_import_reference` | `VARCHAR(128)` | YES | Provider batch/file identifier reference |

## Import Idempotency

- **Scoped Idempotency Constraint**: `CREATE UNIQUE INDEX uk_bsi_provider_account_hash ON bank_statement_imports (provider, provider_account_id, source_hash);`
- Guarantees that the exact same statement feed cannot be re-imported under the same bank account scope, even if requested across varied query time windows.
- Ingestion queries are accelerated by search index `idx_bsi_provider_window` on `(provider, provider_account_id, window_start, window_end)` and `idx_bsi_status` on `(status)`.

## bank_statement_lines

Table storing individual immutable statement transactions:

| Column | Type | Nullable | Description & Constraints |
|---|---|---|---|
| `id` | `UUID` | NO | Primary Key (Application-generated UUID) |
| `import_id` | `UUID` | NO | Foreign Key to import batch (`REFERENCES bank_statement_imports(id) ON DELETE RESTRICT`) |
| `provider` | `VARCHAR(64)` | NO | Banking provider identifier |
| `provider_account_id` | `VARCHAR(128)` | NO | Stable bank/provider account scope token |
| `provider_line_id` | `VARCHAR(128)` | YES | Provider native line transaction identifier |
| `transaction_reference` | `VARCHAR(128)` | YES | Provider external transaction reference |
| `utr_number` | `VARCHAR(128)` | YES | Unique Transaction Reference (NEFT/RTGS/IMPS) |
| `amount` | `NUMERIC(19,4)` | NO | Financial movement amount (`chk_bsl_amount`: `amount > 0`) |
| `currency` | `VARCHAR(3)` | NO | ISO 4217 uppercase code (`chk_bsl_currency`: `^[A-Z]{3}$`) |
| `direction` | `VARCHAR(16)` | NO | Financial direction (`chk_bsl_direction`: `CREDIT`, `DEBIT`) |
| `provider_status` | `VARCHAR(64)` | YES | Raw status string supplied by provider |
| `normalized_status` | `VARCHAR(32)` | NO | Normalized finality status (`chk_bsl_normalized_status`: `FINAL_SUCCESS`, `PENDING`, `FAILED`, `REVERSED`, `UNKNOWN`) |
| `transaction_time` | `TIMESTAMPTZ` | NO | Bank transaction occurrence timestamp |
| `value_date` | `DATE` | YES | Financial value posting date |
| `line_fingerprint` | `VARCHAR(64)` | NO | SHA-256 canonical statement line fingerprint (`chk_bsl_fingerprint`: `^[0-9a-f]{64}$`) |
| `created_at` | `TIMESTAMPTZ` | NO | Record ingestion timestamp |

## Financial Direction

Enum `FinancialDirection` with exact database check constraint:
- **`CREDIT`**: Money entering FinX-controlled account scope (inflow).
- **`DEBIT`**: Money leaving FinX-controlled account scope (outflow).

## Normalized Bank Status

Enum `BankStatementLineStatus`:
- `FINAL_SUCCESS`: Confirmed irrevocable bank settlement.
- `PENDING`: In-flight or processing external transaction.
- `FAILED`: Terminal bank failure.
- `REVERSED`: Transaction reversed or refunded by bank.
- `UNKNOWN`: Default / non-authoritative status when provider finality evidence is absent.

**Fail-Closed Rule**: `UNKNOWN` status does not provide financial finality. New lines default to `UNKNOWN` in Java entities until verified.

## Statement Line Identity

Durable identity across overlapping imports and statements is enforced via:
1. `uk_bsl_provider_line`: Partial unique index on `(provider, provider_account_id, provider_line_id) WHERE provider_line_id IS NOT NULL`.
2. `uk_bsl_provider_fingerprint`: Unique index on `(provider, provider_account_id, line_fingerprint)` as deterministic canonical fallback.
3. Neither `utr_number` nor `transaction_reference` is globally unique, preserving multitenant and cross-bank isolation.

## Sensitive Data Boundary

- `provider_account_id` is strictly documented and modeled as a provider account scope identifier/token, NOT a raw customer/bank account number.
- No `raw_payload`, `raw_response`, or full JSON statement payloads are persisted.
- No plaintext customer counterparty account numbers or PII fields exist in `bank_statement_imports` or `bank_statement_lines`.
- Provenance is maintained exclusively via `source_hash`, `line_fingerprint`, UTR, and provider references.

## ReconciliationRun Extensions

Additively extended with nullable columns for legacy compatibility:
- `provider_account_id VARCHAR(128)`
- `window_start TIMESTAMP WITH TIME ZONE`
- `window_end TIMESTAMP WITH TIME ZONE` (`chk_recon_runs_window`: `window_end >= window_start`)
- `bank_statement_import_id UUID REFERENCES bank_statement_imports(id) ON DELETE RESTRICT`
- `opening_balance NUMERIC(19,4)`
- `closing_balance NUMERIC(19,4)`
- `currency VARCHAR(3)` (`chk_recon_runs_currency`: `^[A-Z]{3}$`)

## ReconciliationItem Extensions

Additively extended with nullable columns for 1-to-1 match lineage:
- `bank_statement_line_id UUID REFERENCES bank_statement_lines(id) ON DELETE RESTRICT`
- `internal_effect_type VARCHAR(32)` (`chk_recon_items_internal_effect_type`: `PAYMENT_TRANSACTION`, `SETTLEMENT_INSTRUCTION`)
- `internal_effect_id UUID`
- `currency VARCHAR(3)` (`chk_recon_items_currency`: `^[A-Z]{3}$`)
- `direction VARCHAR(16)` (`chk_recon_items_direction`: `CREDIT`, `DEBIT`)
- `bank_status VARCHAR(32)`
- `resolution_notes TEXT`

## One-To-One Support

Database partial unique indexes enforce strict 1-to-1 match lineage within a single reconciliation run:
- `uk_recon_items_run_statement_line`: `(reconciliation_run_id, bank_statement_line_id) WHERE bank_statement_line_id IS NOT NULL`
- `uk_recon_items_run_internal_effect`: `(reconciliation_run_id, internal_effect_type, internal_effect_id) WHERE internal_effect_type IS NOT NULL AND internal_effect_id IS NOT NULL`
- Allows historical reruns across different runs while preventing double-consumption within any single run.

## Existing Resolution Fields

Preserved historical fields from migration `V14`:
- `resolution_status` (`UNRESOLVED`, `RESOLVED`, `IGNORED`)
- `resolved_by`
- `resolved_at`
- Added `resolution_notes` (nullable `TEXT`) for future audit justifications in R4.

## Entity Classes

- `com.finx.reconciliation.entity.BankStatementImport`
- `com.finx.reconciliation.entity.BankStatementLine` (`@org.hibernate.annotations.Immutable`)
- `com.finx.reconciliation.entity.ReconciliationRun` (updated with V26 fields)
- `com.finx.reconciliation.entity.ReconciliationItem` (updated with V26 fields)
- Enums: `BankStatementImportStatus`, `FinancialDirection`, `BankStatementLineStatus`, `ReconciliationEffectType`

## Repository Classes

- `com.finx.reconciliation.repository.BankStatementImportRepository`
- `com.finx.reconciliation.repository.BankStatementLineRepository`
- `com.finx.reconciliation.repository.ReconciliationRunRepository` (extended with `findByBankStatementImportId`)
- `com.finx.reconciliation.repository.ReconciliationItemRepository` (extended with scoped line and effect lookups)

## Immutability

- `BankStatementLine` entity is marked with `@org.hibernate.annotations.Immutable`.
- Foreign key from `bank_statement_lines` to `bank_statement_imports` uses `ON DELETE RESTRICT`, preventing accidental cascading deletion of financial evidence.
- No deletion or mutation endpoints/services exist in R1.

## PostgreSQL Verification

- Executed against real PostgreSQL 16 container (`finx_postgres` on `localhost:5432`).
- Disposable database `finx_b06_test` migrated cleanly from `V1` to `V26`.
- `PostgresDurableBankStatementSchemaTest` executed all 26 test methods covering:
  - Table existence
  - Check constraints (dates, hex hashes, currencies, total lines, positive amounts, directions, statuses)
  - Partial unique indexes on provider line IDs and internal effects
  - Scoped fingerprint uniqueness
  - Restricted foreign key deletion
  - Non-global UTR and transaction reference uniqueness
  - Legacy reconciliation backward compatibility

## Flyway

- Full Flyway clean sequence `V1` -> `V26`: **PASS** (`FlywayCleanMigrationTest`).

## Legacy Reconciliation Runtime

- Existing `ReconciliationService` runtime matching algorithm remains **UNCHANGED** and **NONAUTHORITATIVE**.
- 0 background bank transactions polled.
- 0 automatic reconciliation schedulers created.

## Remaining Defects

The following defects documented in `RECONCILIATION_REMEDIATION_PLAN.md` remain unresolved until later phases:
- **R01**: Settlement amount not compared (Deferred to R3 Matcher)
- **R02**: Missing outbound settlements not detected (Deferred to R3 Matcher)
- **R03**: Currency comparison absent in matcher (Deferred to R3 Matcher)
- **R04**: Direction comparison absent in matcher (Deferred to R3 Matcher)
- **R05**: Account validation absent in matcher (Deferred to R3 Matcher)
- **R06**: Bank finality verification absent in matcher (Deferred to R3 Matcher)
- **R07**: Runtime 1-to-1 consumption pool (Deferred to R3 Matcher)
- **R08**: Bounded candidates query in matcher (Deferred to R3 Matcher)
- **R11**: Provider pagination/cursor tracking (Deferred to R2 Ingestion / Provider Contract)

## Banking Calls

- **0** banking provider calls from R1 infrastructure.

## Financial Side Effects

- **0** ledger, escrow, or settlement mutations.

## Reconciliation EngineDecision

- **NOT IMPLEMENTED** (Deferred to Phase R5).

## RELEASE

- **NOT IMPLEMENTED** / Payout disabled.

## Files Created

- `backend/src/main/resources/db/migration/V26__durable_bank_statement_reconciliation.sql`
- `backend/src/main/java/com/finx/reconciliation/enums/BankStatementImportStatus.java`
- `backend/src/main/java/com/finx/reconciliation/enums/FinancialDirection.java`
- `backend/src/main/java/com/finx/reconciliation/enums/BankStatementLineStatus.java`
- `backend/src/main/java/com/finx/reconciliation/enums/ReconciliationEffectType.java`
- `backend/src/main/java/com/finx/reconciliation/entity/BankStatementImport.java`
- `backend/src/main/java/com/finx/reconciliation/entity/BankStatementLine.java`
- `backend/src/main/java/com/finx/reconciliation/repository/BankStatementImportRepository.java`
- `backend/src/main/java/com/finx/reconciliation/repository/BankStatementLineRepository.java`
- `backend/src/test/java/com/finx/reconciliation/PostgresDurableBankStatementSchemaTest.java`
- `backend/src/test/java/com/finx/reconciliation/DurableBankStatementEntityMappingTest.java`
- `backend/src/test/java/com/finx/reconciliation/DurableReconciliationSchemaArchitectureTest.java`
- `RECONCILIATION_R1_DURABLE_SCHEMA_REPORT.md`

## Files Modified

- `backend/src/main/java/com/finx/reconciliation/entity/ReconciliationRun.java`
- `backend/src/main/java/com/finx/reconciliation/entity/ReconciliationItem.java`
- `backend/src/main/java/com/finx/reconciliation/repository/ReconciliationRunRepository.java`
- `backend/src/main/java/com/finx/reconciliation/repository/ReconciliationItemRepository.java`
- `backend/src/test/java/com/finx/FlywayCleanMigrationTest.java`

## Tests Added

- `PostgresDurableBankStatementSchemaTest` (26 tests)
- `DurableBankStatementEntityMappingTest` (4 tests)
- `DurableReconciliationSchemaArchitectureTest` (3 tests)
- `FlywayCleanMigrationTest` (V26 verification added)

## Tests Run

- `PostgresDurableBankStatementSchemaTest`: 26 / 26 PASS
- `DurableBankStatementEntityMappingTest`: 4 / 4 PASS
- `DurableReconciliationSchemaArchitectureTest`: 3 / 3 PASS
- `FlywayCleanMigrationTest`: 1 / 1 PASS
- Full Maven Test Suite: 917 tests run, 0 failures, 0 errors, 1 skipped (H2-conditional)

## Full Suite

- **BUILD SUCCESS** (`.\mvnw.cmd test`)

## R1 Status

- **PASS**

## Ready For R2

- **YES**
