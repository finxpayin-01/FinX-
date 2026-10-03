# B11 Remediation Report

## Blocker

B11 — Inconsistent locking and retry boundaries expose races

## Status

PASS (Code remediation complete; live PostgreSQL 16 multi-connection concurrency and Flyway migration verified)

## Root Cause

1. **Nondeterministic Ledger Lock Ordering:** `LedgerService.postJournalEntry(...)` iterated through caller-supplied journal entry lines in the order provided, acquiring pessimistic write locks (`accountRepository.findByAccountNumberForUpdate`) on each account sequentially. If Transaction 1 posted from Account A to Account B while Transaction 2 posted from Account B to Account A, lock acquisition occurred in reverse order, exposing the system to potential deadlocks.
2. **Aborted Transaction Reuse During Escrow Creation:** `EscrowService.ensureEscrowAccount(...)` attempted to insert a new `EscrowAccount` and caught `DataIntegrityViolationException` upon collision, then immediately executed a query (`escrowAccountRepository.findByDealId(...)`) within the *same* database transaction. In PostgreSQL and compliant transactional engines, a uniqueness violation marks the transaction rollback-only, causing any subsequent operation in that transaction to fail with an aborted transaction error.
3. **Unprotected Approval & Agreement Writers:** Milestone approval/rejection (`approveMilestone`, `rejectMilestone`) and agreement drafting/signing (`createOrUpdateDraft`, `signAgreement`) used ordinary non-locking reads (`findById`). Concurrent operations (e.g. concurrent approve vs. reject, or concurrent draft updates) could interleave and silently overwrite state.
4. **Inconsistent Lock Ordering Across Financial Paths:** Deposit funding, escrow release, and agreement/milestone mutations lacked a single canonical hierarchy for locking parent `Deal`, child `Milestone`, and `EscrowAccount`.
5. **Approval Applicability Snapshot Incoherence:** The B10 freshness evaluation gathered evidence across multiple independent repository queries without verifying whether the underlying Deal or Milestone had been concurrently modified during the evaluation window.

## Before

Documented exact discovered orders:

* **deposit (`fundEscrow`):**
  1. `milestoneRepository.findById(milestoneId)` (non-locking)
  2. `escrowAccountRepository.findByDealIdForUpdate(dealId)` (pessimistic lock)
  3. `dealRepository.findById(dealId)` (non-locking)
* **settlement (`releaseEscrowForSettlement`):**
  1. `escrowAccountRepository.findByDealIdForUpdate(settlement.getDealId())` (pessimistic lock)
  2. `milestoneRepository.findById(milestoneId)` (non-locking)
  3. `ledgerService.postJournalEntry(...)` (locks accounts in arbitrary line order)
* **legacy payment (`PaymentService.handlePaymentSuccess`):**
  1. `paymentTransactionRepository.findById(...)`
  2. `milestoneRepository.findById(...)` (non-locking)
  3. `escrowService.fundEscrow(...)` (non-canonical order)
* **ledger (`postJournalEntry`):**
  Account locks acquired sequentially following `entry.getLines()` caller order: nondeterministic.
* **approval writers (`approveMilestone`, `rejectMilestone`):**
  1. `milestoneRepository.findById(milestoneId)` (non-locking)
  2. `dealRepository.findById(dealId)` (non-locking)
  (No lock held; concurrent approve and reject could race and corrupt state)
* **escrow account creation (`ensureEscrowAccount`):**
  Attempted `save(new EscrowAccount(...))` without serialization; on `DataIntegrityViolationException`, queried `findByDealId(...)` inside the same aborted transaction.
* **risk transaction boundary:**
  `RiskActionService.recordPayoutReview` executes under `REQUIRES_NEW`, but lacked protection against lock contention if invoked while financial locks were held.
* **external effects reachable:**
  NONE. Bank execution is disabled under B04 (`BankingProvider.initiateSettlement` calls = 0). New Razorpay intake is disabled under B12.

