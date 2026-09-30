# FinX EngineDecision Framework: Blocker Remediation Plan

## 1. Scope and readiness gate

This plan follows a complete reading of `ENGINE_IMPLEMENTATION_PLAN.md` and targeted verification of the current settlement, payment, banking, ledger, identity, risk, legacy, schema and test code. It supersedes the earlier document's broad readiness interpretation **only for introducing the shared framework**, not for enabling production money movement.

Only this document is created. No engines, Java changes, frontend changes, migrations, configuration changes, legacy removals, test runs or provider calls are performed. Findings are static source findings; deployed credentials, live bank activity, production database state and applied migrations were not inspected. The supplied 325-test baseline remains unverified in this task.

The gate assessed here is: introduce a tenant-scoped, auditable, decision-recording framework around trusted existing evidence, with **no authority to dispatch money or treat a stored ALLOW as authorization**. Writing an isolated enum is not inherently unsafe; integrating a framework into the current application without these boundaries is unsafe.

Priority definitions:

- **P0:** existing financial/security correctness defect. Fix it or enforce the specific fail-closed containment described below before it can influence the framework.
- **P1:** existing architecture behavior that prevents a reliable shared evidence/decision boundary.
- **P2:** important existing issue or production-readiness work that can remain outside the initial framework's trusted inputs and execution capabilities.
- **P3:** future improvement to existing infrastructure, not a readiness gate.

P2/P3 entries are disposition records explaining why they are excluded from the minimum set. The requested final counters use the word BLOCKERS for all priority buckets, but only P0/P1 are prerequisites here. Missing Trust, Fraud, Approval or Release engines, an EngineDecision table, and a ReleaseInstruction model are **not defects and are not counted**.

Containment is an explicit code/configuration boundary with negative tests, not an assumption that credentials are absent or that a page is hidden. It may temporarily disable an unsafe operation. If the product requires that operation to remain enabled, its full targeted correction must be completed instead. No containment is implemented by this document.

Source prefixes used to keep exact file lists readable:

- `J/` = `backend/src/main/java/com/finx/`
- `M/` = `backend/src/main/resources/db/migration/`
- `T/` = `backend/src/test/java/com/finx/`

Each path below is an exact repository-relative file after prefix expansion. Migration YES/NO refers to the **smallest remediation specified for the framework gate**, not the entire future banking platform. Historical migrations are evidence only and must not be edited.

## 2. Actual current settlement and financial paths

### CURRENT FLOW: actual order, not the intended architecture

```text
POST /api/v1/settlements or /api/v1/settlements/authorize
  -> PaymentOrchestrationController.authorizeSettlement
  -> SettlementAuthorizationService.authorizeAndInitiateSettlement
     [one outer @Transactional transaction]
     -> lock milestone, then deal
     -> optional GLOBAL idempotency lookup; may RETURN before caller authorization
     -> buyer/admin authorization
     -> seller KYB and verified-bank lookup, with legacy user-ID fallbacks
     -> deal/agreement/milestone/invoice/balance/dispute checks
     -> RiskActionService.payoutReviews [REQUIRES_NEW]
     -> check for an existing SETTLED instruction only
     -> construct settlement reference and transient bank request
     -> BankingProvider.initiateSettlement                 EXECUTION FIRST
     -> construct SettlementInstruction                    INSTRUCTION SECOND
     -> copy provider ID/UTR; force status SETTLED
     -> save instruction [not yet committed]
     -> LedgerService.postJournalEntry
     -> EscrowService.releaseEscrow
        -> debit escrow projection; append RELEASE; complete milestone/deal
     -> audit + notifications
     -> commit outer transaction

SEPARATE later request:
POST /api/v1/reconciliation/trigger (also /api/reconciliation/trigger)
  -> ReconciliationService.triggerReconciliation
  -> BankingProvider.fetchTransactions + internal records -> run/items
```

Thus the actual order is **request -> authorization/checks -> external execution -> instruction persistence -> journal -> escrow projection -> commit; reconciliation is independent**. There is no durable pre-dispatch authorization/instruction boundary. Reconciliation is not invoked as part of settlement and no reconciliation scheduler was found.

Other active mutation paths:

| Entry point / caller | Actual effect | Boundary problem |
|---|---|---|
| `EscrowController.releaseEscrow` -> `EscrowService.releaseEscrow` | Internal balance and milestone completion; no bank payout and no double-entry settlement journal | Bypasses settlement's KYB, invoice, bank and risk checks. Do not describe this as an actual bank transfer. |
| `PaymentOrchestrationController.confirmPayment` -> `PaymentOrchestrationService.confirmPayment` | Declares transaction/instruction SUCCESS, posts deposit journal and credits escrow | Caller identity is not checked against permission to assert a bank credit. |
| `BankingWebhookController.handleBankingWebhook` -> same confirmation | Public webhook can drive funding | Authentication optional; replay handling is process-local. |
| `PaymentService.verifyPayment` / `processWebhook` -> `EscrowService.fundEscrow` | Razorpay payment SUCCESS credits shared escrow projection | Separate legacy evidence lineage; no `LedgerService` call in this service. |
| `PaymentOrchestrationService.createPaymentInstruction` | Calls provider before saving instruction | Instruction creation also has external-call/rollback ambiguity, although it is not an outbound settlement. |

The active Java call-site search found `SettlementAuthorizationService` as the application caller of `BankingProvider.initiateSettlement`. The interface declaration and mock implementation are not additional application execution paths. Legacy blockchain classes are outside the Maven application source tree.

### Desired future boundary: not an implementation prerequisite in full

```text
ReleaseDecisionEngine
  -> ReleaseInstruction
  -> Authorization
  -> BankingExecutionService
  -> BankProvider [use existing BankingProvider interface]
  -> trusted final bank outcome
  -> Ledger [existing LedgerService + escrow projection]
  -> Reconciliation [existing ReconciliationService]
```

Decisions express eligibility; authorization grants permission for a particular immutable instruction; the execution service is the only outbound settlement caller. Persist intent before dispatch; never hold a database transaction across the bank call. Accepted/processing/unknown are not settled. Final accounting follows trusted finality and is idempotent. Reconciliation verifies and recovers outcomes; it does not create fictitious bank success.

The initial shared framework needs a tested **no-execution dependency boundary**, not a completed ReleaseDecisionEngine, ReleaseInstruction subsystem, worker platform or real bank integration. The smallest safe path is to fail closed on the current combined settlement operation until its separate execution remediation is ready.

## 3. P0 financial and security blockers

### B01 — P0 — Untrusted payment confirmation creates financial truth

**Files:** `J/paymentorchestration/controller/PaymentOrchestrationController.java`; `J/paymentorchestration/service/PaymentOrchestrationService.java`; `J/paymentorchestration/dto/PaymentOrchestrationDtos.java`; `J/security/config/SecurityConfig.java`.

**Class/method:** controller and service `confirmPayment`.

**Current behavior:** any authenticated caller can reach confirmation by instruction ID. The service checks neither role nor ownership nor bank proof, creates SUCCESS using the instruction's expected amount/currency, and fabricates a UTR when absent. The same method accepts null principal from the webhook. It can also reprocess non-SUCCESS states without a strict transition policy.

