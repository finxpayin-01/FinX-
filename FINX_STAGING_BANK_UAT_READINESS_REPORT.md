# FinX Staging & Bank UAT Readiness Report

## Certified Build

SHA:
7b2b74193509090b3623427d8a4de992edcaa81d

Branch:
`feature/finx-engine-platform`

Certified Baseline:
- E1: PASS
- E1.5: PASS
- E2: PASS
- E3: PASS
- E4: PASS
- E5: PASS
- E6: PASS
- E7: PASS
- E8: PASS
- E9: PASS
- E10: PASS
- E11: PASS
- E12: PASS
- Web Repair W1 (W1-A, W1-B, W1-C): PASS
- Financial Core Changed: NO

---

## Deployment

- **Backend Target**: Render (`finx-backend`, Docker runtime from `./backend/Dockerfile`), or local staging Docker/JVM.
- **Frontend Target**: Render (`finx-frontend`, Node.js 20.18.0 / Next.js 16 App Router), or local container.
- **Database Target**: Dedicated PostgreSQL 16 (`finx_staging_db` on PostgreSQL 16.15).
- **Redis Target**: Redis 7 (`finx_redis` Alpine on port 6379, `redis://localhost:6379`).
- **Health Endpoints**:
  - `/api/health`: Authoritative application health check (`UP`).
  - `/actuator/health`: Operational monitoring endpoint (`when_authorized`).
- **Deployment Status**: PASS (Staging JVM & container runtime tested on port 8080).

---

## Database

- **Engine**: PostgreSQL 16.15 (Debian 16.15-1.pgdg120+1).
- **Instance**: Dedicated staging database `finx_staging_db`.
- **Connectivity**: Verified via JDBC and native psql client.
- **Total Tables**: 71 relational tables mapped and validated.

---

## Flyway

- **Migration Range**: V1 -> V36 sequential clean application.
- **Highest Migration**: `V36__add_financial_outbox_admin_hold.sql`.
- **Validation**:
  - `flyway_schema_history` table healthy, 36 migrations executed.
  - Checksum mismatch: 0.
  - Unexpected migrations (e.g. V37): 0.
- **Flyway Status**: PASS.

---

## Provider Mode

- **Active Staging Provider**: `MockBankingProvider` (`finx.banking.provider=mock`).
- **Partner Provider**: `PartnerBankingProvider` (`finx.banking.provider=partner`) configured with strict fail-closed validation.
- **Current Mode**:
  ```
  BANK UAT CONFIGURATION: WAITING FOR PARTNER CREDENTIALS
  ```
- **Live Production Payout**: DISABLED (Fail-closed master gate).

---

## Authentication

- **Registration / Login**: Tested on staging endpoints (`/api/auth/register`, `/api/auth/login`).
- **Roles Verified**:
  - `BUYER`: Access granted to buyer workflows; blocked with `403 Forbidden` from `/api/v1/finance/*`.
  - `SELLER`: Access granted to vendor workflows; blocked from administrative endpoints.
  - `ADMIN`: Full access to `/api/v1/finance/summary`, `/api/v1/finance/overview`, `/api/v1/finance/transactions`, `/api/v1/finance/ledger`.
- **Token Format**: Standard RFC 7519 HMAC-SHA256 JWT with refresh token rotation.

---

## KYB/KYC

- **Verification Layer**: `MockVerificationProvider` active in sandbox simulation mode.
- **Org Beneficiary**: Verified bank account binding with IFSC and encrypted account storage.
- **Sandbox KYB Status**: PASS.

---

## Deal / Milestone Flow

- **Deal Creation**: Synthetic deal funded in escrow.
- **Milestone Approval**: Milestone status set to `APPROVED` with cryptographic `MilestoneApprovalBinding` binding approval fingerprint, actor, and deliverables.
- **Flow Status**: PASS.

---

## Decision Engines

- **All 9 Decision Engines Active**:
  1. `RISK`
  2. `TRUST`
  3. `MILESTONE_RULES`
  4. `APPROVAL`
  5. `FRAUD`
  6. `DOCUMENT`
  7. `RECONCILIATION`
  8. `DISPUTE`
  9. `RELEASE`
- **Release Decision Outcome**: `ALLOW`.
- **Invariants Verified**:
  - Engine evaluation produces immutable `EngineDecision` records with SHA-256 fingerprints.
  - Policies bound to versioned database records (`EnginePolicyVersion`).
  - No decision engine can directly invoke banking or move funds.

---

## Release Authorization

- **Authority**: Restricted to `ADMIN` / `FINANCE`.
- **Instruction**: `ReleaseInstruction` created in `PENDING_AUTHORIZATION` and transitioned to `AUTHORIZED`.
- **Containment**: Cannot directly call bank transport; authorization is purely internal state.

---

## Reservation

- **Entity**: `ReleaseReservation` created atomically.
- **Status**: `ACTIVE`.
- **Escrow Integrity**: Protects against oversubscription. Escrow ledger balance is NOT deducted at reservation stage.

---

## Outbox

- **Entity**: `FinancialOutboxMessage`.
- **Event**: `RELEASE_AUTHORIZED`.
- **Atomicity**: Created in the same database transaction as the authorization and reservation.
- **Admin Hold Capability**: Migration V36 provides outbox operational hold capability.

---

## Worker

