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
| **P0-1** | **Absence of ReleaseInstruction Entity & Lifecycle** | ReleaseDecisionEngine produces advisory `EngineDecision`. Prior to E2, no entity tracked release intent. | **RESOLVED E1 + E2** (Schema `V29` persistence foundation + `ReleaseInstructionService` deterministic creation with canonical beneficiary and reconciled funding scope resolution; CREATED lifecycle state). |
| **P0-2** | **Absence of Durable Escrow Fund Reservation** | Escrow funds checked synchronously without durable claims; risk of concurrent oversubscription and double-spend. | **RESOLVED E1 + E2** (Schema `V29` reservation persistence + `ReleaseAuthorizationService` atomic TX2 `CREATED -> AUTHORIZED -> RESERVED` with row-level locks on `EscrowAccount`, preventing oversubscription). |
| **P0-3** | **Absence of Transactional Outbox & Worker Leasing** | Transactional financial outbox (`financial_outbox` table V31) and atomic enqueue implemented in TX2 (`CREATED -> QUEUED` with `ACTIVE` reservation and `PENDING` outbox). Worker leasing framework remains in Phase E4. | **CLOSED (Outbox Implemented in E3; Worker scheduled for E4)** |
| **P0-4** | **Legacy Settlement Service Fails-Closed (B04 Gate)** | `SettlementAuthorizationService` throws `AccessDeniedException` and collapses authorization/execution into a single unsafe transaction. | **PARTIALLY REMEDIATED** (Safe durable dispatch intent now exists via E3 transactional outbox; legacy B04 bank execution remains active and fail-closed; worker and execution attempt model scheduled for E4; bank dispatch in E5). |
| **P0-5** | **Absence of Asynchronous Bank Finality Observation** | System has no mechanism to observe asynchronous bank status (webhooks/polling/statements) prior to marking transactions settled. | **OPEN** (Scheduled for Phase E6). |
| **P0-6** | **Absence of Atomic Settlement Finalizer** | No atomic coordinator exists to transition reservation, double-entry GL ledger, escrow balance, and milestone completion upon trusted evidence. | **OPEN** (Scheduled for Phase E7). |

---

## P1 Blockers (Required Before Controlled Pilot Payouts)

| ID | Blocker Description | Current State | Target Resolution Phase |
| :--- | :--- | :--- | :--- |
| **P1-1** | **Webhook Security Hardening (HMAC & Replay Defense)** | Current `WebhookEvent` stores events but lacks raw-body HMAC SHA256 validation and timestamp freshness skew limits. | **Phase E6** |
| **P1-2** | **Unknown Outcome & Crash Recovery Protocol** | If an HTTP call to the bank times out, no automated poller resolves the ambiguous status before retrying. | **Phase E4 & E6** |
| **P1-3** | **Dispute & Beneficiary Race Conditions** | No pre-dispatch check exists to ensure disputes raised after authorization immediately halt outbox dispatch. | **Phase E2 & E5** |
| **P1-4** | **Reconciliation Closed-Loop Feedback** | Outbound settlement instructions are not automatically fed into `DeterministicReconciliationMatcher` against live statement lines. | **Phase E8** |

---

## P2 Blockers (Operational Hardening for Production Scale)

| ID | Blocker Description | Current State | Target Resolution Phase |
| :--- | :--- | :--- | :--- |
| **P2-1** | **Production Provider Adapter (Axis / ICICI / HDFC)** | Only `MockBankingProvider` currently exists; production provider adapters with mTLS and request signing are absent. | **Phase E10** |
| **P2-2** | **Granular Execution Attempt Logging** | Network failures are not logged to a separate `settlement_execution_attempts` table for diagnostics. | **Phase E4** |
| **P2-3** | **Release Execution Metrics & Prometheus Dashboards** | No Micrometer metrics tracking outbox queue depth, lease expiration, or settlement latency. | **Phase E11** |
| **P2-4** | **Admin Release Management UI / API** | Platform Finance team lacks dedicated manual review, hold, and cancel endpoints for stuck release instructions. | **Phase E11** |

---

## P3 Blockers (Post-Pilot Optimizations & Governance)

| ID | Blocker Description | Current State | Target Resolution Phase |
| :--- | :--- | :--- | :--- |
| **P3-1** | **Enterprise Multi-Sig Release Authorization** | System currently supports single-approver (`FINANCE_ADMIN`); multi-signature approval rules for large releases. | **Post-Launch** |
| **P3-2** | **Dynamic Liquidity & Nodal Account Balancing** | Automatic rebalancing between escrow pool sub-accounts and operational bank nodal accounts. | **Post-Launch** |
| **P3-3** | **Automated Dead-Letter Queue Triage Workflows** | Self-healing automated workflows for transient infrastructure failures in the outbox. | **Post-Launch** |