**Why unsafe:** expected funds become asserted received funds; downstream risk or decision records could rely on manufactured funding. An FK failure in one schema does not make this authorization safe.

**Target behavior:** only trusted, validated bank facts can confirm a deposit. Public business actors cannot supply confirmation truth. Provider/account/instruction, actual amount, currency, reference and terminal status must agree; reversed/cancelled states require explicit transitions.

**Smallest safe remediation:** deny the existing public confirmation command and place a service-level trusted-ingestion boundary around it. Keep sandbox simulation separately gated and visibly nonproduction. If trusted ingestion remains enabled, validate all credit facts and reject missing financial identity; do not generate a bank UTR. Until that is ready, reject confirmation and exclude unverified historical funding from trusted framework evidence.

**Tests required:** ordinary user, unrelated tenant and privileged human without bank evidence denied; null principal cannot bypass a service guard; amount/currency/account/instruction mismatch; missing UTR/provider event; late success after reversal; legitimate trusted credit succeeds only through B02/B05/B06 controls.

**Database migration required:** NO for containment and validation; identity/schema protections are tracked in B05/B06.

**API compatibility impact:** clients using `/payments/instructions/{id}/confirm` as a simulator will receive a denial. Do not preserve unsafe semantics for compatibility.

**Dependencies:** B02 for webhook trust; B05 for dedupe; B06 for posting integrity; B07 for scope.

### B02 — P0 — Bank webhook authentication and replay control fail open

**Files:** `J/banking/controller/BankingWebhookController.java`; `J/banking/provider/BankingProvider.java`; `J/banking/provider/mock/MockBankingProvider.java`; `J/security/config/SecurityConfig.java`; `J/payment/entity/WebhookEvent.java`; `J/payment/repository/WebhookEventRepository.java`; `M/V8__create_onramp_transactions.sql`.

**Class/method:** `handleBankingWebhook`, `verifyWebhookSignature`.

**Current behavior:** missing signature skips verification; missing timestamp is accepted and malformed timestamp is ignored. Path provider is not bound to the configured adapter. Event IDs may be blank. A local `processedEventIds` set records the ID before confirmation succeeds, is lost on restart, and is not shared between replicas. V8 already provides unique `(provider, provider_event_id)` in `webhook_events` but this controller does not use it.

**Why unsafe:** forged credits, replay across replicas/restart, and lost retries after failed processing. A mock signature does not authenticate a production payload.

**Target behavior:** authenticate exact raw payload using the selected provider contract, require valid event identity/replay metadata, and atomically deduplicate processing using durable identity and payload hash.

**Smallest safe remediation:** reject unauthenticated/malformed events and unknown providers; restrict mock ingestion to a sandbox boundary. Reuse the existing inbox table for synchronous transactional insert/confirm/status update; on rollback a valid retry must remain processable. Reject same identity/different hash. No generic outbox or new inbox table is required. Disable ingestion if a configured provider cannot authenticate it.

**Tests required:** absent/blank/invalid signature, malformed/stale timestamp, provider mismatch, blank ID, changed body with same ID; two instances process same event once; crash/restart and business failure permit safe retry; audit failure does not leave an acknowledged but unrecorded credit.

**Database migration required:** NO for the minimal synchronous use of V8. Add future schema only if a provider account namespace or asynchronous retry contract needs fields not already present.

**API compatibility impact:** permissive test clients must send authenticated sandbox events. Return success only after durable receipt/processing according to the chosen contract.

**Dependencies:** B01, B05, B06. Inbox event IDs must be namespaced consistently with provider configuration.

### B03 — P0 — Direct escrow release bypasses settlement controls

**Files:** `J/escrow/controller/EscrowController.java`; `J/escrow/service/EscrowService.java`; `J/paymentorchestration/service/SettlementAuthorizationService.java`.

**Class/method:** `EscrowController.releaseEscrow`, `EscrowService.releaseEscrow`.

**Current behavior:** `POST /api/escrow/{milestoneId}/release` allows buyer/admin to reduce escrow and mark the milestone complete after narrower checks, independently of bank settlement and its invoice/KYB/risk gates.

**Why unsafe:** the same business concept has two incompatible completion authorities. A common decision can be bypassed and internal release can be mistaken for paid-out money.

**Target behavior:** escrow release is an internal projection update of a validated financial outcome, not an independent public command.

**Smallest safe remediation:** fail closed on the public release route and guard the mutation against untrusted internal callers. Do not redirect it to the unsafe combined method. Preserve reads and historical release entries. Once B04 is corrected, projection updates require the corresponding confirmed settlement identity.

**Tests required:** buyer/admin/PM cannot bypass via old endpoint; directly invoking mutation without trusted settlement context fails; historical reads remain; confirmed finalization changes the projection only once.

**Database migration required:** NO for containment; source linkage/dedupe belongs to B06.

**API compatibility impact:** old release button/API no longer promises disbursement. A disabled response is safer than simulated success.

**Dependencies:** B04 and B06 for subsequent re-enablement of internal finalization.

### B04 — P0 — Coupled authorization, external execution, rollback and forced success

**Files:** `J/paymentorchestration/service/SettlementAuthorizationService.java`; `J/paymentorchestration/controller/PaymentOrchestrationController.java`; `J/paymentorchestration/service/PaymentOrchestrationService.java`; `J/paymentorchestration/entity/SettlementInstruction.java`; `J/paymentorchestration/enums/PaymentOrchestrationEnums.java`; `J/banking/dto/BankingDtos.java`; `J/banking/provider/BankingProvider.java`.

**Class/method:** `authorizeAndInitiateSettlement`, `authorizeSettlement`, `createPaymentInstruction`.

**Current behavior:** authorization calls the bank while holding local transaction locks, before instruction persistence. Every returned result is marked SETTLED without checking status. Journal/audit/escrow failure can roll back the local record after bank acceptance. No durable uncertain-attempt recovery exists. The instruction-creation path also calls externally before saving.

**Why unsafe:** a caller cannot inspect eligibility without triggering execution; retry can duplicate an irreversible external effect; PENDING/FAILED may be represented as successful financial history.

**Target behavior:** authorization and execution have separate interfaces and transaction boundaries, durable intent precedes dispatch, status changes reflect bank facts, and unknown outcome never becomes automatic fresh retry. No framework-facing service can reach this combined command.

**Smallest safe remediation for framework introduction:** fail closed on combined payout execution at both service and route boundaries; prohibit framework dependencies on it and provider/ledger mutation APIs. Keep existing code and history. An isolated, nonmutating evidence read must not call the command. Also do not enable new external instruction creation through the framework. This closes the framework gate without implementing any engine.

**Required before restoring execution:** separate authorization from a bank executor; commit the existing settlement intent and stable key first, call externally outside the transaction, then record honest outcome and idempotent finalization. Pending intent must reserve funds under a shared lock. Unknown attempts block a new attempt until resolved by a trusted status query/manual recovery. Do not implement a naive extract-method that leaves the call inside the original transaction. This is follow-up financial-service repair, not a prerequisite to creating the common model while execution is disabled.

