# FinX Post-Engine Release Execution Architecture

## 1. Certified Preconditions
- **Decision Engine Platform Status**: Certified Complete (Commit `39a5ca0`).
- **Engine Type Coverage**: 9 / 9 verified with real PostgreSQL test suites.
- **Prerequisite Engines (8/8)**: `RISK`, `TRUST`, `MILESTONE_RULES`, `APPROVAL`, `FRAUD`, `DOCUMENT`, `RECONCILIATION`, `DISPUTE`.
- **Top-Level Orchestrator**: `ReleaseDecisionEngine` evaluated and certified.
- **Flyway Migrations**: V1 through V28 cleanly applied; V29 absent.
- **Production Payout Execution**: **DISABLED** (`PRODUCTION_PAYOUT_ENABLED = false`).
- **Side-Effect Boundary**: Zero financial side effects and zero banking provider calls during decision engine evaluation.
- **B04 Containment**: `SettlementAuthorizationService.authorizeAndInitiateSettlement` fails closed with `AccessDeniedException`.

---

## 2. Current Runtime Audit
| Audit Item | Current System Status / Value |
| :--- | :--- |
| **CURRENT RELEASE DECISION** | `ReleaseDecisionEngine.evaluate(...)` returns advisory `ReleaseEvaluationResult` with deterministic `RecordEngineDecisionCommand` (ALLOW / REVIEW / BLOCK). Zero bank calls, zero financial state mutations. |
| **CURRENT PAYOUT EXECUTION ENTRY POINT** | `SettlementAuthorizationService.authorizeAndInitiateSettlement(...)` (Fails closed prior to banking network dispatch). |
| **CURRENT B04 CONTAINMENT** | Active in `SettlementAuthorizationService.java` (Line ~167 throws `AccessDeniedException("Settlement initiation disabled by safety gate B04")`). |
| **CURRENT SettlementInstruction SEMANTICS** | Entity modeling settlement reference, `dealId`, `milestoneId`, `amount`, `currency`, `providerAccountId`, UTR, and status (`PENDING`, `PROCESSING`, `SETTLED`, `FAILED`). |
| **CURRENT SETTLEMENT STATUS ENUM** | `PaymentOrchestrationEnums.SettlementStatus`: `PENDING`, `PROCESSING`, `SETTLED`, `FAILED`. |
| **CURRENT PROVIDER ACCOUNT LINEAGE** | Scoped via `providerAccountId` mapped to `organizationId` and Deal context. |
| **CURRENT BENEFICIARY SNAPSHOT** | Milestone / Deal snapshotting seller/beneficiary banking details; verified against trust/fraud engine decisions. |
| **CURRENT ESCROW BALANCE MODEL** | `EscrowAccount` (`balance`, `total_funded`, `total_released`, `total_disputed`, `currency`, `status`) backed by `EscrowLedger` entries. |
| **CURRENT ESCROW RELEASE FINALIZER** | `EscrowService.releaseEscrowForSettlement(UUID dealId, UUID milestoneId, UUID settlementInstructionId, String utr)` — updates balance, logs ledger, creates journal entry, marks milestone `COMPLETED`. |
| **CURRENT LEDGER SETTLEMENT JOURNAL** | `LedgerService.recordEscrowSettlement(...)` generating double-entry journal lines: `DEBIT 2010-ESCROW-LIABILITY`, `CREDIT 1020-BANK-NODAL` (or `2020-VENDOR-PAYABLE` depending on fee split). |
| **CURRENT OUTBOX** | **ABSENT** (No durable transactional outbox table or polling worker). |
| **CURRENT WEBHOOK INBOX** | `WebhookEvent` table (`provider`, `providerEventId`, `eventType`, `payloadHash`, `processingStatus`) in `BankingWebhookController`. |
| **CURRENT BANK FINALITY MODEL** | MockBankingProvider synchronous acknowledgement; real asynchronous bank finality model is absent. |
| **CURRENT RETRY MODEL** | Manual / none for outbound bank payouts. |
| **CURRENT LEASE/WORKER MODEL** | None for outbound settlement dispatch. |
| **CURRENT DEAD-LETTER MODEL** | None for outbox. |
| **CURRENT CRASH RECOVERY** | Relies on manual investigation if a process dies mid-network call. |
| **CURRENT RELEASE RESERVATION** | **ABSENT** (No durable reservation table to prevent concurrent milestone release dispatch). |
| **CURRENT EXECUTION ATTEMPT HISTORY** | **ABSENT** (No granular attempt log capturing raw transport outcomes). |
| **CURRENT IDEMPOTENCY** | Inbound API idempotency exists; outbound bank idempotency is handled solely by `SettlementInstruction.idempotencyKey`. |

