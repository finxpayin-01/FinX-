# FinX E7 Atomic Trusted Settlement Finalizer Report

## Scope
Implementation of **Phase E7 — Atomic Trusted Settlement Finalizer** in FinX.
Phase E7 converts an authoritative trusted `FINAL_SUCCESS` `SettlementObservation` into FinX internal financial truth within a single, atomic local PostgreSQL transaction:
- Canonical pessimistic lock hierarchy: `Deal -> Milestone -> EscrowAccount -> ReleaseInstruction -> ReleaseReservation -> sorted LedgerAccounts`.
- Immutable release/attempt/observation identity verification (provider, operation key, provider settlement ID, UTR, amount, currency, DEBIT direction).
- Balanced double-entry general ledger journal entry posting (`MILESTONE_SETTLEMENT`).
- Escrow account balance deduction and total released increment.
- Escrow ledger projection creation (transaction type `RELEASE`).
- Durable `ReleaseReservation` consumption (`ACTIVE -> CONSUMED`).
- Durable binding of final settlement observation and actor to `ReleaseInstruction` (`SETTLED`).
- Milestone completion (`APPROVED -> COMPLETED`) and safe Deal completion.
- Fail-closed sandbox finalization gate protecting production environments.
- Zero network calls, zero banking provider calls, zero `SettlementInstruction` entities created.

---

## Preconditions
- **E6 Base Commit**: `a79b187`
- **Flyway Migrations V1 through V33**: CERTIFIED & APPLIED.
- **Trusted SettlementObservation Inbox**: Operational via Webhook, Status Poll, and Operation Lookup.
- **Outbox Worker & Lease Engine**: Operational with crash recovery and unknown-outcome boundaries.
- **B04 Containment**: Legacy payout authorization fails closed (`AccessDeniedException`).
- **Production Payout**: STRICTLY DISABLED.

---

## Migration
- **File**: `backend/src/main/resources/db/migration/V34__bind_release_finalization_to_observation.sql`
- **Modifications**:
  - `ALTER TABLE release_instructions ADD COLUMN final_settlement_observation_id UUID;`
  - `ALTER TABLE release_instructions ADD COLUMN finalized_by UUID;`
  - `ALTER TABLE release_instructions ADD CONSTRAINT fk_release_instructions_final_observation FOREIGN KEY (final_settlement_observation_id) REFERENCES settlement_observations(id) ON DELETE RESTRICT;`
  - `ALTER TABLE release_instructions ADD CONSTRAINT fk_release_instructions_finalized_by FOREIGN KEY (finalized_by) REFERENCES users(id) ON DELETE RESTRICT;`
  - Unique index `uix_release_instructions_final_observation` on `(final_settlement_observation_id) WHERE final_settlement_observation_id IS NOT NULL;` (one observation finalizes at most one release).
  - Index `idx_release_instructions_final_observation` for foreign key navigation.
  - Check constraint `chk_release_instructions_settled_observation`:
    ```sql
    CHECK (
        (status = 'SETTLED' AND settled_at IS NOT NULL AND final_settlement_observation_id IS NOT NULL AND finalized_by IS NOT NULL)
        OR
        (status != 'SETTLED' AND final_settlement_observation_id IS NULL)
    );
    ```
- **Invariants**:
  - V1 through V33: UNCHANGED.
  - V35: ABSENT.
  - Restrictive foreign key delete rules prevent deletion of observations or users bound to settled releases.

---

## Final Observation Binding
- `ReleaseInstruction` explicitly records:
  - `finalSettlementObservationId`: The UUID of the authoritative trusted `FINAL_SUCCESS` observation.
  - `finalizedBy`: The platform actor (`ADMIN` or `FINANCE`) who invoked the finalizer.
  - `settledAt`: The exact timestamp of internal accounting finalization.
- Hardened domain method `markSettledFromObservation(UUID settlementObservationId, UUID finalizedBy, Instant settledAt)`:
  - Permitted only from legitimate external finality states: `PENDING_SETTLEMENT`, `UNKNOWN_RECON_REQUIRED`, `DISPATCHING`.
  - Rejects transitions from non-finality states: `CREATED`, `AUTHORIZED`, `RESERVED`, `QUEUED`, `CANCELLED`, `FAILED`, `MANUAL_REVIEW`.
  - Unrestricted `markSettled(Instant)` removed/hardened.

