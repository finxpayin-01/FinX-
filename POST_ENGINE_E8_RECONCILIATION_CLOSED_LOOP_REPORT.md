# FinX E8 Deterministic Reconciliation Closed-Loop Report

## Scope
Implementation of Phase E8 — Deterministic Reconciliation Closed-Loop Integration.
Connects the modern FinX post-E7 release settlement architecture (`ReleaseInstruction` + trusted `SettlementObservation` `FINAL_SUCCESS`) directly to durable bank statement debits via the authoritative deterministic reconciliation engine (`DeterministicReconciliationMatcher` V2).

## Preconditions
- **E7 Base Commit**: `28b3099`
- **Flyway Baseline**: V1 -> V34 certified
- **Decision Engine Platform**: 9/9 PASS
- **Settlement Execution**: E1-E7 PASS
- **Bank Calls**: 0 network / initiateSettlement calls
- **SettlementInstruction Writes**: 0
- **Production Payout**: DISABLED (Fail-closed legacy B04 gate active)

## Migration
- **Script**: `V35__add_release_instruction_reconciliation_effect.sql`
- **Path**: `backend/src/main/resources/db/migration/V35__add_release_instruction_reconciliation_effect.sql`
- **Checksum / Status**: Validated through FlywayCleanMigrationTest and real PostgreSQL integration tests.
- **V1-V34**: Unchanged.
- **V36**: ABSENT (No preallocation).

## Why V35 Was Required
Migration `V26__durable_bank_statement_reconciliation.sql` originally enforced check constraint `chk_recon_items_internal_effect_type`:
```sql
CONSTRAINT chk_recon_items_internal_effect_type CHECK (
    internal_effect_type IS NULL OR internal_effect_type IN (
        'PAYMENT_TRANSACTION',
        'SETTLEMENT_INSTRUCTION'
    )
)
```
In the modern FinX execution architecture established across phases E1-E7, outbound settlements intentionally produce **0 writes to `SettlementInstruction`**, producing `ReleaseInstruction` bound to trusted `SettlementObservation` rows instead.
Without V35, persisting an outbound reconciliation item for a modern release was rejected by the database. V35 cleanly expands `chk_recon_items_internal_effect_type` to allow:
`'PAYMENT_TRANSACTION'`, `'SETTLEMENT_INSTRUCTION'`, `'RELEASE_INSTRUCTION'`, and `NULL` (for non-effect control items).

## Matcher Version
- **Historical**: `FINX_RECONCILIATION_MATCHER_V1`
- **Modern**: `FINX_RECONCILIATION_MATCHER_V2`
- **Semantics**:
  - `MATCHER_VERSION_V1 = "FINX_RECONCILIATION_MATCHER_V1"`
  - `MATCHER_VERSION_V2 = "FINX_RECONCILIATION_MATCHER_V2"`
  - Default matcher version for all new runs is `MATCHER_VERSION_V2`.
  - Upgrading the version constant ensures transparent auditing: outbound candidate matching semantics materially expanded to support `RELEASE_INSTRUCTION`. Historical V1 rows are never rewritten.

## Funding Compatibility
- Existing authoritative inbound funding reconciliation runs produced under V1 remain valid for pre-release decisioning.
- `DeterministicReconciliationMatcher.isAuthoritativeFundingMatcherVersion(version)` returns `true` for both `V1` and `V2`.
- `ReconciliationDecisionAdapter` accepts both V1 and V2 runs as authoritative inbound funding proof. Unknown matcher versions evaluate to `REQUIRES_REVIEW`.

## Modern Outbound Effect
- `ReconciliationEffectType.RELEASE_INSTRUCTION` represents modern post-E7 outbound settlement effects.
- Lineage binds `ReleaseInstruction.id` (`internalEffectId`) with `ReleaseInstruction.amount`, `direction = DEBIT`, and `finxReference = observation.providerOperationKey`.

## Legacy Outbound Effect
- `ReconciliationEffectType.SETTLEMENT_INSTRUCTION` is strictly preserved for backward-compatible reconciliation of historical pre-E1 settlement instructions.
- Modern E1-E7 pipeline produces 0 `SettlementInstruction` entities.

## Candidate Window
- Candidate windowing for modern releases uses the final settlement observation time:
  `COALESCE(so.provider_observed_at, so.observed_at) BETWEEN :windowStart AND :windowEnd`.
- Release creation, authorization, and local finalization timestamps are not used for window bounds because internal accounting finalization may take place asynchronously after external bank execution.
- Release candidate query enforces:
  - `ri.status = 'SETTLED'`
  - `ri.provider = :provider`
  - `ri.provider_account_id = :providerAccountId`
  - Bounded window on observation timestamp
  - Deterministic ordering: `COALESCE(so.provider_observed_at, so.observed_at) ASC, ri.id ASC`.

## Final Observation Binding
- Each candidate `ReleaseInstruction` requires a non-null `finalSettlementObservationId`.
- Exact observation lookup verifies:
  - Observation exists and `observation.releaseInstructionId == release.id`
  - Observation `finality == FINAL_SUCCESS`
  - Provider, providerAccountId, amount, currency, and direction (`DEBIT`) match the release instruction
  - `providerOperationKey` matches `FINX-REL-{release.id}`
  - Non-blank `providerSettlementId` and `utrNumber`.