---

## 3. Current Components To Keep
1. **`ReleaseDecisionEngine` & Decision Framework**: Advisory only; strictly produces immutable `EngineDecision(RELEASE, ALLOW/REVIEW/BLOCK)`.
2. **`LedgerService`**: Robust double-entry accounting engine with deterministic account sorting, source effect uniqueness, and strict balance invariants.
3. **`EscrowAccount` & `EscrowLedger`**: Reliable state and audit trail for deal-level escrow holdings.
4. **`DeterministicReconciliationMatcher` & `ReconciliationDecisionAdapter`**: Core R1-R5 reconciliation logic matching bank statement lines against internal transaction records.
5. **`WebhookEvent` & Ingress Deduplication**: Baseline replay protection and payload hashing for inbound provider webhooks.
6. **`SettlementInstruction`**: Retained as the outbound bank settlement intent and reconciliation record.

---

## 4. Components To Deprecate / Replace
1. **`SettlementAuthorizationService.authorizeAndInitiateSettlement`**:
   - *Disposition*: **DEPRECATE / REPLACE**.
   - *Rationale*: Collapses authorization, validation, bank dispatch, and state transition into a single synchronous blocking call. Must be replaced by a decoupled, multi-stage pipeline (`ReleaseInstruction` -> Authorization -> Fund Reservation -> Outbox -> Worker -> Finalizer).
   - *Safety Invariant*: The B04 fail-closed gate must **never** be removed from this legacy method. The new architecture must use dedicated services.
2. **Synchronous Direct Payout Pattern**:
   - Any code path that attempts to invoke `BankingProvider.initiateSettlement` directly in response to an HTTP API call or decision event will be deprecated and eliminated.

---

## 5. Target Architecture