## After

All financial, evidence, and approval writers strictly follow a single global lock hierarchy, sort multi-resource locks deterministically, serialize escrow account creation under parent locks, and enforce snapshot coherence.

## Canonical Lock Order

The canonical hierarchy across all retained financial and evidence paths is:

```
Deal (findByIdForUpdate)
  └── Milestone (findByIdForUpdate)
        └── EscrowAccount (findByDealIdForUpdate)
              └── Ledger Accounts (sorted lexicographically by account number, findByAccountNumberForUpdate)
```

Every writer touching overlapping domain entities acquires locks strictly down this hierarchy:
1. `Deal` lock acquired first via `dealRepository.findByIdForUpdate(dealId)`.
2. `Milestone` lock acquired next via `milestoneRepository.findByIdForUpdate(milestoneId)`.
3. `EscrowAccount` lock acquired next via `escrowAccountRepository.findByDealIdForUpdate(dealId)`.
4. `LedgerAccount` locks acquired last, with all distinct account numbers sorted deterministically prior to acquisition.

## Ledger Lock Ordering

In `LedgerService.postJournalEntry(...)`:
1. Before acquiring any account locks or modifying balances, all distinct account numbers involved in the journal entry are collected:
   ```java
   List<String> sortedAccountNumbers = entry.getLines().stream()
           .map(JournalLine::getAccountNumber)
           .distinct()
           .sorted()
           .toList();
   ```
2. Locks are acquired in strictly ascending lexicographical order:
   ```java
   Map<String, LedgerAccount> lockedAccounts = new HashMap<>();
   for (String accNo : sortedAccountNumbers) {
       LedgerAccount acc = accountRepository.findByAccountNumberForUpdate(accNo)
               .orElseThrow(...);
       lockedAccounts.put(accNo, acc);
   }
   ```
3. Original journal line ordering and debit/credit semantics are strictly preserved when applying balance changes and creating line records.
4. Independent concurrent postings touching accounts `(A, B)` and `(B, A)` both acquire `A` then `B`, eliminating deadlock potential.

## Deal / Milestone Ordering

All methods that manipulate both `Deal` and `Milestone` now acquire locks in identical sequence:
* `MilestoneService.approveMilestone(...)`: Acquires `Deal` lock, then `Milestone` lock.
* `MilestoneService.rejectMilestone(...)`: Acquires `Deal` lock, then `Milestone` lock.
* `EscrowService.fundEscrow(...)`: Acquires `Deal` lock, then `Milestone` lock, then `EscrowAccount` lock.
* `EscrowService.releaseEscrowForSettlement(...)`: Acquires `Deal` lock, then `Milestone` lock, then `EscrowAccount` lock, then sorted ledger account locks.

## Escrow Account Creation

In `EscrowService.ensureEscrowAccount(...)`:
* Creation is serialized under the parent `Deal` lock (`dealRepository.findByIdForUpdate(dealId)`).
* Inside the lock, an existence check (`findByDealId`) is performed. If the account already exists, it is returned immediately.
* If it does not exist, the account is saved.
* Because the parent `Deal` lock serializes concurrent callers, uniqueness conflicts on `deal_id` are prevented before the insert occurs.
* The anti-pattern of catching `DataIntegrityViolationException` and attempting subsequent queries inside an aborted, rollback-only transaction has been completely removed.

## Approval Writer Locking

`MilestoneService`:
* `approveMilestone`: Acquires pessimistic write lock on `Deal` followed by `Milestone`. Validates that milestone status is `SUBMITTED`. Serializes concurrent approval and rejection calls; a racing rejection or approval receives a state conflict rather than silently winning a lost update race.
* `rejectMilestone`: Follows the identical lock sequence (`Deal` -> `Milestone`). Requires reason; validates `SUBMITTED` status under lock.

`AgreementService`:
* `createOrUpdateDraft`: Serialized under parent `Deal` lock (`dealRepository.findByIdForUpdate(dealId)`).
* `signAgreement`: Serialized under parent `Deal` lock (`dealRepository.findByIdForUpdate(dealId)`).

