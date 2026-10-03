# B05 Remediation Report

## Blocker
B05 — P0 — Idempotency and financial-effect identity are insufficient.

## Root Cause
Prior to remediation:
1. `PaymentInstruction` and `SettlementInstruction` had global, unscoped unique constraints on `idempotency_key` (`uk_payment_instructions_idempotency_key` and `uk_settlement_instructions_idempotency_key`), exposing collisions across separate deals/tenants and allowing potential cross-tenant key exhaustion.
2. Stored idempotent responses did not verify request payload integrity. Replaying the same key with changed payload parameters (e.g. modified amount, milestone, or comments) either silently replayed or misattributed transactions without raising conflicts.
3. Financial commands permitted missing/blank idempotency keys or lacked caller authorization prior to stored replay inspection, risking information disclosure across organizational boundaries.
4. Business effect identity was solely reliant on the client-supplied idempotency key. A caller could generate a new key and trigger duplicate active settlements for the same milestone.
5. Inbound provider settlement credits lacked scoped provider uniqueness. A single bank UTR could theoretically be reused across disparate transactions, or collision could occur if provider namespace was unspecified.
6. Financial transaction references relied on in-memory process-local counters (`AtomicLong INSTRUCTION_SEQ` and `AtomicLong SETTLEMENT_SEQ`), which reset upon application restart, creating potential reference collisions.

## Before
- `PaymentInstruction.idempotencyKey` had a global unique constraint without deal scoping or request hash binding.
- `SettlementInstruction.idempotencyKey` had a global unique constraint. Multiple settlements could be authorized for the same milestone simply by alternating idempotency keys.
- `PaymentTransaction` lacked explicit `provider` scoping on UTRs.
- `AtomicLong` counters generated transaction references (`FINX-TXN-<timestamp>-<seq>`), which could collide after restarts.
- Missing or blank idempotency keys were permitted on financial intake and payout endpoints.
- Replay was evaluated without caller tenant authorization, allowing cross-tenant existence probes.

## Authorization Before Replay
Caller authentication and tenant authorization are strictly evaluated before any stored idempotent transaction is inspected or returned:
1. The caller `UserPrincipal` is validated.
2. The associated `Deal` and `Milestone` records are resolved.
3. `DealAccessService.requireDealAccess(deal, currentUser)` verifies that the caller belongs to the buyer/seller organization or holds platform administrator/finance authority.
4. If unauthorized, access is denied without disclosing whether an idempotency key exists or what financial effect was stored.

## Scoped Idempotency Identity
Idempotency keys are strictly scoped to the financial context:
- **Payment Instructions (Funding):** Scoped by `(deal_id, idempotency_key)` via PostgreSQL unique index `uk_payment_instructions_deal_key`.
- **Settlement Instructions (Payouts):** Scoped by `(deal_id, idempotency_key)` via PostgreSQL unique index `uk_settlement_instructions_deal_key`.
- Cross-deal or cross-tenant key reuses with different deals are strictly isolated and do not collide. Replaying an existing key across different deals triggers a clean conflict without data leakage.

## Request Hash Binding
A deterministic SHA-256 hash is computed over canonical financial request attributes:
- **Payment Funding:** `dealId:milestoneId:amount:currency`
- **Settlement Authorization:** `dealId:milestoneId:amount:currency:comment`
Stored records persist `request_hash` (VARCHAR(64)).
- Same scoped key + same payload: Safe idempotent replay.
- Same scoped key + modified amount/milestone/currency/comment: Throws 409 Conflict (`DuplicateResourceException`) and blocks execution.

## Mandatory Idempotency Key
All financial mutation commands governed by B05 enforce mandatory idempotency keys:
- Null, empty string, or whitespace-only keys are rejected with 400 Bad Request (`BadRequestException("Mandatory idempotency key is missing or blank")`).
- Read-only endpoints (`GET`) do not require idempotency keys.

## Stable Financial References
Process-local `AtomicLong` sequence counters were completely removed from `PaymentOrchestrationService` and `SettlementAuthorizationService`.
Transaction references now use durable, restart-safe, UUID-derived identifiers:
- `PaymentInstruction`: `FINX-TXN-<year>-<12-hex-uuid>`
- `SettlementInstruction`: `FINX-SET-<year>-<12-hex-uuid>`

## Settlement Business-Effect Uniqueness
The client-provided idempotency key is not the sole financial uniqueness gate. Multiple active settlement attempts for the same milestone are strictly prohibited.
- **Active Statuses:** `PENDING`, `PROCESSING`, `SUBMITTED`, `UNKNOWN`, `SETTLED`.
- Flyway migration `V23` creates a partial unique index on PostgreSQL:
  ```sql
  CREATE UNIQUE INDEX uk_settlement_active_milestone
      ON settlement_instructions (milestone_id)
      WHERE status IN ('PENDING', 'PROCESSING', 'SUBMITTED', 'UNKNOWN', 'SETTLED');
  ```
- Any secondary request targeting the same milestone while an active settlement exists is blocked by application-level validation and ultimately rejected by the PostgreSQL database constraint.
- Terminal failed attempts (`FAILED`, `CANCELLED`) are preserved in history without violating uniqueness.

## Provider Credit Identity
A single bank/provider credit confirmation cannot be consumed by multiple transactions.
- Scope: `(provider, utr_number)`
- Flyway migration `V23` creates a partial unique index:
  ```sql
  CREATE UNIQUE INDEX uk_payment_tx_provider_utr
      ON payment_transactions (provider, utr_number)
      WHERE utr_number IS NOT NULL AND TRIM(utr_number) != '';
  ```
- Concurrent or replayed confirmation attempts with the same provider-scoped UTR are rejected.

