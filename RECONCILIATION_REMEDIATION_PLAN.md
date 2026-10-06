# Reconciliation Remediation Plan

## Current Defects

An exhaustive audit of `ReconciliationService.java`, `ReconciliationRun.java`, `ReconciliationItem.java`, and `BankingDtos.java` confirms sixteen critical architectural defects in the existing reconciliation implementation:

| Defect ID | Defect Description | Audit Status | Code Evidence / Location | Impact on Financial Attestation |
|---|---|---|---|---|
| **R01** | **Settlement amount not compared** | **CONFIRMED** | `ReconciliationService.java:114-120`: `inSettlement = settlements.stream().anyMatch(...)`; sets `MATCHED` using `b.amount(), b.amount()` without checking `settlement.getAmount().compareTo(b.amount()) == 0`. | Material financial discrepancy between internal ledger and actual bank payout is masked as `MATCHED`. |
| **R02** | **Outbound settlement missing externally not detected** | **CONFIRMED** | `ReconciliationService.java:84-109`: Outbound loop only iterates over `PaymentTransaction` (inbound). `SettlementInstruction` is never iterated to check if submitted payouts were executed by the bank. | Unexecuted, dropped, or failed outbound settlements never produce `MISSING_EXTERNAL`. |
| **R03** | **Currency not compared** | **CONFIRMED** | `ReconciliationService.java:86-88, 114-115`: Matching checks only reference/UTR, completely ignoring currency fields. | Multi-currency mismatches (e.g. USD vs INR) with identical numeric values match silently. |
| **R04** | **Direction not compared** | **CONFIRMED** | `ReconciliationService.java:84-126`: `BankTransactionRecord.type()` (`CREDIT` vs `DEBIT`) is never compared against internal payment flow. | A credit (inbound) can match a debit (outbound) if references collide. |
| **R05** | **Destination/source account not compared** | **CONFIRMED** | `ReconciliationService.java:84-126`: No validation that transaction occurred in the designated escrow virtual account or platform account. | Cross-account misallocations match as valid escrow movements. |
| **R06** | **Final bank status not checked** | **CONFIRMED** | `ReconciliationService.java:84-126`: All records in bank feed are assumed settled; pending, processing, or reversed records are treated as final. | Non-final provisional bank transactions erroneously satisfy escrow requirements. |
| **R07** | **One bank row can match multiple records** | **CONFIRMED** | `ReconciliationService.java:85-88`: `bankRecords.stream().filter(...).findFirst()` does not remove or track consumed bank records across iterations. | Multiple internal transactions can claim the same external bank credit. |
| **R08** | **30-day feed vs unbounded historical scan** | **CONFIRMED** | `ReconciliationService.java:74-76`: Bounded 30-day bank query (`today.minusDays(30)`) matched against `transactionRepository.findAll()` and `settlementRepository.findAll()`. | Database queries grow without bound; historical transactions falsely match recent bank records or flag false `MISSING_EXTERNAL`. |
| **R09** | **No durable statement import** | **CONFIRMED** | `ReconciliationService.java:74`: `bankingProvider.fetchTransactions(...)` returns ephemeral in-memory DTOs. | Statement feeds cannot be re-audited, verified against raw feeds, or verified for duplicate file ingestion. |
| **R10** | **No durable statement line identity** | **CONFIRMED** | No `bank_statement_lines` table exists in Flyway migrations V1–V25. | Individual bank lines have no persistent database primary key or immutable lineage. |
| **R11** | **No pagination or cursor tracking** | **CONFIRMED** | `BankingProvider.fetchTransactions(LocalDate, LocalDate)` returns a flat list with no cursor or continuation token. | Large volume bank statement feeds drop records beyond default page sizes. |
| **R12** | **No provider / account / window identity** | **CONFIRMED** | `ReconciliationRun` stores only `provider` string, lacking `providerAccountId`, `windowStart`, and `windowEnd`. | Ingested feeds cannot be idempotently tracked per bank account and date window. |
| **R13** | **No timezone / value date model** | **CONFIRMED** | `BankTransactionRecord` contains only UTC `Instant timestamp` without value date or bank local posting date. | Transactions posted near midnight UTC generate false window-boundary discrepancies. |
| **R14** | **No opening / closing balance control** | **CONFIRMED** | `ReconciliationRun` has no balance fields (`openingBalance`, `closingBalance`, `calculatedBalance`). | Missing transactions cannot be verified against bank statement closing balances. |
| **R15** | **COMPLETED run != clean run** | **CONFIRMED** | `ReconciliationService.java:132`: Run status is set to `COMPLETED` even when `mismatchCount > 0`. | Consumers treat `status == COMPLETED` as financial clearance rather than computation completion. |
| **R16** | **No exception lifecycle or ownership** | **CONFIRMED** | `ReconciliationItem.java`: Fields `resolved_by`, `resolved_at`, and `resolution_status` exist in entity but have no service methods, validation, or audit trail. | Mismatches and exceptions cannot be assigned, reviewed, or formally resolved by finance operators. |

