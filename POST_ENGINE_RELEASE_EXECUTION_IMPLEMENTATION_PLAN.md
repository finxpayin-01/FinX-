# FinX Post-Engine Release Execution Implementation Plan

## Overview
This document outlines the strict 12-phase engineering roadmap (E1 through E12) for safely building, testing, and operationalizing the post-engine release execution architecture.

```
+---------------------------------------------------------------------------------------+
|                               IMPLEMENTATION ROADMAP                                   |
|                                                                                       |
|  [E1: Release & Reservation DB Foundation] (V29)                                      |
|       |                                                                               |
|       v                                                                               |
|  [E1.5: Durable Milestone Approval Binding] (V30)                                     |
|       |                                                                               |
|       v                                                                               |
|  [E2: Authorization & Atomic Reservation] (No migration)                              |
|       |                                                                               |
|       v                                                                               |
|  [E3: Transactional Outbox Schema & Enqueue] (V31)                                     |
|       |                                                                               |
|       v                                                                               |
|  [E4: Worker Framework & Execution Attempt Model] (V32)                                |
|       |                                                                               |
|       v                                                                               |
|  [E5: Mock Provider Sandbox Dispatch]                                                 |
|       |                                                                               |
|       v                                                                               |
|  [E6: Observation Inbox & Polling Recovery] (V33)                                      |
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

### Phase E1 — ReleaseInstruction & Reservation Persistence Foundation [COMPLETE]
- **Scope**: Define domain models, database schema, and JPA repositories for `ReleaseInstruction` and `ReleaseReservation`.
- **Status**: **COMPLETE** (Commit `7fcac79`).
- **Flyway Migration**: `V29__create_release_instructions_and_reservations.sql`.
- **Classes**:
  - `com.finx.release.entity.ReleaseInstruction`
  - `com.finx.release.entity.ReleaseReservation`
  - `com.finx.release.repository.ReleaseInstructionRepository`
  - `com.finx.release.repository.ReleaseReservationRepository`
- **Tests**: `ReleasePersistenceEntityMappingTest`, `ReleasePersistenceFoundationArchitectureTest`, `ReleaseRepositoryTest`, `PostgresReleasePersistenceFoundationTest`.
- **Financial Side Effects Allowed**: None.
- **Bank Calls Allowed**: None.
- **Acceptance Criteria**: Entity constraints, foreign keys to `engine_decisions` and `escrow_accounts`, and partial unique indexes verified in PostgreSQL.
- **Commit Message**: `feat(release): create release instruction and reservation persistence foundation`

---

### Phase E1.5 — Durable Milestone Approval Binding [COMPLETE]
- **Scope**: Create immutable, durable evidence of milestone approval events (`MilestoneApprovalBinding`) with deterministic SHA-256 fingerprints, transactional persistence in `MilestoneService`, and enable legitimate `ALLOW` decisions in `ApprovalDecisionAdapter`.
- **Status**: **COMPLETE** (Commit `5db581d`).
- **Flyway Migration**: `V30__create_milestone_approval_bindings.sql`.
- **Classes**:
  - `com.finx.approval.entity.MilestoneApprovalAuthority`
  - `com.finx.approval.entity.MilestoneApprovalBinding`
  - `com.finx.approval.repository.MilestoneApprovalBindingRepository`
  - `com.finx.approval.dto.ApprovalBindingEvaluation`
  - `com.finx.approval.service.MilestoneApprovalFingerprintService`
  - `com.finx.approval.service.MilestoneApprovalBindingService`
- **Tests**: `MilestoneApprovalBindingServiceTest`, `MilestoneApprovalBindingIntegrationTest`, `ApprovalDecisionAdapterTest`, `PostgresMilestoneApprovalBindingTest`.
- **Financial Side Effects Allowed**: None.
- **Bank Calls Allowed**: None.
- **Acceptance Criteria**: Immutable bindings with SHA-256 fingerprints, canonical `Deal -> Milestone` lock order, `ApprovalDecisionAdapter` allows only when durable binding is present and current, zero bank calls or financial side effects.
- **Commit Message**: `feat(approval): add durable milestone approval binding`

---

### Phase E2 — Release Authorization & Atomic Reservation Transaction [COMPLETE]
- **Scope**: Implement `ReleaseInstructionService`, `ReleaseDecisionExecutionValidator`, `ReleaseFundingScopeResolver`, and `ReleaseAuthorizationService` implementing TX1 and TX2.
- **Status**: **COMPLETE**.
- **Flyway Migration**: None (V30 is certified and sufficient; no V31 created).
- **Classes**:
  - `com.finx.release.command.CreateReleaseInstructionCommand`
  - `com.finx.release.service.ReleaseInstructionRequestFingerprint`
  - `com.finx.release.service.ReleaseFundingScopeResolver`
  - `com.finx.release.service.ReleaseDecisionExecutionValidator`
  - `com.finx.release.service.ReleaseInstructionService`
  - `com.finx.release.service.EscrowReservationService`
  - `com.finx.release.service.ReleaseAuthorizationService`
- **Tests**: `ReleaseFundingScopeResolverTest`, `ReleaseDecisionExecutionValidatorTest`, `ReleaseInstructionServiceTest`, `ReleaseAuthorizationServiceTest`, `ReleaseExecutionE2ArchitectureTest`, `ReleaseInstructionLifecycleTest`, `PostgresReleaseAuthorizationConcurrencyTest`.
- **Financial Side Effects Allowed**: Internal reservation claims only (zero ledger postings, zero escrow balance deductions, zero bank calls).
- **Bank Calls Allowed**: None (0 calls; legacy B04 containment remains fail-closed).
- **Acceptance Criteria**: Strict canonical pessimistic lock order (`Deal -> Milestone -> EscrowAccount -> ReleaseInstruction`), full invariant recheck, escrow availability calculation under row lock preventing oversubscription, atomic `CREATED -> AUTHORIZED -> RESERVED` transition.
- **Commit Message**: `feat(release): implement authorization and atomic fund reservation`

---

### Phase E3 — Transactional Outbox Schema & Enqueueing [COMPLETE]
- **Scope**: Implement `financial_outbox` table and atomic enqueueing mechanism during TX2 (`CREATED -> QUEUED` with `ACTIVE` reservation and `PENDING` outbox).
- **Status**: **COMPLETE**.
- **Flyway Migration**: `V31__create_financial_outbox.sql`.
- **Classes**:
  - `com.finx.outbox.model.FinancialOutboxEventType`
  - `com.finx.outbox.model.FinancialOutboxStatus`
  - `com.finx.outbox.model.FinancialOperationKey`
  - `com.finx.outbox.entity.FinancialOutboxMessage`
  - `com.finx.outbox.repository.FinancialOutboxRepository`
  - `com.finx.outbox.service.FinancialOutboxService`
  - `com.finx.release.service.ReleaseAuthorizationService` (atomic enqueue integration)
  - `com.finx.release.entity.ReleaseInstruction` (`markQueued`)
- **Tests**: `FinancialOutboxEntityTest`, `FinancialOutboxServiceTest`, `FinancialOutboxArchitectureTest`, `ReleaseExecutionE3ArchitectureTest`, `ReleaseStartupSideEffectTest`, `PostgresFinancialOutboxSchemaTest`, `PostgresOutboxTransactionalAtomicityTest`, `PostgresReleaseAuthorizationConcurrencyTest`.
- **Financial Side Effects Allowed**: Local outbox command persistence only (`PENDING`). Zero ledger writes, zero escrow balance deductions, zero settlement instructions.
- **Bank Calls Allowed**: None (0 calls; banking provider dependencies strictly forbidden).
- **Acceptance Criteria**: Rollback of outbox enqueue rolls back authorization and reservation atomically; identifier-only payload (no raw banking payload, credentials, or secrets); unique constraints prevent duplicate commands; legacy RESERVED safe upgrade and QUEUED idempotent replay verified in real PostgreSQL.
- **Commit Message**: `feat(outbox): implement transactional financial outbox`

---

### Phase E4 — Worker Leasing Framework & Execution Attempt Model [COMPLETE]
- **Scope**: Build PostgreSQL worker leasing engine (`SELECT ... FOR UPDATE SKIP LOCKED`), pre-dispatch lease lifecycle/recovery, and durable `SettlementExecutionAttempt` tracking with stable provider operation identity.
- **Status**: **COMPLETE**.
- **Flyway Migration**: `V32__create_settlement_execution_attempts.sql`.
- **Classes**:
  - `com.finx.outbox.service.FinancialOutboxLeaseService`
  - `com.finx.outbox.worker.FinancialOutboxWorker`
  - `com.finx.release.execution.entity.SettlementExecutionAttempt`
  - `com.finx.release.execution.model.SettlementExecutionAttemptStatus`
  - `com.finx.release.execution.model.ProviderSettlementOperationKey`
  - `com.finx.release.execution.model.PreparedSettlementWorkItem`
  - `com.finx.release.execution.repository.SettlementExecutionAttemptRepository`
  - `com.finx.release.execution.service.SettlementExecutionAttemptService`
- **Tests**: `ProviderSettlementOperationKeyTest`, `SettlementExecutionAttemptEntityTest`, `SettlementExecutionAttemptServiceTest`, `FinancialOutboxLeaseServiceTest`, `FinancialOutboxWorkerTest`, `ReleaseExecutionE4ArchitectureTest`, `PostgresSettlementExecutionAttemptSchemaTest`, `PostgresOutboxWorkerLeasingConcurrencyTest`.
- **Financial Side Effects Allowed**: None. Outbox leasing (`PENDING/RETRY -> LEASED`) and attempt preparation (`PREPARED`) only. Zero ledger writes, zero escrow balance deductions, zero settlement instructions.
- **Bank Calls Allowed**: None (0 bank calls; zero BankingProvider dependencies in worker/lease/attempt services).
- **Acceptance Criteria**: Parallel worker threads claim non-overlapping job partitions using `SELECT ... FOR UPDATE SKIP LOCKED`; durable PREPARED execution attempts created; stable deterministic provider operation key `FINX-REL-{id}`; network-started attempts strictly blocked from retry recovery; ReleaseInstruction remains QUEUED; ReleaseReservation remains ACTIVE.
- **Commit Message**: `feat(worker): build outbox leasing and execution attempt framework`

---

### Phase E5 — Mock Banking Provider Sandbox Dispatch & Unknown-Outcome Safety [COMPLETE]
- **Scope**: Explicit sandbox-gated dispatch orchestration, out-of-transaction HTTP/mock boundary, ephemeral in-memory PII decryption, idempotent `MockBankingProvider` simulation, two-phase dispatch transactions (TX-D1 and TX-D2), and unknown-outcome safety with automatic retry blocking.
- **Status**: **COMPLETE**.
- **Flyway Migration**: **NONE** (V32 remains highest migration; V33 strictly reserved for E6).
- **Classes**:
  - `com.finx.release.execution.gate.SandboxSettlementDispatchGate`
  - `com.finx.release.execution.service.SettlementDispatchHandler`
  - `com.finx.release.execution.service.SettlementDispatchStateService`
  - `com.finx.release.execution.model.DispatchPreparedContext`
  - `com.finx.release.execution.model.SettlementDispatchResult`
  - `com.finx.banking.provider.mock.MockBankingProvider` (Harded with in-memory request fingerprint idempotency, conflict detection, single debit)
- **Tests**: `SandboxSettlementDispatchGateTest`, `MockBankSettlementIdempotencyTest`, `SettlementExecutionAttemptEntityTest`, `ReleaseInstructionLifecycleTest`, `FinancialOutboxEntityTest`, `SettlementDispatchStateServiceTest`, `SettlementDispatchHandlerTest`, `ReleaseExecutionE5ArchitectureTest`, `ReleaseStartupSideEffectTest`, `PostgresMockSettlementDispatchIntegrationTest`.
- **Financial Side Effects Allowed**: None. Mock `SETTLED` status is treated as synchronous acknowledgement only, NOT financial settlement finality. ReleaseInstruction transitions to `PENDING_SETTLEMENT` (or `UNKNOWN_RECON_REQUIRED` on failure); Outbox transitions to `PROCESSED`; ReleaseReservation strictly remains `ACTIVE`; Milestone remains `APPROVED`; Zero ledger writes; Zero escrow balance deductions; Zero SettlementInstruction writes.
- **Bank Calls Allowed**: MockBankingProvider only in explicit sandbox mode (`finx.release.sandbox-dispatch.enabled=true`, provider `mock`). Real provider calls: 0. Production payout: DISABLED.
- **Acceptance Criteria**: Strict fail-closed sandbox gate; no DB locks held during provider call; ephemeral PII decryption in memory only (never persisted/logged); stable provider operation key (`FINX-REL-{id}`); unknown outcome transitions to `UNKNOWN_RECON_REQUIRED` and blocks blind automated retries; second call to `dispatchPreparedWorkItem` makes 0 additional bank calls; crash recovery blocks retry for network-started rows; 1458/1458 tests passing in full Maven build.
- **Next**: **Phase E6 — Trusted Settlement Observation Inbox & Provider Status Recovery** (Migration: `V33__create_settlement_observations.sql`).
- **Commit Message**: `feat(dispatch): add safe mock settlement dispatch`

---

### Phase E6 — Trusted Settlement Observation Inbox & Provider Status Recovery [COMPLETE]
- **Scope**: Durable immutable trusted settlement observation inbox (`settlement_observations` table V33), authenticated outbound-settlement webhooks with raw-body signature validation and replay defense, read-only status query by providerSettlementId, crash recovery via stable operation-key (`FINX-REL-{id}`) lookup, status normalization, and safe acknowledgement identity binding.
- **Status**: **COMPLETE**.
- **Flyway Migration**: `V33__create_settlement_observations.sql`.
- **Classes**:
  - `com.finx.release.observation.entity.SettlementObservation`
  - `com.finx.release.observation.model.SettlementObservationSource`
  - `com.finx.release.observation.model.SettlementObservationFinality`
  - `com.finx.release.observation.model.SettlementObservationReasonCodes`
  - `com.finx.release.observation.model.SettlementObservationFingerprint`
  - `com.finx.release.observation.repository.SettlementObservationRepository`
  - `com.finx.release.observation.service.SettlementStatusNormalizer`
  - `com.finx.release.observation.service.SettlementObservationService`
  - `com.finx.release.observation.service.SettlementStatusRecoveryService`
  - `com.finx.release.observation.service.SettlementObservationEvidenceReader`
  - `com.finx.release.observation.service.RecordObservationCommand`
  - `com.finx.release.observation.service.SettlementRecoveryResult`
  - `com.finx.banking.BankingProvider` (Extended with default `lookupSettlementByOperationKey`)
  - `com.finx.banking.BankingDtos.SettlementLookupResult`
  - `com.finx.banking.controller.BankingWebhookController` (Extended for settlement events with B02 replay & HMAC)
  - `com.finx.banking.provider.mock.MockBankingProvider` (Hardened against fabricated SETTLED; operation-key lookup implemented)
- **Tests**: `SettlementStatusNormalizerTest`, `SettlementObservationEntityTest`, `SettlementObservationServiceTest`, `MockBankSettlementStatusSafetyTest`, `SettlementStatusRecoveryServiceTest`, `SettlementObservationEvidenceReaderTest`, `BankingWebhookControllerTest`, `ReleaseExecutionE6ArchitectureTest`, `PostgresSettlementObservationSchemaTest`, `PostgresSettlementWebhookObservationTest`, `PostgresSettlementStatusRecoveryTest`, `FlywayCleanMigrationTest`.
- **Financial Side Effects Allowed**: None. E6 observes external truth only. ZERO ledger postings, ZERO escrow mutations, ZERO reservation consumption (`ReleaseReservation` strictly remains `ACTIVE`), ZERO `ReleaseInstruction` transitions to `SETTLED`, ZERO milestone completions, ZERO `SettlementInstruction` writes.
- **Bank Calls Allowed**: Read-only status calls (`getSettlementStatus`, `lookupSettlementByOperationKey`) and webhook signature verification (`verifyWebhookSignature`) only. `initiateSettlement` calls in E6: 0. Production payout: DISABLED.
- **Acceptance Criteria**: Immutable observations with deterministic SHA-256 evidence fingerprint; exact duplicate webhook replay deduplicated; conflicting payload rejected; `MockBankingProvider` returns `NOT_FOUND` (no fake UTR/status) for unknown IDs; crash recovery resolves existing settlement by stable operation key; crash before provider call blocks blind retries; PostgreSQL schema and Flyway V1->V33 verified.
- **Next**: **Phase E7 — Atomic Trusted Settlement Finalizer (Ledger + Escrow + Reservation + Milestone)**. (Do NOT preassign V34 unless E7 audit proves migration required).
- **Commit Message**: `feat(settlement): add trusted observation and recovery`

---

### Phase E7 — Settlement Finalizer (Ledger + Escrow + Reservation + Milestone) [COMPLETE]
- **Scope**: Implement `TrustedSettlementFinalizer` coordinating atomic single-transaction local financial finalization based on trusted authoritative `FINAL_SUCCESS` observations (`V34__bind_release_finalization_to_observation.sql`).
- **Status**: **COMPLETE**.
- **Flyway Migration**: `V34__bind_release_finalization_to_observation.sql`.
- **Classes**:
  - `com.finx.release.finalizer.service.TrustedSettlementFinalizer`
  - `com.finx.release.finalizer.gate.SandboxSettlementFinalizationGate`
  - `com.finx.release.finalizer.model.SettlementFinalizationResult`
  - `com.finx.release.entity.ReleaseInstruction` (Extended with `finalSettlementObservationId`, `finalizedBy`, and hardened `markSettledFromObservation`)
  - `com.finx.release.repository.ReleaseReservationRepository` (Added pessimistic lock `findByReleaseInstructionIdForUpdate`)
  - `com.finx.ledger.service.LedgerService` (Added atomic double-entry `postMilestoneSettlement`)
- **Tests**: `SandboxSettlementFinalizationGateTest`, `ReleaseInstructionLifecycleTest`, `ReleaseExecutionE7ArchitectureTest`, `TrustedSettlementFinalizerTest`, `FlywayCleanMigrationTest`, `PostgresTrustedSettlementFinalizationSchemaTest`, `PostgresTrustedSettlementFinalizerTest`.
- **Financial Side Effects Allowed**: Authorized ledger and escrow state updates. Strict balanced double-entry: `DEBIT 2010-ESCROW-LIABILITY`, `CREDIT 1010-ESCROW-POOL` with transaction type `MILESTONE_SETTLEMENT` and referenceId `releaseInstructionId`. (Account `2020-VENDOR-PAYABLE` and `1020-BANK-NODAL` are NOT touched). Escrow balance decremented by exact release amount; totalReleased incremented; single `RELEASE` row in `escrow_ledger`. Reservation `ACTIVE -> CONSUMED`. ReleaseInstruction `SETTLED`. Milestone `COMPLETED`. Deal completed if all milestones complete and deal not disputed/cancelled/rejected.
- **Bank Calls Allowed**: None (Zero network calls, zero banking provider calls; finalizer reflects already completed external bank truth).
- **Acceptance Criteria**: Strict fail-closed sandbox gate (`finx.release.sandbox-finalization.enabled: false` by default); canonical lock hierarchy (`Deal -> Milestone -> EscrowAccount -> ReleaseInstruction -> ReleaseReservation -> sorted LedgerAccounts`); immutable snapshot identity validation (amount, currency, direction=DEBIT, provider settlement ID, UTR); post-dispatch milestone mutations and disputes do not block accounting of already executed bank payouts; idempotent replay produces 0 additional ledger/escrow writes; atomic rollback leaves zero partial writes; 0 SettlementInstruction entities created; 0 bank calls; 1613 tests passing in full Maven build.
- **Next**: **Phase E8 — Deterministic Reconciliation Closed-Loop Integration**. (Do NOT preallocate V35 unless E8 audit proves required).
- **Commit Message**: `feat(settlement): finalize trusted settlement atomically`

---

### Phase E8 — Reconciliation (R3/R5) Closed-Loop Integration [COMPLETE]
- **Scope**: Reconcile modern settled release instructions (`ReleaseInstruction` + `SettlementObservation` `FINAL_SUCCESS`) against durable bank statement debit lines in `DeterministicReconciliationMatcher` V2 via `RELEASE_INSTRUCTION` effect type. Add read-only `ReleaseReconciliationEvidenceReader` for authoritative closed-loop audit status.
- **Status**: **COMPLETE**.
- **Flyway Migration**: `V35__add_release_instruction_reconciliation_effect.sql` (Drops and recreates `chk_recon_items_internal_effect_type` to allow `PAYMENT_TRANSACTION`, `SETTLEMENT_INSTRUCTION`, `RELEASE_INSTRUCTION`, and `NULL`).
- **Classes**:
  - `com.finx.reconciliation.matcher.DeterministicReconciliationMatcher` (Upgraded to `FINX_RECONCILIATION_MATCHER_V2`, modern outbound candidate matching, lineage validation, UTR resolution priority, V1/V2 funding compatibility)
  - `com.finx.reconciliation.model.ReconciliationEffectType` (Added `RELEASE_INSTRUCTION`)
  - `com.finx.reconciliation.model.ReconciliationReasonCode` (Added `UTR_MISMATCH`, `REFERENCE_MISMATCH`)
  - `com.finx.release.repository.ReleaseInstructionRepository` (Added bounded native query `findOutboundReconciliationCandidates` joining `release_instructions` to `settlement_observations` on `ri.final_settlement_observation_id = so.id` within observation window)
  - `com.finx.reconciliation.model.ReleaseReconciliationStatus`
  - `com.finx.reconciliation.dto.ReleaseReconciliationResult`
  - `com.finx.reconciliation.service.ReleaseReconciliationEvidenceReader` (Read-only authoritative closed-loop evidence verification)
  - `com.finx.reconciliation.adapter.ReconciliationDecisionAdapter` (Updated to accept both V1 and V2 authoritative funding runs)
- **Tests**: `ReleaseOutboundReconciliationCandidateTest`, `DeterministicReleaseReconciliationMatcherTest`, `ReleaseReconciliationEvidenceReaderTest`, `PostgresReleaseReconciliationEffectSchemaTest`, `PostgresReleaseClosedLoopReconciliationTest`, `PostgresMockReleaseClosedLoopTest`, `FlywayCleanMigrationTest`, `ReconciliationDecisionAdapterTest`, `DeterministicReconciliationMatcherTest`, `PostgresDeterministicReconciliationMatcherTest`.
- **Financial Side Effects Allowed**: None. 0 mutations to ReleaseInstruction, ReleaseReservation, EscrowAccount, EscrowLedger, JournalEntry, JournalLine, Milestone, or Deal. 0 `SettlementInstruction` entities created.
- **Bank Calls Allowed**: None (0 network calls, 0 initiateSettlement calls). Production payout remains disabled. Legacy B04 gate remains active.
- **Acceptance Criteria**: Settled releases matched with exact 1-to-1 precision against statement debits via UTR -> providerSettlementId -> providerOperationKey; ambiguous references and duplicate claims detected; UTR mismatches flagged; observation reversals flagged as REVERSAL_RECOVERY_REQUIRED; V1/V2 funding backward compatibility maintained; full PostgreSQL schema and Flyway V1->V35 verified; V36 absent.
- **Next**: **Phase E9 — Concurrency, Crash Recovery & Adversarial Testing**. (Do NOT preallocate V36 unless E9 proves a schema requirement).
- **Commit Message**: `feat(reconciliation): close release settlement reconciliation loop`

---

### Phase E9 — Concurrency, Crash Recovery & Adversarial Testing [COMPLETE]
- **Scope**: Comprehensive adversarial, concurrency, and chaos test suite under real PostgreSQL 16 (`finx_postgres` container on 5432, database `finx_release_e9_test`).
- **Status**: **COMPLETE**.
- **Flyway Migration**: None (Highest Flyway migration is `V35__add_release_instruction_reconciliation_effect.sql`; `V36` is strictly ABSENT).
- **Test Suites (11 Dedicated E9 Test Classes)**:
  - `com.finx.release.ReleaseLifecycleEscapeHatchArchitectureTest` (7 tests PASS)
  - `com.finx.release.ReleaseExecutionE9ArchitectureTest` (9 tests PASS)
  - `com.finx.release.ConcurrentAuthorizationConflictTest` (4 tests PASS)
  - `com.finx.outbox.WorkerClaimStormAndCrashRecoveryTest` (3 tests PASS)
  - `com.finx.release.WorkerKilledMidHttpCallRecoveryTest` (5 tests PASS)
  - `com.finx.release.DisputeRaisedDuringDispatchRaceTest` (2 tests PASS)
  - `com.finx.release.DuplicateWebhookAndPollRaceTest` (4 tests PASS)
  - `com.finx.release.finalizer.FinalizationConcurrencyAndRollbackMatrixTest` (5 tests PASS)
  - `com.finx.release.PostgresReleaseExecutionChaosCertificationTest` (1 test with 50 deterministic chaos loop iterations PASS)
  - `com.finx.release.ReleaseSecurityAndConstraintTest` (6 tests PASS)
  - `com.finx.reconciliation.ReconciliationMismatchAlertTest` (6 tests PASS)
- **Financial Side Effects Allowed**: None outside controlled test isolation. Zero double-spend, zero double-debit, zero double-reservation, zero double-release, zero ghost ledger postings.
- **Bank Calls Allowed**: `MockBankingProvider` sandbox simulation only. Real production bank adapters: 0. Production payout: DISABLED. Legacy B04 fail-closed gate: ACTIVE.
- **Acceptance Criteria**: 3 consecutive successful runs of critical 34-test concurrency suite (102/102 PASS, 0 failures, 0 errors); 50 chaos iterations with zero deadlocks and zero invariant violations; strict PostgreSQL foreign key teardown ordering validated; Flyway clean migration V1->V35 verified; V36 absent; full suite regression passing.
- **Next**: **Phase E10 — Production Bank Provider Capability Adapters**.
- **Commit Message**: `test(release): add comprehensive adversarial and crash recovery test suite`

---

### Phase E10 — Production Bank Provider Capability Adapters [COMPLETE]
- **Scope**: Implement production bank provider capability framework, type-safe partner configuration, mTLS WebClient factory, canonical HMAC-SHA256 request signing abstraction, fail-closed `UnsupportedBankApiContract`, capability guards, provider readiness service, and fail-closed production execution dispatch gate.
- **Status**: **COMPLETE**.
- **Flyway Migration**: None (Highest Flyway migration is `V35__add_release_instruction_reconciliation_effect.sql`; `V36` is strictly ABSENT).
- **Classes**:
  - `com.finx.banking.capability.BankingProviderCapability` (10 capabilities)
  - `com.finx.banking.capability.BankingProviderCapabilities`
  - `com.finx.banking.capability.BankingProviderCapabilityGuard`
  - `com.finx.banking.capability.BankingCapabilityNotSupportedException`
  - `com.finx.banking.capability.BankingCapabilityNotConfiguredException`
  - `com.finx.banking.config.BankingPartnerProperties`
  - `com.finx.banking.config.BankingProviderAutoConfiguration`
  - `com.finx.banking.contract.BankApiContract` (SPI)
  - `com.finx.banking.contract.UnsupportedBankApiContract` (Fail-closed default)
  - `com.finx.banking.signing.BankRequestSigner`
  - `com.finx.banking.signing.BankSigningRequest`
  - `com.finx.banking.signing.BankSigningResult`
  - `com.finx.banking.signing.HmacSha256BankRequestSigner`
  - `com.finx.banking.transport.BankingWebClientFactory`
  - `com.finx.banking.transport.BankHttpTransport`
  - `com.finx.banking.transport.BankTransportException` hierarchy
  - `com.finx.banking.provider.partner.PartnerBankingProvider`
  - `com.finx.banking.readiness.BankingProviderReadinessService`
  - `com.finx.release.execution.gate.ProductionSettlementDispatchGate`
- **Tests**:
  - `com.finx.banking.provider.BankingProviderContractTest`
  - `com.finx.banking.provider.mock.MockBankingProviderContractTest`
  - `com.finx.banking.provider.partner.PartnerBankingProviderFailClosedContractTest`
  - `com.finx.banking.signing.BankRequestSignerTest`
  - `com.finx.banking.transport.BankingWebClientFactoryTest`
  - `com.finx.banking.transport.BankHttpTransportTest`
  - `com.finx.release.execution.gate.ProductionSettlementDispatchGateTest`
  - `com.finx.banking.readiness.BankingProviderReadinessServiceTest`
  - `com.finx.banking.security.NoRealCredentialTest`
  - `com.finx.banking.BankingProviderE10ArchitectureTest`
- **Financial Side Effects Allowed**: None. 0 mutations to financial ledger, escrow balances, release instructions, or reservations.
- **Bank Calls Allowed**: None (0 real bank network calls, 0 invented partner endpoints, 0 fabricated payloads).
- **Acceptance Criteria**: Strict capability model with 10 capabilities; `MockBankingProvider` declares genuine capabilities; `PartnerBankingProvider` loads conditionally under `finx.banking.provider=partner` without fallback; mTLS client factory enforces TLS 1.2+ and hostname verification; HMAC-SHA256 canonical signing abstraction; zero credentials logged or persisted; stable `FINX-REL-{id}` identity enforced; automatic settlement POST retry permanently disabled; `ProductionSettlementDispatchGate` fails closed; full test suite passes under real PostgreSQL 16; Flyway clean migration V1->V35 verified; V36 absent.
- **Next**: **Phase E11 — Observability, Metrics & Operational Controls**.
- **Commit Message**: `feat(banking): add production provider capability framework`

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