## Approval Applicability Snapshot Coherence

`ApprovalApplicabilityService.evaluateApprovalApplicability(...)`:
* Before returning authorization applicability, re-verifies the state of the parent `Deal` and target `Milestone` against the initial state observed at the start of evaluation:
  - `deal.getUpdatedAt()` equals `finalDeal.getUpdatedAt()`
  - `milestone.getUpdatedAt()` equals `finalMilestone.getUpdatedAt()`
  - `deal.getStatus()` equals `finalDeal.getStatus()`
  - `milestone.getStatus()` equals `finalMilestone.getStatus()`
  - `deal.getProjectManagerId()` equals `finalDeal.getProjectManagerId()`
* If any attribute changed concurrently during evaluation, the evaluation fails closed (`applicable = false`) with reason code `SNAPSHOT_COHERENCE_VIOLATION`.

## Risk Transaction Boundary

* `RiskActionService.recordPayoutReview(...)` runs under `REQUIRES_NEW` to record immutable audit decisions.
* Inbound callers avoid holding long-lived nested locks across risk evaluation where possible, preventing lock inversion.
* Risk evaluation checks remain fail-closed without modifying B13 read-contract behavior.

## Retry Boundary

* In accordance with B11 requirements, retry operations must never occur inside a failed, rollback-only transaction.
* Where retries are required, they must begin only from a fresh transaction boundary.
* In FinX service boundaries, racing or conflicting operations fail fast with clean exceptions (`IllegalStateException`, `BusinessException`) rather than attempting unsafe in-transaction retries.

## External Effect Safety

* No external financial dispatch can be retried or executed:
  - B04 keeps `SettlementAuthorizationService.authorizeAndInitiateSettlement(...)` disabled (fail-closed).
  - Outbound bank execution remains unreachable (`BankingProvider.initiateSettlement` calls = 0).
  - B12 keeps new Razorpay order execution disabled.

## PostgreSQL Concurrency Verification

* Multi-threaded concurrency verification was executed directly against live PostgreSQL 16 (`PostgreSQL 16.15 on x86_64-pc-linux-musl`) running in Docker container `finx_postgres` via dedicated test database `finx_b11_test`.
* All 22 Flyway migrations (V1 through V22) were applied and validated against `finx_b11_test`.
* Dedicated integration test suite `backend/src/test/java/com/finx/security/PostgresLockingConcurrencyTest.java` was executed via:
  `.\mvnw.cmd "-Dtest=PostgresLockingConcurrencyTest" test`
* Runtime details verified:
  - Database Product: `PostgreSQL`
  - Database Version: `16.15`
  - Database Name: `finx_b11_test`
  - Flyway Migrations: PASS (22 migrations applied)
  - Independent Transactions: YES (`Propagation.REQUIRES_NEW` with separate database connections)
  - Multi-threaded Concurrency: `ExecutorService` with 10-second bounded timeouts
  - Ledger Reversed-Order Concurrency: PASS (lines sorted deterministically; 0 deadlocks)
  - Escrow Creation Race: PASS (serialized under Deal lock; exactly 1 account created; 0 collisions)
  - Approve/Reject Race: PASS (Deal->Milestone locks serialize; exactly 1 succeeds, racing rejection fails validation; 0 lost updates)
  - Agreement Concurrency: PASS (draft updates serialized under Deal lock)
  - Approval Snapshot Coherence: PASS (fails closed on concurrent mutation with `SNAPSHOT_COHERENCE_VIOLATION`)
  - Risk Contention: PASS (`REQUIRES_NEW` risk reviews execute cleanly without lock inversion)
  - Settlement Fail-Closed: PASS (B04 containment verified; 0 calls to `BankingProvider.initiateSettlement`)
  - PostgreSQL Deadlocks: 0
  - PostgreSQL Timeouts: 0
  - Outbound Bank Execution Reachable: NO (`BankingProvider.initiateSettlement` calls = 0)
  - Results: Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 100.6 s (BUILD SUCCESS)

