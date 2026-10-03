# B06 Remediation Report

## Blocker
B06 — Financial source references and ledger consistency disagree

## Root Cause
Prior to remediation:
1. **Invalid Source Lineage**: Bank funding paths passed `PaymentInstruction.id` (UUID) into `escrow_ledger.payment_id`, but that column had a foreign key constraint referencing legacy `payments(id)` (`fk_escrow_ledger_payment`). Because bank payment instructions exist in `payment_instructions` rather than `payments`, either an invalid foreign key violation occurred or bank IDs were erroneously cross-referenced as legacy payments.
2. **Missing Journal Effect Uniqueness**: `journal_entries.source_reference` and `reference_id` were indexed for search, but lacked database-level uniqueness for financial effects. Replays or concurrent posts for the same underlying transaction could create duplicate journal entries and line items.
3. **Incomplete Currency Enforcement**: Ledger balances and accounts were configured with INR defaults, but journal postings and escrow projections did not validate that caller requests, account currencies, and line item currencies agreed. Mismatched or non-INR postings could potentially be processed.
4. **Weak Escrow Projection Uniqueness & Exclusivity**: Escrow ledger rows did not enforce source exclusivity. A row could ambiguously omit a source or claim multiple conflicting source types, and projection replays were protected primarily through application-level checks rather than database constraints.

## Before
- **Bank Source as Legacy Payment**: Bank funding code attempted to store `paymentInstruction.getId()` into `escrow_ledger.payment_id`, violating relational integrity and corrupting audit provenance.
- **Journal Source Uniqueness**: Journal entry references had no database uniqueness constraints scoped by transaction type and reference ID; duplicate journal postings for the same financial source were physically possible at the DB level.
- **Currency Consistency**: No central validation prevented multi-currency or non-INR postings across ledger lines, journal entries, and accounts.
- **V4 FUND Policy**: V4 enforced milestone-level uniqueness for `FUND` transactions, but lacked source-exclusivity check constraints distinguishing bank payment instructions from legacy Razorpay payments.
- **Transaction Atomicity**: Journal entry creation, escrow projection updates, and instruction finalization were not encapsulated in a single, strictly bounded local database transaction with tested atomic rollback.

## After
- **Additive Bank Source FK**: Added `payment_instruction_id UUID REFERENCES payment_instructions(id) ON DELETE SET NULL` to `escrow_ledger`, preserving `payment_id` strictly for legacy `payments(id)`.
- **Source Exclusivity Check**: Database constraint `chk_escrow_ledger_source_exclusivity` ensures a row cannot simultaneously specify both `payment_id` and `payment_instruction_id`.
- **Mandatory Funding Lineage**: Database constraint `chk_escrow_ledger_fund_source` enforces that any `FUND` transaction must have exactly one non-null source (`payment_instruction_id` OR `payment_id`).
- **Journal Effect Uniqueness**: Created PostgreSQL partial unique index `uk_journal_entries_type_reference` on `journal_entries(transaction_type, reference_id)` WHERE `reference_id IS NOT NULL`, guaranteeing that one financial source event can post at most one journal entry of a given type.
- **Escrow Projection Uniqueness**: Created PostgreSQL partial unique index `uk_escrow_ledger_payment_instruction_id` on `escrow_ledger(payment_instruction_id)` WHERE `payment_instruction_id IS NOT NULL`, preventing multiple escrow funding projections for the same bank instruction.
- **INR-Only Currency Policy**: Strict validation rejects non-INR or mismatched currencies across journal headers, journal lines, ledger accounts, payment instructions, and escrow projections.
- **Exact Balance Validation**: Debits and credits must sum to zero using exact `BigDecimal` scale (`totalDebit.compareTo(totalCredit) == 0`).
- **Atomic Local Finalization**: `PaymentOrchestrationService.finalizeBankFunding` performs journal posting, escrow projection, and instruction finalization within one local `@Transactional` boundary, rolling back completely if any step fails.

## Legacy Payment Source
- Historical Razorpay and legacy checkout payments continue to be stored and referenced via `payments(id)`.
- `escrow_ledger.payment_id` remains intact with its foreign key to `payments(id)` (`fk_escrow_ledger_payment`).
- The legacy `escrowService.fundEscrow(...)` method populates `payment_id` and sets `payment_instruction_id = null`.
- No historical IDs were rewritten or migrated to the bank instruction table.