```
+---------------------------------------------------------------------------------------------------+
|                                  RELEASE DECISION PLATFORM                                        |
|  [ReleaseDecisionEngine] ----> Produces EngineDecision (Type: RELEASE, Outcome: ALLOW)           |
|                                (Advisory Only - Zero Side Effects - Zero Bank Calls)              |
+---------------------------------------------------------------------------------------------------+
                                                  |
                                                  v
+---------------------------------------------------------------------------------------------------+
|                                    STAGE 1: RELEASE INSTRUCTION                                   |
|  Create ReleaseInstruction (State: CREATED)                                                       |
|  - Snapshots: releaseDecisionId, fingerprint, milestoneId, dealId, beneficiaryBankAccountId       |
+---------------------------------------------------------------------------------------------------+
                                                  |
                                                  v
+---------------------------------------------------------------------------------------------------+
|                                STAGE 2: AUTHORIZATION & RESERVATION                               |
|  Local PostgreSQL Transaction (TX2):                                                              |
|  1. Platform Finance/Admin Authorizes (State: AUTHORIZED)                                         |
|  2. Re-verifies Invariants (No active dispute, beneficiary unchanged, snapshot fresh)            |
|  3. Claims Durable Fund Reservation on EscrowAccount (State: RESERVED)                            |
|  4. Enqueues Job into Transactional Outbox (financial_outbox)                                      |
+---------------------------------------------------------------------------------------------------+
                                                  |
                                                  v
+---------------------------------------------------------------------------------------------------+
|                                   STAGE 3: DISPATCH WORKER                                        |
|  Async Worker claims outbox job using `SELECT ... FOR UPDATE SKIP LOCKED` (State: LEASED)        |
|  Records SettlementExecutionAttempt (Attempt #N, State: SENDING)                                  |
+---------------------------------------------------------------------------------------------------+
                                                  |
                                                  v
+---------------------------------------------------------------------------------------------------+
|                                STAGE 4: BANK NETWORK DISPATCH                                     |
|  *Executed OUTSIDE any database transaction*                                                      |
|  Invokes BankingProvider.initiateSettlement(SettlementRequest with stable IdempotencyKey)         |
|  Transport Result / HTTP 200 Acknowledgement (State: SUBMITTED / ACKNOWLEDGED)                    |
|  *CRITICAL RULE: HTTP 200 IS NOT SETTLEMENT FINALITY*                                            |
+---------------------------------------------------------------------------------------------------+
                                                  |
                                                  v
+---------------------------------------------------------------------------------------------------+
|                              STAGE 5: TRUSTED SETTLEMENT OBSERVATION                              |
|  Observed via:                                                                                    |
|  - Authenticated Webhook (with HMAC verification, timestamp freshness, replay protection)         |
|  - Authoritative Status Polling Worker (getSettlementStatus)                                      |
|  - Statement Reconciliation (fetchStatementPage / R3 Matcher)                                     |
|  Evidence Captured: Provider Settlement ID, UTR, Amount, Currency, Final Status                   |
+---------------------------------------------------------------------------------------------------+
                                                  |
                                                  v
+---------------------------------------------------------------------------------------------------+
|                                STAGE 6: ATOMIC FINALIZATION                                       |
|  Local PostgreSQL Transaction (TX6):                                                              |
|  1. Consume Fund Reservation                                                                      |
|  2. Post Double-Entry Journal to LedgerService (DEBIT Escrow Liability, CREDIT Bank Nodal)        |
|  3. Deduct EscrowAccount balance & log EscrowLedger                                               |
|  4. Transition ReleaseInstruction -> SETTLED                                                      |
|  5. Transition Milestone -> COMPLETED                                                             |
+---------------------------------------------------------------------------------------------------+
                                                  |
                                                  v
+---------------------------------------------------------------------------------------------------+
|                                  STAGE 7: RECONCILIATION & RECOVERY                               |
|  DeterministicReconciliationMatcher confirms internal debits match bank nodal bank statement.     |
+---------------------------------------------------------------------------------------------------+
```

---

## 6. Decision vs Execution Boundary
- **Separation Principle**: `DECIDE` != `AUTHORIZE` != `RESERVE` != `DISPATCH` != `SETTLE` != `FINALIZE`.
- **The Decision Engine is strictly advisory**: `EngineDecision(RELEASE, ALLOW)` proves compliance and approval readiness, but does **not** grant automatic execution authority, does not hold money, and does not dispatch payment.
- **Execution Requirements**: Real execution requires:
  1. Immutable binding to a fresh `EngineDecision`.
  2. Human / Platform execution authorization.
  3. Durable database fund reservation.
  4. Outbox-based asynchronous dispatch.
  5. Multi-channel trusted settlement observation.
  6. Idempotent double-entry finalization.

---

## 7. ReleaseInstruction Model
- **Concept**: An immutable, safely evolving authorized intent to release a specific milestone amount.
- **Draft Schema Attributes**:
  - `id`: UUID (Primary Key)
  - `organization_id`: UUID (Tenant Isolation)
  - `deal_id`: UUID
  - `milestone_id`: UUID (Unique per active release)
  - `release_decision_id`: UUID (FK to `engine_decisions`)
  - `release_decision_fingerprint`: VARCHAR(64) (Input snapshot hash)
  - `policy_version_id`: VARCHAR(64)
  - `policy_content_hash`: VARCHAR(64)
  - `beneficiary_bank_account_id`: UUID (Immutable snapshot of target bank details)
  - `provider`: VARCHAR(32) (e.g., MOCK, AXIS, ICICI)
  - `provider_account_id`: VARCHAR(64)
  - `amount`: NUMERIC(19,4)
  - `currency`: VARCHAR(3)
  - `status`: VARCHAR(32) (`CREATED`, `AUTHORIZED`, `RESERVED`, `DISPATCHING`, `SUBMITTED`, `PENDING_SETTLEMENT`, `SETTLED`, `FAILED`, `CANCELLED`, `UNKNOWN_RECON_REQUIRED`)
  - `idempotency_key`: VARCHAR(128) (Unique constraint)
  - `request_hash`: VARCHAR(64)
  - `created_by`: UUID
  - `authorized_by`: UUID
  - `created_at`: TIMESTAMP WITH TIME ZONE
  - `authorized_at`: TIMESTAMP WITH TIME ZONE
  - `settled_at`: TIMESTAMP WITH TIME ZONE
  - `version`: BIGINT (Optimistic Lock)

