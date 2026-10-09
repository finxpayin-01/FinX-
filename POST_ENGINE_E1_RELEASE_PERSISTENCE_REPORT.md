# FinX E1 Release Persistence Foundation Report

## Scope

E1 only: Database / Entity / Repository Persistence Foundation for `ReleaseInstruction` and `ReleaseReservation`.

No service layer, no authorization workflow, no escrow reservation logic, no financial outbox, no background workers, no bank dispatch, and no production payout.

---

## Preconditions

- **Engine platform**: CERTIFIED COMPLETE (Commit `39a5ca0` / `c15c2bc`)
- **Engine types**: 9 / 9 certified
- **Architecture design**: Commit `c42ee67` (`POST_ENGINE_RELEASE_EXECUTION_ARCHITECTURE.md`)
- **Flyway baseline before E1**: V1 -> V28 PASS
- **Production Payout**: DISABLED (`PRODUCTION_PAYOUT_ENABLED = false`)
- **B04 containment gate**: ACTIVE (`SettlementAuthorizationService` fails closed)

---

## Migration

- **File**: `backend/src/main/resources/db/migration/V29__create_release_instructions_and_reservations.sql`
- **Prior migrations modified**: NONE (V1 - V28 completely untouched)
- **V30 status**: ABSENT
- **Created tables**:
  1. `release_instructions`
  2. `release_reservations`

---

## ReleaseInstruction

### Actual Schema (`release_instructions`)

| Column | Type | Constraints / Defaults | Description |
| :--- | :--- | :--- | :--- |
| `id` | `UUID` | `PRIMARY KEY` | Java-generated UUID primary key |
| `organization_id` | `UUID` | `NOT NULL REFERENCES organizations(id) ON DELETE RESTRICT` | Tenant ownership boundary |
| `deal_id` | `UUID` | `NOT NULL REFERENCES deals(id) ON DELETE RESTRICT` | Deal context |
| `milestone_id` | `UUID` | `NOT NULL REFERENCES milestones(id) ON DELETE RESTRICT` | Milestone context |
| `release_decision_id` | `UUID` | `NOT NULL UNIQUE REFERENCES engine_decisions(id) ON DELETE RESTRICT` | Certified EngineDecision binding |
| `release_decision_fingerprint` | `VARCHAR(64)` | `NOT NULL` (CHECK: 64 lowercase hex) | Cryptographic hash of decision snapshot |
| `policy_version_id` | `UUID` | `NOT NULL REFERENCES engine_policy_versions(id) ON DELETE RESTRICT` | Certified policy version UUID |
| `policy_content_hash` | `VARCHAR(64)` | `NOT NULL` (CHECK: 64 lowercase hex) | Cryptographic hash of policy content |
| `beneficiary_bank_account_id` | `UUID` | `NOT NULL REFERENCES organization_bank_accounts(id) ON DELETE RESTRICT` | Target bank account entity FK |
| `provider` | `VARCHAR(50)` | `NOT NULL` (CHECK: nonblank) | Banking provider identifier |
| `provider_account_id` | `VARCHAR(128)` | `NOT NULL` (CHECK: nonblank) | Tenant provider account identifier |
| `amount` | `NUMERIC(19,4)` | `NOT NULL` (CHECK: `amount > 0`) | Exact money amount |
| `currency` | `VARCHAR(3)` | `NOT NULL` (CHECK: 3 uppercase letters) | ISO 4217 currency code |
| `status` | `VARCHAR(40)` | `NOT NULL` (CHECK: valid `ReleaseInstructionStatus`) | Execution lifecycle status |
| `idempotency_key` | `VARCHAR(128)` | `NOT NULL` (CHECK: nonblank) | Client/caller idempotency token |
| `request_hash` | `VARCHAR(64)` | `NOT NULL` (CHECK: 64 lowercase hex) | Hash of payload parameters |
| `created_by` | `UUID` | `NOT NULL REFERENCES users(id) ON DELETE RESTRICT` | Audit creator user |
| `authorized_by` | `UUID` | `REFERENCES users(id) ON DELETE RESTRICT` | Dual-control authorizer user |
| `created_at` | `TIMESTAMP WITH TIME ZONE` | `NOT NULL` | Creation timestamp |
| `updated_at` | `TIMESTAMP WITH TIME ZONE` | `NOT NULL` | Last update timestamp |
| `authorized_at` | `TIMESTAMP WITH TIME ZONE` | Nullable | Authorization timestamp |
| `settled_at` | `TIMESTAMP WITH TIME ZONE` | Nullable | Settlement timestamp |
| `version` | `BIGINT` | `NOT NULL DEFAULT 0` | Optimistic locking version |

---

## Release Decision Binding

- **Field**: `release_decision_id` (`UUID`)
- **FK**: `REFERENCES engine_decisions(id) ON DELETE RESTRICT`
- **Uniqueness**: `UNIQUE (release_decision_id)` (One EngineDecision -> at most one ReleaseInstruction)
- **Database Semantic Limitation**:
  - The database FK guarantees foreign-key referential integrity to `engine_decisions`.
  - The database schema alone cannot verify that `engine_decisions.engine_type == 'RELEASE'` and `decision == 'ALLOW'` and `status == 'SUCCESS'`.
  - Semantic validation of engine decision status and freshness belongs to the E2 service layer (`ReleaseInstructionService`).

