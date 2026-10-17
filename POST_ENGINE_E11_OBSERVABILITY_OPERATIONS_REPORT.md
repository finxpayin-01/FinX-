# FinX E11 Observability & Operational Controls Report

## Baseline
- **E10 Commit**: `7339e30`
- **Initial Verification**: All E10 and prior baseline suites passing cleanly.
- **Working Tree**: Verified clean before starting E11.

## Migration
- **Migration File**: `V36__add_financial_outbox_admin_hold.sql`
- **Purpose**: Extends `chk_financial_outbox_status` with `HELD` state and updates lifecycle invariants (`chk_financial_outbox_lifecycle`).
- **Invariants**:
  - `status = 'HELD'` requires `locked_at IS NULL`, `locked_by IS NULL`, `processed_at IS NULL`, and `dead_lettered_at IS NULL`.
  - Operation identity, attempt count, and payload remain completely immutable.
- **Flyway Status**: Verified clean execution V1 -> V36; `V37` is ABSENT.

## Metrics
- **Registry**: Prometheus (`io.micrometer:micrometer-registry-prometheus`).
- **Component**: `ReleaseExecutionMetrics` registering low-cardinality Gauges on Micrometer:
  - `finx.release.instructions.total`: Total release instructions.
  - `finx.release.outbox.pending`: Outbox backlog pending dispatch.
  - `finx.release.outbox.retry`: Outbox messages queued for retry.
  - `finx.release.outbox.leased`: Active worker leases.
  - `finx.release.outbox.held`: Pre-dispatch administratively held releases.
  - `finx.release.outbox.dead_letter`: Outbox dead-letter queue count.
  - `finx.release.outbox.oldest_pending_age_seconds`: Age of the oldest pending outbox message.
  - `finx.release.attempts.prepared`: Execution attempts prepared.
  - `finx.release.attempts.sending`: Attempts actively sending to provider.
  - `finx.release.attempts.unknown`: In-flight attempts with unknown outcomes.
  - `finx.release.attempts.acknowledged`: Attempts acknowledged by provider.
  - `finx.release.pending_settlement.count`: Releases waiting for trusted settlement finalization.
  - `finx.release.pending_settlement.oldest_age_seconds`: Age of oldest pending settlement release.
  - `finx.release.settled.count`: Settled release instructions.
  - `finx.release.observations.conflict`: Settlement observations in conflicting state.
  - `finx.release.observations.reversed`: Settlement observations recording provider reversals.
  - `finx.release.reconciliation.review`: Releases flagged for manual reconciliation review.
  - `finx.release.reconciliation.blocked`: Releases blocked from reconciliation.
  - `finx.release.reconciliation.reversal_required`: Reconciliation items requiring reversal recovery.
  - `finx.release.provider.ready_sandbox`: Provider sandbox readiness boolean (0 or 1).
  - `finx.release.provider.ready_production`: Provider production readiness boolean (0 or 1).
  - `finx.release.production_gate.open`: Production settlement dispatch gate status (0 or 1).
- **Metric Cardinality Rule**: Zero high-cardinality tags. No releaseInstructionId, dealId, milestoneId, orgId, userId, bank account numbers, UTRs, or idempotency keys are used in metric tags.

## Prometheus
- **Actuator Endpoint**: `/actuator/prometheus` enabled and active.
- **Access Control**: Protected by Spring Security; requires authenticated `ADMIN` or `FINANCE` role.
- **Safe Exposure**: Only `health`, `info`, `metrics`, and `prometheus` are exposed. Sensitive endpoints (`env`, `configprops`, `heapdump`, `beans`) are strictly disabled.

