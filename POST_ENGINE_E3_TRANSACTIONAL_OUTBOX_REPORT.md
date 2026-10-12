# FinX E3 Transactional Financial Outbox Report

## Scope
Implementation of Phase E3: Transactional Financial Outbox Schema and Atomic Enqueue for the FinX post-engine release platform.
In this phase:
- Added `financial_outbox` table via Flyway migration `V31__create_financial_outbox.sql`.
- Built identifier-only outbox domain model (`FinancialOutboxMessage`, `FinancialOutboxEventType`, `FinancialOutboxStatus`, `FinancialOperationKey`).
- Implemented `FinancialOutboxService` with `Propagation.MANDATORY` transaction semantics.
- Added `ReleaseInstruction.markQueued()` state transition (`RESERVED -> QUEUED`).
- Integrated atomic enqueue into `ReleaseAuthorizationService` (TX2), ensuring `ReleaseInstruction` (QUEUED), `ReleaseReservation` (ACTIVE), and `FinancialOutboxMessage` (PENDING) commit atomically in a single local database transaction.
- Maintained zero banking provider interactions, zero ledger postings, zero escrow balance deductions, and zero settlement instruction writes.

## Preconditions
- E1: `7fcac79` (ReleaseInstruction & Reservation persistence foundation)
- E1.5: `5db581d` (Durable milestone approval binding)
- E2: `7ccb36d` (Release authorization & atomic reservation TX2)
- Decision Engine Platform: 9 / 9 PASS certified
- B04 containment: ACTIVE (production payout disabled)

## Migration
- **File**: `backend/src/main/resources/db/migration/V31__create_financial_outbox.sql`
- **Table**: `financial_outbox`
- **Migration Status**: Flyway V1 -> V31 PASS.
- **V1–V30**: Unmodified.
- **V32**: Absent (strictly reserved for Phase E4).

## Outbox Model
- **Entity**: `com.finx.outbox.entity.FinancialOutboxMessage`
- **Fields**:
  - `id`: UUID (Primary Key)
  - `release_instruction_id`: UUID (Foreign Key to `release_instructions(id)` ON DELETE RESTRICT)
  - `event_type`: VARCHAR(64) (`RELEASE_DISPATCH_REQUESTED`)
  - `operation_key`: VARCHAR(128) (Deterministic string representation of `releaseInstructionId`)
  - `payload_version`: INTEGER (1)
  - `status`: VARCHAR(20) (`PENDING`, `LEASED`, `RETRY`, `PROCESSED`, `DEAD_LETTER`)
  - `attempt_count`: INTEGER (Default 0)
  - `available_at`: TIMESTAMP WITH TIME ZONE
  - `locked_at`: TIMESTAMP WITH TIME ZONE (NULL for PENDING)
  - `locked_by`: VARCHAR(128) (NULL for PENDING)
  - `last_error_code`: VARCHAR(100)
  - `created_at`: TIMESTAMP WITH TIME ZONE
  - `updated_at`: TIMESTAMP WITH TIME ZONE
  - `processed_at`: TIMESTAMP WITH TIME ZONE (NULL for PENDING)
  - `dead_lettered_at`: TIMESTAMP WITH TIME ZONE (NULL for PENDING)
  - `version`: BIGINT (@Version optimistic locking)

## Event Type
- Enum: `FinancialOutboxEventType`
- Exact supported value for E3: `RELEASE_DISPATCH_REQUESTED`
- No speculative or unrelated events exist.

## Operation Identity
- Helper: `FinancialOperationKey.releaseDispatch(UUID releaseInstructionId)`
- Value: `releaseInstruction.id.toString()`
- **Properties**: Stable and deterministic across retries, crash recoveries, and re-evaluations. No timestamps, random UUIDs, caller tokens, or external idempotency keys are used.

## Payload Boundary
- **Identifier-Only Architecture**: The outbox record stores strictly canonical database identifiers (`id`, `release_instruction_id`, `event_type`, `operation_key`).
- **No Raw Bank Payload**: The table contains no JSON payload, no bank request, no account number, no IFSC, no encrypted/masked beneficiary data, no credentials, and no KYC/KYB data.
- Downstream workers (E4/E5) reload authoritative state directly from normalized PostgreSQL tables.

## TX2 Atomicity
- Fresh authorization path:
  1. Acquire row-level locks on `Deal`, `Milestone`, `EscrowAccount`, and `ReleaseInstruction`.
  2. Verify all pre-execution guards (decision currentness, approval binding, dispute checks, funding scope, beneficiary verification).
  3. `markAuthorized(principal.getId())`
  4. Create `ACTIVE` `ReleaseReservation`.
  5. `markReserved()`
  6. Enqueue `RELEASE_DISPATCH_REQUESTED` outbox message (`PENDING`) in the same mandatory transaction.
  7. `markQueued()`
  8. Save `ReleaseInstruction` and commit.
