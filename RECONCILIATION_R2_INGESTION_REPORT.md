# Reconciliation R2 Durable Bank Statement Ingestion Report

## Scope

Phase R2 implementation only:
- Provider evidence contract (`BankStatementQuery`, `BankStatementPage`, `BankStatementEntry`, `BankStatementDirection`, `BankStatementFinality`).
- Durable bank statement ingestion service (`BankStatementIngestionService`) with un-transactional network page fetching, cursor safety, pagination, and validation.
- Deterministic canonical statement line fingerprinting and import source hashing (`BankStatementEvidenceFingerprint`).
- Transactional persistence service (`BankStatementPersistenceService`) storing durable `BankStatementImport` and `BankStatementLine` rows in existing V26 tables.
- Idempotent and concurrent replay protection.

## Preconditions

- Phase R1 Commit: `edf31d1` (PASS)
- Database Migration: Existing V26 (`V26__durable_bank_statement_reconciliation.sql`) applied and verified.
- V27: ABSENT (No database migration added).
- Common Engine Decision Adapter Coverage: 7 / 8 prerequisite component adapters active (RISK, DOCUMENT, MILESTONE_RULES, APPROVAL, TRUST, DISPUTE, FRAUD).
- Production Payout: DISABLED.

## Provider Evidence Contract

Safe, immutable banking DTOs and enums defined in `com.finx.banking.dto`:
- `BankStatementDirection`: `CREDIT`, `DEBIT`
- `BankStatementFinality`: `FINAL_SUCCESS`, `PENDING`, `FAILED`, `REVERSED`, `UNKNOWN`
- `BankStatementQuery`: Record containing `providerAccountId`, `windowStart`, `windowEnd`, `cursor`, `pageSize`.
- `BankStatementEntry`: Record containing `providerLineId`, `transactionReference`, `utrNumber`, `amount`, `currency`, `direction`, `providerStatus`, `finality`, `transactionTime`, `valueDate`.
- `BankStatementPage`: Record containing `providerAccountId`, `providerImportReference`, `currency`, `openingBalance`, `closingBalance`, `entries`, `nextCursor`.

Sensitive data boundary enforced: No `rawPayload`, `rawResponse`, `counterpartyAccount`, `counterpartyName`, arbitrary `description`, or customer account numbers are permitted in the contract or entities.

## Legacy Provider Contract

`BankingProvider.fetchTransactions(LocalDate, LocalDate)` remains completely preserved and unchanged for backward compatibility with the legacy `ReconciliationService`.
The legacy reconciliation path remains explicitly marked as:
**LEGACY / NONAUTHORITATIVE**.

## New Provider Method

Added to `BankingProvider`:
```java
BankStatementPage fetchStatementPage(BankStatementQuery query);
```
Implemented in `MockBankingProvider` with simulated durable evidence, deterministic ordering, and cursor-based pagination.

## Provider Account Scope

`providerAccountId` represents a stable provider/platform escrow account-scope identifier or token (such as `MOCK_ESCROW_ACCOUNT` or `ESCROW_VIRTUAL_001`), never a customer or beneficiary bank account number.

## Pagination & Cursor Safety

- `BankStatementIngestionService` iterates through provider pages using opaque cursors until `nextCursor == null`.
- Enforces loop and cycle detection (`visitedCursors` set tracking); detects repeated cursors and fails closed before persistence.
- Enforces maximum iteration boundary (`MAX_PAGE_ITERATIONS = 1000`).
- Validates cross-page consistency: `providerAccountId`, `currency`, and `providerImportReference` must remain consistent across all pages.

## Mock Provider Evidence

`MockBankingProvider`:
- Scoped to `MOCK_ESCROW_ACCOUNT`.
- Implements deterministic pagination: ordered by `transactionTime` (ascending), then `providerLineId` (ascending).
- Populates `providerLineId`, `transactionReference`, `utrNumber`, `amount`, `currency`, `direction`, `providerStatus`, `finality`, `transactionTime`, and `valueDate`.

## Finality & UNKNOWN Semantics

Provider finality is mapped directly to `BankStatementLineStatus`:
- `BankStatementFinality.FINAL_SUCCESS` -> `BankStatementLineStatus.FINAL_SUCCESS`
- `BankStatementFinality.PENDING` -> `BankStatementLineStatus.PENDING`
- `BankStatementFinality.FAILED` -> `BankStatementLineStatus.FAILED`
- `BankStatementFinality.REVERSED` -> `BankStatementLineStatus.REVERSED`
- `BankStatementFinality.UNKNOWN` -> `BankStatementLineStatus.UNKNOWN`

`UNKNOWN` finality is treated as **NONAUTHORITATIVE / FAIL-CLOSED**. It cannot be used to confirm or reconcile transactions without additional authoritative confirmation.

## Statement Line Fingerprint

`BankStatementEvidenceFingerprint.computeLineFingerprint(...)`:
- Computed using length-prefixed canonical encoding and SHA-256 (64 lowercase hex characters).
- Canonical fields: `provider`, `providerAccountId`, `providerLineId`, `transactionReference`, `utrNumber`, normalized `amount` (`stripTrailingZeros().toPlainString()`), uppercase `currency`, `direction`, `providerStatus`, `finality`, ISO-8601 `transactionTime`, ISO-8601 `valueDate`.
- Excludes transient data, timestamps of import, and sensitive counterparty data.