## Bank PaymentInstruction Source
- Bank funding instructions created via `PaymentOrchestrationService` now project into `escrow_ledger` using the new `payment_instruction_id` column.
- `payment_instruction_id` has an explicit foreign key to `payment_instructions(id)` (`escrow_ledger_payment_instruction_id_fkey`).
- `escrowService.fundEscrowForInstruction(...)` populates `payment_instruction_id` and sets legacy `payment_id = null`.
- Bank PaymentInstruction IDs are never passed to or stored in `payment_id`.

## Source Exclusivity
- Database constraint `chk_escrow_ledger_source_exclusivity`:
  ```sql
  CONSTRAINT chk_escrow_ledger_source_exclusivity
      CHECK (NOT (payment_id IS NOT NULL AND payment_instruction_id IS NOT NULL))
  ```
- Database constraint `chk_escrow_ledger_fund_source`:
  ```sql
  CONSTRAINT chk_escrow_ledger_fund_source
      CHECK (
          transaction_type != 'FUND' OR
          ((payment_id IS NOT NULL AND payment_instruction_id IS NULL) OR
           (payment_id IS NULL AND payment_instruction_id IS NOT NULL))
      )
  ```
- A row cannot claim both sources; every `FUND` entry must have exactly one verified source reference.

## Confirmed Event Uniqueness
- An authenticated incoming bank confirmation event (B01/B02) with a unique provider transaction identity (B05) triggers local finalization exactly once.
- Subsequent replay attempts encounter the idempotent completion check in `PaymentOrchestrationService.finalizeBankFunding` and return the existing instruction in `SUCCESS` status without duplicating journal lines or escrow ledger records.

## Journal Effect Uniqueness
- **Uniqueness Scope**: `(transaction_type, reference_id)` WHERE `reference_id IS NOT NULL`.
- Database index:
  ```sql
  CREATE UNIQUE INDEX uk_journal_entries_type_reference
      ON journal_entries (transaction_type, reference_id)
      WHERE reference_id IS NOT NULL;
  ```
- Any concurrent or duplicate posting attempt for the same transaction type (e.g. `ESCROW_DEPOSIT`) referencing the same `PaymentInstruction.id` is rejected by PostgreSQL unique constraint violation (`23505`).

## Escrow Projection Uniqueness
- Database index:
  ```sql
  CREATE UNIQUE INDEX uk_escrow_ledger_payment_instruction_id
      ON escrow_ledger (payment_instruction_id)
      WHERE payment_instruction_id IS NOT NULL;
  ```
- Combined with B05 command/event idempotency and `uk_escrow_ledger_payment_instruction_id`, duplicate bank funding cannot increment escrow balance or append duplicate ledger rows.

## Local Finalization Transaction
- Implemented in `PaymentOrchestrationService.finalizeBankFunding(UUID instructionId, String utr, UUID actorId)`:
  1. Acquires instruction with lock / idempotency check.
  2. Posts balanced double-entry journal (`ESCROW_DEPOSIT`) with reference to instruction ID.
  3. Projects escrow funding via `escrowService.fundEscrowForInstruction(dealId, milestoneId, instructionId, amount, currency, actorId)`.
  4. Records `PaymentTransaction` with provider UTR.
  5. Updates `PaymentInstruction` status to `SUCCESS`.
  6. Emits audit events and buyer notification.
- All actions execute within a single `@Transactional` local boundary. No external bank network calls occur within this transaction.

## Rollback Safety
- Tested in `PostgresFinancialConsistencyTest.test24_journalThenProjectionFailureRollsBackAll`:
  - When a controlled failure occurs during escrow projection after journal entry posting, the transaction rolls back completely.
  - Asserted state:
    - Delta `JournalEntry` count = 0
    - Delta `JournalLine` count = 0
    - Delta `EscrowLedger` count = 0
    - Escrow account balance unchanged
  - No orphaned journals without corresponding escrow projections can exist.

## Currency Policy
- **Supported Currency**: `INR` only. FinX currently operates exclusively on the Indian Rupee.
- `LedgerService.validateCurrency(entryCurrency, accountCurrencies, lineCurrencies)` rejects any currency other than `INR` (`IllegalArgumentException("Currency not supported: Only INR is supported in FinX")`).
- `LedgerService` rejects mismatched account currencies or line item currencies.
- `EscrowService.validateSupportedCurrency` rejects any non-`INR` deposit or release attempt.
- Multi-currency and FX conversion are explicitly unsupported and deferred.

## Journal Balance Validation
- `LedgerService.postJournalEntry` enforces exact equality between total debits and total credits:
  ```java
  if (totalDebit.compareTo(totalCredit) != 0) {
      throw new IllegalArgumentException(String.format(
          "Unbalanced journal entry: debits=%s, credits=%s", totalDebit, totalCredit));
  }
  ```