---

## Policy Binding

- **Field**: `policy_version_id` (`UUID`)
- **FK**: `REFERENCES engine_policy_versions(id) ON DELETE RESTRICT`
- **Data type**: `UUID` (Corrected from earlier architecture draft that noted `VARCHAR(64)`).
- **Integrity**: `policy_content_hash` enforced via `CHECK (policy_content_hash ~ '^[0-9a-f]{64}$')`.

---

## Beneficiary Binding

- **Field**: `beneficiary_bank_account_id` (`UUID`)
- **FK**: `REFERENCES organization_bank_accounts(id) ON DELETE RESTRICT`
- **Deletion Protection**: Verified in PostgreSQL test; deleting an active or referenced beneficiary account is rejected with foreign key violation (`RESTRICT`).

---

## Provider Account Binding

- **Fields**: `provider`, `provider_account_id`
- **Constraints**: Nonblank string checks enforced at the database level.
- **Index**: `(provider, provider_account_id, status)` for provider-scoped lookup and reconciliation.

---

## Amount / Currency

- **Amount Type**: `NUMERIC(19,4)` in PostgreSQL, `BigDecimal` with 4 decimal places in Java.
- **Floating Point**: Zero usage of `float` or `double`.
- **Amount Constraint**: `CHECK (amount > 0)`.
- **Currency Constraint**: `CHECK (currency ~ '^[A-Z]{3}$')`.

---

## Instruction Status

Lifecycle values in `ReleaseInstructionStatus`:
- `CREATED`: Instruction recorded but not authorized.
- `AUTHORIZED`: Dual-control authorization completed.
- `RESERVED`: Escrow funds durably reserved.
- `QUEUED`: Queued into financial outbox.
- `DISPATCHING`: Worker claiming dispatch lock.
- `SUBMITTED`: Dispatched to banking provider.
- `PENDING_SETTLEMENT`: Acknowledged by provider; awaiting settlement.
- `UNKNOWN_RECON_REQUIRED`: Ambiguous bank state requiring reconciliation.
- `MANUAL_REVIEW`: Triaged for manual operator review.
- `SETTLED`: Terminal success with verified bank UTR.
- `FAILED`: Terminal failure.
- `CANCELLED`: Terminal cancellation before dispatch.

---

## Scoped Idempotency

- **Index**: `CREATE UNIQUE INDEX uk_release_instructions_org_idempotency ON release_instructions (organization_id, idempotency_key)`
- **Tenant Isolation**: Idempotency is strictly tenant-scoped (scoped to `organization_id`). Duplicate keys within the same organization are rejected; the same key used by separate organizations is permitted.

---

## Active Milestone Uniqueness

- **Index**: `CREATE UNIQUE INDEX uk_release_instructions_active_milestone ON release_instructions (milestone_id) WHERE status NOT IN ('SETTLED', 'FAILED', 'CANCELLED')`
- **Enforcement**: At most one active (non-terminal) ReleaseInstruction per milestone. Historical terminal instructions coexist with subsequent instructions if re-initiated.

---

## ReleaseReservation

### Actual Schema (`release_reservations`)

| Column | Type | Constraints / Defaults | Description |
| :--- | :--- | :--- | :--- |
| `id` | `UUID` | `PRIMARY KEY` | Java-generated UUID primary key |
| `release_instruction_id` | `UUID` | `NOT NULL UNIQUE REFERENCES release_instructions(id) ON DELETE RESTRICT` | 1-to-1 instruction binding |
| `escrow_account_id` | `UUID` | `NOT NULL REFERENCES escrow_accounts(id) ON DELETE RESTRICT` | Escrow account binding |
| `milestone_id` | `UUID` | `NOT NULL REFERENCES milestones(id) ON DELETE RESTRICT` | Milestone binding |
| `amount` | `NUMERIC(19,4)` | `NOT NULL` (CHECK: `amount > 0`) | Reserved amount |
| `currency` | `VARCHAR(3)` | `NOT NULL` (CHECK: 3 uppercase letters) | Currency code |
| `status` | `VARCHAR(20)` | `NOT NULL` (CHECK: `ACTIVE`, `CONSUMED`, `RELEASED`) | Reservation status |
| `created_at` | `TIMESTAMP WITH TIME ZONE` | `NOT NULL` | Creation timestamp |
| `updated_at` | `TIMESTAMP WITH TIME ZONE` | `NOT NULL` | Last update timestamp |
| `consumed_at` | `TIMESTAMP WITH TIME ZONE` | Nullable | Settlement consumption timestamp |
| `released_at` | `TIMESTAMP WITH TIME ZONE` | Nullable | Cancellation release timestamp |
| `version` | `BIGINT` | `NOT NULL DEFAULT 0` | Optimistic locking version |

---

## Reservation Lifecycle