**Tests required:** all public/internal command paths fail closed under containment; no provider call/journal/escrow write from framework-facing reads. Before restoration: failure before/after bank acceptance, timeout, DB/audit failure, callback/poll races, accepted/failed/pending statuses, restart recovery and insufficient shared funds.

**Database migration required:** NO for the specified minimum containment. YES later for durable attempt/snapshot/reservation fields if execution is restored.

**API compatibility impact:** settlement endpoints return unavailable/disabled rather than 201 disbursed. Future asynchronous completion changes semantics and needs explicit client migration.

**Dependencies:** B03 prevents alternative release; B05/B06/B09/B10/B11 must protect any restored execution. The containment itself can ship first.

### B05 — P0 — Idempotency and financial-effect identity are insufficient

**Files:** `J/paymentorchestration/service/SettlementAuthorizationService.java`; `J/paymentorchestration/service/PaymentOrchestrationService.java`; `J/paymentorchestration/repository/SettlementInstructionRepository.java`; `J/paymentorchestration/repository/PaymentTransactionRepository.java`; `J/paymentorchestration/entity/SettlementInstruction.java`; `M/V13__bank_payment_orchestration_and_settlement.sql`; `M/V8__create_onramp_transactions.sql`; `M/V4__harden_payment_and_escrow_integrity.sql`.

**Class/method:** settlement/funding idempotency lookup, `confirmPayment`, repository identity queries.

**Current behavior:** keys optional and globally unique; settlement returns stored result before buyer/admin check; funding checks requested deal but not the stored key's subject. Request payloads are not bound to keys. V13 has unique settlement reference/key, **not** unique milestone settlement or provider settlement identity. UTR is indexed for incoming transactions, not unique. Process-local counters create references. Only SETTLED is checked, excluding pending or uncertain attempts.

**Existing protection:** milestone/deal locks serialize ordinary concurrent calls; V4 blocks duplicate escrow RELEASE; transaction references can reject duplicate confirmations. Therefore duplicate payout is a risk under rollback/retry/alternate-state paths, not a claim that every simultaneous happy-path call currently pays twice. The late escrow constraint cannot undo a bank call.

**Why unsafe:** replays can return another subject's data; changing client keys can evade command-level dedupe; different instructions can reuse one bank credit; restarts/uncertain payouts lose stable identity.

**Target behavior:** authorize before replay; bind key to org/operation/subject/payload; unique business effect independent of HTTP key; stable references; provider identity is scoped correctly.

**Smallest safe remediation:** fix lookup order and compare stored request/subject, using V8 request-hash capability rather than duplicating an idempotency system. Require keys on retained financial commands, namespace them and use durable UUID-derived references. Add a constraint preventing multiple active/settled full settlements per milestone, and provider-confirmed credit identity uniqueness. Resolve existing duplicates before enforcement. Preserve failed attempt history; uncertain attempts count as active. If a provider has only UTR identity, establish documented provider/account/direction scope rather than asserting global UTR uniqueness. Keep execution disabled under B04 until these constraints are exercised.

**Tests required:** same key/same body, changed body, another org/deal's key, blank key, different keys for one milestone, same bank credit against two instructions, restart reference collisions, pending/unknown repeat attempt, and valid distinct provider namespaces. PostgreSQL tests must assert one effect, not merely one successful HTTP response.

**Database migration required:** YES, new uniqueness/identity constraints and any missing scope/hash fields; reuse V8/V13 tables, never edit their migrations.

**API compatibility impact:** mandatory key and conflict response for different payload; previously accepted duplicate financial references rejected.

**Dependencies:** B07 for validated scope; B02 for authenticated provider identity; B04 for pre-dispatch enforcement. B06 owns journal/projection uniqueness separately.

### B06 — P0 — Financial source references and ledger consistency disagree

**Files:** `J/paymentorchestration/service/PaymentOrchestrationService.java`; `J/escrow/service/EscrowService.java`; `J/escrow/entity/EscrowLedger.java`; `J/ledger/service/LedgerService.java`; `J/ledger/repository/JournalEntryRepository.java`; `M/V3__create_milestones_payments_escrow_disputes_notifications.sql`; `M/V4__harden_payment_and_escrow_integrity.sql`; `M/V12__double_entry_ledger.sql`; `T/FlywayCleanMigrationTest.java`; `backend/src/main/resources/application-test.yml`.

**Class/method:** `confirmPayment` -> `fundEscrow`; `LedgerService.postJournalEntry`.

**Current behavior:** bank funding passes a PaymentInstruction ID into `escrow_ledger.payment_id`, whose V3 FK references legacy `payments`. No later correction was found. Journal source reference is indexed but not unique. Account balances are initialized for INR and posting does not establish account/entry currency equality. V4 milestone/type uniqueness also applies to FUND, constraining multiple funding entries.

**Why unsafe:** bank funding should fail on the migrated FK unless a coincidental legacy ID exists, while JPA-created H2 tests can conceal the difference. Evidence may disagree between journal and projection, or aggregate unlike currencies. Header debit=credit does not independently enforce line totals against arbitrary writes.

**Target behavior:** explicit correct source FK, one journal/projection effect per confirmed event, one currency per posting, no silent reinterpretation of historical balances.

**Smallest safe remediation:** add a separate bank-source reference while preserving legacy payment references; enforce source exclusivity and confirmed-event uniqueness. Post journal and projection in one local finalization transaction. Reject unsupported/mismatched currency rather than implement multicurrency now. Keep one-full-funding policy explicit initially; do not remove V4 protections just to make partial funding work. Identify inconsistent existing histories and exclude them from trusted decisions pending reviewed repair; do not silently synthesize balancing entries.

**Tests required:** PostgreSQL clean/upgrade Flyway bank deposit, legacy deposit preservation, wrong source FK, replay once, rollback after journal before projection, valid balanced lines and matching currency, duplicate source, unsupported partial funding rejection. Existing H2 tests are retained but insufficient.

**Database migration required:** YES, additive bank FK/source constraints and journal effect uniqueness.

**API compatibility impact:** response shapes can remain; unsupported currency/funding patterns fail explicitly. No destructive rewrite of old records.

**Dependencies:** B01/B02 for trusted facts, B05 for event identity, B11 for transaction/locking protocol, B12 for legacy segregation.

### B07 — P0 — Tenant binding, membership and IDOR checks are incomplete

**Files:** `J/deal/service/DealService.java`; `J/org/service/OrganizationService.java`; `J/org/controller/OrganizationController.java`; `J/paymentorchestration/service/PaymentOrchestrationService.java`; `J/paymentorchestration/service/SettlementAuthorizationService.java`; `J/dispute/controller/DisputeController.java`; `J/dispute/service/DisputeService.java`; `J/reconciliation/controller/ReconciliationController.java`; `J/reconciliation/service/ReconciliationService.java`.

**Class/method:** `createDeal`, `validateOrgMembership`, `validateOrgRole`, `getInstructionsForDeal`, `getSettlementsForDeal`, `getDisputesForDeal`, reconciliation reads/trigger, `getVerifiedBusinessProfile`.