- Amounts are handled using `BigDecimal` with `RoundingMode.HALF_UP`. Floating-point numbers are prohibited.
- Malformed or negative lines are rejected.

## One-Full-Funding Policy
- Preserved from `V4__harden_payment_and_escrow_integrity.sql` and enforced in `EscrowService`:
  - If milestone status is already `FUNDED` or if an existing `FUND` transaction exists for the milestone, second funding attempts are explicitly rejected with `IllegalStateException("Milestone is already funded: " + milestoneId)`.
  - Partial funding is not supported unless explicitly guaranteed by existing product specifications.

## Historical Data Scan
- Scanned existing database tables (`escrow_ledger`, `payments`, `journal_entries`, `journal_lines`, `payment_instructions`) across active databases (`finx_db`, `finx_b05_test`, `finx_b11_test`):
  - Inconsistent `escrow_ledger` rows with unresolvable `payment_id`: **0**
  - Bank `PaymentInstruction` IDs misplaced in `payment_id`: **0**
  - Duplicate journal source effects: **0**
  - Duplicate escrow funding effects: **0**
  - Currency mismatches: **0**
- No historical data repair was required; schema constraints apply cleanly to fresh and migrated databases.

## PostgreSQL Migration
- **Filename**: `backend/src/main/resources/db/migration/V24__harden_financial_source_and_ledger_consistency.sql`
- Additive modifications:
  - Adds `payment_instruction_id UUID REFERENCES payment_instructions(id) ON DELETE SET NULL` to `escrow_ledger`.
  - Adds index `idx_escrow_ledger_payment_instruction_id` on `escrow_ledger(payment_instruction_id)`.
  - Adds partial unique index `uk_escrow_ledger_payment_instruction_id` on `escrow_ledger(payment_instruction_id)` WHERE `payment_instruction_id IS NOT NULL`.
  - Adds check constraint `chk_escrow_ledger_source_exclusivity`.
  - Adds check constraint `chk_escrow_ledger_fund_source`.
  - Adds partial unique index `uk_journal_entries_type_reference` on `journal_entries(transaction_type, reference_id)` WHERE `reference_id IS NOT NULL`.

## PostgreSQL Clean Migration
- Executed via `FlywayCleanMigrationTest` against `finx_b06_test` on `finx_postgres` (PostgreSQL 16.15).
- Clean execution: Applied all 24 migrations (V1 through V24) sequentially from scratch in 3.902 seconds.
- Result: **PASS** (61 tables created, all columns and constraints validated).

## PostgreSQL Upgrade Verification
- Executed against `finx_b05_test` and `finx_b11_test` (both previously at V23).
- Migration `V24` applied cleanly on top of V23 in 0.165s and 0.233s respectively.
- Result: **PASS** (schema successfully upgraded to V24).

## PostgreSQL Concurrency Verification
- Executed via `PostgresFinancialConsistencyTest.test26_concurrentBankFundingRaceFinalizesExactlyOnce`:
  - Raced 2 concurrent finalization attempts across independent database connections with a `CyclicBarrier`.
  - Result: Exactly 1 journal entry, 1 escrow ledger row, 1 balance update. Second attempt safely detected idempotent completion.
  - Zero deadlocks, zero lock timeouts, zero bank calls.
  - Result: **PASS**.

## Interaction With B01
- B01 established that incoming bank confirmations require cryptographic authenticity, and unauthenticated public payment confirmation was closed (`AccessDeniedException`).
- B06 internal finalization is reachable solely via trusted, authenticated provider webhook events or system-verified evidence.

## Interaction With B02
- B02 established authenticated webhook verification for incoming bank transfer credits.
- B06 receives verified UTR and instruction context from B02 webhook handling and finalizes ledger and escrow states atomically.

## Interaction With B05
- B05 established idempotency key scoping (`(deal_id, idempotency_key)`), payload hash binding, and provider UTR uniqueness (`uk_payment_tx_provider_utr`).
- B06 integrates with B05 by using instruction and event identities to enforce journal uniqueness (`uk_journal_entries_type_reference`) and escrow ledger uniqueness (`uk_escrow_ledger_payment_instruction_id`).

## Interaction With B11
- B11 established deterministic lock hierarchies (Deal -> Milestone -> Escrow -> sorted LedgerAccount) and removed unsafe same-transaction retries.
- B06 local finalization strictly conforms to B11 locking protocols and executes within bounded transactional boundaries without lock inversions.

## Interaction With B12
- B12 established clear provenance boundaries separating legacy Razorpay flows from modern bank rails.
- B06 reinforces B12 by enforcing physical database foreign keys: `payment_id` maps exclusively to legacy `payments`, and `payment_instruction_id` maps exclusively to `payment_instructions`.