---

## 8. Authorization Model
- **Authority Separation**: Creating a `ReleaseInstruction` does not authorize it.
- **Allowed Roles**: Only users with platform roles `FINANCE_ADMIN` or `SUPER_ADMIN` can transition a `ReleaseInstruction` from `CREATED` to `AUTHORIZED`.
- **Restricted Roles**: Buyer users, seller users, project managers, and automated AI engines cannot authorize bank money movement.
- **Pre-Authorization Invariant Re-Check**:
  - `EngineDecision(RELEASE)` must still be valid and unexpired.
  - Milestone must be in `APPROVED` state.
  - Deal must not have an active `Dispute`.
  - Beneficiary bank account must match the snapshot.

---

## 9. Reservation Model
- **Core Invariant**: Escrow funds must **not** be decremented at authorization time, but must be safely reserved to prevent double-spending or race conditions across concurrent release instructions.
- **Available Balance Formula**:
  $$\text{AVAILABLE\_TO\_RESERVE} = \text{Escrow Funded Balance} - \text{Completed Releases} - \text{Active Reservations}$$
- **Recommended Implementation**: Dedicated `release_reservations` table.
- **Reservation Lifecycle**:
  - `ACTIVE`: Created during TX2 (Authorization & Outbox Enqueue).
  - `CONSUMED`: Atomically transitioned during TX6 (Settlement Finalization).
  - `RELEASED`: Transitioned upon terminal failure or cancellation.
- **PostgreSQL Invariant**: Row-level locking on `EscrowAccount` (`SELECT ... FOR UPDATE`) during reservation creation ensures strict serializability.

---

## 10. Outbox Model
- **Pattern**: Transactional Outbox (`financial_outbox` table).
- **Atomicity**: The `ReleaseInstruction` state update (`RESERVED`), the `release_reservations` insertion, and the `financial_outbox` insertion commit in **one single local PostgreSQL transaction**.
- **Delivery Semantics**: At-least-once internal processing combined with provider-level idempotency to achieve **effectively-once financial side effects**.
- **Payload Design**: Stores only minimal identifiers (`releaseInstructionId`, `operationKey`, `payloadVersion`). Sensitive beneficiary banking information (account numbers, IFSC) is **never** serialized in JSON outbox payloads.

---

## 11. Execution Attempt Model
- **Entity**: `SettlementExecutionAttempt` (or `settlement_execution_attempts` table).
- **Purpose**: Tracks every distinct HTTP network interaction with the banking provider without polluting the root `ReleaseInstruction`.
- **Fields**:
  - `id`: UUID
  - `release_instruction_id`: UUID (FK)
  - `attempt_number`: INT
  - `provider`: VARCHAR(32)
  - `provider_operation_key`: VARCHAR(128)
  - `request_hash`: VARCHAR(64)
  - `provider_settlement_id`: VARCHAR(128)
  - `request_started_at`: TIMESTAMP WITH TIME ZONE
  - `response_received_at`: TIMESTAMP WITH TIME ZONE
  - `transport_outcome`: VARCHAR(32) (`SUCCESS`, `TIMEOUT`, `HTTP_ERROR`, `CONNECTION_REFUSED`)
  - `provider_acknowledgement_status`: VARCHAR(32)
  - `error_code`: VARCHAR(64)
  - `error_message_sanitized`: TEXT (No PII / credentials)
  - `retryable`: BOOLEAN

---