## Import Source Hash

`BankStatementEvidenceFingerprint.computeSourceHash(...)`:
- Computed using length-prefixed canonical encoding and SHA-256 (64 lowercase hex characters).
- Canonical fields for non-empty statement snapshots: `provider`, `providerAccountId`, uppercase `currency`, normalized `openingBalance`, normalized `closingBalance`, `providerImportReference`, and all `lineFingerprint`s sorted lexicographically.
- **Empty Window Rule**: For snapshots with 0 entries, `windowStart` and `windowEnd` are included in the source hash material so that different empty statement windows (e.g. 1 Oct vs 2 Oct) produce distinct source hashes.

## Duplicate Detection & Snapshot Validation

Before persistence, `BankStatementIngestionService` validates:
- Amount > 0 and 3-letter uppercase ISO currency code.
- Direction, finality, and transactionTime are non-null.
- At least one valid external reference exists (`providerLineId`, `transactionReference`, or `utrNumber`).
- String lengths conform to V26 column limits.
- Detects duplicate `providerLineId` or duplicate `lineFingerprint` in the fetched snapshot and fails closed (0 rows persisted).

## Transaction & Network Boundary Separation

- `BankStatementIngestionService`: Un-transactional. Fetches all statement pages from the external provider outside database transactions.
- `BankStatementPersistenceService`: `@Transactional`. Executes atomic persistence of `BankStatementImport` and `BankStatementLine` rows only after the full snapshot is validated.
- Architecture tests verify that `BankStatementPersistenceService` has 0 dependencies on `BankingProvider`.

## Idempotent Replay & Concurrent Ingestion

- If an import with the same `(provider, providerAccountId, sourceHash)` already exists in status `COMPLETED`, `BankStatementPersistenceService` immediately returns the existing import without inserting any new rows (0 import delta, 0 line delta).
- If an existing import is in a non-`COMPLETED` status, it fails closed (`IllegalStateException`).
- Under concurrent execution against PostgreSQL, unique constraint `uk_bsi_provider_account_hash` catches race conditions; `BankStatementIngestionService` catches `DataIntegrityViolationException` and re-queries for the existing completed import, guaranteeing exactly 1 import and N lines with no duplicate rows or deadlocks.

## Real PostgreSQL & Flyway Verification

- Tested against real PostgreSQL container `finx_postgres` (PostgreSQL 16.15 on `127.0.0.1:5432`).
- Flyway V1 -> V26 executed and validated.
- Real concurrent execution verified across multiple threads.
- PostgreSQL test was NOT skipped.

## Non-Implemented Boundaries (Safety Containment)

- Database Migration: NONE (Highest is V26; V27 is ABSENT).
- Deterministic Reconciliation Matcher: NOT IMPLEMENTED.
- Reconciliation Decision Adapter: NOT IMPLEMENTED.
- Release Decision Engine: NOT IMPLEMENTED.
- Reconciliation Exception Workflow: NOT IMPLEMENTED.
- ReconciliationRuns Created: 0
- ReconciliationItems Created: 0
- Financial Execution / Settlement Calls: 0
- Ledger Writes: 0
- Escrow Writes: 0

## Files Created

- `backend/src/main/java/com/finx/banking/dto/BankStatementDirection.java`
- `backend/src/main/java/com/finx/banking/dto/BankStatementFinality.java`
- `backend/src/main/java/com/finx/banking/dto/BankStatementQuery.java`
- `backend/src/main/java/com/finx/banking/dto/BankStatementEntry.java`
- `backend/src/main/java/com/finx/banking/dto/BankStatementPage.java`
- `backend/src/main/java/com/finx/reconciliation/service/BankStatementEvidenceFingerprint.java`
- `backend/src/main/java/com/finx/reconciliation/service/BankStatementSnapshot.java`
- `backend/src/main/java/com/finx/reconciliation/service/BankStatementPersistenceService.java`
- `backend/src/main/java/com/finx/reconciliation/service/BankStatementIngestionService.java`
- `backend/src/test/java/com/finx/reconciliation/BankStatementProviderContractTest.java`
- `backend/src/test/java/com/finx/reconciliation/BankStatementEvidenceFingerprintTest.java`
- `backend/src/test/java/com/finx/reconciliation/BankStatementIngestionServiceTest.java`
- `backend/src/test/java/com/finx/reconciliation/BankStatementPersistenceServiceTest.java`
- `backend/src/test/java/com/finx/reconciliation/BankStatementIngestionArchitectureTest.java`
- `backend/src/test/java/com/finx/reconciliation/PostgresBankStatementIngestionTest.java`
- `RECONCILIATION_R2_INGESTION_REPORT.md`

## Files Modified

- `backend/src/main/java/com/finx/banking/provider/BankingProvider.java`
- `backend/src/main/java/com/finx/banking/provider/mock/MockBankingProvider.java`

## Verification Summary

- Tests Run: 968
- Failures: 0
- Errors: 0
- Skipped: 1 (existing optional provider test)
- Full Suite Result: `BUILD SUCCESS`

## R2 Status

**PASS**

## Ready For R3

**YES**