## Files Modified
1. `backend/src/main/java/com/finx/escrow/entity/EscrowLedger.java`
2. `backend/src/main/java/com/finx/escrow/dto/response/EscrowLedgerResponse.java`
3. `backend/src/main/java/com/finx/escrow/repository/EscrowLedgerRepository.java`
4. `backend/src/main/java/com/finx/escrow/service/EscrowService.java`
5. `backend/src/main/java/com/finx/ledger/repository/JournalEntryRepository.java`
6. `backend/src/main/java/com/finx/ledger/service/LedgerService.java`
7. `backend/src/main/java/com/finx/paymentorchestration/service/PaymentOrchestrationService.java`
8. `backend/src/test/java/com/finx/FlywayCleanMigrationTest.java`
9. `backend/src/test/java/com/finx/paymentorchestration/TestEscrowFundingFixture.java`

## Files Created
1. `backend/src/main/resources/db/migration/V24__harden_financial_source_and_ledger_consistency.sql`
2. `backend/src/test/java/com/finx/security/PostgresFinancialConsistencyTest.java`
3. `B06_REMEDIATION_REPORT.md`

## Database Migration
YES (`V24__harden_financial_source_and_ledger_consistency.sql`)

## API Compatibility
- `EscrowLedgerResponse` includes additive `paymentInstructionId` field while preserving `paymentId`.
- Existing endpoints maintain identical response structures.
- Mismatched or non-INR currency requests fail explicitly with 400 Bad Request.
- Duplicate funding attempts for already-funded milestones fail explicitly with 409 Conflict / 400 Bad Request.

## Tests Added
- `backend/src/test/java/com/finx/security/PostgresFinancialConsistencyTest.java` (11 comprehensive PostgreSQL tests):
  - `test19_bankSourceFkSuccess`: Verifies `payment_instruction_id` populated, `payment_id` null.
  - `test20_wrongSourceFkRejected`: Verifies foreign key constraints block non-existent sources and cross-table leakage.
  - `test21_legacyPreservationSuccess`: Verifies legacy `payment_id` functions normally with null `payment_instruction_id`.
  - `test22_sourceExclusivityEnforced`: Verifies `chk_escrow_ledger_source_exclusivity` and `chk_escrow_ledger_fund_source`.
  - `test23_confirmedEventReplayOnce`: Verifies idempotent replay creates exactly 1 journal entry, 1 escrow ledger, 1 balance change.
  - `test25_duplicateJournalSourceBlocked`: Verifies `uk_journal_entries_type_reference` rejects duplicate journals for same source.
  - `test24_journalThenProjectionFailureRollsBackAll`: Verifies atomic rollback on projection failure.
  - `test27_currencyPolicyEnforced`: Verifies INR-only policy and blocks mismatched currencies.
  - `test28_journalBalanceCheck`: Verifies debits == credits validation with exact `BigDecimal` comparison.
  - `test11_oneFullFundingPolicyEnforced`: Verifies duplicate funding of same milestone is rejected.
  - `test26_concurrentBankFundingRaceFinalizesExactlyOnce`: Multi-connection concurrency race verifying exactly 1 effect.

## Tests Run
- `FlywayCleanMigrationTest`
- `PostgresFinancialConsistencyTest`
- `PostgresFinancialIdentityTest`
- `PostgresLockingConcurrencyTest`
- `FinancialIdempotencyContainmentTest`
- `LockingAndRetryContainmentTest`
- `PaymentConfirmationContainmentTest`
- `BankingWebhookControllerTest`
- `EscrowReleaseContainmentTest`
- `SettlementExecutionContainmentTest`
- `LegacyBoundaryContainmentTest`
- `TenantIsolationContainmentTest`
- `PlatformRoleProvisioningContainmentTest`
- `BeneficiaryVerificationContainmentTest`
- `ApprovalFreshnessContainmentTest`
- Full Maven test suite (`mvnw test`)

## Test Results
- All unit, integration, and PostgreSQL concurrency tests: **PASS**
- `BankingProvider.initiateSettlement` invocations: **0**

## Known Limitations
- Partial funding is not enabled; one-full-funding is strictly enforced.
- Multi-currency and FX conversion are unsupported; INR is the sole supported currency.
- Inconsistent legacy histories (if any exist in outside environments) require reviewed administrative repair.
- Outbound bank payout execution remains strictly disabled (B04).
- B13 risk engine source-read contract remains the next remediation blocker.

## B06 Status
PASS

## Ready for B13
YES