## 12. Provider Idempotency
- **Stable Identity**: The `idempotencyKey` sent to the banking provider is deterministically derived from `ReleaseInstruction.id` (e.g., `FINX-REL-{releaseInstructionId}`).
- **Retry Invariant**: Every retry of a given release instruction **must reuse the exact same provider idempotency key**.
- **Rule**: Never generate a new business operation key for a network retry.

---

## 13. Worker Leasing
- **Mechanism**: PostgreSQL row-level leasing using `SELECT ... FOR UPDATE SKIP LOCKED`.
- **Worker Lock Query**:
  ```sql
  SELECT * FROM financial_outbox
  WHERE status IN ('PENDING', 'RETRY')
    AND available_at <= NOW()
  ORDER BY created_at ASC
  LIMIT :batchSize
  FOR UPDATE SKIP LOCKED;
  ```
- **Lease Attributes**: `locked_by` (worker instance ID), `locked_at`, `lease_expires_at`.
- **Execution Boundary**: Bank network calls occur strictly outside the database transaction that leased the outbox row.

---

## 14. Unknown Outcome Recovery
- **Scenario**: FinX dispatches a settlement request to the bank; the network connection drops or times out before receiving a response.
- **Forbidden Action**: Never retry blindly with a new reference key.
- **Recovery State**: `UNKNOWN_RECON_REQUIRED`.
- **Deterministic Resolution Protocol**:
  1. **Step 1**: Query the provider using `BankingProvider.getSettlementStatus(originalIdempotencyKey)`.
  2. **Step 2**: Inspect authenticated inbound webhook events for matching `providerSettlementId` or idempotency key.
  3. **Step 3**: Reconcile against intraday or end-of-day bank statement feed (`fetchStatementPage`).
  4. **Step 4**: Transition to `SETTLED` (if proven successful) or `RETRY` / `FAILED` (if proven absent/rejected).

---

## 15. Trusted Settlement Observation
- **Observation Channels**:
  1. `WEBHOOK`: Real-time push notification from bank.
  2. `STATUS_POLL`: Scheduled worker query for pending settlements.
  3. `STATEMENT_RECONCILIATION`: Bank nodal account statement match (R3 engine).
- **Trusted Evidence Invariant**: Settlement is considered final if and only if verified evidence contains:
  - Provider identity match.
  - Scoped provider account match.
  - Expected amount and currency match.
  - Direction = `DEBIT`.
  - Authoritative terminal success status.
  - Verified UTR / reference number.

---

## 16. Webhook Security
- **HMAC Signature Verification**: Compute SHA256 HMAC over raw payload bytes using provider secret before parsing JSON.
- **Timestamp Freshness**: Reject webhook payloads with timestamp skew $> 300$ seconds.
- **Idempotent Ingestion**: Unique constraint on `(provider, provider_event_id)` in `webhook_events`.
- **Payload Hash Integrity**: Store SHA256 of payload; reject duplicate event IDs with mismatching hashes (tamper detection).

---

## 17. Polling Recovery
- **Dedicated Poller**: `SettlementStatusPollingWorker`.
- **Eligibility**: Any `ReleaseInstruction` in `SUBMITTED` or `PENDING_SETTLEMENT` status older than 5 minutes.
- **Backoff Strategy**: Exponential backoff with jitter (5m, 15m, 30m, 1h, 2h) up to 24 hours.

---

## 18. Bank Statement Recovery
- **Mechanism**: Integrate with `DeterministicReconciliationMatcher` (R3/R5).
- **Match Criteria**: Bank debit statement line matches `SettlementInstruction` on `(provider, providerAccountId, amount, currency, utr/ref)`.
- **Fallback Action**: If webhook and polling fail, statement reconciliation provides absolute final settlement truth.

---

