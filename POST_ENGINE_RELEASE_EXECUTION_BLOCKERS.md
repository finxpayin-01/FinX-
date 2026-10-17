# FinX Post-Engine Release Execution Blocker Audit

This document inventories and categorizes all architectural and implementation blockers that currently prevent safe production money movement (payouts).

```
+---------------------------------------------------------------------------------------+
|                                  BLOCKER SUMMARY MATRIX                                |
|                                                                                       |
|   P0 (Blockers to ANY real payout)         : 6 Critical Items                         |
|   P1 (Required before controlled pilot)    : 4 High-Priority Items                    |
|   P2 (Required for production scale)       : 4 Hardening Items                        |
|   P3 (Post-launch improvements)            : 3 Optimization Items                     |
+---------------------------------------------------------------------------------------+
```

---

## P0 Blockers (Critical — Cannot Safely Execute Real Payout)

| ID | Blocker Description | Current State | Target Resolution Phase |
| :--- | :--- | :--- | :--- |
| **P0-0** | **Absence of Durable Financial Milestone Approval Binding** | Milestone `APPROVED` status alone was nonauthoritative; approval adapter had no `ALLOW` branch (`milestoneUnbound = true` hardcoded), preventing legitimate `ReleaseDecisionEngine` ALLOW decisions. | **RESOLVED E1.5** (Immutable `MilestoneApprovalBinding` table `V30`, SHA-256 fingerprints, canonical `Deal -> Milestone` lock order, and real `ALLOW` adapter branch implemented). |
| **P0-1** | **Absence of ReleaseInstruction Entity & Lifecycle** | ReleaseDecisionEngine produces advisory `EngineDecision`. Full lifecycle from CREATED through QUEUED, DISPATCHING, PENDING_SETTLEMENT, and UNKNOWN_RECON_REQUIRED implemented. | **CLOSED** (V29 persistence in E1, deterministic creation in E2, and dispatch state transitions in E5). |
| **P0-2** | **Absence of Durable Escrow Fund Reservation** | Escrow funds checked synchronously without durable claims; risk of concurrent oversubscription and double-spend. | **CLOSED** (V29 reservation persistence in E1, atomic TX2 authorization in E2; ACTIVE reservation invariant strictly preserved during sandbox dispatch in E5). |
| **P0-3** | **Absence of Transactional Outbox & Worker Leasing** | Transactional financial outbox (`financial_outbox` table V31), atomic enqueue in TX2 (`CREATED -> QUEUED`), and worker leasing framework with `SELECT ... FOR UPDATE SKIP LOCKED` and execution attempt model implemented. | **CLOSED** (Outbox in E3, worker leasing & attempt persistence in E4, processed/dead-letter lifecycle in E5). |
| **P0-4** | **Legacy Settlement Service Fails-Closed (B04 Gate)** | `SettlementAuthorizationService` throws `AccessDeniedException` and collapses authorization/execution into a single unsafe transaction. | **SANDBOX DISPATCH + RECOVERY PATH IMPLEMENTED (Production real-provider path: NOT implemented)**. Mock sandbox dispatch implemented (`SettlementDispatchHandler`, `SettlementDispatchStateService`), stable provider operation identity `FINX-REL-{id}`, pre-dispatch guard revalidation, out-of-transaction network calls, ephemeral PII decryption, unknown-outcome no-retry boundaries, and operation-key status recovery implemented in E6. Real provider dispatch is NOT implemented; production payout remains disabled; legacy B04 containment remains active and fail-closed. |
| **P0-5** | **Absence of Asynchronous Bank Finality Observation** | System has no mechanism to observe asynchronous bank status (webhooks/polling/statements) prior to marking transactions settled. | **CLOSED** — Trusted settlement observation inbox implemented (`V33__create_settlement_observations.sql`), authenticated outbound-settlement webhooks ingested with B02 HMAC and replay protection, read-only status recovery and stable operation-key recovery (`FINX-REL-{id}`) implemented in E6. Closed-loop bank statement reconciliation against durable statement debits closed in E8 (`V35__add_release_instruction_reconciliation_effect.sql`, `DeterministicReconciliationMatcher` V2). |
| **P0-6** | **Absence of Atomic Settlement Finalizer** | No atomic coordinator exists to transition reservation, double-entry GL ledger, escrow balance, and milestone completion upon trusted evidence. | **CLOSED AT SANDBOX FINALIZATION LAYER** — Trusted settlement finalizer implemented (`V34__bind_release_finalization_to_observation.sql`, `TrustedSettlementFinalizer`). Converts authoritative trusted `FINAL_SUCCESS` observation atomically into internal financial truth: canonical lock hierarchy (`Deal -> Milestone -> EscrowAccount -> ReleaseInstruction -> ReleaseReservation -> sorted LedgerAccounts`), balanced double-entry (Dr `2010-ESCROW-LIABILITY`, Cr `1010-ESCROW-POOL`, type `MILESTONE_SETTLEMENT`), escrow balance deduction + single `RELEASE` escrow ledger row, reservation consumption (`ACTIVE -> CONSUMED`), release binding to observation (`SETTLED`), milestone completion (`APPROVED -> COMPLETED`), safe deal completion. Zero bank calls, zero `SettlementInstruction` entities created. Idempotent replay and atomic rollback certified in real PostgreSQL. Production real-bank payout remains disabled (`finx.release.sandbox-finalization.enabled: false`). |