---

## Target Architecture

The target reconciliation architecture completely decouples external bank feed ingestion from decision evaluation:

```
+---------------------------------------------------------------------------------------+
|                               INGESTION WORKFLOW (ASYNCHRONOUS)                       |
|                                                                                       |
|  [BankingProvider.fetchTransactions]                                                  |
|                  |                                                                    |
|                  v (Idempotent import verification)                                   |
|      [bank_statement_imports]                                                         |
|                  |                                                                    |
|                  v (Durable immutable statement lines with scoped identity)           |
|      [bank_statement_lines]                                                           |
+---------------------------------------------------------------------------------------+
                                        |
                                        v
+---------------------------------------------------------------------------------------+
|                               MATCHING ENGINE (DETERMINISTIC)                         |
|                                                                                       |
|  Bounded Internal Candidates          Durable Statement Lines                         |
|  (PaymentTransaction / Settlement)    (1-to-1 Consumption Tracking)                   |
|                  \                     /                                              |
|                   \                   /                                               |
|                    v                 v                                                |
|             [Deterministic Reconciliation Matcher]                                    |
|             - Amount & Currency Equality                                              |
|             - Direction Enforcement (CREDIT vs DEBIT)                                 |
|             - Account Scope Matching                                                  |
|             - Terminal Bank Finality                                                  |
|             - One-to-One Consumption Pool                                             |
|                               |                                                       |
|                               v                                                       |
|             [reconciliation_runs] & [reconciliation_items]                            |
+---------------------------------------------------------------------------------------+
                                        |
                                        v
+---------------------------------------------------------------------------------------+
|                          DECISION EVALUATION (STRICTLY READ-ONLY)                     |
|                                                                                       |
|  [ReconciliationDecisionAdapter]                                                      |
|  - Evaluates persisted ReconciliationRun & ReconciliationItem records                 |
|  - NEVER calls BankingProvider.fetchTransactions                                      |
|  - ZERO external bank network calls during evaluation                                 |
|  - Maps clean reconciliation -> ALLOW, pending/mismatch -> REVIEW/BLOCK               |
+---------------------------------------------------------------------------------------+
```

---

## Durable Statement Import

Statement imports are durably tracked in `bank_statement_imports`:
- **Unique Scoped Idempotency**: `(provider, provider_account_id, window_start, window_end, source_hash)` ensures identical statement feeds cannot be processed twice.
- **Balance Control**: Captures `opening_balance` and `closing_balance` to verify ledger arithmetic.
- **Execution Tracking**: Records `status` (`RECEIVED`, `PARSING`, `COMPLETED`, `FAILED`), record counts, and payload hashes.

---

## Statement Line Identity

Individual bank statement lines are stored in `bank_statement_lines`:
- **Durable Identity**: `id` (UUID primary key) and `import_id` (foreign key to import).
- **Scoped Natural Identity**: `(provider, provider_account_id, provider_line_id)` or `(provider, provider_account_id, provider_reference, transaction_time, amount, direction)`.
- **Reference Preservation**: Preserves raw UTR, external reference, description, and counterparty metadata without unvetted payload truncation.

---

## Provider / Account Scope

Every internal financial transaction belongs to an explicit account context:
- Inbound escrow funding occurs into designated **Virtual Bank Accounts** (`virtual_account_number`).
- Outbound payouts originate from platform **Escrow Nodal Accounts** to verified **Beneficiary Accounts**.
- Reconciliation matches transactions strictly within the identical account and provider namespace.

---

## Direction

Direction is explicitly modeled as `FinancialDirection`:
- **`CREDIT`** (Inflow into platform bank account):
  - Must match internal `PaymentTransaction` (buyer funding escrow).