## 19. Ledger Accounting
- **Double-Entry Principle**: Every monetary movement must balance to zero.
- **Accounting Event Matrix**:
  - **A. Escrow Funding Confirmation**:
    - `DEBIT 1010-ESCROW-POOL` (Cash in Escrow)
    - `CREDIT 2010-ESCROW-LIABILITY` (Obligation to Buyer/Deal)
  - **B. Fund Reservation**:
    - *No General Ledger entry* (Internal balance reservation only; tracked in `release_reservations`).
  - **C. Settlement Initiated / Dispatched**:
    - *No General Ledger cash movement* (Pending external settlement).
  - **D. Settlement Final Success (Payout Finalization)**:
    - `DEBIT 2010-ESCROW-LIABILITY` (Relieve Escrow Liability)
    - `CREDIT 1020-BANK-NODAL` (Nodal Bank Cash Outflow)
    - *(If platform fee applies)*: `CREDIT 4010-FINX-FEE-REVENUE`.
  - **E. Settlement Failure**:
    - *No General Ledger movement*; release internal reservation.
  - **F. Payout Reversal / Chargeback**:
    - `DEBIT 1020-BANK-NODAL` (Reversed Funds Inflow)
    - `CREDIT 2010-ESCROW-LIABILITY` (Restore Escrow Liability).

---

## 20. Escrow Finalization
- **Component**: `EscrowService.releaseEscrowForSettlement` (or dedicated `SettlementFinalizerService`).
- **Preconditions for Finalization**:
  1. Trusted settlement observation verified.
  2. Milestone locked in canonical order.
  3. No active dispute on Deal.
  4. Active fund reservation exists for release instruction.
- **Atomic Operations**:
  - Mutate `EscrowAccount` (`balance = balance - amount`, `total_released = total_released + amount`).
  - Insert `EscrowLedger` record (`DEPOSIT_RELEASE`).
  - Post double-entry journal via `LedgerService`.
  - Transition `release_reservations` state to `CONSUMED`.
  - Transition `ReleaseInstruction` state to `SETTLED`.

---

## 21. Milestone State
- **State Transition Guard**:
  - `APPROVED` -> `COMPLETED` occurs **only upon trusted settlement finality** (TX6).
  - A milestone must **never** be marked `COMPLETED` upon outbox enqueue or provider request acknowledgement.

---

## 22. Dispute Race
- **Risk**: A dispute is raised after a release decision is evaluated or authorized, but before the bank worker dispatches the payout.
- **Execution Guard**: Worker re-checks Deal dispute status (`SELECT has_active_dispute FROM deals WHERE id = :dealId FOR SHARE`) immediately prior to making the external bank call.
- **Action on Dispute**: Transition `ReleaseInstruction` to `HELD_DISPUTE` and abort bank dispatch.

---

## 23. Beneficiary Race
- **Risk**: A seller updates their banking details after release authorization.
- **Execution Guard**: The `ReleaseInstruction` binds to an immutable `beneficiaryBankAccountId` snapshot. At dispatch, worker verifies that the snapshot matches the currently approved beneficiary. If mismatch, the job aborts to `MANUAL_REVIEW`.

---

## 24. Concurrency
- **Multiple Authorizations**: Unique constraint on active `ReleaseInstruction` per `milestone_id`.
- **Multiple Outbox Workers**: Handled by `FOR UPDATE SKIP LOCKED`.
- **Concurrent Webhook & Poll**: Idempotent finalizer using database locks on `ReleaseInstruction` (`SELECT ... FOR UPDATE`); the first to acquire lock transitions state, subsequent calls recognize `SETTLED` and no-op.

---

## 25. Lock Order
- **Global Invariant (B11 Compliance)**: To prevent deadlocks, all execution transactions must acquire locks in the strict canonical order:
  $$\text{Deal} \longrightarrow \text{Milestone} \longrightarrow \text{EscrowAccount} \longrightarrow \text{ReleaseInstruction} \longrightarrow \text{release\_reservations}$$
  $$\longrightarrow \text{Sorted Ledger Accounts (by account number ascending)}$$

---

## 26. Idempotency Matrix
| Operation | Idempotency Key / Mechanism | Scope | Action on Duplicate |
| :--- | :--- | :--- | :--- |
| **Create ReleaseInstruction** | `(milestone_id, release_decision_id)` | Milestone | Return existing instruction |
| **Authorize Release** | `(release_instruction_id, version)` | Instruction | Return current state / no-op |
| **Outbox Enqueue** | `(aggregate_id, event_type, operation_key)` | Outbox | Unique constraint ignores/fails |
| **Provider Dispatch** | `FINX-REL-{releaseInstructionId}` | Banking Provider | Provider returns existing settlement |
| **Webhook Ingestion** | `(provider, provider_event_id)` | Webhook Inbox | Return HTTP 200 / no-op |
| **Settlement Finalization** | `(settlement_instruction_id, utr)` | Finalizer | Return existing journal / no-op |