- **Leasing Mechanism**: `SELECT FOR UPDATE SKIP LOCKED` with durable lease expiry.
- **Lease Concurrency**: Tested under concurrent worker pools; exactly one worker acquires lease per message.

---

## Sandbox Dispatch

- **Handler**: `SettlementDispatchHandler`.
- **Operation Key**: Stable idempotency reference `FINX-REL-{releaseInstructionId}`.
- **Pre-Network Persistence**: `SettlementExecutionAttempt` persisted with `request_started_at` before outbound HTTP call.
- **Dispatch Result**: Provider ACK recorded; ACK is NOT treated as final settlement.

---

## Settlement Observation

- **Observation Ingestion**: Webhook receiver and status recovery worker create immutable `SettlementObservation` records.
- **Signatures**: Requires valid provider signature; rejected when unsigned or signature invalid.
- **Observation Binding**: `ReleaseInstruction` binds `final_settlement_observation_id`.

---

## Finalization

- **Service**: `TrustedSettlementFinalizer`.
- **ReleaseReservation**: Transitioned from `ACTIVE` to `CONSUMED`.
- **ReleaseInstruction**: Transitioned to `SETTLED` with timestamp and UTR.
- **Escrow**: Released exactly once in `EscrowLedger`.
- **Double-Entry Ledger**: Balanced double-entry journal entry posted (`1010-ESCROW-POOL` -> `1020-BANK-NODAL` / `2020-VENDOR-PAYABLE`).
- **Idempotency**: Repeated calls replay the existing result without generating duplicate financial effects.

---

## Reconciliation

- **Matcher**: `DeterministicReconciliationMatcher`.
- **Statement Ingestion**: Ingests durable bank statement lines from provider feed.
- **Matching Criteria**: Strict correlation on provider, providerAccountId, currency, amount, and UTR.
- **Reconciliation Effect**: Release instruction bound to `ReconciliationItem` with `MATCHED` status. Mismatches enter review without mutating ledger.

---

## Finance Dashboard

- **Routes Built**: 63 Next.js pages compiled without error.
- **Endpoints Verified**:
  - `/api/v1/finance/summary`: 200 OK.
  - `/api/v1/finance/overview`: 200 OK.
  - `/api/v1/finance/transactions`: 200 OK.
  - `/api/v1/finance/ledger`: 200 OK.
- **API Paths**: 0 duplicate `/api/api` prefixes.
- **Semantics**: Only authoritative `SETTLED` displays as completed; `PENDING_SETTLEMENT` and `UNKNOWN` are preserved as in-flight / review.

---

## Negative Tests

- **Chaos / Negative Scenarios Verified**:
  - Duplicate release authorization: Blocked.
  - Duplicate outbox dispatch: Idempotent replay; no double payout.
  - Duplicate webhook callback: Idempotent processing.
  - Invalid webhook signature: Rejected with HTTP 401/403.
  - Stale release decision: Re-evaluation required.
  - Changed beneficiary: Fails closed.
  - Insufficient escrow availability: Fails closed.
  - Network timeout / UNKNOWN provider outcome: Retains `UNKNOWN` state pending trusted recovery.
  - Concurrent finalizer calls: Exactly one execution succeeds; subsequent calls return cached result.
  - Reconciliation mismatch: Flagged as `UNMATCHED_AMOUNT` or `UNMATCHED_REFERENCE`.

---

## Security / Secret Handling

- **Log Audit**: Inspected application and test logs.
- **No Secret Leakage**:
  - Bank account numbers: Masked or encrypted.
  - Passwords & secrets: `[CONFIGURED - NEVER LOGGED]`.
  - PAN, GSTIN, JWT secrets, private keys: Absent from logs and API DTO responses.
- **Role Isolation**: Enforced via Spring Security annotations (`@PreAuthorize("hasAnyRole('ADMIN', 'FINANCE')")`).

---

## Production Gate

DISABLED

- `finx.release.production-execution.enabled: false` (Master fail-closed gate).
- `finx.banking.partner.production-execution-enabled: false`.
- `ProductionExecutionStartupValidator`: Verified fail-closed if production execution is requested without full multi-party certification signoff.

---

## Banking Partner Dependencies

### Known FinX Requirements
1. Bank payout API (IMPS / NEFT / RTGS) accepting idempotency keys.
2. Webhook delivery for real-time settlement state transitions (SUCCESS, FAILURE, REVERSED).
3. Webhook HMAC-SHA256 signature scheme with shared secret.
4. Settlement status inquiry API for recovery of UNKNOWN states.
5. End-of-day bank statement feed (CAMT.053, MT940, or JSON/CSV) with UTR for deterministic reconciliation.
6. mTLS authentication (mutual TLS) using client certificates (PKCS12 / JKS).
7. Dedicated escrow / nodal account identifier (`providerAccountId`).

### Bank Must Confirm
1. Formal commercial partnership agreement and escrow/nodal operational authority.
2. Production and sandbox API base URLs, client IDs, and client secrets.
3. IP whitelisting specifications (inbound webhook IPs and outbound NAT IPs).
4. Webhook payload schema and signature header specification.
5. Rate limits and concurrency thresholds per account.
6. Bank UAT test cases and signoff criteria before production enablement.

---

## UAT Blockers

- NONE for Sandbox/Mock UAT (Staging environment is completely verified and operational).
- External commercial onboarding and partner sandbox credentials pending from banking institution.

---

## Ready For Bank UAT

YES (Ready for controlled partner sandbox UAT)