## Grafana
- **Dashboard Artifact**: `ops/grafana/finx-release-execution-dashboard.json`
- **Panels**:
  - Outbox Status Distribution (Pending, Leased, Retry, Held, Dead Letter).
  - Execution Attempt Lifecycle (Prepared, Sending, Acknowledged, Unknown).
  - Settlement Pipeline Latency (Oldest Outbox Pending Age & Oldest Pending Settlement Age).
  - Anomaly & Incident Monitors (Unknown Outcomes, Observation Conflicts & Reversals).
  - Reconciliation Health (Review, Blocked, Reversal Required).
  - Platform Gate & Readiness Status (Provider Ready Sandbox/Prod, Production Gate Open).
- **Privacy**: No customer PII, bank account data, or entity keys.

## Health Indicator
- **Component**: `ReleaseExecutionHealthIndicator` (`/actuator/health`).
- **Status Reporting**:
  - `UP`: Zero critical backlog conditions.
  - `DEGRADED`: Backlog warnings, stale leases (>300s), stale pending settlements (>900s), dead-letter presence, unknown settlement outcomes, or blocked reconciliation items.
- **Privacy**: Safe status reporting without internal entity IDs or credentials.

## Alert Rules
- **Rule Artifact**: `ops/prometheus/finx-release-alerts.yml`
- **Active Alerts**:
  - `FinxReleaseOutboxBacklogHigh`: Outbox pending backlog exceeds threshold (> 50).
  - `FinxReleaseDeadLetterPresent`: Any dead-letter outbox command detected.
  - `FinxReleaseUnknownOutcomePresent`: Any settlement execution in UNKNOWN status.
  - `FinxReleaseLeaseStale`: Worker lease stuck longer than stale threshold (> 300s).
  - `FinxReleasePendingSettlementStale`: Settlement unfinalized longer than threshold (> 900s).
  - `FinxReleaseReconciliationBlocked`: Reconciliation blocked on financial discrepancies.
  - `FinxReleaseReversalRecoveryRequired`: Bank reversal detected requiring manual recovery.
  - `FinxReleaseProductionGateUnexpectedlyOpen`: Gate evaluates open without certified readiness.

## Admin Operational API
- **Controller**: `AdminReleaseManagementController` (`/api/v1/finance/release-operations/**`).
- **Security**: Strictly restricted to `ADMIN` and `FINANCE` roles via `SecurityConfig`.
- **Endpoints**:
  - `GET /summary`: Safe operational overview with counts, ages, and gate status.
  - `GET /releases/{releaseInstructionId}`: Safe detail view of release state, outbox, and attempt.
  - `GET /unknown-outcomes`: Read-only list of attempts with UNKNOWN settlement outcomes.
  - `GET /dead-letter`: Read-only list of outbox messages in DEAD_LETTER.
  - `GET /stale-settlements`: Read-only list of releases stuck in PENDING_SETTLEMENT.
  - `GET /reconciliation-exceptions`: Read-only list of blocked/review reconciliation items.
  - `GET /provider-readiness`: Inspection of active banking provider capability and readiness.
  - `GET /production-gate`: Current production gate closed status and blocker list.
  - `POST /releases/{id}/hold`: Pre-dispatch administrative hold.
  - `POST /releases/{id}/resume`: Resume pre-dispatch held release.
  - `POST /releases/{id}/cancel`: Cancel pre-dispatch release.
- **DTO Safety**: Exposes only high-level status, safe timestamps, reason codes, amounts, and currencies. Never returns raw bank account numbers, encrypted credentials, or webhook payloads.

## Hold
- **Method**: `ReleaseOperationalControlService.holdPreDispatchRelease(UUID, UserPrincipal, String)`.
- **Preconditions**: Release must be `QUEUED`, Outbox `PENDING` or `RETRY`, Reservation `ACTIVE`, with zero network-started execution attempts.
- **Atomic Transition**:
  - ReleaseInstruction: `QUEUED` -> `MANUAL_REVIEW`.
  - FinancialOutbox: `PENDING`/`RETRY` -> `HELD`.
  - Reservation: Remains `ACTIVE`.
  - Audit: `RELEASE_ADMIN_HOLD`.