---

## Sandbox Finalization Gate
- **Class**: `com.finx.release.finalizer.gate.SandboxSettlementFinalizationGate`
- **Configuration**:
  - `finx.release.sandbox-finalization.enabled` (default: `false` in `application.yml` and `application-test.yml`).
- **Enforcement Rules**:
  - Gate blocks execution if `finx.release.sandbox-finalization.enabled` is `false` or missing.
  - Gate blocks execution if `finx.banking.provider` != `"mock"`.
  - Gate blocks execution if `ReleaseInstruction.provider` != `BankingProviderType.MOCK`.
- **Purpose**:
  - Guarantees that future real bank provider observations cannot be finalized internally until E10/E12 production certification is complete.

---

## Trusted Evidence Rule
- Evidence is obtained exclusively via `SettlementObservationEvidenceReader.getLatestAuthoritativeFinalObservation(releaseInstructionId)`.
- Required status: `AuthoritativeStatus.FINAL_SUCCESS`.
- Any other observation status immediately aborts finalization without side effects:
  - `NO_EVIDENCE` -> BLOCKED.
  - `PENDING` -> BLOCKED.
  - `NOT_FOUND` -> BLOCKED.
  - `UNKNOWN` -> BLOCKED.
  - `FAILED` -> BLOCKED.
  - `REVERSED` -> BLOCKED.
  - `CONFLICT` -> BLOCKED.

---

## Why Authorization Is Not Re-run
- **Core Principle**: External payout has already occurred at the bank.
- Pre-dispatch authorization guards (`ReleaseDecisionEngine.evaluateMilestone`, `ReleaseDecisionExecutionValidator`, `MilestoneApprovalBindingService` currentness, `VerificationEligibilityService` beneficiary currentness, `ReleaseFundingScopeResolver`) are designed to decide *whether money should leave the bank*.
- Once the bank has debited funds and produced trusted `FINAL_SUCCESS`, re-running pre-dispatch checks would risk refusing to reflect bank truth in FinX books, causing an unreconciled orphan debit.
- FinX must record financial reality; post-settlement audits or workflow anomalies are handled separately, never by pretending bank debits did not happen.

---

## Late Dispute Rule
- If a Deal becomes `DISPUTED` or a dispute is opened *after* payout dispatch:
  - Financial finalization is **NOT** blocked.
  - Double-entry journal, escrow deduction, and reservation consumption proceed normally.
  - The Deal status is preserved as `DISPUTED` (it is not overwritten with `COMPLETED`).
  - Safe audit anomaly is recorded.
- Reason: A dispute raised after bank execution affects legal/recovery workflows; it cannot undo an already settled bank debit.

---

## Lock Order
To prevent deadlocks across concurrent platform operations, locks are strictly acquired in canonical order within the single transaction:
1. **Deal**: `dealRepository.findByIdForUpdate(dealId)` (`PESSIMISTIC_WRITE`).
2. **Milestone**: `milestoneRepository.findByIdForUpdate(milestoneId)` (`PESSIMISTIC_WRITE`).
3. **EscrowAccount**: `escrowAccountRepository.findByDealIdForUpdate(dealId)` (`PESSIMISTIC_WRITE`).
4. **ReleaseInstruction**: `releaseInstructionRepository.findByIdForUpdate(releaseInstructionId)` (`PESSIMISTIC_WRITE`).
5. **ReleaseReservation**: `releaseReservationRepository.findByReleaseInstructionIdForUpdate(releaseInstructionId)` (`PESSIMISTIC_WRITE`).
6. **LedgerAccounts**: `ledgerService.postMilestoneSettlement(...)` locks accounts in deterministic alphanumeric order:
   - Account `1010-ESCROW-POOL`
   - Account `2010-ESCROW-LIABILITY`

---

## Accounting
- **Funding Entry (Prior Invariant)**:
  - `DEBIT`: `1010-ESCROW-POOL`
  - `CREDIT`: `2010-ESCROW-LIABILITY`
- **Settlement Entry (E7 Finalization)**:
  - `DEBIT`: `2010-ESCROW-LIABILITY` (amount: `ReleaseInstruction.amount`)
  - `CREDIT`: `1010-ESCROW-POOL` (amount: `ReleaseInstruction.amount`)