## Interaction With B01

B01 (Manual payment confirmation containment) remains intact. Deposit confirmation endpoints remain disabled. Any retained local funding path obeys the canonical lock order.

## Interaction With B02

B02 (Banking webhook authentication and signature verification) remains intact.

## Interaction With B03

B03 (Direct escrow release containment) remains intact. Direct release endpoints remain fail-closed.

## Interaction With B04

B04 (Settlement authorization fail-closed) remains intact. Bank payout execution is completely disabled (`BankingProvider.initiateSettlement` calls = 0).

## Interaction With B12

B12 (Legacy payment boundary containment) remains intact. Razorpay order creation in BANK mode remains disabled.

## Interaction With B07

B07 (Tenant isolation) remains intact. All tenant boundary checks across organizations, deals, and milestones continue to be strictly enforced.

## Interaction With B08

B08 (Platform role provisioning containment) remains intact. Public registration of platform roles remains prohibited.

## Interaction With B09

B09 (Beneficiary and bank account verification authority) remains intact. Penny-drop mock sandbox and verification rules remain enforced.

## Interaction With B10

B10 (Approval freshness and evidence invalidation) remains intact. `ApprovalApplicabilityService` is hardened with snapshot coherence re-verification.

## Review of Deal.java Change

* During initial B11 development, a `setUpdatedAt(Instant)` setter was added to `Deal.java` for test convenience.
* Per Section 14 instructions, this modification was thoroughly reviewed and classified as TEST-ONLY convenience.
* `Deal.java` has been **completely REVERTED** (`git diff` is empty).
* The test (`test14` in `LockingAndRetryContainmentTest.java`) now uses reflection to simulate timestamp changes without altering the entity's production API.

## Files Modified

* `backend/src/main/java/com/finx/ledger/service/LedgerService.java`: Deterministic account number sorting and locking prior to journal posting.
* `backend/src/main/java/com/finx/escrow/service/EscrowService.java`: Serialized escrow account creation under parent `Deal` lock; removed aborted transaction reuse; aligned `fundEscrow` and `releaseEscrowForSettlement` to canonical lock order.
* `backend/src/main/java/com/finx/milestone/service/MilestoneService.java`: Added pessimistic locks on Deal and Milestone in `approveMilestone` and `rejectMilestone`.
* `backend/src/main/java/com/finx/agreement/service/AgreementService.java`: Added parent `Deal` pessimistic locking in `createOrUpdateDraft` and `signAgreement`.
* `backend/src/main/java/com/finx/paymentorchestration/service/ApprovalApplicabilityService.java`: Added snapshot coherence re-verification across evaluation boundary.

## Files Created

* `backend/src/test/java/com/finx/security/LockingAndRetryContainmentTest.java`: Comprehensive B11 containment test suite (22 tests).
* `backend/src/test/java/com/finx/security/PostgresLockingConcurrencyTest.java`: Dedicated live PostgreSQL 16 multi-connection concurrency test suite (7 tests).
* `B11_REMEDIATION_REPORT.md`: This remediation report.

## Database Migration

NONE

No Flyway migrations were created or modified. Existing database schema and pessimistic locking constructs (`PESSIMISTIC_WRITE`) were used.

## API Compatibility

Racing or stale commands that previously risked silent lost updates or lock deadlocks will now deterministically serialize or return conflict/state validation errors (`IllegalStateException`, HTTP 400/409).

## Tests Added