---

## P1 Blockers (Required Before Controlled Pilot Payouts)

| ID | Blocker Description | Current State | Target Resolution Phase |
| :--- | :--- | :--- | :--- |
| **P1-1** | **Webhook Security Hardening (HMAC & Replay Defense)** | Current `WebhookEvent` stores events but lacks raw-body HMAC SHA256 validation and timestamp freshness skew limits. | **RESOLVED E6** (Raw-body HMAC SHA256 signature verification, provider binding, timestamp freshness skew protection, event ID replay protection, and payload hash mismatch defense enforced in `BankingWebhookController`). |
| **P1-2** | **Unknown Outcome & Crash Recovery Protocol** | If an HTTP call to the bank times out, no automated poller resolves the ambiguous status before retrying. | **RESOLVED AT OBSERVATION LAYER E6** (Explicit `SettlementStatusRecoveryService` with `lookupSettlementByOperationKey` and `getSettlementStatus`; safe acknowledgement recovery for crash after provider acceptance; blind retries strictly blocked for crash before provider call). |
| **P1-3** | **Dispute & Beneficiary Race Conditions** | Pre-dispatch guards revalidate deal dispute status and beneficiary account stability prior to provider HTTP call. If dispute is raised post-authorization, outbox dispatch halts and releases remain locked in reservation. | **RESOLVED E5 & CERTIFIED E9** (Pre-dispatch guards validated under real PostgreSQL 16 concurrency in `DisputeRaisedDuringDispatchRaceTest` and `WorkerClaimStormAndCrashRecoveryTest`). |
| **P1-4** | **Reconciliation Closed-Loop Feedback** | Outbound settlement instructions are not automatically fed into `DeterministicReconciliationMatcher` against live statement lines. | **RESOLVED E8** (Closed-loop reconciliation: IMPLEMENTED; modern outbound effect: `RELEASE_INSTRUCTION` under `V35__add_release_instruction_reconciliation_effect.sql`; matcher upgraded to `FINX_RECONCILIATION_MATCHER_V2`; reference priority UTR -> providerSettlementId -> providerOperationKey; `ReleaseReconciliationEvidenceReader` provides read-only closed-loop audit status; legacy `SettlementInstruction` preserved for historical compatibility only; production payout: DISABLED). |

---

## P2 Blockers (Operational Hardening for Production Scale)

| ID | Blocker Description | Current State | Target Resolution Phase |
| :--- | :--- | :--- | :--- |
| **P2-1** | **Production Provider Adapter (Axis / ICICI / HDFC)** | Only `MockBankingProvider` currently exists; production provider adapters with mTLS and request signing are absent. | **Phase E10** |
| **P2-2** | **Granular Execution Attempt Logging** | Table `settlement_execution_attempts` (V32), immutable snapshot fields, attempt ordering, and retry recovery tracking implemented. | **RESOLVED E4** (Migration `V32__create_settlement_execution_attempts.sql`). |
| **P2-3** | **Release Execution Metrics & Prometheus Dashboards** | No Micrometer metrics tracking outbox queue depth, lease expiration, or settlement latency. | **Phase E11** |
| **P2-4** | **Admin Release Management UI / API** | Platform Finance team lacks dedicated manual review, hold, and cancel endpoints for stuck release instructions. | **Phase E11** |

---

## P3 Blockers (Post-Pilot Optimizations & Governance)

| ID | Blocker Description | Current State | Target Resolution Phase |
| :--- | :--- | :--- | :--- |
| **P3-1** | **Enterprise Multi-Sig Release Authorization** | System currently supports single-approver (`FINANCE_ADMIN`); multi-signature approval rules for large releases. | **Post-Launch** |
| **P3-2** | **Dynamic Liquidity & Nodal Account Balancing** | Automatic rebalancing between escrow pool sub-accounts and operational bank nodal accounts. | **Post-Launch** |
| **P3-3** | **Automated Dead-Letter Queue Triage Workflows** | Self-healing automated workflows for transient infrastructure failures in the outbox. | **Post-Launch** |
