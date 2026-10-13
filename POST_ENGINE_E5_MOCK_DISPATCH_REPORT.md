# FinX E5 Mock Settlement Dispatch Report

## Scope
Implementation of Phase E5: Mock Bank Provider Sandbox Dispatch & Unknown-Outcome Safety.
E5 connects the prepared settlement execution attempt and leased outbox row to `MockBankingProvider` in an explicit, isolated sandbox mode, enforcing out-of-transaction network execution, ephemeral in-memory PII handling, stable operation identities, two-phase transaction boundaries (TX-D1 and TX-D2), and strict non-finality semantics for provider acknowledgements.

## Preconditions
- **E4 Baseline Commit**: `763fc13`
- **Previous Phases**: E1 (`7fcac79`), E1.5 (`5db581d`), E2 (`7ccb36d`), E3 (`17dc01f`), E4 (`763fc13`)
- **Decision Engine Platform**: Certified complete (9 / 9 engine adapters verified PASS)
- **Work State Entering E5**:
  - `ReleaseInstruction`: QUEUED
  - `ReleaseReservation`: ACTIVE
  - `FinancialOutboxMessage`: LEASED
  - `SettlementExecutionAttempt`: PREPARED
  - Provider Operation Key: `FINX-REL-{releaseInstructionId}`

## Migration
- **New Migration**: NONE
- **Highest Active Migration**: `V32` (`V32__create_settlement_execution_attempts.sql`)
- **V33 Status**: ABSENT (strictly reserved for Phase E6 trusted observation persistence)

## Sandbox Gate
- **Class**: `com.finx.release.execution.gate.SandboxSettlementDispatchGate`
- **Default Application Configuration**: `finx.release.sandbox-dispatch.enabled: false` (DISABLED)
- **Gate Requirements**:
  1. `finx.release.sandbox-dispatch.enabled == true`
  2. `finx.banking.provider == mock` (configured provider)
  3. `injected BankingProvider.getProviderName() == "MOCK"`
- **Fail-Closed Behavior**: If any condition fails, dispatch is rejected with zero network calls and zero financial mutations. Mere presence of a `MockBankingProvider` bean does NOT bypass the gate.

## Dispatch Boundary
- **Class**: `com.finx.release.execution.service.SettlementDispatchHandler`
- **Isolation Principle**: `SettlementDispatchHandler` is the ONLY new production class with direct dependencies on `BankingProvider` and `PiiEncryptionService`. Worker, lease, and preparation services remain completely provider-agnostic.
- **Explicit Execution**: Exposes `dispatchPreparedWorkItem(PreparedSettlementWorkItem workItem)` with no automatic invocation, `@Scheduled` jobs, or application-startup dispatch hooks.

## Network Transaction Boundary
- **Architecture**:
  - **TX-D1 (`beginDispatch`)**: Revalidates execution-critical guards (decision validity, approval binding, absence of disputes, canonical sandbox beneficiary, funding scope). Atomically transitions `SettlementExecutionAttempt: PREPARED -> SENDING` and `ReleaseInstruction: QUEUED -> DISPATCHING`. Commits immediately.
  - **NETWORK CALL**: `bankingProvider.initiateSettlement(request)` executes **completely outside any active database transaction**. No DB locks or connections held during provider call.
  - **TX-D2 (`recordAcknowledged` / `recordUnknown`)**: Separate short transaction recording the outcome.
- **Verification**: `SettlementDispatchHandler` and `dispatchPreparedWorkItem` are strictly NOT annotated with `@Transactional`.

## Provider Operation Identity
- **Format**: `FINX-REL-{releaseInstructionId}`
- **Immutability**: Stable across retries, re-executions, and crash recoveries. No random UUIDs, timestamps, or attempt numbers are used for provider financial operation identity.

## Beneficiary Secret Boundary
- **Storage**: Plaintext bank account numbers are stored encrypted via AES-GCM-256 (`OrganizationBankAccount.encryptedAccountNumber`).
- **Decryption**: `PiiEncryptionService.decrypt(...)` is invoked ephemerally in-memory immediately prior to constructing the `SettlementRequest`.
- **Leakage Prevention**: Plaintext account numbers are never logged, never persisted in `financial_outbox`, `settlement_execution_attempts`, `release_instructions`, audit logs, error messages, or work items. Database text scan in integration test verifies 0 occurrences.

## Attempt Lifecycle
- `PREPARED -> SENDING` (committed in TX-D1; `requestStartedAt` populated)
- `SENDING -> ACKNOWLEDGED` (committed in TX-D2; `providerSettlementId`, `providerStatus`, `responseReceivedAt` populated; `retryable = false`)
- `SENDING -> UNKNOWN` (committed in TX-D2 on provider exception or malformed response; `errorCode` populated; `retryable = false`)
- `PREPARED -> TERMINAL_FAILED` (preflight local data failure before network start)
- Direct transitions `PREPARED -> ACKNOWLEDGED` or `PREPARED -> UNKNOWN` are strictly forbidden and throw `IllegalStateException`.

