# FinX Post-Engine Release Execution Implementation Plan

## Overview
This document outlines the strict 12-phase engineering roadmap (E1 through E12) for safely building, testing, and operationalizing the post-engine release execution architecture.

```
+---------------------------------------------------------------------------------------+
|                               IMPLEMENTATION ROADMAP                                   |
|                                                                                       |
|  [E1: Release & Reservation DB Foundation]                                            |
|       |                                                                               |
|       v                                                                               |
|  [E2: Authorization & Atomic Reservation]                                             |
|       |                                                                               |
|       v                                                                               |
|  [E3: Transactional Outbox Schema & Enqueue]                                          |
|       |                                                                               |
|       v                                                                               |
|  [E4: Worker Framework & Execution Attempt Model]                                     |
|       |                                                                               |
|       v                                                                               |
|  [E5: Mock Provider Sandbox Dispatch]                                                 |
|       |                                                                               |
|       v                                                                               |
|  [E6: Observation Inbox & Polling Recovery]                                           |
|       |                                                                               |
|       v                                                                               |
|  [E7: Atomic Settlement Finalizer (Ledger + Escrow)]                                  |
|       |                                                                               |
|       v                                                                               |
|  [E8: Reconciliation (R3/R5) Closed-Loop Integration]                                 |
|       |                                                                               |
|       v                                                                               |
|  [E9: Concurrency, Crash Recovery & Adversarial Testing]                              |
|       |                                                                               |
|       v                                                                               |
|  [E10: Production Bank Provider Capability Adapters]                                 |
|       |                                                                               |
|       v                                                                               |
|  [E11: Observability, Metrics & Operational Controls]                                 |
|       |                                                                               |
|       v                                                                               |
|  [E12: Final Certification & Controlled Production Gate Enablement]                   |
+---------------------------------------------------------------------------------------+
```

---

## Phase Breakdown

### Phase E1 — ReleaseInstruction & Reservation Persistence Foundation
- **Scope**: Define domain models, database schema, and JPA repositories for `ReleaseInstruction` and `ReleaseReservation`.
- **Flyway Migration**: `V29__create_release_instructions_and_reservations.sql`.
- **Classes**:
  - `com.finx.release.domain.ReleaseInstruction`
  - `com.finx.release.domain.ReleaseReservation`
  - `com.finx.release.repository.ReleaseInstructionRepository`
  - `com.finx.release.repository.ReleaseReservationRepository`
- **Tests**: `ReleaseInstructionPersistenceTest`, `ReleaseReservationPersistenceTest`.
- **Financial Side Effects Allowed**: None.
- **Bank Calls Allowed**: None.
- **Acceptance Criteria**: Entity constraints, foreign keys to `engine_decisions` and `escrow_accounts`, and partial unique indexes verified in PostgreSQL.
- **Commit Message**: `feat(release): create release instruction and reservation persistence foundation`

---

### Phase E2 — Release Authorization & Atomic Reservation Transaction
- **Scope**: Implement `ReleaseInstructionService` and `ReleaseAuthorizationService` implementing TX1 and TX2.
- **Classes**:
  - `com.finx.release.service.ReleaseInstructionService`
  - `com.finx.release.service.ReleaseAuthorizationService`
  - `com.finx.release.service.EscrowReservationService`
- **Tests**: `ReleaseAuthorizationServiceTest`, `EscrowReservationConcurrencyTest`.
- **Financial Side Effects Allowed**: Internal reservation claims only (no ledger postings, no escrow deductions).
- **Bank Calls Allowed**: None.
- **Acceptance Criteria**: Invariant re-check (dispute, beneficiary, decision fingerprint freshness) strictly enforced before acquiring row-level locks on `EscrowAccount`.
- **Commit Message**: `feat(release): implement release authorization and atomic fund reservation`

---

