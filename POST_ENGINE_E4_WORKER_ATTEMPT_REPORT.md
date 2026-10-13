# FinX E4 Worker Leasing and Execution Attempt Report

## Scope
Implementation and certification of Post-Engine Phase E4: PostgreSQL Worker Leasing Framework and Settlement Execution Attempt Model.
Specifically:
1. Native PostgreSQL worker leasing via `SELECT ... FOR UPDATE SKIP LOCKED`.
2. Safe multi-node non-overlapping claim semantics.
3. Durable `SettlementExecutionAttempt` persistence model initialized in status `PREPARED`.
4. Deterministic, stable provider operation identity `FINX-REL-{releaseInstructionId}`.
5. Safe pre-dispatch lease release and expired-lease recovery into `RETRY`.
6. Strict boundary preventing automatic retry of network-started attempts (`SENDING`, `ACKNOWLEDGED`, `UNKNOWN`).
7. Complete isolation: Zero bank provider calls, zero ledger postings, zero escrow mutations, zero settlement instructions.

---

## Preconditions
- **Phase E3 Baseline**: Certified and committed at `17dc01f`.
- **Decision Engine Platform**: Certified complete (9 / 9 PASS).
- **Phases E1, E1.5, E2, E3**: All PASS.
- **Highest Baseline Migration**: `V31__create_financial_outbox.sql`.
- **Working Tree Clean**: Verified.

---

## Migration
- **Created Migration**: `backend/src/main/resources/db/migration/V32__create_settlement_execution_attempts.sql`.
- **Table Created**: `settlement_execution_attempts`.
- **Foreign Keys**:
  - `release_instruction_id` -> `release_instructions(id)` `ON DELETE RESTRICT`
  - `outbox_message_id` -> `financial_outbox(id)` `ON DELETE RESTRICT`
  - `beneficiary_bank_account_id` -> `organization_bank_accounts(id)` `ON DELETE RESTRICT`
- **Check Constraints**:
  - `attempt_number > 0`
  - `amount > 0`
  - `currency ~ '^[A-Z]{3}$'`
  - `request_hash ~ '^[a-f0-9]{64}$'`
  - `status IN ('PREPARED', 'SENDING', 'ACKNOWLEDGED', 'TRANSPORT_FAILED', 'UNKNOWN', 'TERMINAL_FAILED')`
  - Non-blank text constraints on `operation_key`, `provider_operation_key`, `provider`, `provider_account_id`, `worker_id`
  - Lifecycle consistency constraint `chk_settlement_attempts_lifecycle`: enforces that for `PREPARED`, `request_started_at`, `response_received_at`, `provider_settlement_id`, `provider_status`, `retryable`, and `error_code` MUST be NULL.
- **Unique Indexes**:
  - `uk_settlement_attempts_release_attempt` on `(release_instruction_id, attempt_number)`
  - `uk_settlement_attempts_outbox_attempt` on `(outbox_message_id, attempt_number)`
  - `uk_settlement_attempts_active_outbox` on `(outbox_message_id)` `WHERE status IN ('PREPARED', 'SENDING', 'ACKNOWLEDGED', 'UNKNOWN')`
  - `uk_settlement_attempts_provider_settlement_id` on `(provider, provider_settlement_id)` `WHERE provider_settlement_id IS NOT NULL`
- **Lookup Indexes**:
  - `idx_settlement_attempts_release_inst` on `(release_instruction_id, attempt_number DESC)`
  - `idx_settlement_attempts_outbox_msg` on `(outbox_message_id, attempt_number DESC)`
  - `idx_settlement_attempts_status_created` on `(status, created_at)`
  - `idx_settlement_attempts_provider_status` on `(provider, provider_settlement_id)`
- **Migrations V1-V31**: Unchanged.
- **Migration V33**: Strictly reserved for Phase E6; absent in E4.

---

## Outbox Leasing
- **Mechanism**: PostgreSQL native query:
  ```sql
  SELECT * FROM financial_outbox
  WHERE status IN ('PENDING', 'RETRY')
    AND available_at <= :now
  ORDER BY available_at ASC, created_at ASC, id ASC
  FOR UPDATE SKIP LOCKED
  LIMIT :limit
  ```
- **Transaction Boundary**: The claim transaction only selects and locks eligible rows, marks them `LEASED`, updates lock fields (`locked_by`, `locked_at`, increments `attempt_count`), and commits immediately.
- **No Global Bottleneck**: As verified in `PostgresOutboxWorkerLeasingConcurrencyTest`, workers claiming concurrent batches do not block each other; `SKIP LOCKED` skips concurrently locked rows smoothly.

---

## Lease Semantics
- **Lease != Bank Dispatch**: Leasing an outbox row represents worker ownership of the database command only. It does NOT indicate that an HTTP call or provider request has started.
- **State Separation**:
  - `FinancialOutbox`: `PENDING` -> `LEASED`
  - `ReleaseInstruction`: Remains strictly `QUEUED`
  - `SettlementExecutionAttempt`: `PREPARED`

---

## Worker Attempt Count
- **Semantics**: `financial_outbox.attempt_count` tracks worker lease / processing claim count.
- **Distinction**: It does NOT track bank debits, provider requests, or financial effects. Bank network attempts are tracked independently in `settlement_execution_attempts.attempt_number`.

---