## Reference Priority
Modern release outbound resolution matches bank statement lines using strict deterministic reference priority:
1. **UTR**: `observation.utrNumber` against `line.utrNumber` (or `line.transactionReference` fallback).
2. **Provider Settlement ID**: `observation.providerSettlementId` against `line.transactionReference`.
3. **Provider Operation Key**: `observation.providerOperationKey` against `line.transactionReference`.

Matching rules:
- 0 matching lines -> `MISSING_EXTERNAL` (release recorded as missing external bank debit).
- 1 matching line -> candidate match.
- >1 matching lines -> `REFERENCE_AMBIGUOUS` (flagged for review; no match confirmed).

## Financial Dimensions
Exact validation dimensions required for `MATCHED`:
- Provider: exact match
- Provider Account ID: exact match
- Direction: `DEBIT`
- Currency: exact match
- Amount: exact `BigDecimal` match
- Bank line status: normalized `FINAL_SUCCESS`
- Reference: authoritatively resolved without ambiguity
- UTR: consistent (if bank line specifies UTR, must match observation UTR case-insensitively; mismatch yields `UTR_MISMATCH`).

## One-To-One Matching
- Global one-to-one claim tracking ensures no bank statement line is claimed by multiple internal entities.
- If two `ReleaseInstruction` entities or a `ReleaseInstruction` and a `SettlementInstruction` attempt to claim the same statement debit line, the collision triggers `DUPLICATE_INTERNAL_MATCH` / `REFERENCE_AMBIGUOUS`. Neither is silently preferred.

## Statement Balance Control
- Mathematical statement balance verification is preserved:
  `openingBalance - totalDebits + totalCredits == closingBalance`.
- Discrepancy invalidates clean run status and flags `BALANCE_MISMATCH`.

## Release Closed-Loop Reader
- Read-only service: `ReleaseReconciliationEvidenceReader`
- Provides API `getReleaseReconciliationStatus(releaseInstructionId)` returning `ReleaseReconciliationResult`.
- Status taxonomy:
  - `CONFIRMED`: Latest authoritative V2 run is clean, fully matched, with matching line details.
  - `REVIEW`: Ambiguous reference, pending bank status, or missing evidence.
  - `BLOCKED`: Lineage or dimension mismatch (e.g. wrong account, provider, or amount).
  - `REVERSAL_RECOVERY_REQUIRED`: Bank statement debit indicates `REVERSED` or external observation in `CONFLICT`/`REVERSED`.
  - `NO_EVIDENCE`: No reconciliation item exists for this release.
- 0 writes, 0 bank calls, 0 state mutations.

## Latest Run Rule
- Evaluates the latest authoritative V2 reconciliation run containing the release instruction (ordered by `endTime DESC, id DESC`).
- Does not fall back to an older clean run if a newer authoritative run reports a mismatch.

## Reversal Boundary
- External bank statement reversal (`bankStatus = REVERSED`) or observation reversal yields `REVERSAL_RECOVERY_REQUIRED`.
- Zero automatic financial compensation, escrow restoration, ledger undo, or milestone reversal is performed. Reversal handling remains strictly isolated to administrative recovery workflows (E9+).

## End-to-End Mock Closed Loop
- Verified in `PostgresMockReleaseClosedLoopTest`:
  1. Funded escrow established.
  2. Release prepared and authorized (E1-E4).
  3. Sandbox settlement dispatched to `MockBankingProvider` generating simulated bank debit (E5).
  4. Trusted settlement observation recorded with `FINAL_SUCCESS` and UTR (E6).
  5. `TrustedSettlementFinalizer` transitions release to `SETTLED`, consumes reservation, and posts double-entry GL ledger (E7).
  6. Durable `BankStatementImport` and `BankStatementLine` ingested with matching UTR.
  7. `DeterministicReconciliationMatcher` V2 reconciles import.
  8. `ReleaseReconciliationEvidenceReader` reports `CONFIRMED`.
  9. Additional bank calls: 0. Additional financial mutations: 0.

## Financial Side Effects
- Zero mutation to `ReleaseInstruction` state, `ReleaseReservation`, `EscrowAccount`, `EscrowLedger`, `JournalEntry`, `JournalLine`, `Milestone`, `Deal`, or `SettlementObservation`.
- Reconciliation writes are strictly confined to `ReconciliationRun` and `ReconciliationItem`.

## Bank Calls
- Exactly 0 network calls or `initiateSettlement` calls.

## SettlementInstruction Writes
- Exactly 0 `SettlementInstruction` entities created or written.

## PostgreSQL
- Real PostgreSQL 16 verification in `finx_postgres`:
  - `PostgresReleaseReconciliationEffectSchemaTest`: 6/6 PASS
  - `PostgresReleaseClosedLoopReconciliationTest`: 8/8 PASS
  - `PostgresMockReleaseClosedLoopTest`: 1/1 PASS
  - `PostgresDeterministicReconciliationMatcherTest`: 18/18 PASS
  - Skipped: 0

## Flyway
- `FlywayCleanMigrationTest`: V1 -> V35 PASS
- Highest migration: V35
- V36: ABSENT

## Production Payout
- DISABLED.

## Ready For E9
- **YES**.