- **`DEBIT`** (Outflow from platform bank account):
  - Must match internal `SettlementInstruction` (vendor payout / buyer refund).

> **INVARIANT R01**: A `CREDIT` bank statement line can NEVER match a `SettlementInstruction`. A `DEBIT` bank statement line can NEVER match an inbound `PaymentTransaction`.

---

## Currency

Every matching comparison explicitly verifies:
- `internal.currency == bankLine.currency`
- Currencies are standardized to ISO 4217 three-letter codes (e.g. `INR`, `USD`). Currency mismatches immediately produce `ReconciliationItemStatus.AMOUNT_MISMATCH` or `STATUS_MISMATCH`.

---

## Finality

Only terminal bank statuses represent financial settlement:
- **Eligible Final Statuses**: `SETTLED`, `SUCCESS`, `COMPLETED`, `CONFIRMED`.
- **Non-Final Statuses**: `PENDING`, `IN_PROCESS`, `INITIATED`, `QUEUED`, `REVERSED`, `FAILED`.
- Non-final statement lines cannot fulfill reconciliation requirements and are marked `REQUIRES_REVIEW`.

---

## Inbound Matching

For each internal `PaymentTransaction` in status `CONFIRMED`:
1. Query matching `BankStatementLine` in `bank_statement_lines` where:
   - `direction == FinancialDirection.CREDIT`
   - `currency == paymentTransaction.currency`
   - `provider == paymentTransaction.provider`
   - `provider_account_id == paymentTransaction.virtualAccountNumber`
   - `utrNumber == paymentTransaction.utrNumber` OR `providerReference == paymentTransaction.providerReference`
2. Validate amount: `paymentTransaction.amount.compareTo(bankLine.amount) == 0`.
3. Validate finality: `bankLine.status IN ('SETTLED', 'SUCCESS')`.
4. If valid, mark bank line as consumed and record `ReconciliationItem` as `MATCHED`.
5. If amount differs, record `AMOUNT_MISMATCH`.
6. If no matching bank line exists within window, record `MISSING_EXTERNAL`.

---

## Outbound Matching

For each internal `SettlementInstruction` in status `SETTLED` or `INITIATED`:
1. Query matching `BankStatementLine` where:
   - `direction == FinancialDirection.DEBIT`
   - `currency == settlementInstruction.currency`
   - `provider == settlementInstruction.provider`
   - `utrNumber == settlementInstruction.utrNumber` OR `providerSettlementId == bankLine.providerReference`
2. Validate amount: `settlementInstruction.amount.compareTo(bankLine.amount) == 0`.
3. Validate finality: `bankLine.status IN ('SETTLED', 'SUCCESS')`.
4. If valid, mark bank line as consumed and record `ReconciliationItem` as `MATCHED`.
5. If amount differs, record `AMOUNT_MISMATCH`.
6. If submitted settlement is absent from statement, record `MISSING_EXTERNAL`.

---

## One-to-One Consumption

To eliminate defect **R07**:
- The matching engine maintains a set of consumed `bank_statement_line_id`s during execution.
- Once a statement line is matched to an internal transaction, it is removed from the candidate pool.
- At the database level, `reconciliation_items` can record `bank_statement_line_id` with a uniqueness constraint per active reconciliation run.

---

## Bounded Windows

To eliminate defect **R08**:
- Internal transactions are queried using bounded repository methods:
  - `paymentTransactionRepository.findInboundCandidates(provider, accountId, windowStart, windowEnd)`
  - `settlementInstructionRepository.findOutboundCandidates(provider, accountId, windowStart, windowEnd)`
- Prevents full-table scans and historical cross-window contamination.

---

## Missing External Records

Reconciliation evaluates both inbound and outbound completeness:
- **Inbound Missing**: Internal ledger claims money received, but bank has no record -> `MISSING_EXTERNAL`.
- **Outbound Missing**: Internal system initiated settlement, but bank has no record -> `MISSING_EXTERNAL`.
- **Unclaimed Bank Credit**: Money received in bank with no corresponding FinX instruction -> `MISSING_INTERNAL`.

---

## Unknown External Effects

Where external banking execution is in-flight or indeterminate:
- Marked as `PENDING_RECONCILIATION`.
- Does not trigger immediate critical alerts if within acceptable bank settlement SLAs (e.g. NEFT/RTGS processing window).
- Prevents premature false alarms while preventing unauthorized releases.

---

## Run Semantics