### Phase E3 — Transactional Outbox Schema & Enqueueing
- **Scope**: Implement `financial_outbox` table and enqueueing mechanism during TX2.
- **Flyway Migration**: `V30__create_financial_outbox.sql`.
- **Classes**:
  - `com.finx.outbox.domain.FinancialOutboxMessage`
  - `com.finx.outbox.repository.FinancialOutboxRepository`
  - `com.finx.outbox.service.FinancialOutboxService`
- **Tests**: `FinancialOutboxEnqueueTest`, `OutboxTransactionalAtomicityTest`.
- **Financial Side Effects Allowed**: Outbox persistence only.
- **Bank Calls Allowed**: None.
- **Acceptance Criteria**: Rollback of authorization/reservation rolls back outbox message; sanitized payloads verified.
- **Commit Message**: `feat(outbox): implement transactional financial outbox schema and service`

---

### Phase E4 — Worker Leasing Framework & Execution Attempt Model
- **Scope**: Build PostgreSQL worker leasing engine (`SELECT ... FOR UPDATE SKIP LOCKED`) and `SettlementExecutionAttempt` tracking.
- **Flyway Migration**: `V31__create_settlement_execution_attempts.sql`.
- **Classes**:
  - `com.finx.outbox.worker.FinancialOutboxWorker`
  - `com.finx.release.domain.SettlementExecutionAttempt`
  - `com.finx.release.repository.SettlementExecutionAttemptRepository`
- **Tests**: `OutboxWorkerLeasingConcurrencyTest`, `ExecutionAttemptLoggingTest`.
- **Financial Side Effects Allowed**: None.
- **Bank Calls Allowed**: None (Mock handler returns stubbed lease completion).
- **Acceptance Criteria**: Multiple parallel worker nodes claim non-overlapping job partitions without deadlock.
- **Commit Message**: `feat(worker): build outbox leasing framework and attempt logging`

---

### Phase E5 — Mock Banking Provider Dispatch Sandbox
- **Scope**: Connect `FinancialOutboxWorker` to `MockBankingProvider` for end-to-end sandbox dispatch.
- **Classes**:
  - `com.finx.release.worker.SettlementDispatchHandler`
  - `com.finx.banking.provider.MockBankingProvider` (Harden with simulated network delays/failures).
- **Tests**: `SettlementDispatchHandlerTest`, `MockBankDispatchIntegrationTest`.
- **Financial Side Effects Allowed**: Sandbox test simulations only.
- **Bank Calls Allowed**: MockBankingProvider only.
- **Acceptance Criteria**: Stable provider idempotency key (`FINX-REL-{id}`) verified across retries.
- **Commit Message**: `feat(dispatch): connect outbox worker to mock banking provider dispatch`

---

### Phase E6 — Trusted Settlement Observation Inbox & Polling Recovery
- **Scope**: Implement multi-channel settlement observation via HMAC-verified webhooks and status polling.
- **Flyway Migration**: `V32__create_settlement_observations.sql`.
- **Classes**:
  - `com.finx.webhook.controller.BankingWebhookController` (Hardened)
  - `com.finx.release.service.SettlementObservationService`
  - `com.finx.release.worker.SettlementStatusPollingWorker`
- **Tests**: `BankingWebhookHmacVerificationTest`, `SettlementStatusPollingWorkerTest`.
- **Financial Side Effects Allowed**: Ingestion of verified observation evidence only.
- **Bank Calls Allowed**: Mock / Status query APIs only.
- **Acceptance Criteria**: Webhook signature verification, replay protection, and polling recovery verified under high concurrency.
- **Commit Message**: `feat(observation): implement trusted settlement observation inbox and status poller`

---

### Phase E7 — Settlement Finalizer (Ledger + Escrow + Milestone)
- **Scope**: Implement `SettlementFinalizerService` coordinating TX6 (Ledger posting, Escrow deduction, Reservation consumption, Milestone completion).
- **Classes**:
  - `com.finx.release.service.SettlementFinalizerService`
  - `com.finx.escrow.service.EscrowService` (Integrate hardened finalizer)
  - `com.finx.ledger.service.LedgerService`