**Current behavior:** supplied deal org IDs are copied without party membership binding; membership helpers check existence/role but not ACTIVE status. Financial/dispute reads omit ownership checks. Reconciliation operations rely only on generic authentication. Organization profile has no caller scope and includes global metrics. Bank instruction constructor receives null org fields despite available deal orgs.

**Why unsafe:** framework organization ownership could legitimize forged links, leak decisions/evidence and allow inactive members to act. Authentication and globally unique UUIDs do not establish resource access.

**Target behavior:** derive scope from validated domain ownership; active organization authority, deal participation and platform roles are distinct. Both participants may see permitted deal data; private counterparty evidence is not automatically shared.

**Smallest safe remediation:** centralize and enforce service-level active membership/party access; validate buyer/user-to-buyer-org and seller/user-to-seller-org binding; populate existing instruction org fields; restrict reconciliation to provisioned platform finance/admin. Restrict the profile surface or explicitly sanitize its public contract. Exclude unmapped/invalid legacy subjects from trusted framework evaluation; do not guess an org from a user UUID. Database RLS rollout is not required to perform these corrections.

**Tests required:** cross-org GET/POST/replay, buyer/seller org swapping, inactive member, assigned/unassigned PM, provisioned platform admin/finance, null-org historical deal, counterparty-private evidence; service invocation tests in addition to controller tests.

**Database migration required:** NO for smallest correction using existing columns and exclusion of invalid rows. Reviewed legacy backfill/stronger relational constraints may follow separately.

**API compatibility impact:** unauthorized requests now denied; legacy unbound deals cannot become trusted new decisions until repaired.

**Dependencies:** B08 for trustworthy platform roles. B05 must authorize the stored resource as well as the requested one.

### B08 — P0 — Public FINANCE registration grants platform authority

**Files:** `J/auth/service/AuthService.java`; `J/auth/dto/request/RegisterRequest.java`; `J/common/enums/Role.java`; `J/security/config/SecurityConfig.java`; `J/finance/controller/FinanceController.java`; `J/finance/controller/FinanceProjectController.java`.

**Class/method:** `AuthService.register`, security finance matchers, finance summary/ledger/project methods.

**Current behavior:** registration rejects ADMIN only and accepts FINANCE; finance endpoints permit FINANCE and expose global records. Organization FINANCE is a separate concept but does not limit this platform role.

**Why unsafe:** an untrusted registrant gains privileged financial visibility; future decision review endpoints cannot rely on this role as trustworthy authority.

**Target behavior:** public registration cannot provision platform finance; only controlled provisioning grants platform privileges.

**Smallest safe remediation:** allowlist public roles excluding FINANCE/ADMIN; retain organization finance through active membership. Review and revoke/quarantine existing unprovisioned platform FINANCE assignments, without blindly revoking legitimate operators. Merely blocking new registrations does not neutralize existing grants.

**Tests required:** direct JSON FINANCE/ADMIN registration denied; aliases cannot bypass; legitimate user registration retained; unprovisioned existing finance loses global access; explicitly provisioned finance retains intended scope.

**Database migration required:** NO. Existing account remediation is controlled data review, not a schema change.

**API compatibility impact:** FINANCE is no longer a public signup option. Existing unauthorized accounts lose privileges.

**Dependencies:** none for containment; B07 applies consistent object scope afterwards.

### B09 — P0 — Beneficiary and verification authority can be misbound

**Files:** `J/org/service/OrganizationService.java`; `J/org/repository/OrganizationBankAccountRepository.java`; `J/org/entity/OrganizationBankAccount.java`; `J/verification/service/VerificationService.java`; `J/verification/service/VerificationEligibilityService.java`; `J/agreement/service/AgreementService.java`; `J/paymentorchestration/service/SettlementAuthorizationService.java`.

**Class/method:** both services' `addBankAccount`/`verifyBankAccount`, `signAgreement`, settlement beneficiary selection, eligibility validation helpers.

**Current behavior:** both bank verification paths check that the supplied account belongs to the org; that check **already exists**. The weaknesses are upstream organization binding, inactive membership, two unequal verification paths, and settlement fallback from org ID to user ID. Older org verification sends masked account data. The richer path uses encrypted account data and name match, but can fall back to masked data. Settlement selects an arbitrary matching VERIFIED account via Optional, passes masked account number and stores only an account FK. Eligibility helpers for KYC/signing authority/production money movement exist but are not wired into those financial paths.

**Why unsafe:** a VERIFIED flag is not sufficient evidence that the chosen, current beneficiary belongs to the correct legal party and is usable by a bank. Multiple verified accounts can produce ambiguity; changed verification/authority can outlive a previous approval.

**Target behavior:** one canonical verification route, real validated party/account ownership, current signing/verification authority and explicit beneficiary selection; existing approval must never silently retarget to another account.

**Smallest safe remediation for framework:** close the weaker verification bypass, reject masked-only/ambiguous beneficiary evidence as sufficient verification, remove user-ID-as-org fallback for new trusted evaluations, and enforce active membership and applicable verification expiry/authority. Keep all payout use disabled by B04; the framework may record source status/provenance but cannot label sandbox/legacy verification production-ready. No beneficiary snapshot table is needed to achieve that restricted boundary.

**Before restoring payout:** pin account identity/verification fingerprint to the existing authorized instruction; invalidate on change and recheck immediately before dispatch. Store the immutable destination reference/snapshot securely. Reuse verification components, not a new Trust Engine.

**Tests required:** correct org/wrong account rejected on both APIs; forged org binding, suspended owner, multiple verified accounts, masked-only account, verification expiry/revocation, authority missing, sandbox flag; account switch after authorization cannot redirect a payout.

**Database migration required:** NO for the specified containment/validation. YES for persistent beneficiary snapshot/version binding before payout restoration.

**API compatibility impact:** old verification paths delegate to the canonical path or deny; ambiguous/unbound legacy records require remediation, not fallback.

**Dependencies:** B07/B08; B04 prevents unsafe execution; B10/B11 govern later revalidation.

### B10 — P0 — Existing approvals can become stale without invalidation

**Files:** `J/milestone/service/MilestoneService.java`; `J/milestone/entity/Milestone.java`; `J/agreement/service/AgreementService.java`; `J/invoice/service/InvoiceService.java`; `J/paymentorchestration/service/SettlementAuthorizationService.java`; `J/dispute/service/DisputeService.java`; `J/projectmanager/service/ProjectManagerInvitationService.java`.

**Class/method:** milestone approve/reject, agreement draft/sign, invoice review, settlement gates, dispute resolve, PM assignment.

**Current behavior:** milestone approval is a status mutation without revision-bound approval evidence. Invoice review does check current fingerprint/latest revision at review time, but settlement only checks invoice APPROVED. Agreement, account verification and assignment can change afterward. Dispute resolution restores ACCEPTED without a versioned prior state.

**Why unsafe:** a shared decision could incorrectly present an old approval as authorization of current evidence. This is an existing stale-state issue, not absence of a future Approval Engine.

**Target behavior:** distinguish historical approval from current applicable approval; material input changes invalidate eligibility. A stored financial decision is always evidence about a particular state/time, never a permanent permission.