---

## 27. State Machines

### ReleaseInstruction State Machine
```
[CREATED] ------------> [AUTHORIZED] ------------> [RESERVED] ------------> [QUEUED]
   |                        |                           |                      |
   v                        v                           v                      v
[CANCELLED]             [CANCELLED]                [CANCELLED]            [DISPATCHING]
                                                                               |
                                                                               v
                                                                          [SUBMITTED]
                                                                          /         \
                                                                         v           v
                                                        [PENDING_SETTLEMENT]   [UNKNOWN_RECON]
                                                                 |                    |
                                                                 v                    v
                                                             [SETTLED] <--------------+
                                                                 |
                                                                 v
                                                             [FAILED]
```

### Outbox State Machine
`PENDING` $\longrightarrow$ `LEASED` $\longrightarrow$ `PROCESSED` (or `RETRY` $\longrightarrow$ `DEAD_LETTER`).

### Execution Attempt State Machine
`CREATED` $\longrightarrow$ `SENDING` $\longrightarrow$ `ACKNOWLEDGED` (or `TRANSPORT_FAILED` / `UNKNOWN_TIMEOUT` / `REJECTED`).

---

## 28. Database Schema Plan
- **New Table**: `release_instructions` (Master release intent & authorization).
- **New Table**: `release_reservations` (Durable escrow balance reservation).
- **New Table**: `financial_outbox` (Transactional outbox & worker leasing).
- **New Table**: `settlement_execution_attempts` (Granular network dispatch logs).
- **New Table**: `settlement_observations` (Normalized trusted webhook / poll evidence).

---

## 29. Migration Plan
- **V29**: Create `release_instructions` and `release_reservations` tables with constraints and indexes.
- **V30**: Create `financial_outbox` and `settlement_execution_attempts` tables.
- **V31**: Create `settlement_observations` table and harden `webhook_events`.
- **V32**: Finalize FK relationships, indexes, and performance constraints.

---

## 30. Service/Class Plan
- `ReleaseInstructionService`: Creation, validation, and lifecycle management of release instructions.
- `ReleaseAuthorizationService`: Multi-role authorization and invariant verification.
- `EscrowReservationService`: Atomic balance reservation against `EscrowAccount`.
- `FinancialOutboxService`: Enqueueing, leasing, claiming, and dead-lettering outbox jobs.
- `SettlementDispatchWorker`: Background scheduler leasing outbox jobs and dispatching to `BankingProvider`.
- `SettlementObservationService`: Ingestion and normalization of webhook and poll evidence.
- `SettlementFinalizerService`: Atomic coordinator for Ledger, Escrow, and Milestone finalization.

---

## 31. API Plan
- `POST /api/v1/releases`: Create `ReleaseInstruction` from `EngineDecision`.
- `POST /api/v1/releases/{id}/authorize`: Platform Finance authorization endpoint.
- `POST /api/v1/releases/{id}/cancel`: Cancellation of un-dispatched release instruction.
- `GET /api/v1/releases/{id}`: Detailed status and audit trail.
- *Strict Rule*: No public or seller/buyer API endpoint can trigger immediate bank dispatch.

---

## 32. Worker Plan
- **Scheduler**: `@Scheduled(fixedDelayString = "${finx.outbox.poll-interval:5000}")`.
- **Lease Timeout**: 60 seconds (reclaimable by another worker if unacknowledged).
- **Batch Size**: 20 jobs per poll cycle.

---