`backend/src/test/java/com/finx/security/LockingAndRetryContainmentTest.java`:
1. `test01_canonicalLockOrder_milestoneApproval_locksDealThenMilestone`
2. `test02_canonicalLockOrder_milestoneRejection_locksDealThenMilestone`
3. `test03_canonicalLockOrder_agreementDraft_locksDeal`
4. `test04_canonicalLockOrder_agreementSigning_locksDeal`
5. `test05_canonicalLockOrder_escrowFunding_locksDealThenMilestoneThenEscrow`
6. `test06_canonicalLockOrder_escrowRelease_locksDealThenMilestoneThenEscrow`
7. `test07_ledgerLockOrder_sortsAccountNumbersDeterministically`
8. `test08_ledgerLockOrder_reversedOrder_bothSortedDeterministically`
9. `test09_ledgerPosting_multiThreadedReversedOrder_noDeadlock`
10. `test10_ensureEscrowAccount_serializesUnderDealLock_returnsExisting`
11. `test11_ensureEscrowAccount_createsWhenNotPresentUnderLock`
12. `test12_milestoneApproveReject_raceProtection_secondCallerFails`
13. `test13_agreementDraftAndSign_raceProtection`
14. `test14_approvalApplicability_failsClosedOnConcurrentDealMutation`
15. `test15_approvalApplicability_failsClosedOnConcurrentMilestoneMutation`
16. `test16_approvalApplicability_failsClosedOnConcurrentPmReassignment`
17. `test17_externalEffects_bankExecutionCallsRemainZeroDuringLocking`
18. `test18_externalEffects_razorpayExecutionRemainsZeroDuringLocking`
19. `test19_retryBoundary_noAutomaticRetryInsideAbortedTransaction`
20. `test20_riskRequiresNew_doesNotHoldFinancialLocks`
21. `test21_multipleMilestoneLocking_sortsDeterministically`
22. `test22_staleCallerLosesRace_noSilentOverwrite`

`backend/src/test/java/com/finx/security/PostgresLockingConcurrencyTest.java`:
1. `test01_postgres_reversedOrderLedgerConcurrency`
2. `test02_postgres_concurrentEscrowAccountCreation`
3. `test03_postgres_concurrentApproveVsReject`
4. `test04_postgres_concurrentAgreementMutation`
5. `test05_postgres_approvalApplicabilityFailsClosedOnMutation`
6. `test06_postgres_riskPayoutReviewContentionBoundary`
7. `test07_postgres_settlementRemainsFailClosed`

## Tests Run

1. `.\mvnw.cmd "-Dtest=PostgresLockingConcurrencyTest" test` (Live PostgreSQL 16 on `finx_b11_test`)
2. `.\mvnw.cmd "-Dtest=LockingAndRetryContainmentTest" test`
3. `.\mvnw.cmd "-Dtest=PaymentConfirmationContainmentTest,BankingWebhookControllerTest,EscrowReleaseContainmentTest,SettlementExecutionContainmentTest,LegacyBoundaryContainmentTest,TenantIsolationContainmentTest,PlatformRoleProvisioningContainmentTest,BeneficiaryVerificationContainmentTest,ApprovalFreshnessContainmentTest,LockingAndRetryContainmentTest" test`
4. `.\mvnw.cmd test` (Full backend test suite)

## Test Results

* **PostgresLockingConcurrencyTest (Live PostgreSQL 16 + Flyway):** Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 (BUILD SUCCESS)
* **LockingAndRetryContainmentTest:** Tests run: 22, Failures: 0, Errors: 0, Skipped: 0 (BUILD SUCCESS)
* **Prior Blocker Containment Regression (10 suites):** Tests run: 217, Failures: 0, Errors: 0, Skipped: 0 (BUILD SUCCESS)
* **Full Backend Suite:** Tests run: 555, Failures: 0, Errors: 0, Skipped: 1 (BUILD SUCCESS)
* **Overall Result:** PASS

## Known Limitations

* B05 / B06 still provide final financial effect uniqueness and double-entry balancing constraints.
* B13 still fixes stable read-only risk assessment identity.
* Bank execution remains completely disabled under B04.
* No optimistic `@Version` columns were added; pessimistic locking and snapshot coherence were used.

## B11 Status

PASS

## Ready for Next Blocker

YES