**Smallest safe remediation for framework:** define trusted source-read contracts that expose assessment IDs, existing policy/fingerprint/version and observation time; explicitly mark unbound legacy milestone approval as unverified for present financial authorization. Do not emit an effective release ALLOW from that flag. Keep payout disabled by B04. Use existing invoice assessment evidence instead of assuming all APPROVED rows are current. This can be enforced without enterprise workflow tables.

**Before restoring payout:** persist minimal approval fingerprint/revision, invalidate it on every material writer and revalidate under the shared lock protocol. Require reapproval when the fingerprint no longer matches; do not build stages/quorums just to fix freshness.

**Tests required:** deadline/agreement/invoice revision, beneficiary verification change, PM reassignment, dispute reopen and policy update after approval; stale source cannot become current ALLOW. Preserve invoice's existing review freshness tests. Old decision remains historical, not overwritten.

**Database migration required:** NO for containment and rejecting unbound approvals. YES for missing persistent approval revisions/fingerprints before payout restoration.

**API compatibility impact:** no need to change existing review payload for the initial framework; financial eligibility may be unavailable pending reapproval in the later corrected flow.

**Dependencies:** B04, B09, B11, B13. The new common decision fields are future implementation, not a blocker counted separately.

### B11 — P0 — Inconsistent locking and retry boundaries expose races

**Files:** `J/ledger/service/LedgerService.java`; `J/escrow/service/EscrowService.java`; `J/paymentorchestration/service/PaymentOrchestrationService.java`; `J/paymentorchestration/service/SettlementAuthorizationService.java`; `J/payment/service/PaymentService.java`; `J/milestone/service/MilestoneService.java`; `J/agreement/service/AgreementService.java`; `J/risk/service/RiskActionService.java`; `J/risk/repository/BusinessRiskRepository.java`.

**Class/method:** `postJournalEntry`, `fundEscrow`, `releaseEscrow`, `ensureEscrowAccount`, financial commands, approval writers, `payoutReviews`.

**Current behavior:** deposit locks ledger then deal/escrow; settlement locks milestone/deal then ledger. Ledger locks follow caller line order, reversed between deposit and settlement. Legacy order creation locks deal then milestone, unlike release. Some approval writers use ordinary reads. Risk opens REQUIRES_NEW while caller holds financial locks. Escrow initialization catches uniqueness error and attempts to continue in the same transaction.

**Why unsafe:** deadlock potential, lost approval updates and rollback-only retries; an external call followed by local retry is especially dangerous. No runtime deadlock frequency is claimed.

**Target behavior:** deterministic lock ordering across retained writers, coherent evidence reads, and retry only at a fresh transaction boundary before external effects.

**Smallest safe remediation:** align retained financial writers' deal/milestone/account ordering, sort ledger account locks, use existing pessimistic locks for approval/state checks, and restart a failed transaction instead of querying inside an aborted uniqueness transaction. Keep external dispatch disabled under B04. Verify risk nested-transaction contention and move evaluation outside held financial locks where necessary, while preserving durable review behavior. Coherent framework snapshots must be version checked or read transactionally; a patchwork of stale rows is not a snapshot.

**Tests required:** PostgreSQL multi-connection deposit/release, legacy-order/release, approve/reject, account creation and risk-refresh contention; bounded retries, no duplicate posting, no bank retry following ambiguous outcome. Verify locks used by all retained invalidating writers.

**Database migration required:** NO for minimal use of existing pessimistic locks. New optimistic version columns are optional alternatives, not mandatory framework prerequisites.

**API compatibility impact:** stale/racing operations return conflict/retryable errors; never silently overwrite another decision.

**Dependencies:** B03/B04 contain bypasses/external effects; B05/B06 provide final uniqueness safeguards; B10 defines evidence changes.

## 4. P1 architecture blockers

### B12 — P1 — Legacy execution and evidence have no enforced boundary

**Files:** `J/payment/controller/PaymentController.java`; `J/payment/service/PaymentService.java`; `J/payment/service/RazorpayService.java`; `J/payment/config/RazorpayProperties.java`; `J/payment/config/PaymentRailPolicy.java`; `J/payment/entity/Payment.java`; `J/risk/service/RiskEvidenceService.java`; `backend/pom.xml`; `backend/src/server.ts`.

**Class/method:** Razorpay create/verify/webhook routes, `PaymentService` injection, `PaymentRailPolicy`, `RiskEvidenceService.collect`.

**Current behavior:** active Spring components inject Razorpay directly; `PaymentRailPolicy` always reports BANK but is not consulted by `PaymentService`/controller to disable legacy execution. Risk evidence unions legacy payments and bank instructions. Legacy verification credits a shared escrow projection without the bank double-entry deposit path. Node startup is disabled; archived blockchain code is not in Maven source.

**Why unsafe:** the common framework could inherit Razorpay dependency or treat heterogeneous historical funding as one bank-confirmed fact. Mere presence of old source files is not the defect; uncontrolled execution and provenance are.

**Target behavior:** no common-framework dependency on a legacy executor; explicit source rail/provider in evidence; historical data stays readable without becoming automatic proof of bank funds.

**Smallest safe remediation:** enforce disabled new legacy intake in the bank-engine environment, preserve a separately scoped historical read/callback capability only when obligations require it, and expose read-only evidence with explicit rail and origin identity. Exclude mixed/unreconciled balances from financial eligibility. Add architecture tests forbidding new framework imports/calls into legacy executors. No source deletion or full legacy migration is necessary.

**Tests required:** BANK configuration cannot execute Razorpay orders/verification as new bank funding; retained callback only processes known historical obligations with normal security/idempotency; bank evidence never counts a copied legacy transaction twice; application/framework starts without configured Razorpay; no blockchain executor in artifact or common dependency graph.

**Database migration required:** NO for boundary and provenance using existing provider/rail/source fields. Historical balance conversion is separate and must not silently relabel records.

**API compatibility impact:** legacy write calls may be explicitly unavailable; history remains. Do not default missing rail to Razorpay in any new consumer.

**Dependencies:** B03/B04 for all execution paths, B06 for ledger lineage, B07 for history access. Detailed runtime classification appears in section 6.

### B13 — P1 — Risk inspection is not a stable, read-only assessment reference

**Files:** `J/risk/service/RiskEngineService.java`; `J/risk/service/RiskScoringService.java`; `J/risk/service/RiskActionService.java`; `J/risk/service/RiskHistoryService.java`; `J/risk/entity/RiskAssessment.java`; `J/risk/repository/BusinessRiskRepository.java`; `J/invoice/service/InvoiceService.java`.

**Class/method:** `getAssessmentForDeal` -> `assessDealRisk`, `recalculate`, `payoutReviews`; invoice assessment history/fingerprint access.

**Current behavior:** a method named `getAssessmentForDeal` recalculates both subjects and persists scoring/history/review effects, then returns a newly constructed **unsaved** aggregate RiskAssessment. Its ID is not a persisted deal-assessment reference. Native reviewRequired and effective configured payout restrictions also differ. Invoice has actual persisted assessment IDs/fingerprints and should not be flattened to just status/score.

