# FinX E6 Trusted Settlement Observation and Recovery Report

## Scope
Phase E6 implements the **Trusted Settlement Observation Inbox** and **Provider Status / Operation-Key Recovery** architecture for the FinX platform.
The purpose of E6 is to observe external banking truth as durable, immutable evidence without assuming finality or creating financial truth internally.
E6 strictly decouples external settlement observation from internal money finalization.

## Preconditions
- **E5 Baseline Commit**: `d457280`
- **Branch**: `feature/finx-engine-platform`
- **Starting Migrations**: V1 through V32 applied cleanly.
- **Decision Engine Platform**: Certified complete (9 / 9 engines PASS).
- **Execution Containment Gate (B04)**: ACTIVE and fail-closed.

## Migration
- **Migration File**: `backend/src/main/resources/db/migration/V33__create_settlement_observations.sql`
- **Created Table**: `settlement_observations`
- **Unmodified Migrations**: V1 through V32 strictly untouched.
- **Future Migrations**: V34 absent (no migration preallocated for E7 until schema requirements are audited).
- **Constraints & Indexes**:
  - Primary Key: `id UUID`
  - Foreign Keys:
    - `release_instruction_id` -> `release_instructions(id)` ON DELETE RESTRICT
    - `execution_attempt_id` -> `settlement_execution_attempts(id)` ON DELETE RESTRICT
    - `webhook_event_id` -> `webhook_events(id)` ON DELETE RESTRICT (nullable)
  - Enums / Checks:
    - `source` IN (`WEBHOOK`, `STATUS_POLL`, `OPERATION_LOOKUP`)
    - `finality` IN (`FINAL_SUCCESS`, `PENDING`, `FAILED`, `REVERSED`, `NOT_FOUND`, `UNKNOWN`)
    - `evidence_hash`: 64 lowercase hexadecimal characters (`^[0-9a-f]{64}$`)
    - `observed_amount`: NULL or > 0
    - `observed_currency`: NULL or 3 uppercase letters (`^[A-Z]{3}$`)
    - `observed_direction`: NULL or 'DEBIT'
    - `FINAL_SUCCESS` requires both `provider_settlement_id IS NOT NULL` AND `utr_number IS NOT NULL`
  - Uniqueness:
    - Partial Unique Index: `uix_settlement_observations_webhook_event_id` on `webhook_event_id WHERE webhook_event_id IS NOT NULL`
    - Composite Unique: `(execution_attempt_id, source, evidence_hash)` preventing duplicate identical evidence ingestion
  - Lookup Indexes:
    - `(release_instruction_id, observed_at DESC)`
    - `(execution_attempt_id, observed_at DESC)`
    - `(provider, provider_settlement_id)`
    - `(provider_operation_key)`
    - `(finality, observed_at)`
    - `(source, source_reference)`

## Observation Sources
1. **WEBHOOK**: Ingested via authenticated inbound banking webhooks signed by the provider.
2. **STATUS_POLL**: Ingested via read-only status query using a known `providerSettlementId`.
3. **OPERATION_LOOKUP**: Ingested via read-only lookup using the deterministic stable idempotency key `FINX-REL-{releaseInstructionId}`.
*(Bank statement closed-loop reconciliation is deliberately excluded from E6 and scheduled for E8).*

## Webhook Security
Extended `BankingWebhookController` with zero weakening of existing B02 protections:
- **Raw-body HMAC SHA-256 validation**: Signature computed over exact raw request bytes.
- **Provider path binding**: Enforced URL path parameter matches configured active provider.
- **Timestamp freshness window**: Clamped to 300-second maximum skew (rejects stale and future timestamps).
- **Event ID required**: Provider event identifier strictly nonblank.
- **Payload hash verification**: SHA-256 of raw body recorded in durable `webhook_events` table; duplicate event ID with conflicting payload is rejected (`PAYLOAD_HASH_MISMATCH`).
- **Durable replay defense**: Exact duplicate signed event replays return cached idempotent response (`200 OK`) with zero duplicate observations and zero financial side effects.
- **Attempt binding**: Outbound settlement webhooks resolve attempts by `providerSettlementId` or `settlementReference` (`providerOperationKey`). Disagreeing identities fail closed (`AMBIGUOUS_SETTLEMENT_REFERENCE`).
- **Unknown references**: Webhook events that cannot bind to a known FinX attempt fail processing and remain marked `FAILED` (no observation created).

## Observation Immutability
`SettlementObservation` is an `@Immutable` entity:
- All columns have `updatable = false`.
- Zero setter methods.
- IDs generated in Java (`UUID.randomUUID()`).
- Deterministic SHA-256 `evidenceHash` computed over canonical evidence fields.
- New provider updates (e.g. `PENDING` -> `FINAL_SUCCESS`) are inserted as distinct immutable observation rows, never updating existing rows.

## Status Normalization
Implemented case-insensitive deterministic status mapping in `SettlementStatusNormalizer`:
- `SETTLED`, `SUCCESS`, `COMPLETED` -> `FINAL_SUCCESS` (strictly requires UTR).
- `PENDING`, `PROCESSING`, `SUBMITTED`, `ACKNOWLEDGED`, `INITIATED` -> `PENDING`.
- `FAILED`, `REJECTED` -> `FAILED`.
- `REVERSED`, `REFUNDED`, `RETURNED` -> `REVERSED`.
- `NOT_FOUND` -> `NOT_FOUND`.
- Any unmapped status or missing required field -> `UNKNOWN`.