## Pending / Unknown / Failed Handling
- `PENDING`, `PROCESSING`, `SUBMITTED`, and `UNKNOWN` statuses represent active or uncertain external states and prevent any secondary fresh effect for the same milestone.
- `FAILED` history remains queryable for audit trail and compliance. Legitimate retry semantics allow subsequent settlement initiation after an attempt terminates in `FAILED`.

## PostgreSQL Migration
- **Migration File:** `backend/src/main/resources/db/migration/V23__harden_financial_idempotency_and_effect_identity.sql`
- **Actions:**
  1. Drops legacy global unscoped constraints `uk_payment_instructions_idempotency_key` and `uk_settlement_instructions_idempotency_key`.
  2. Adds `request_hash` (VARCHAR(64)) column to `payment_instructions` and `settlement_instructions`.
  3. Adds `provider` (VARCHAR(32) NOT NULL DEFAULT 'BANK') column to `payment_transactions`.
  4. Creates partial unique index `uk_payment_instructions_deal_key` on `payment_instructions(deal_id, idempotency_key)`.
  5. Creates partial unique index `uk_settlement_instructions_deal_key` on `settlement_instructions(deal_id, idempotency_key)`.
  6. Creates partial unique index `uk_settlement_active_milestone` on `settlement_instructions(milestone_id)` for active statuses.
  7. Creates partial unique index `uk_payment_tx_provider_utr` on `payment_transactions(provider, utr_number)`.

## Duplicate Scan
A diagnostic scan was executed against the PostgreSQL test catalog and active tables:
- `payment_instructions`: 0 duplicate `(deal_id, idempotency_key)` pairs.
- `settlement_instructions`: 0 duplicate active milestones.
- `payment_transactions`: 0 duplicate `(provider, utr_number)` pairs.
Existing historical records were preserved without data loss.

## PostgreSQL Concurrency Verification
Verified against PostgreSQL 16.15 container (`finx_postgres`) on dedicated database `finx_b05_test`:
- Multi-connection concurrent race of two different keys targeting the same milestone resolved to exactly 1 active settlement effect.
- Multi-connection concurrent race of two transactions attempting to consume the same provider UTR resolved to exactly 1 confirmed transaction.
- Scoped idempotency constraints verified in `pg_indexes`.
- Zero deadlocks, zero lock timeouts observed.

## Interaction With B02
- Retains single-entrypoint bank orchestration containment.
- Webhook events and banking confirms remain strictly isolated.

## Interaction With B04
- Direct escrow release and banking dispatch remain completely disabled.
- `BankingProvider.initiateSettlement` calls remain strictly 0.
- All new settlement initiation attempts fail closed with `AccessDeniedException`.

## Interaction With B07
- Reused `DealAccessService` for strict tenant authorization before returning any idempotent replay responses.
- Cross-tenant requests are denied without leaking financial state.

## Interaction With B11
- Preserves deterministic locking order (`Deal` -> `Milestone` -> `Escrow` -> sorted `LedgerAccount`).
- Verified zero deadlocks under concurrent execution on PostgreSQL 16.

## Interaction With B12
- Preserves legacy Razorpay intake deprecation and fail-closed state in BANK mode.

## Files Modified
- `backend/src/main/java/com/finx/paymentorchestration/entity/PaymentInstruction.java`
- `backend/src/main/java/com/finx/paymentorchestration/entity/SettlementInstruction.java`
- `backend/src/main/java/com/finx/paymentorchestration/entity/PaymentTransaction.java`
- `backend/src/main/java/com/finx/paymentorchestration/repository/PaymentInstructionRepository.java`
- `backend/src/main/java/com/finx/paymentorchestration/repository/SettlementInstructionRepository.java`
- `backend/src/main/java/com/finx/paymentorchestration/repository/PaymentTransactionRepository.java`
- `backend/src/main/java/com/finx/paymentorchestration/service/PaymentOrchestrationService.java`
- `backend/src/main/java/com/finx/paymentorchestration/service/SettlementAuthorizationService.java`
- `backend/src/test/java/com/finx/paymentorchestration/SettlementAuthorizationServiceTest.java`

## Files Created
- `backend/src/main/resources/db/migration/V23__harden_financial_idempotency_and_effect_identity.sql`
- `backend/src/test/java/com/finx/security/FinancialIdempotencyContainmentTest.java`
- `backend/src/test/java/com/finx/security/PostgresFinancialIdentityTest.java`
- `B05_REMEDIATION_REPORT.md`

## Database Migration
YES (`V23__harden_financial_idempotency_and_effect_identity.sql`)

## API Compatibility
- All financial mutation endpoints now require a non-blank `Idempotency-Key` / request `idempotencyKey`.
- Read-only endpoints (`GET`) remain unchanged and do not require idempotency keys.
- Safe idempotent replays return original HTTP 200/201 response payloads.
- Payload mismatches on identical keys return 409 Conflict.

## Tests Added
- `com.finx.security.FinancialIdempotencyContainmentTest` (20 application containment tests)
- `com.finx.security.PostgresFinancialIdentityTest` (7 real PostgreSQL concurrency and identity tests)

## Tests Run
1. `FinancialIdempotencyContainmentTest`: 20/20 passed
2. `PostgresFinancialIdentityTest`: 7/7 passed
3. `PostgresLockingConcurrencyTest`: 7/7 passed
4. Containment regression suite: 237/237 passed
5. Full Maven test suite: 582/582 passed (0 failures, 0 errors, 1 skipped)

## Test Results
PASS

## Known Limitations
- B06 still owns ledger/source/projection lineage.
- Bank execution remains disabled pending orchestration redesign.
- Provider contract may later expand namespace beyond provider + UTR.

## B05 Status
PASS

## Ready for B06
YES