**Why unsafe:** a framework adapter treating the compatibility view as persisted evidence would record a dangling source identity, mutate risk state on reads, or change payout policy by naïve status mapping. This is a concrete existing source-contract ambiguity, not the absence of EngineDecision.

**Target behavior:** stable read-only source records distinct from explicit recalculation commands; preserve both counterparties' actual assessment identities, policy versions, timestamps, missing-signal information and effective restriction semantics.

**Smallest safe remediation:** provide/use a read path over existing persisted risk score/history/assessment records; keep explicit recalculation separate and retain the compatibility endpoint if required. Mark its aggregate identity transient rather than citing it as a saved assessment. Document/test native outcome mapping before any common adapter consumes it; retain invoice assessment identity and provenance. Missing/stale evidence stays unresolved; no guessed ALLOW.

**Tests required:** repeated read creates no history/review rows; all cited source IDs exist; buyer/vendor references preserved; high score with cleared review/configured policy is not silently remapped; active restriction remains represented; missing/expired evidence and invoice manual-entry provenance retained.

**Database migration required:** NO. Existing persisted assessments/history and invoice snapshots suffice.

**API compatibility impact:** add/use a distinct read contract; do not silently change a documented recalculation command into a read or expose transient IDs as durable.

**Dependencies:** B07 scope, B10 freshness boundary, B12 provenance. Existing scoring/rules are REUSE, not rewrite candidates.

## 5. P2/P3 dispositions: not prerequisites for the common framework

| ID | Priority | Existing issue / evidence | Why not blocking the restricted framework; required boundary |
|---|---|---|---|
| D01 | P2 | No end-to-end persisted correlation ID; `J/audit/service/AuditService.java` uses actor/entity/action; Setu generates provider-local IDs | Existing stable subject/assessment/source IDs can anchor first framework records. Introduce correlation with the framework and extend cross-service propagation later. Correlation cannot substitute for idempotency or authorization. |
| D02 | P2 | No generic outbox; `AuditService` publishes synchronous events; `RiskEventService` persists specialized risk work with retries | Missing outbox alone is not event loss in every path. Keep synchronous transactional source reads/writes and existing durable risk queue. No detached asynchronous decision or bank dispatch is allowed without reliable delivery. General outbox becomes required when those asynchronous consumers are introduced. Banking inbox defect is already P0 B02. |
| D03 | P2 | `J/reconciliation/service/ReconciliationService.java` matches settlement reference then copies bank amount as both amounts, omits outbound missing checks/currency/direction, compares all internal history to a bounded bank window | These are real correctness defects before reconciliation can attest financial eligibility, but that attestation is excluded from initial framework scope. Preserve raw source status and mark reconciliation nonauthoritative; fix the existing service before consuming its MATCHED as a financial gate. No second reconciliation engine. |
| D04 | P2 | Audit/journal/invoice history lacks broad DB append-only guards; V18 protects only specific risk history | Existing fail-closed audit behavior and protected source permissions can support initial recording. Do not expose evidence mutation through the new framework. Broader DB privileges/triggers and tamper-evident retention are separate hardening; rollback/financial audit loss is contained by B04 and tested under B06. |
| D05 | P2 | `BankingProvider` exists; only `provider/mock/MockBankingProvider.java` implements it; `render.yaml` selects mock | A real adapter, settlement polling/attempt scheduler and durable statement import are production money-movement prerequisites, not common-model prerequisites. Enforce sandbox labeling and disabled production dispatch (B04/B09). Do not count the absent adapter as a missing-framework blocker. |
| D06 | P2 | `OrganizationService.getVerifiedBusinessProfile` counts global milestones/disputes and compares org IDs to user party IDs | B07 must close unauthorized visibility now. Correcting metric computation is required before a Trust evaluation uses it, not before the shared framework exists. Exclude these metrics from trusted initial evidence. |
| D07 | P2 | Frontend Razorpay/blockchain consumers, `b2b.service.ts` route mismatch, README overclaims, `.github/workflows/ci.yml` obsolete contracts path | Runtime isolation is B12. UI migration/docs/CI deployment cleanup are important, but not required to build the backend decision contract with focused validated checks. These remain release blockers where they actually break deployment. |
| D08 | P2 | `DefaultInvoiceExtractionProvider` uses manual inputs with confidence 1; deliverable URLs lack invoice-style private byte validation; limited document content checks | Preserve explicit manual provenance through B13; do not represent these as OCR-verified facts. Real OCR, malware scanning and generalized document storage are later document hardening. No need to build Document Intelligence to introduce the common model. |
| F01 | P3 | Existing `findAll()` reporting, unbounded history and repeated evidence queries | Query/pagination/index tuning based on measured workload. Authorization filtering is B07 and is not deferred as performance work. |
| F02 | P3 | Limited consolidated operational dashboards for decision lag/recovery and legacy retirement progress | Add richer observability/retirement automation later. Required negative tests and financial incident audit evidence are not deferred. |

No extra blocker IDs are assigned to future engines, approval stages/quorum, generic policy registries, score aggregation, new tables that belong to framework implementation, or absence of advanced automation.

## 6. Razorpay and blockchain runtime classification

Here **production-active** means compiled/registered/callable in the production application source, not proof of deployed live credentials or actual transactions. The normal Maven/Docker build compiles `src/main/java`, excludes `src/test/java` from the runnable application, and does not compile `legacy/` as application sources.