## Happy Path
- Even when `MockBankingProvider` returns `status = "SETTLED"`, E5 treats this solely as **synchronous provider acknowledgement / reference acquisition**, NOT trusted financial settlement finality.
- Result:
  - `SettlementExecutionAttempt`: ACKNOWLEDGED
  - `ReleaseInstruction`: PENDING_SETTLEMENT (NOT SETTLED)
  - `FinancialOutboxMessage`: PROCESSED (dispatch command consumed)
  - `ReleaseReservation`: ACTIVE (NOT CONSUMED)
  - `Milestone`: APPROVED (NOT COMPLETED)

## Unknown Outcome
- Any exception thrown by `initiateSettlement(...)` after SENDING or any malformed/ambiguous response is treated as `UNKNOWN`.
- Result:
  - `SettlementExecutionAttempt`: UNKNOWN
  - `ReleaseInstruction`: UNKNOWN_RECON_REQUIRED
  - `FinancialOutboxMessage`: PROCESSED (safe error code recorded)
  - `ReleaseReservation`: ACTIVE
- **No-Retry Boundary**: Never transitioned to RETRY. Blind automatic re-dispatch is strictly blocked. Subsequent calls to `dispatchPreparedWorkItem` fail closed (`RECOVERY_REQUIRED`). Recovery proceeds via E6 status query / observation.

## Crash Before Call
- Scenario: TX-D1 commits (`SENDING` / `DISPATCHING` / `LEASED`), and process crashes before calling the provider.
- Invariant: `FinancialOutboxLeaseService.isNetworkStarted()` detects `requestStartedAt != null` / `SENDING` status and strictly blocks automatic lease retry.
- Classification: `EXECUTION_OUTCOME_RECOVERY_REQUIRED`. Resolves via E6 status recovery.

## Crash After Provider Acceptance
- Scenario: Provider returns valid response, but process crashes before TX-D2 commits.
- Invariant: Persisted state remains `SENDING` / `DISPATCHING` / `LEASED`. Automatic lease retry is blocked. Second handler call is blocked from duplicate provider dispatch. Requires E6 recovery.

## Outbox Semantics
- `ACKNOWLEDGED`: PROCESSED
- `UNKNOWN`: PROCESSED
- `PROCESSED` denotes that the dispatch command itself has been consumed from the queue. It does NOT denote financial settlement finality.

## Release Status
- On Valid Acknowledgement: `PENDING_SETTLEMENT`
- On Ambiguous / Unknown / Exception: `UNKNOWN_RECON_REQUIRED`
- E5 contains 0 code paths that transition `ReleaseInstruction` to `SETTLED`.

## Reservation
- **Status**: Strictly `ACTIVE` across all E5 execution outcomes.
- Consumption occurs only in Phase E7 upon verified observation finality.

## Mock Idempotency
- `MockBankingProvider` hardened with in-memory request fingerprint mapping (`MockSettlementFingerprint`).
- Same idempotency key + matching request returns identical `providerSettlementId` and `UTR`, and registers exactly **one debit** in the simulated bank feed.
- Conflicting requests under the same key throw `DuplicateResourceException`.

## SettlementInstruction
- **Writes**: 0
- Creation and projection deferred to E6/E7 observation model.

## Ledger
- **Writes**: 0
- Double-entry GL posting deferred to E7 atomic settlement finalizer.

## Escrow Financial Mutation
- **Mutations**: 0
- Escrow balance deduction and `totalReleased` increment deferred to E7 finalizer.

## Milestone
- **Status**: Remained `APPROVED`. Not marked `COMPLETED`.

## Real Bank
- **Calls**: 0
- Non-mock provider names fail closed before any network execution.

## Mock Bank
- **Calls**: Sandbox simulation only under explicit property enablement.

## B04
- **Status**: ACTIVE & FAIL-CLOSED.
- Legacy `SettlementAuthorizationService.authorizeAndInitiateSettlement(...)` remains blocked via `AccessDeniedException`. Legacy bank calls: 0.

## PostgreSQL
- **Database**: Real PostgreSQL instance (`finx_release_e5_test` disposable database on port 5432).
- **Execution**: Verified under genuine concurrency, row locks, foreign keys, and transaction commits.
- **Skipped Tests**: 0 material PostgreSQL tests skipped.

## Flyway
- **Migrations**: `V1` -> `V32` PASS.
- **V33**: ABSENT.

## Production Payout
- **Status**: DISABLED.
- Default configuration fails closed.

## Full Test Suite Results
- **Tests Run**: 1458
- **Failures**: 0
- **Errors**: 0
- **Skipped**: 1 (standard conditional test)
- **Result**: `BUILD SUCCESS`

## Ready For E6
**YES** — Ready for Phase E6: Trusted Settlement Observation Inbox + Provider Status Recovery (Migration `V33`).