- **Committed State**:
  - `ReleaseInstruction`: `QUEUED`
  - `ReleaseReservation`: `ACTIVE`
  - `FinancialOutboxMessage`: `PENDING`

## Rollback
- Verified in `PostgresOutboxTransactionalAtomicityTest.testB_outboxFailureRollsBackEntireTx2`:
  - When an outbox enqueue failure is forced inside TX2, the entire transaction rolls back.
  - `ReleaseInstruction` remains in `CREATED` status.
  - 0 reservations exist.
  - 0 outbox messages exist.
  - Escrow balance remains unchanged.
  - Bank calls: 0.

## Legacy RESERVED Upgrade
- When `authorizeAndReserve(...)` encounters a legitimate pre-E3 `RESERVED` instruction with an `ACTIVE` reservation and no outbox:
  1. Re-runs all execution-critical guards.
  2. Verifies exactly one consistent `ACTIVE` reservation.
  3. Ensures no outbox event exists.
  4. Enqueues `RELEASE_DISPATCH_REQUESTED` (`PENDING`).
  5. `markQueued()`.
  6. Commits.
- Upgrades legacy rows safely without stranding them and without calling bank providers.
- If `RESERVED` already has an outbox row, fails closed as `DATA_INTEGRITY_ERROR`.

## Idempotency
- Replaying `authorizeAndReserve(...)` on an already `QUEUED` instruction:
  - Verifies exactly one `ACTIVE` reservation and matching `RELEASE_DISPATCH_REQUESTED` outbox message.
  - Returns existing reservation idempotently.
  - Creates 0 duplicate outbox rows.
  - Makes 0 bank calls.
- Enqueue idempotency in `FinancialOutboxService`:
  - Matching existing event returns existing row.
  - Conflicting operation key or event identity fails closed (`OUTBOX_IDENTITY_CONFLICT`).

## Database Constraints
- Foreign Key: `fk_financial_outbox_release_instruction` to `release_instructions(id)` with `ON DELETE RESTRICT`.
- Unique Constraints:
  - `uk_financial_outbox_instruction_event`: `UNIQUE(release_instruction_id, event_type)`
  - `uk_financial_outbox_event_opkey`: `UNIQUE(event_type, operation_key)`
- Check Constraints:
  - `chk_outbox_event_type`: `event_type IN ('RELEASE_DISPATCH_REQUESTED')`
  - `chk_outbox_op_key`: Non-blank operation key
  - `chk_outbox_payload_version`: `payload_version > 0`
  - `chk_outbox_attempt_count`: `attempt_count >= 0`
  - `chk_outbox_status`: `status IN ('PENDING', 'LEASED', 'RETRY', 'PROCESSED', 'DEAD_LETTER')`
  - Lifecycle consistency checks enforcing lock and termination timestamps for each status.

## Sensitive Data Boundary
- Zero sensitive data stored in `financial_outbox`.
- Verified by `FinancialOutboxEntityTest` and `PostgresFinancialOutboxSchemaTest`.

## Banking
- Bank calls made: **0**
- `BankingProvider.initiateSettlement`: **0**

## Worker
- **NOT IMPLEMENTED** (Scheduled for Phase E4).
- No `SKIP LOCKED`, no poller, no scheduler, no leasing logic in E3.

## Settlement Attempt
- **NOT IMPLEMENTED** (Scheduled for Phase E4).
- Table `settlement_execution_attempts` reserved for V32.

## Ledger
- Writes: **0** (No journal entries or lines created).

## Escrow Financial Mutation
- Escrow balance changes: **0.0000**
- Total released changes: **0.0000**

## SettlementInstruction
- Writes: **0**

## PostgreSQL
- Verified against live PostgreSQL 16 container (`finx_postgres`).
- Tests:
  - `PostgresFinancialOutboxSchemaTest`: 20 / 20 PASS
  - `PostgresOutboxTransactionalAtomicityTest`: 4 / 4 PASS
  - `PostgresReleaseAuthorizationConcurrencyTest`: 3 / 3 PASS
  - `PostgresFinancialIdentityTest`: 7 / 7 PASS
  - `PostgresLockingConcurrencyTest`: 7 / 7 PASS
- Skipped Postgres tests: **0**

## Concurrency
- Concurrent authorization of same instruction: exactly 1 reservation, 1 outbox message, status `QUEUED`.
- Concurrent oversubscription: exactly 1 succeeds, total reserved <= escrow balance, 0 orphan outbox rows.
- Concurrent enqueue race: duplicate enqueue blocked by database uniqueness constraint.

## Flyway
- Migration suite: `V1` -> `V31` PASS.
- `V32`: Absent.

## B04
- **ACTIVE**: Production payout remains disabled and fail-closed.

## P0 Status
- P0-1: **CLOSED**
- P0-2: **CLOSED**
- P0-3: **CLOSED**
- P0-4: **PARTIAL**
- P0-5: **OPEN**
- P0-6: **OPEN**

## Ready For E4
**YES**