To eliminate defect **R15**:
- `ReconciliationRunStatus` is decoupled from reconciliation outcome:
  - **Processing Status**: `IN_PROGRESS`, `COMPLETED`, `FAILED`.
  - **Outcome Cleanliness**: Evaluated via `mismatchCount == 0 && missingExternalCount == 0 && missingInternalCount == 0`.
- A run is only **AUTHORITATIVE / CLEAN** when `status == COMPLETED` AND `mismatchCount == 0`.

---

## Exception Lifecycle

Each discrepancy recorded in `reconciliation_items` supports human exception handling:
- `resolutionStatus`: `UNRESOLVED`, `INVESTIGATING`, `RESOLVED_LEDGER_ADJUSTED`, `RESOLVED_BANK_TIMING`, `IGNORED_AUTHORIZED`.
- `resolvedBy`: UUID of platform finance operator.
- `resolvedAt`: Timestamp of resolution.
- `resolutionNotes`: Mandatory textual justification.

---

## Tenant / Platform Authorization

- Reconciliation runs and statement imports are strictly restricted to `Role.ADMIN` and `Role.FINANCE`.
- Scoped tenant views allow organization admins to view reconciliation attestations for their specific deals/milestones without exposing platform-wide banking feeds.

---

## Proposed Migration

**Target Filename**: `V26__durable_bank_statement_reconciliation.sql`
*(PROPOSED ONLY — NOT CREATED IN THIS RUN)*

---

## Migration Schema

```sql
-- V26__durable_bank_statement_reconciliation.sql

-- 1. Bank Statement Imports
CREATE TABLE bank_statement_imports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider VARCHAR(64) NOT NULL,
    provider_account_id VARCHAR(128) NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    window_end TIMESTAMPTZ NOT NULL,
    source_hash VARCHAR(64) NOT NULL,
    opening_balance NUMERIC(19, 4),
    closing_balance NUMERIC(19, 4),
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    total_lines INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'RECEIVED',
    imported_by UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_statement_import_window UNIQUE (provider, provider_account_id, window_start, window_end, source_hash)
);

CREATE INDEX idx_statement_imports_provider_window ON bank_statement_imports(provider, provider_account_id, window_start, window_end);

-- 2. Bank Statement Lines
CREATE TABLE bank_statement_lines (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    import_id UUID NOT NULL REFERENCES bank_statement_imports(id) ON DELETE CASCADE,
    provider VARCHAR(64) NOT NULL,
    provider_account_id VARCHAR(128) NOT NULL,
    provider_line_id VARCHAR(128),
    transaction_reference VARCHAR(128) NOT NULL,
    utr_number VARCHAR(128),
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    direction VARCHAR(16) NOT NULL, -- 'CREDIT' or 'DEBIT'
    status VARCHAR(32) NOT NULL DEFAULT 'SETTLED',
    transaction_time TIMESTAMPTZ NOT NULL,
    value_date DATE,
    counterparty_account VARCHAR(128),
    counterparty_name VARCHAR(255),
    description TEXT,
    consumed_in_run_id UUID REFERENCES reconciliation_runs(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_statement_lines_lookup ON bank_statement_lines(provider, provider_account_id, direction, transaction_time);
CREATE INDEX idx_statement_lines_utr ON bank_statement_lines(utr_number);
CREATE INDEX idx_statement_lines_ref ON bank_statement_lines(transaction_reference);

-- 3. Enhance Reconciliation Items
ALTER TABLE reconciliation_items
    ADD COLUMN bank_statement_line_id UUID REFERENCES bank_statement_lines(id),
    ADD COLUMN currency VARCHAR(3) DEFAULT 'INR',
    ADD COLUMN direction VARCHAR(16),
    ADD COLUMN resolution_notes TEXT;

CREATE INDEX idx_reconciliation_items_run_status ON reconciliation_items(reconciliation_run_id, status);
```

---

## Entity / Repository Changes

1. **New Entities**:
   - `BankStatementImport.java` (`@Entity @Table(name = "bank_statement_imports")`)
   - `BankStatementLine.java` (`@Entity @Table(name = "bank_statement_lines")`)
2. **New Repositories**:
   - `BankStatementImportRepository.java`
   - `BankStatementLineRepository.java`
3. **Repository Extensions**:
   - `PaymentTransactionRepository.findInboundCandidates(...)`
   - `SettlementInstructionRepository.findOutboundCandidates(...)`
   - `ReconciliationItemRepository.findByRunAndUnresolved(...)`