## Settlement Execution Attempt
- **Entity**: `com.finx.release.execution.entity.SettlementExecutionAttempt`.
- **Status Lifecycle**: `SettlementExecutionAttemptStatus` (`PREPARED`, `SENDING`, `ACKNOWLEDGED`, `TRANSPORT_FAILED`, `UNKNOWN`, `TERMINAL_FAILED`).
- **Production Status in E4**: Strictly `PREPARED`. Zero transitions to network-active or terminal states.

---

## Attempt Number
- **Calculation**: `MAX(attempt_number for outbox) + 1`, starting at 1.
- **Decoupling**: Distinct from `outbox.attempt_count`. If an outbox lease expires and is re-leased before bank dispatch, `attempt_count` increments while the execution attempt number remains 1.

---

## Provider Operation Key
- **Deterministic Identity**: Generated by `ProviderSettlementOperationKey.release(UUID releaseInstructionId)`.
- **Format**: `FINX-REL-{releaseInstructionId}`.
- **Invariance**: Pure, deterministic, stable across worker crashes, lease retries, transport retries, and multi-node re-leases. Contains no timestamps, random UUIDs, worker IDs, or attempt numbers.

---

## Prepared Attempt
- **Semantics**: FinX has durably staged a canonical snapshot of the intended provider operation from canonical `ReleaseInstruction` and `ReleaseReservation` data.
- **Fields**: Zero network response/error fields. `request_started_at = null`, `response_received_at = null`, `provider_settlement_id = null`, `provider_status = null`, `retryable = null`, `error_code = null`.

---

## ReleaseInstruction State
- **Status Throughout E4**: `QUEUED`.
- **State Transitions**: `ReleaseInstruction` does NOT transition to `DISPATCHING`, `SUBMITTED`, `PENDING_SETTLEMENT`, or `SETTLED` in E4.

---

## Reservation State
- **Status Throughout E4**: `ACTIVE`.
- **Integrity**: Verified by `SettlementExecutionAttemptService.prepareAttempt` before preparing any attempt. Unchanged, not consumed, not released.

---

## Pre-Dispatch Crash Recovery
- **Workflow**:
  1. Worker A leases outbox message (`attempt_count = 1`) and prepares attempt (`attemptNumber = 1, status = PREPARED`).
  2. Worker A crashes before dispatch.
  3. `FinancialOutboxLeaseService.recoverExpiredPreDispatchLeases` identifies expired lease (`locked_at < now - timeout`) with `PREPARED` attempt (`request_started_at == null`).
  4. Outbox transitions to `RETRY`, clearing `locked_at` and `locked_by`.
  5. Worker B claims message (`attempt_count = 2`).
  6. Worker B calls `prepareAttempt` and receives the existing `PREPARED` attempt (same ID, attempt number 1).
  7. No duplicate attempt created.

---

## Network-Started Safety Boundary
- **Safety Invariant**: If an execution attempt status is `SENDING`, `ACKNOWLEDGED`, `UNKNOWN`, or has `request_started_at != null`:
  - `releaseLeaseBeforeDispatchForRetry` strictly throws `IllegalStateException`.
  - `recoverExpiredPreDispatchLeases` skips the outbox row and logs `RECOVERY_REQUIRED`.
  - The row is NEVER automatically returned to `RETRY` or made redispatchable.

---

## Multi-Node Concurrency
- **Concurrency Test**: Verified in `PostgresOutboxWorkerLeasingConcurrencyTest`:
  - 20 `PENDING` rows claimed by 4 concurrent worker threads (batch size 5).
  - All 20 claimed exactly once.
  - Worker claim intersection: Empty (0 duplicate claims).
  - Deadlocks: 0.
  - All claimed rows status: `LEASED`.
  - `ReleaseInstruction` status: `QUEUED`.

---

## SKIP LOCKED Proof
- **Verification**: In `test02_skipLockedAllowsConcurrentWorkersToClaimDifferentRowsWithoutBlocking`:
  - Worker A locks 5 rows inside an active transaction and pauses.
  - Worker B concurrently calls `claimBatch` and immediately receives the remaining 5 rows without blocking on Worker A.
  - Proves non-blocking concurrency across the worker fleet.

---

## Sensitive Data Boundary
- **Outbox & Attempt Tables**: Contain zero raw bank payloads, zero credentials, zero decrypted bank account numbers, zero IFSC codes.
- **Work Items**: `PreparedSettlementWorkItem` contains only safe UUIDs and keys (`outboxMessageId`, `releaseInstructionId`, `executionAttemptId`, `operationKey`, `providerOperationKey`, `workerId`).

---

## Bank Calls
- **Total Real Bank Calls**: 0.
- **Total Mock Bank Calls**: 0.
- **Banking Provider Dependencies**: 0.

---

## Ledger
- **Journal Entries Written**: 0.
- **Journal Lines Written**: 0.

---

## Escrow Financial Mutation
- **Escrow Account Mutations**: 0.
- **Escrow Balance Changes**: 0.

---

## SettlementInstruction
- **SettlementInstruction Writes**: 0.

---

## PostgreSQL
- **Schema & Concurrency Testing**:
  - `PostgresSettlementExecutionAttemptSchemaTest`: 24 schema constraint tests PASS.
  - `PostgresOutboxWorkerLeasingConcurrencyTest`: 6 concurrency & crash recovery tests PASS.
- **Skipped PostgreSQL Tests**: 0.

---

## Flyway
- **Migration Clean Run**: V1 -> V32 PASS.
- **V33 Migration**: ABSENT.

---

## B04
- **Status**: ACTIVE and fail-closed (`SettlementAuthorizationService` throws `AccessDeniedException`).

---

## Ready For E5
**YES**