| Exact component | Current status and dependents | Treatment / can remain? |
|---|---|---|
| `J/payment/service/RazorpayService.java` | Production-active `@Service`; uses RestClient against Razorpay `/orders`, not a required Razorpay Maven SDK. Injected into PaymentService. Missing configuration fails calls; sandboxMode does not itself replace HTTP order creation. | **LEGACY / KEEP TEMPORARILY.** Isolate execution through B12; may remain in tree/artifact without being a framework dependency. **REMOVE LATER** after obligations end. |
| `J/payment/config/RazorpayProperties.java` | Production configuration bean used by RazorpayService and config response | **LEGACY / KEEP TEMPORARILY** while legacy support exists. Presence of unset settings is not an enforcement boundary. |
| `J/payment/controller/PaymentController.java` | Production-active create-order, verify, config, payment reads and webhook routes | **EXTEND** to enforce legacy capability boundary; **KEEP TEMPORARILY** historical reads/known-order callbacks. New-order/checkout write surface **REMOVE LATER**. |
| `J/payment/service/PaymentService.java` | Direct Razorpay dependency; order/verify/webhook call escrow funding. Does not consult PaymentRailPolicy. Tests and controller depend on it. | **LEGACY** executor; **EXTEND** scope/provenance and isolate new intake. Reuse only provider-neutral validated logic, not this class as the engine foundation. |
| `J/payment/config/PaymentRailPolicy.java` | Production bean always selects BANK; no execution guard in legacy service/controller | **EXTEND** enforceable capability selection or leave it unused outside framework. Its name is not evidence Razorpay is disabled. |
| `J/payment/entity/Payment.java`, `J/payment/entity/FundingRail.java`, request/response DTOs | Active persistence/API history; Razorpay defaults/field aliases. RiskEvidenceService reads payments, finance/history pages consume them. | **LEGACY / KEEP TEMPORARILY.** Preserve original rail; historical persistence can remain indefinitely. Do not delete financial records during runtime retirement. |
| `J/payment/entity/WebhookEvent.java`, `J/payment/repository/WebhookEventRepository.java`, V8 inbox | Provider-neutral reusable infrastructure despite payment package location | **REUSE / EXTEND** for B02; do not remove with Razorpay executor. |
| `T/payment/config/RazorpayTestConfiguration.java` | **Test-only** `@TestConfiguration` and primary subclass overriding createOrder. Keeps production signature logic. Imported by E2E and risk tests. | **KEEP TEMPORARILY.** Does not disable production Razorpay. |
| `T/payment/RazorpayServiceTest.java`, `T/payment/PaymentServiceTest.java` | **Test-only** signature/payment behavior tests | **KEEP TEMPORARILY** regression coverage; isolate/retire only after legacy removal scope is approved. |
| `T/e2e/TransactionEngineEndToEndTest.java`, `T/e2e/SecurityHardenedTest.java`, `T/e2e/BankIntegratedEndToEndTest.java`, `T/risk/RiskScoringComprehensiveTest.java`, `T/risk/RiskIntegrationTest.java` | Test sources import legacy test configuration; some exercise Razorpay scenarios directly | **EXTEND / KEEP TEMPORARILY.** Future bank-only tests must not mistake those fixtures for real bank validation. |
| `RazorpayService.generateTestSignature` | Method lives in **production source**, though observed callers are tests | **LEGACY / REMOVE LATER** from production service to test helper. No HTTP endpoint for it was found; do not misreport it as a public signature oracle. |
| `frontend/lib/razorpay.ts`; `frontend/services/payment.service.ts`; `frontend/app/corporate/projects/[projectId]/milestones/[milestoneId]/payment/page.tsx` | Active frontend checkout loader/service/page calls legacy APIs; not test-only | **LEGACY / REMOVE LATER** checkout; **EXTEND** history clients separately. Can remain during backend framework build if server write capability is safely gated. |
| `legacy/backend-razorpay/` | Archived copies outside authoritative Maven main/test roots | **LEGACY / KEEP TEMPORARILY.** Not active simply because files exist. |
| `backend/src/server.ts`, `backend/src/controllers/payment.controller.ts`, `backend/src/controllers/webhook.controller.ts` | Node server explicitly disabled; legacy payment/webhook stubs reject use | **LEGACY / KEEP TEMPORARILY.** Do not revive as a workaround. |

Razorpay can safely remain as quarantined source, test fixtures, accurately labelled historical evidence, and narrowly authorized historical callbacks. What must eventually leave the bank platform's active product path is new Razorpay order/checkout execution and its implicit default/fallback behavior. History, audit references and reusable inbox infrastructure do not need deletion.

Blockchain-specific conclusion:

- `legacy/backend-blockchain/blockchain/BlockchainController.java`, `BlockchainEscrowRail.java` and the archived on-ramp/chain classes are outside the authoritative source build. No active Java chain executor or web3j dependency was found in `backend/pom.xml`/main source search.
- `J/payment/entity/OnRampTransaction.java`, on-ramp DTO remnants and V8/V10 schema retain legacy concepts; their existence is not proof of reachable blockchain execution. **LEGACY / KEEP TEMPORARILY**; preserve historical migrations.
- `frontend/services/blockchain.service.ts`, `frontend/components/escrow/BlockchainEscrow.tsx`, `BlockchainDashboard.tsx`, `EscrowRailView.tsx`, `frontend/app/blockchain/page.tsx` and corporate/vendor project pages retain runtime UI imports/routes. Some components gate activity using config; this does not establish backend execution. **LEGACY / REMOVE LATER** UI, nonblocking for an isolated backend framework.
- CI's old `blockchain/contracts` path is delivery drift, D07. Do not invent a P0 chain-money execution path from archived files or frontend labels.

## 7. Component disposition beyond legacy code

| Existing component | Classification | Remediation boundary |
|---|---|---|
| Risk scoring/rules/events/history/reviews | REUSE / EXTEND | Preserve calculations; B13 separates durable read evidence from recalculation and transient view. |
| Invoice extraction/matching/document validation/reviews | REUSE / EXTEND | Preserve persisted assessments and stale-review safeguards; explicit manual provenance. |
| Organization/KYB/KYC and bank verification | REUSE / EXTEND | B07/B09 correct ownership, active authority, canonical verification and expiry. |
| Milestone, agreement, deliverable, PM assignment | REUSE / EXTEND | B10/B11 guard applicability and state races; no enterprise Approval Engine prerequisite. |
| SettlementAuthorizationService | EXTEND | B04 containment now; separate authorization/execution before money movement resumes. |
| BankingProvider | REUSE / EXTEND | Existing abstraction suffices for framework isolation; real adapter is D05. |
| LedgerService, escrow projection and existing history | REUSE / EXTEND | Correct source identity/currency/dedupe; no second ledger. |
| ReconciliationService and runs/items | REUSE / EXTEND | Scope access now; improve matching before it attests eligibility. |
| AuditService, notifications, risk event queue | REUSE / EXTEND | Preserve transactional audit and durable risk queue; generic outbox is not required for synchronous framework. |
| V8 idempotency/inbox infrastructure | REUSE / EXTEND | Apply it to the actual bank path; no duplicate infrastructure. |

## 8. Coverage of the 28 requested investigations

| Investigation | Disposition |
|---|---|
| 1. Authorization/execution coupling | B04 |
| 2. Direct settlement/release paths | Current flow, B03/B04/B12 |
| 3-5. Duplicate settlement, uniqueness, repeat releases | B05; B04 rollback effect; B06 internal posting |
| 6. Concurrency/races | B11 plus source freshness B10 |
| 7. Idempotency | B05, including replay authorization |
| 8. Webhook replay | B02; durable V8 reuse |
| 9. Active Razorpay | B12 and section 6 |
| 10. Active blockchain dependencies | Section 6; no active backend executor established; D07 UI/CI |
| 11. Beneficiary changes | B09; B04 containment; pin/recheck before restoring execution |
| 12-13. Stale approvals/financial decisions | B10/B13; historical records never permanent permission |
| 14-15. Tenant isolation/IDOR | B07/B08; B05 stored-response leaks |
| 16. Account ownership | B09; existing account-to-org check retained, upstream org binding fixed |
| 17. Payment/settlement authorization | B01/B03/B04/B07 |
| 18. Correlation IDs | D01, not mandatory infrastructure retrofit before framework |
| 19. Audit gaps | B04/B06 rollback boundary; D04 broader immutability |
| 20-21. Transactions/rollback/retry | B04/B05/B11 |
| 22. Ledger/settlement consistency | B06; B03 bypass and B12 legacy lineage |
| 23. Outbox/event reliability | D02; B02 is the existing concrete durable ingestion defect |
| 24. Bank abstraction | Existing interface REUSE; B04/B09 enforce safety; D05 real adapter |
| 25. Settlement status transitions | B04 forced success and unknown retry; B05 active effect identity |
| 26. UTR/reference reuse | B05 scoped identity; B01 no fabricated confirmation |
| 27. Organization/KYB/KYC enforcement | B07/B09; helpers alone do not enforce callers |
| 28. Authorization bypasses | B01/B02/B03/B05/B07/B08/B09 |