1. `ACTIVE`: Funds durably claimed. `consumed_at IS NULL` AND `released_at IS NULL`.
2. `CONSUMED`: Consumed upon verified settlement. `consumed_at IS NOT NULL` AND `released_at IS NULL`.
3. `RELEASED`: Released upon cancellation/failure. `released_at IS NOT NULL` AND `consumed_at IS NULL`.

Database check constraints enforce that `consumed_at` and `released_at` can never both be non-null simultaneously.

---

## One Reservation Per Instruction

- **Constraint**: `UNIQUE (release_instruction_id)`
- Each `ReleaseInstruction` owns at most one durable reservation. Network retries operate on the existing instruction/reservation and do not generate new reservation records.

---

## One Active Reservation Per Milestone

- **Index**: `CREATE UNIQUE INDEX uk_release_reservations_active_milestone ON release_reservations (milestone_id) WHERE status = 'ACTIVE'`
- Guarantees at the database level that no two active reservations can exist for the same milestone concurrently.

---

## Escrow Accounting

- **Escrow balance modified**: NO (0 writes to `escrow_accounts`)
- **Escrow ledger writes**: NO (0 writes to `escrow_ledgers`)
- **Journal entries posted**: NO (0 writes to `journal_entries`, `journal_lines`)
- **Settlement instructions created**: NO (0 writes to `settlement_instructions`)

---

## B04 Containment

- **Status**: UNCHANGED / ACTIVE
- `SettlementAuthorizationService.authorizeAndInitiateSettlement` fails closed before any call to `BankingProvider.initiateSettlement`.

---

## Banking

- **Banking calls made**: 0
- **Dependencies on `BankingProvider`**: 0 in E1 production classes.

---

## Outbox

- **Status**: NOT IMPLEMENTED (Deferred to Phase E3 per architecture plan).

---

## Authorization

- **Status**: NOT IMPLEMENTED (Deferred to Phase E2 per architecture plan).

---

## Sensitive Data Boundary

Verified by `ReleasePersistenceFoundationArchitectureTest`:
- `ReleaseInstruction` contains NO raw account numbers, encrypted account numbers, masked account numbers, IFSC codes, PANs, GSTINs, bank credentials, or raw webhook payloads.
- Only reference identifiers (`beneficiaryBankAccountId`, `provider`, `providerAccountId`) are stored.

---

## PostgreSQL

- **Engine**: PostgreSQL 16.15 (`finx_postgres` container)
- **Disposable Verification DB**: `finx_release_e1_test`
- **Result**: PASS (19 comprehensive test methods verifying all 37 database assertions)
- **Skipped PostgreSQL Tests**: 0

---

## Flyway

- **Status**: V1 -> V29 PASS cleanly on clean database (`FlywayCleanMigrationTest`).
- **Highest Version**: V29
- **V30**: ABSENT

---

## Financial Side Effects

- **Count**: 0

---

## Files Created

1. `backend/src/main/resources/db/migration/V29__create_release_instructions_and_reservations.sql`
2. `backend/src/main/java/com/finx/release/model/ReleaseInstructionStatus.java`
3. `backend/src/main/java/com/finx/release/model/ReleaseReservationStatus.java`
4. `backend/src/main/java/com/finx/release/entity/ReleaseInstruction.java`
5. `backend/src/main/java/com/finx/release/entity/ReleaseReservation.java`
6. `backend/src/main/java/com/finx/release/repository/ReleaseInstructionRepository.java`
7. `backend/src/main/java/com/finx/release/repository/ReleaseReservationRepository.java`
8. `backend/src/test/java/com/finx/release/PostgresReleasePersistenceFoundationTest.java`
9. `backend/src/test/java/com/finx/release/ReleasePersistenceEntityMappingTest.java`
10. `backend/src/test/java/com/finx/release/ReleaseRepositoryTest.java`
11. `backend/src/test/java/com/finx/release/ReleasePersistenceFoundationArchitectureTest.java`
12. `POST_ENGINE_E1_RELEASE_PERSISTENCE_REPORT.md`

---

## Files Modified

1. `backend/src/test/java/com/finx/FlywayCleanMigrationTest.java` (Updated migration target assertion to V29)

---

## Tests Added

- `PostgresReleasePersistenceFoundationTest` (19 test methods, 37 Postgres DB assertions)
- `ReleasePersistenceEntityMappingTest` (4 test methods)
- `ReleaseRepositoryTest` (2 test methods)
- `ReleasePersistenceFoundationArchitectureTest` (3 test methods)

---

## Tests Run

- Baseline safety & containment suite: 200 tests run, 0 failures, 0 errors.
- Full Maven test suite: 1115 tests run, 0 failures, 0 errors, BUILD SUCCESS.

---

## P0 Status

- **P0-1**: FOUNDATION IMPLEMENTED (`release_instructions` schema & entities created).
- **P0-2**: PERSISTENCE FOUNDATION IMPLEMENTED (`release_reservations` schema & entities created; functional atomic reservation logic scheduled for E2).
- **P0-3 through P0-6**: UNCHANGED (Awaiting subsequent phases E2 - E12).

---

## E1 Status

**PASS**

---

## Ready For E2

**YES**