## 33. Retry Matrix
| Error Class | Retryable? | Max Attempts | Backoff Strategy | Action on Max Retries |
| :--- | :--- | :--- | :--- | :--- |
| **Network Connect Timeout** | Yes | 5 | Exponential (10s, 30s, 2m, 5m, 15m) | Mark `UNKNOWN_RECON_REQUIRED` |
| **HTTP 502 / 503 / 504** | Yes | 5 | Exponential (10s, 30s, 2m, 5m, 15m) | Mark `UNKNOWN_RECON_REQUIRED` |
| **HTTP 400 Bad Request** | No | 1 | None | Mark `FAILED`, alert Finance |
| **HTTP 401 / 403 Auth** | No | 1 | None | Mark `FAILED`, alert Ops |
| **Provider "Duplicate Key"** | No | 1 | Query Status Endpoint | Reconcile existing settlement |
| **Insufficient Escrow** | No | 1 | None | Abort to `FAILED` |

---

## 34. Failure Matrix
- **Provider Rejection**: Release reservation released; `ReleaseInstruction` set to `FAILED`; milestone remains `APPROVED`.
- **Worker Crash Mid-Network**: Outbox lease expires; recovery poller queries provider with original idempotency key before any re-dispatch.
- **Finalization Crash**: Bank transfer succeeded; local DB crashed before ledger update. Recovery worker replays `SettlementFinalizerService` using durable observation evidence.

---

## 35. Recovery Matrix
- **Startup Recovery**: On service boot, scan for expired outbox leases (`lease_expires_at < NOW()`) and reset to `PENDING`.
- **Unknown Outcome Poller**: Dedicated cron job continuously queries provider status for all instructions in `UNKNOWN_RECON_REQUIRED`.

---

## 36. Security Threat Model
1. **Cross-Tenant IDOR**: Validated by enforcing `organization_id` match across Deal, Milestone, Escrow, and Beneficiary account.
2. **Replay Attacks**: Prevented by unique constraints on `idempotency_key` and SHA256 request payload hashing.
3. **Forged Webhooks**: Prevented by mandatory HMAC SHA256 signature verification and timestamp freshness bounds.
4. **Data Exfiltration**: Beneficiary account numbers and secrets are encrypted in DB and never stored in outbox/audit logs.

---

## 37. Audit Events
- The system emits structured audit logs (`AuditService`) for:
  `RELEASE_INSTRUCTION_CREATED`, `RELEASE_INSTRUCTION_AUTHORIZED`, `ESCROW_RESERVATION_ACQUIRED`, `OUTBOX_JOB_ENQUEUED`, `SETTLEMENT_DISPATCHED`, `PROVIDER_ACKNOWLEDGED`, `SETTLEMENT_OBSERVED_SUCCESS`, `SETTLEMENT_FINALIZED`, `SETTLEMENT_FAILED`, `RESERVATION_RELEASED`.

---

## 38. Metrics / Monitoring
- `finx_release_instruction_status_total{status}`
- `finx_escrow_active_reservations_amount{currency}`
- `finx_outbox_queue_depth`
- `finx_outbox_oldest_job_age_seconds`
- `finx_settlement_dispatch_latency_seconds`
- `finx_settlement_unknown_outcomes_total`
- `finx_reconciliation_mismatches_total`

---

## 39. Testing Strategy
- **Unit Tests**: State machine transitions, amount reservation calculations, HMAC verification.
- **PostgreSQL Integration Tests**: Outbox leasing concurrency, row locking order, transaction rollback on failure.
- **Adversarial / Race Tests**: Concurrent authorization attempts, dispute raised during dispatch, duplicate webhook injection.
- **Disaster Recovery Tests**: Worker killed during HTTP dispatch, recovery from statement feed.

---

## 40. Production Readiness Gates
- [ ] All 12 Implementation Phases (E1 - E12) complete.
- [ ] 100% of real PostgreSQL concurrency and adversarial test suites passing.
- [ ] Production banking provider integration certified with real mutual TLS and webhook verification.
- [ ] Zero P0 and P1 blockers remaining in `POST_ENGINE_RELEASE_EXECUTION_BLOCKERS.md`.
- [ ] Formal sign-off by Platform Security and Lead Financial Architect.
- [ ] `PRODUCTION_PAYOUT_ENABLED` explicitly switched to `true` via environment configuration.