## 9. Minimal remediation order and acceptance gate

All actions in this section are proposed follow-up work. None is executed now.

1. **Contain unsafe financial entry points (B01-B04, B12).** Deny public credit assertions, unauthenticated webhooks, direct escrow release and combined payout execution. Disable new Razorpay intake in the framework environment while preserving known legacy obligations/history under explicit controls. No new engine should be used to perform this containment.
2. **Establish trusted scope and actors (B07-B09).** Close IDOR, validate active membership and legal-party organization links, close FINANCE self-provisioning including existing bad grants, and disallow weak/masked/ambiguous verification as sufficient financial evidence. Exclude unmappable history rather than backfill guesses.
3. **Repair existing financial evidence identity (B05/B06).** Apply new additive migrations for effect uniqueness and correct bank-source FK; scoped replay/hash checks; reject currency ambiguity. Verify on PostgreSQL against clean and upgrade schemas. Retain legacy IDs and constraints needed by history.
4. **Make retained readers/writers safe (B10/B11/B13).** Align lock/retry behavior, separate risk reads from recalculation, cite persisted assessments, and expose provenance/freshness. Unbound old approval cannot become financial permission; payout stays disabled until proper approval/beneficiary binding and recovery are implemented.
5. **Prove the restricted framework boundary.** Architecture and negative integration tests must show no provider, settlement command or financial posting side effects from evidence retrieval/decision recording. Initial trusted sources exclude nonauthoritative reconciliation results, broken organization metrics, unverifiable legacy balances and stale approvals. Keep source policy semantics intact.

This is materially smaller than implementing the prior plan's V23-V32 roadmap: the minimum does **not** require new engine tables in advance, nine engines, enterprise approvals, release workflow tables, execution workers, generic outbox, statement-import tables, real bank adapter, OCR, RLS rollout, frontend replacement or legacy deletion.

Minimum database work is limited to B05/B06's existing financial identity/source defects; exact migration numbers must be chosen after rechecking current Flyway history (audit baseline highest V22). No migration files are created here. All historical SQL/Java migrations remain unchanged.

Acceptance evidence must include: supplied baseline reproduced on the remediation revision; focused PostgreSQL negative/race tests for each retained path; additive migration upgrade/clean validation; no dangling source IDs; tenant isolation; old endpoint denial; no new legacy execution; framework-boundary tests. H2-only green tests, absent provider configuration and passing happy-path tests are insufficient. If an unsafe path remains enabled or an excluded source is needed as authoritative evidence, this minimum is **not** satisfied: fix that path/source before claiming readiness.

After this minimum, common model/persistence and read-only adapters may be implemented in a later authorized task. Live release remains separately blocked until B04's durable execution correction, B09/B10 persistent binding, relevant D03/D05 production work and their recovery tests are complete. The final YES below is a conditional design conclusion for the restricted framework, not a claim remediation has occurred.

## 10. Final classification table

| ID | Priority | Blocker | Must Fix Before Common Engine Framework? | Migration? | Risk |
|---|---|---|---|---|---|
| B01 | P0 | Untrusted bank confirmation | YES, fix or deny ingestion | NO | Fabricated funding evidence |
| B02 | P0 | Optional webhook authentication and memory replay control | YES, authenticate and reuse durable inbox or disable | NO | Forgery, replay, lost retry |
| B03 | P0 | Direct escrow release bypass | YES, fail closed | NO | False release/completion |
| B04 | P0 | Coupled payout, rollback ambiguity, forced SETTLED | YES, contain; full execution repair later | NO for containment | Duplicate external effect, false success |
| B05 | P0 | Weak replay scope and missing financial-effect uniqueness | YES | YES | IDOR, duplicate payout/credit |
| B06 | P0 | Bank-source FK and journal/projection inconsistency | YES | YES | Broken/misrepresented balances |
| B07 | P0 | Organization binding, inactive membership and IDOR | YES | NO | Cross-tenant evidence/authority |
| B08 | P0 | Public FINANCE provisioning | YES | NO | Privileged data exposure |
| B09 | P0 | Unequal beneficiary verification and misbound authority | YES, validation/containment; snapshot before payout | NO for minimum | Wrong/unverified beneficiary |
| B10 | P0 | Stale approval treated as current permission | YES, exclude/mark unbound approvals; bind before payout | NO for minimum | Changed evidence authorized |
| B11 | P0 | Lock/retry and state race exposure | YES for retained paths | NO | Deadlock, lost update, replay |
| B12 | P1 | No enforced legacy execution/evidence boundary | YES; removal is not required | NO | Legacy dependency/false bank provenance |
| B13 | P1 | Mutating risk read and transient assessment identity | YES | NO | Unreliable/dangling decision evidence |
| D01 | P2 | End-to-end correlation retrofit | NO | NO prerequisite | Traceability gaps |
| D02 | P2 | Generic outbox absent | NO under synchronous restricted scope | NO prerequisite | Future async delivery loss |
| D03 | P2 | Reconciliation accuracy/completeness | NO while excluded as financial attestation | NO for basic matcher fix | False assurance if promoted early |
| D04 | P2 | Broad history immutability hardening | NO under restricted writes | YES for proposed DB guards | Tampering through alternate writers |
| D05 | P2 | Real bank adapter/recovery infrastructure | NO while execution disabled | NO for adapter alone | Production finality unproven |
| D06 | P2 | Incorrect organization performance metrics | NO while excluded; access fixed by B07 | NO | Misleading future trust inputs |
| D07 | P2 | Legacy UI, route/docs and CI drift | NO for backend framework; fix before affected delivery | NO | Broken user/deployment flows |
| D08 | P2 | Document/extraction hardening | NO; preserve manual provenance | NO for initial validation hardening | False confidence/unsafe documents |
| F01 | P3 | Reporting/query scalability tuning | NO | NO prerequisite | Future load/latency |
| F02 | P3 | Expanded observability/retirement automation | NO | NO prerequisite | Operational inefficiency |

P0 BLOCKERS: 11

P1 BLOCKERS: 2

P2 BLOCKERS: 8

P3 BLOCKERS: 2

MINIMUM REMEDIATION SET:

- B01-B04: fail-closed financial entry points, authenticated durable bank ingestion, no direct release or combined execution accessible to the framework.
- B05-B06: scoped idempotency, effect uniqueness, correct bank-source references and consistent validated accounting on PostgreSQL.
- B07-B09: authorized tenant ownership and membership, trusted platform roles, canonical verification; keep unsafe beneficiary execution disabled.
- B10-B11: exclude stale/unbound approvals from permission, coherent evidence reads and safe retained-writer locking/retries.
- B12-B13: enforce legacy provenance/execution isolation and stable read-only references to existing risk/invoice evidence.
- Verify the restricted scope with negative, concurrency, migration and dependency tests; keep deferred sources nonauthoritative and live settlement disabled.

SAFE AFTER MINIMUM REMEDIATION: YES

SAFE TO IMPLEMENT COMMON ENGINE FRAMEWORK NOW: NO