---

## Service Changes

1. **`BankStatementIngestionService` (New)**:
   - Ingests raw bank feeds / webhook statements into `bank_statement_imports` and `bank_statement_lines`.
   - Enforces idempotent duplicate rejection.
2. **`DeterministicReconciliationMatcher` (New / Refactored)**:
   - Replaces flawed loop in `ReconciliationService`.
   - Implements 1-to-1 matching with direction, currency, amount, and finality checks.
3. **`ReconciliationExceptionService` (New)**:
   - Manages exception lifecycle and operator sign-off.

---

## PostgreSQL Constraints

1. Unique import window: `uq_statement_import_window` on `(provider, provider_account_id, window_start, window_end, source_hash)`.
2. Positive financial amounts: Check constraints `amount > 0` on statement lines.
3. Currency code constraint: Check constraint `currency ~ '^[A-Z]{3}$'`.

---

## PostgreSQL Test Plan

The future test suite will verify:
1. **Idempotent Import**: Same statement file cannot be imported twice.
2. **Amount Discrepancy Detection**: Inbound payment ₹100,000 vs bank line ₹99,000 produces `AMOUNT_MISMATCH`.
3. **Outbound Settlement Amount Check**: Settlement ₹50,000 vs bank debit ₹45,000 produces `AMOUNT_MISMATCH`.
4. **Currency Mismatch Rejection**: USD internal transaction does not match INR bank line.
5. **Direction Mismatch Rejection**: Inbound payment does not match bank `DEBIT`.
6. **No Line Reuse**: Single bank statement line cannot satisfy two internal transactions.
7. **Missing Outbound Settlement**: Settlement instruction not executed by bank produces `MISSING_EXTERNAL`.
8. **Zero Bank Calls in Decision Adapter**: Decision evaluation invokes 0 network calls.

---

## API Compatibility

Existing endpoints in `ReconciliationController` (`GET /api/v1/reconciliation/runs`, `GET /api/v1/reconciliation/runs/{id}/items`) will remain backwards-compatible, with enhanced response DTOs providing exception resolution details and statement line linkages.

---

## Reconciliation Decision Adapter Contract

*(PROPOSED ONLY — NOT IMPLEMENTED IN THIS PHASE)*

- **Subject Type**: `DecisionSubjectType.MILESTONE`
- **Subject ID**: `milestone.getId()`
- **Tenant Owner**: `deal.getBuyerOrganizationId()`
- **Decision Logic**:
  - **`ALLOW`**: All required inbound funding and prior settlements for the milestone's deal are matched in authoritative reconciliation runs with `mismatchCount == 0` and `resolutionStatus == RESOLVED`.
  - **`REVIEW`**: Matching reconciliation items are in `PENDING_RECONCILIATION` or have unresolved non-critical timing exceptions.
  - **`BLOCK`**: Confirmed `AMOUNT_MISMATCH`, missing funds, or unexecuted settlements on the subject.

---

## Production Banking Dependency

- The `ReconciliationDecisionAdapter` will have **ZERO direct dependency** on live bank connections.
- Ingestion occurs via scheduled background jobs or incoming webhooks, writing to statement tables.
- Decision evaluation reads **ONLY** local PostgreSQL tables.

---

## Implementation Phases

| Phase | Description | Deliverables | Migration Scope |
|---|---|---|---|
| **R1** | **Reconciliation Schema & Entities** | `bank_statement_imports`, `bank_statement_lines`, entity models, repositories. | `V26__durable_bank_statement_reconciliation.sql` |
| **R2** | **Durable Statement Ingestion** | `BankStatementIngestionService`, idempotent import controller. | None |
| **R3** | **Deterministic Matcher** | `DeterministicReconciliationMatcher` (fixing R01–R15). | None |
| **R4** | **Exception Lifecycle & Resolution** | `ReconciliationExceptionService`, operator resolution endpoints. | None |
| **R5** | **Read-Only Decision Adapter** | `ReconciliationDecisionAdapter` implementing `ReadOnlyDecisionAdapter`. | None |

---

## Readiness

**RECONCILIATION CURRENT CODE: NOT READY / BLOCKED**

- Requires execution of phases R1–R4 before `ReconciliationDecisionAdapter` (R5) can be implemented.
- Current `MATCHED` status is non-authoritative and must not be used for release decisions.