## Resume
- **Method**: `ReleaseOperationalControlService.resumeHeldRelease(UUID, UserPrincipal, String)`.
- **Preconditions**: Release must be `MANUAL_REVIEW`, Outbox `HELD`, Reservation `ACTIVE`, with zero network-started execution attempts.
- **Atomic Transition**:
  - ReleaseInstruction: `MANUAL_REVIEW` -> `QUEUED`.
  - FinancialOutbox: `HELD` -> `PENDING` (`availableAt = now`).
  - Reservation: Remains `ACTIVE`.
  - Audit: `RELEASE_ADMIN_RESUME`.

## Cancel
- **Method**: `ReleaseOperationalControlService.cancelPreDispatchRelease(UUID, UserPrincipal, String)`.
- **Preconditions**: Pre-dispatch only (`CREATED`, or `QUEUED`/`MANUAL_REVIEW` before network dispatch).
- **Atomic Transition**:
  - ReleaseInstruction: -> `CANCELLED`.
  - FinancialOutbox: -> `DEAD_LETTER` (error code: `ADMIN_CANCELLED_PRE_DISPATCH`).
  - Reservation: `ACTIVE` -> `RELEASED`.
  - Escrow/Ledger: Unchanged.
  - Audit: `RELEASE_ADMIN_CANCELLED`.

## Network-Started Safety Boundary
- **Enforcement**: Once an attempt transitions to `SENDING`, `ACKNOWLEDGED`, or `UNKNOWN` (or `requestStartedAt != null`), or the release transitions to `DISPATCHING`, `PENDING_SETTLEMENT`, `UNKNOWN_RECON_REQUIRED`, or `SETTLED`:
  - `HOLD`: **BLOCKED** with `IllegalStateException(CRITICAL_SAFETY_VIOLATION)`.
  - `RESUME`: **BLOCKED** with `IllegalStateException(CRITICAL_SAFETY_VIOLATION)`.
  - `CANCEL`: **BLOCKED** with `IllegalStateException(CRITICAL_SAFETY_VIOLATION)`.
- **Rationale**: An external financial obligation or movement may already be pending or completed at the bank. Operational cancellation after network transmission is forbidden to prevent double-spending or reconciliation discrepancies.

## PII Boundary
- Zero customer PII or sensitive bank credentials in logs, metrics, health indicators, or operational DTOs.
- Account numbers masked in all responses; private keys, passwords, client secrets, and HMAC signatures are never logged or exposed.

## Startup Passivity
- Metrics collectors and operational services are entirely passive on startup.
- Zero outbox polling, zero worker claiming, zero provider dispatching, zero reconciliation running, and zero bank calls during Spring container initialization.

## Bank Calls
- **Total Real Bank Calls**: `0` (Zero).
- All interactions operate on local test fixtures or `MockBankingProvider`.

## Financial Side Effects
- **Money Movement**: `0` (Zero).
- Escrow balances, double-entry ledger accounts, and bank accounts are neither debited nor credited by operational hold, resume, or cancel actions.

## PostgreSQL
- **Verification**: Verified on real PostgreSQL 16.15 instance.
- **Suite Results**:
  - `PostgresReleaseOperationalControlTest`: 6 / 6 PASS.
  - `PostgresReleaseExecutionChaosCertificationTest`: PASS.
  - `PostgresMockReleaseClosedLoopTest`: PASS.
  - `PostgresTrustedSettlementFinalizerTest`: PASS.
  - `PostgresReleaseClosedLoopReconciliationTest`: PASS.
- **Skipped**: `0`.

## Flyway
- Verified clean migration `V1` -> `V36` via `FlywayCleanMigrationTest`.
- `V36` successfully validated and applied.
- `V37` is strictly **ABSENT**.

## Production Payout
- **Status**: **DISABLED**.
- `finx.release.production-execution.enabled`: `false`.
- Dispatch gate remains **CLOSED**.

## Ready For E12
- **YES**. Platform is fully observable, operational controls are validated against real PostgreSQL, and ready for E12 final platform certification.