- **Metadata**:
  - `transactionType`: `MILESTONE_SETTLEMENT`
  - `referenceId`: `ReleaseInstruction.id` (B06 idempotency uniqueness: `transactionType + referenceId`)
  - `dealId`: `ReleaseInstruction.dealId`
  - `milestoneId`: `ReleaseInstruction.milestoneId`
  - `postedBy`: `currentUser.id`
  - `narration`: Safe audit string containing release instruction ID, observation ID, and UTR (strictly no account numbers).

---

## Vendor Payable
- **NOT USED**: `2020-VENDOR-PAYABLE` is neither debited nor credited in E7.
- Reason: No prior journal entry moved funds into vendor payable; using it would leave escrow liability uncleared.

---

## Bank Nodal
- **NOT USED**: `1020-BANK-NODAL` is neither debited nor credited in E7.
- Reason: FinX escrow accounting pairs `1010-ESCROW-POOL` directly with `2010-ESCROW-LIABILITY`.

---

## Escrow Projection
- **EscrowAccount Balance Update**:
  - `newBalance = currentBalance - ReleaseInstruction.amount` (verified `>= 0`).
  - `totalReleased = currentTotalReleased + ReleaseInstruction.amount`.
  - `totalFunded`: UNCHANGED.
- **EscrowLedger Row Insert**:
  - `transactionType`: `RELEASE`
  - `escrowAccountId`: `escrowAccount.id`
  - `milestoneId`: `releaseInstruction.milestoneId`
  - `amount`: `releaseInstruction.amount`
  - `balanceAfter`: `newBalance`
  - `paymentId`: `null`
  - `paymentInstructionId`: `null`
  - `description`: Safe string (`"Trusted settlement release for ReleaseInstruction <id>"`, zero PII).
- Database unique constraint `(milestone_id, transaction_type)` prevents duplicate RELEASE rows.

---

## Reservation Consumption
- `ReleaseReservation` transition:
  - State: `ACTIVE -> CONSUMED`
  - Method: `reservation.markConsumed(finalizedAt)`
  - Timestamp: exact shared finalization `Instant`.
- Invariant:
  - Never call `markReleased()` (released indicates unconsumed return of funds).
  - Attempting to consume an already `CONSUMED` or `RELEASED` reservation fails closed.

---

## Release Settlement
- Transition:
  - `status = SETTLED`
  - `finalSettlementObservationId = observation.id`
  - `finalizedBy = currentUser.id`
  - `settledAt = finalizedAt`
- Enforced by database constraint `chk_release_instructions_settled_observation`.

---

## Milestone Completion
- Milestone transition:
  - `status = APPROVED -> COMPLETED`
  - `completedAt = finalizedAt`
- Post-Dispatch Amount Mutation Handling:
  - If `Milestone.amount` was altered after dispatch, financial accounting strictly settles `ReleaseInstruction.amount`.
  - A safe audit anomaly `MILESTONE_CHANGED_AFTER_DISPATCH` is logged without corrupting financial amounts.
- Post-Dispatch State Anomaly:
  - If the milestone was moved to an unexpected state, financial finalization completes and safe anomaly `MILESTONE_STATE_ANOMALY_AFTER_SETTLEMENT` is recorded.

---

## Deal Completion
- After milestone completion, all milestones for the deal are inspected.
- If all milestones are `COMPLETED`:
  - If current Deal status is compatible (`ACTIVE` / `IN_PROGRESS`): `Deal.status = COMPLETED`.
  - If Deal is `DISPUTED`, `CANCELLED`, or `REJECTED`: Deal status is **preserved**. It is never overwritten with `COMPLETED`.

---

## Atomicity
- The entire process executes inside a single `@Transactional` method: `TrustedSettlementFinalizer.finalizeRelease(...)`.
- Zero intermediate commits.
- Zero network calls.
- One local PostgreSQL transaction.

---

## Rollback
- Any failure during:
  - Journal entry creation
  - Escrow account balance deduction
  - Escrow ledger row insertion
  - Reservation consumption
  - Release instruction update
  - Milestone completion