- **Tests**: `SettlementFinalizerServiceTest`, `PostgresDoubleEntrySettlementTest`.
- **Financial Side Effects Allowed**: Authorized ledger and escrow state updates.
- **Bank Calls Allowed**: None (Triggered by trusted observations).
- **Acceptance Criteria**: Idempotent finalizer execution; exact GL balanced lines (`DEBIT 2010`, `CREDIT 1020`); milestone marked `COMPLETED` only after trusted finality.
- **Commit Message**: `feat(finalizer): implement atomic double-entry settlement finalizer`

---

### Phase E8 — Reconciliation (R3/R5) Closed-Loop Integration
- **Scope**: Integrate settled instructions with `DeterministicReconciliationMatcher` and statement parsing feeds.
- **Classes**:
  - `com.finx.reconciliation.matcher.DeterministicReconciliationMatcher`
  - `com.finx.reconciliation.service.ReconciliationProcessingService`
- **Tests**: `PostgresOutboundSettlementReconciliationTest`.
- **Financial Side Effects Allowed**: Reconciliation status updates.
- **Bank Calls Allowed**: Statement fetch APIs.
- **Acceptance Criteria**: Outbound settlements automatically matched with 100% precision against nodal debit statement lines.
- **Commit Message**: `feat(reconciliation): integrate outbound settlement matching and statement feedback`

---

### Phase E9 — Concurrency, Crash Recovery & Adversarial Testing
- **Scope**: Comprehensive adversarial and chaos test suite under real PostgreSQL.
- **Tests**:
  - `ConcurrentAuthorizationConflictTest`
  - `DisputeRaisedDuringDispatchRaceTest`
  - `WorkerKilledMidHttpCallRecoveryTest`
  - `DuplicateWebhookAndPollRaceTest`
  - `ReconciliationMismatchAlertTest`
- **Financial Side Effects Allowed**: Test suites only.
- **Bank Calls Allowed**: MockBankingProvider.
- **Acceptance Criteria**: Zero double-spends, zero duplicate finalizations, zero deadlocks across all chaos scenarios.
- **Commit Message**: `test(release): add comprehensive adversarial and crash recovery test suite`

---

### Phase E10 — Production Bank Provider Capability Adapters
- **Scope**: Implement production bank adapters (e.g., Axis/ICICI) conforming to the provider capability matrix.
- **Classes**:
  - `com.finx.banking.provider.ProductionBankingProvider`
  - `com.finx.banking.config.BankingProviderSecurityConfig` (mTLS / Keystore)
- **Tests**: `ProductionBankAdapterContractTest`.
- **Financial Side Effects Allowed**: None (Production credentials secured; sandboxed endpoints only).
- **Bank Calls Allowed**: Bank sandbox test endpoints.
- **Acceptance Criteria**: Mutual TLS, request signing, and response normalization fully validated.
- **Commit Message**: `feat(banking): implement production banking provider adapters and security config`

---

### Phase E11 — Observability, Metrics & Operational Controls
- **Scope**: Micrometer metrics, Prometheus scrapers, Grafana dashboards, and audit log streams for release execution.
- **Classes**:
  - `com.finx.release.metrics.ReleaseExecutionMetrics`
  - `com.finx.release.controller.AdminReleaseManagementController`
- **Tests**: `ReleaseExecutionMetricsTest`.
- **Financial Side Effects Allowed**: None.
- **Bank Calls Allowed**: None.
- **Acceptance Criteria**: Real-time alerts configured for outbox backlog, unknown outcomes, and reconciliation discrepancies.
- **Commit Message**: `feat(observability): add release execution metrics and admin controls`

---

### Phase E12 — Final Certification & Production Gate Enablement
- **Scope**: Full platform end-to-end audit and controlled release gate verification.
- **Safety Rule**: `PRODUCTION_PAYOUT_ENABLED` set to `true` **only** upon passing all readiness criteria and receiving formal executive sign-off.
- **Tests**: `FullPlatformPostgresEndToEndCertificationTest`.
- **Commit Message**: `chore(release): certify post-engine execution platform and enable production gate`