## Final Success Requirements
`FINAL_SUCCESS` is never returned unless:
1. `providerSettlementId` is nonblank and nonnull.
2. `utrNumber` (UTR) is nonblank and nonnull.
If the provider reports `SETTLED` but omits the UTR, the observation is classified as `UNKNOWN` with reason code `SETTLEMENT_FINAL_MISSING_UTR`.

## Status Poll Evidence Limitations
The current `BankingDtos.SettlementStatusResult` returned by banking status APIs includes only `providerSettlementId`, `status`, `utrNumber`, `settledAt`, and `failureReason`.
It does **NOT** prove `amount`, `currency`, `providerAccountId`, or `direction`.
Therefore, for `STATUS_POLL` observations:
- `observedAmount`: `NULL` (never fabricated).
- `observedCurrency`: `NULL` (never fabricated).
- `providerAccountId`: `NULL` (never fabricated).
- `observedDirection`: `NULL` (never fabricated).
The attempt snapshot remains internal expected context, but external evidence fields are not fabricated.

## Operation-Key Recovery
- **Deterministic Key Format**: `FINX-REL-{releaseInstructionId}`.
- Extended `BankingProvider` with `lookupSettlementByOperationKey(String providerOperationKey)`. Default interface method returns `supported = false, found = false, settlement = null`.
- `MockBankingProvider` implements `lookupSettlementByOperationKey`:
  - When the idempotency key was previously processed: returns `supported = true, found = true` with actual recorded settlement data.
  - When unknown: returns `supported = true, found = false, settlement = null`.

## Crash After Provider Acceptance
- **Closed-Loop Proven**: If FinX crashes between network dispatch and persisting `ACKNOWLEDGED` (leaving the attempt in `SENDING` or `UNKNOWN` with no `providerSettlementId` stored), `SettlementStatusRecoveryService` executes `lookupSettlementByOperationKey`.
- When the provider finds the existing operation:
  - Binds recovered `providerSettlementId` to the attempt.
  - Transitions attempt to `ACKNOWLEDGED` via `recordRecoveredAcknowledgementIdentity`.
  - Transitions `ReleaseInstruction` from `UNKNOWN_RECON_REQUIRED` / `DISPATCHING` to `PENDING_SETTLEMENT`.
  - Persists `OPERATION_LOOKUP` observation.
  - Does **NOT** issue a second `initiateSettlement` call.

## Crash Before Provider Call
- If FinX persisted `SENDING` but crashed before making the network call, the provider has no record under the stable operation key.
- `lookupSettlementByOperationKey` returns `NOT_FOUND`.
- `SettlementStatusRecoveryService` persists a `NOT_FOUND` observation with reason `SETTLEMENT_NOT_FOUND`.
- **Blind Retries Blocked**: The attempt remains `SENDING`, the release instruction remains `DISPATCHING` / `UNKNOWN_RECON_REQUIRED`, and the reservation remains `ACTIVE`. Zero automated re-dispatch is attempted in E6.

## Mock Provider Hardening
- **Eliminated Fabricated Settlement**: Previously, `MockBankingProvider.getSettlementStatus(unknownId)` generated a fabricated `SETTLED` status with a fake UTR and timestamp.
- **Hardened Behavior**: Unknown settlement IDs return `NOT_FOUND` with `utrNumber = null`, `settledAt = null`, and `failureReason = "SETTLEMENT_NOT_FOUND"`.

## Conflicting Observations
- Implemented in `SettlementObservationEvidenceReader`:
  - Scans all observations for a release instruction ordered by observation timestamp.
  - If a release has both `FINAL_SUCCESS` and a subsequent `REVERSED` or conflicting provider status, `getLatestAuthoritativeFinalObservation` returns `AuthoritativeStatus.CONFLICT`.
  - Never marks conflicting evidence as safe finality.

## Release State
- `ReleaseInstruction` remains `PENDING_SETTLEMENT` (or recovers to `PENDING_SETTLEMENT`).
- `ReleaseInstruction` is **NEVER** marked `SETTLED` in E6.

## Reservation
- `ReleaseReservation` strictly remains `ACTIVE`.
- Zero reservation consumption in E6.

## Ledger
- **0 Ledger Entries**: `LedgerService` is never invoked in E6 observation or recovery flows.

## Escrow
- **0 Financial Mutation**: `EscrowService` is never invoked; balances remain untouched.

## Milestone
- Milestone remains `APPROVED`; never transitioned to `COMPLETED` in E6.

## SettlementInstruction
- **0 Writes**: Zero `SettlementInstruction` entities created.

## PostgreSQL
- Real PostgreSQL 16 verified on `localhost:5432` (`finx_user/finx_password`).
- Test databases: `finx_release_e6_test`, `finx_release_e5_test`, `finx_release_e4_test`.
- Schema constraints, foreign key restrictions, unique partial indexes, and immutable entity behaviors verified.
- Postgres skipped tests: 0.

## Flyway
- `FlywayCleanMigrationTest` executed against clean PostgreSQL.
- Applied V1 through V33 cleanly in order.
- Highest applied: V33.
- V34 strictly absent.

## Production Payout
- Production payout remains **DISABLED**.
- Real bank provider remains **BLOCKED**.
- Legacy B04 gate remains active and fail-closed.

## Ready For E7
**YES** — Trusted external settlement evidence is durably recorded, normalized, and queryable. The platform is ready for Phase E7 (Atomic Settlement Finalizer).