- Rolls back the entire database transaction cleanly.
- Verified in `PostgresTrustedSettlementFinalizerTest.testRollbackLeavesNoPartialWrites()`:
  - 0 journal entries created.
  - 0 escrow ledger rows created.
  - Escrow balance completely unchanged.
  - Reservation remains `ACTIVE`.
  - Release instruction remains `PENDING_SETTLEMENT`.
  - Milestone remains `APPROVED`.

---

## Idempotency
- If `finalizeRelease` is invoked for an already `SETTLED` instruction:
  - The finalizer validates that the historical settlement binds to the same `finalSettlementObservationId`.
  - It verifies consistency across all financial projections:
    - Journal entry exists and matches amount, currency, deal, and milestone.
    - Escrow ledger `RELEASE` entry exists and matches.
    - Reservation is `CONSUMED`.
  - It returns the existing `SettlementFinalizationResult` with `idempotentReplay = true`.
  - **Zero additional journal entries posted**.
  - **Zero additional escrow ledger rows written**.
  - **Zero change to escrow balance or totalReleased**.
  - **Zero reservation status mutations**.
- Conflicting settlement identities fail closed (`FINALIZATION_INTEGRITY_ERROR`).

---

## Concurrent Finalization
- Two concurrent finalization requests for the same release instruction:
  - Serialized by `Deal -> Milestone -> EscrowAccount -> ReleaseInstruction` row locks.
  - First caller acquires lock, executes finalization, and commits.
  - Second caller acquires lock, detects `SETTLED` state with matching observation, and executes idempotent replay.
  - Net result: exactly 1 journal entry, exactly 1 escrow release row, exactly 1 reservation consumption, zero deadlocks.
- Two concurrent finalization requests for different milestones sharing the same escrow account:
  - Serialized by the shared `EscrowAccount` row lock.
  - Both finalize sequentially without balance race conditions.

---

## Reversal Boundary
- E7 implements settlement finalization only.
- If a later observation is `REVERSED` or reader returns `CONFLICT`:
  - E7 does **NOT** automatically reverse journal entries.
  - E7 does **NOT** refund escrow balances.
  - E7 does **NOT** unconsume reservations.
  - E7 returns/throws `REVERSAL_RECOVERY_REQUIRED` for specialized E8/E9 compensation workflows.

---

## SettlementInstruction
- `SettlementInstruction` entities created in E7: **0**.
- Legacy settlement projection table remains untouched.

---

## Bank Calls
- Banking provider calls in E7: **0**.
- `initiateSettlement` calls: **0**.
- `getSettlementStatus` calls: **0**.
- `lookupSettlementByOperationKey` calls: **0**.
- `BankingProvider` dependency in `TrustedSettlementFinalizer`: **NONE**.

---

## PostgreSQL
- **Schema Test**: `PostgresTrustedSettlementFinalizationSchemaTest` (5/5 PASS).
  - V34 applied cleanly via Flyway.
  - Foreign key constraints on observation and user enforced with `ON DELETE RESTRICT`.
  - Unique constraint on `final_settlement_observation_id` verified.
  - Database check constraint `chk_release_instructions_settled_observation` verified.
- **Integration Test**: `PostgresTrustedSettlementFinalizerTest` (6/6 PASS).
  - Happy path finalization verified.
  - Idempotent replay certified.
  - Rollback atomicity certified.
  - Post-dispatch dispute non-blocking behavior verified.
  - Post-dispatch milestone amount mutation snapshot-payout verified.
  - Reversal boundary verified.
- **Database**: PostgreSQL 16 on `localhost:5432` (`finx_release_e7_test`).
- **Skipped Tests**: **0**.

---

## Flyway
- Migration suite: `V1` -> `V34` PASS (`FlywayCleanMigrationTest`).
- V1 through V33: UNCHANGED.
- V35: ABSENT.

---

## Production Payout
- Production money movement remains: **STRICTLY DISABLED**.
- `finx.release.sandbox-finalization.enabled` defaults to `false`.

---

## P0-6
- **Status**: **CLOSED AT SANDBOX FINALIZATION LAYER**.
- Trusted settlement observation converts atomically to double-entry ledger, escrow balance deduction, escrow release projection, reservation consumption, release instruction settlement, and milestone completion.

---

## Ready For E8
- **YES**. The platform is fully prepared for Phase E8 — Deterministic Reconciliation Closed-Loop Integration.
