# FinX Decision Intelligence Platform: Architecture Audit and Implementation Plan

## 1. Executive Summary

Audit date: 2026-09-30. Scope: repository architecture and source inspection, not implementation or a production penetration test. This document is the only audit output. No Java, frontend, configuration, or migration changes are authorized by this audit.

FinX should extend its Spring Boot modular monolith into a centralized, bank-integrated B2B milestone transaction control platform. Risk, invoice intelligence, verification, ledger, milestone workflow, and reconciliation already have substantial implementations. Building nine independent replacement systems would discard working behavior and create competing financial authorities.

The current financial architecture does **not** pass the readiness gate for attaching a common engine framework to live execution. Critical gaps include an authenticated but otherwise unrestricted bank-confirmation endpoint, optional bank webhook authentication, settlement execution before durable instruction persistence, unconditional settlement success, a direct escrow release bypass, and incomplete organization isolation. Public registration also accepts FINANCE, which grants platform-wide finance access. Sections 24 and 28 distinguish confirmed source findings from runtime questions.

Recommended direction: preserve existing domain services; introduce a small common decision envelope; adapt existing assessments; persist release decisions and instructions; authorize separately; execute through the existing `BankingProvider` interface using a durable worker and bank-confirmed state transitions. **ReleaseDecisionEngine must never move money or post financial journals.** Razorpay and blockchain are legacy, including code that remains in active source directories.

The user supplied baseline is **325 tests, 0 failures, 0 errors, 1 skipped, BUILD SUCCESS**. This audit did not rerun tests, start services, query a database, or contact providers. The baseline is recorded as supplied, not independently reproduced. Standard tests use H2 with Flyway disabled; the separate clean-migration test also uses H2. PostgreSQL constraints, triggers, and real bank behavior require additional validation.

## 2. Current Architecture

Repository-wide inspection covered the backend package and route inventory, frontend pages/services and legacy consumers, SQL and Java Flyway migrations, tests, scripts, deployment/build files, architecture reports, and legacy trees. Detailed tracing concentrated on the financial, identity, evidence, and authorization paths below. Generated build output, dependency caches, vendored Foundry internals, binary artifacts, and actual environment secrets were not treated as application architecture. This is a repository-wide architectural review, not a claim that every source line or third-party dependency has been formally verified.

| Repository area | Observed architecture and consequence |
|---|---|
| `backend/src/main/java/com/finx/` | Authoritative Spring Boot 3.4.3 / Java 21 application: controllers, transactional services, JPA repositories, JDBC risk evidence queries. Retain the modular monolith. |
| `backend/pom.xml`, `backend/Dockerfile`, root `package.json`, `render.yaml` | Build/deployment paths select Spring Boot. Render configuration selects mock banking. A real deployed configuration was not inspected. |
| `backend/src/server.ts`, `backend/src/{controllers,routes}`, `backend/prisma/` | Parallel Express/Prisma implementation survives, but server startup explicitly throws a legacy-disabled error. Payment/webhook controllers also contain disabled behavior. Do not revive it or use Prisma as the authoritative schema. |
| `frontend/app`, `frontend/services`, `frontend/types`, `frontend/lib/api.ts` | Next.js role-oriented corporate, vendor, PM, finance, admin workflows. Existing bank, invoice, risk, and verification clients coexist with Razorpay checkout and blockchain components. Frontend route visibility is not a security boundary. |
| `backend/src/main/resources/db/migration`, `backend/src/main/java/db/migration` | V1-V22, with V18/V19 implemented as Java migrations. V22 is the current highest version. Preserve all historical files. |
| `legacy/backend-blockchain`, `legacy/backend-razorpay`, `legacy/blockchain` | Archived integrations/contracts; no foundation for new engines. Copies under legacy do not establish that corresponding active code is disabled. |
| `docs/`, root audit reports, scripts and `test-results/` | Useful context and test fixtures, not stronger evidence than current code. Some reports name components absent from the tree. README claims full Razorpay removal and nightly reconciliation that current source does not substantiate. |
| `.github/workflows/ci.yml` | Java and frontend checks exist, but contracts job still references `blockchain/contracts` instead of the archived path; deploy depends on it. Treat as a future CI cleanup blocker. |

No AWS infrastructure interaction is needed for this audit; no relevant AWS skill or retrieval tool was available. Database planning follows the local Postgres best-practices skill, especially short transactions, stable lock ordering, indexed foreign keys, and tenant isolation. No cloud state is inferred from repository configuration.

Source notation below: `J/` means `backend/src/main/java/com/finx/`; `M/` means `backend/src/main/resources/db/migration/`; `T/` means `backend/src/test/java/com/finx/`. Class names identify the existing file in the stated package, including when a method is cited.

## 3. Existing Financial Flow

1. `DealService` links buyer/seller users and optionally organization IDs. Seller acceptance precedes milestone work. `AgreementService` maintains versioned terms, SHA-256 and party signatures.
2. `PaymentOrchestrationService.createPaymentInstruction` locks the deal, checks buyer/admin access and signed agreement, chooses milestone/deal amount, calls `BankingProvider.createPaymentInstruction`, then saves the instruction. Buyer/seller organization IDs are currently passed as null into its entity constructor.
3. `confirmPayment` accepts instruction ID and optional references, creates a SUCCESS payment transaction, sets the instruction SUCCESS, posts a balanced deposit journal, funds the escrow projection, and marks the deal FUNDED. It does not independently validate bank amount/currency/status or caller authority. The controller exposes it to authenticated callers; the public banking webhook invokes the same service.
4. Vendors submit deliverables; buyer, assigned PM, or admin can approve milestones. Invoice upload performs document validation, manual extraction through an interface, deterministic matching, assessment persistence, risk observation, and human review. Invoice approval does not itself release funds.
5. `SettlementAuthorizationService.authorizeAndInitiateSettlement` locks milestone then deal; optionally returns a globally keyed existing settlement before its buyer/admin check; checks vendor KYB/bank, deal status, agreement, milestone, optional invoice gate, balance, disputes, and risk reviews.
6. That same transaction calls `BankingProvider.initiateSettlement` **before saving the settlement instruction**. It ignores returned status, assigns SETTLED, posts the settlement journal, invokes `EscrowService.releaseEscrow`, and emits audit/notifications. Database rollback cannot undo an accepted external payout.
7. `EscrowController` independently exposes `POST /api/escrow/{milestoneId}/release`. It performs internal balance/release/milestone updates without the full settlement gates or bank execution/journal path. This is an accounting and workflow bypass, not evidence of an actual bank transfer.
8. `ReconciliationService` fetches bank records and compares internal payment transactions and settlements; it persists runs/items. It is triggered by API; no reconciliation scheduler was found.
9. Separately, active `PaymentController` / `PaymentService` / `RazorpayService` still implement Razorpay order, verification, and webhook funding of the escrow projection. This is a second funding lineage and must not feed the future platform as an unqualified bank-confirmed event.

## 4. Existing Components Inventory

| Audit area | Existing components / evidence | Assessment |
|---|---|---|
| 1. Deal | `J/deal`, `J/agreement`; V2, V5, V6, V15 | User party ownership, org fields, status, references, amount/currency, agreement versions. Validate organization binding and freeze contract inputs. |
| 2. Milestone | `J/milestone`, V3/V20/V22 | Sequence, amount, currency, due/submission dates, statuses, invoice-required flag; reuse. |
| 3. Deliverable | `MilestoneService`, `Deliverable` | Submission/reviewer metadata and file URLs; no equivalent of invoice private-byte validation. Extend evidence integrity. |
| 4-5. Invoice/document | `J/invoice/{service,extraction,matching,normalization,repository}`, V21/V22 | Existing extraction interface, checks, reviews, documents, assessment fingerprints and revisions. Extend, do not replace. |
| 6. Payments | `J/paymentorchestration`; legacy `J/payment` | Separate bank instructions/transactions and Razorpay payments. Preserve provenance and migrate callers. |
| 7. Settlement | `SettlementAuthorizationService`, `SettlementInstruction`, V13 | Combined eligibility/authority/execution/accounting. Split responsibilities and add durable lifecycle. |
| 8. Banking | `BankingProvider`, `BankingDtos`, `provider/mock/MockBankingProvider` | Interface already provides instruction, status, beneficiary, settlement, feed and signature operations. Only mock implementation found. |
| 9. Ledger | `J/ledger`, `J/escrow`, V3/V4/V7/V12 | Balanced journals plus per-deal escrow projection. Preserve both with one confirmed-event posting authority. |
| 10. Reconciliation | `J/reconciliation`, V14 | Runs/items and matching service already exist. Extend bank feed and matching completeness. |
| 11-13. Risk/events/scoring | `J/risk`, V14/V17, Java V18/V19 | Durable event queue, weighted rules, policy versions, scores/history/factors, alerts/reviews. Authoritative risk implementation. |
| 14-15. KYB/KYC | `J/verification`, V11/V16 | Profiles, authorized representatives, beneficial owners, consents, verification events/checks, mock and Setu provider code. Existence of provider code does not prove full production readiness. |
| 16-17. Bank accounts/beneficiaries | `OrganizationService`, `VerificationService`, `OrganizationBankAccount` | Two add/verify paths write the same table; richer verification path encrypts account and checks name match, older org path uses masked account. Consolidate. |
| 18. Disputes | `J/dispute`, V3 | Deal/milestone scope, reason, status, resolver and notes. Reuse case and lifecycle; add structured evidence references. |
| 19. PM | `J/projectmanager`, V9; milestone/invoice review methods | Invitations, PM IDs, assigned PM checks, invitation optimistic version; no enterprise stage/quorum workflow. |
| 20. Audit | `AuditService`, `AuditLog`, agreement/verification histories | Financial audit writes fail closed; synchronous risk-domain publication. No general correlation/tenant envelope or broad DB append-only guard. |
| 21. Notification | `NotificationService`, `milestone/reminder`, V20 | User-scoped notifications and persistent reminder scheduling/channels. No general financial outbox. |
| 22-23. Authentication/RBAC | `J/auth`, `J/security`, `Role`, org roles | JWT, refresh rotation, DB user-state checks, Spring URL/method authorization; FINANCE self-registration and uneven object checks remain. |
| 24. Tenant isolation | Org membership plus deal user IDs | Partial application checks, nullable organization ownership, cross-tenant aggregate queries. No RLS definitions found in Flyway. |
| 25. Flyway | V1-V22; `FlywayConfig` | Migrate-only strategy. No rewriting, deleting or silently repairing history. |
| 26-27. Idempotency/uniqueness | V3/V4/V8/V13/V17/V20 | Useful keys and constraints exist but enforcement differs by execution path; see section 22. |
| 28. Locking | Repository `findByIdForUpdate`, risk `BusinessRiskRepository.lock`, selected `@Version` | Partial protection, not a global concurrency protocol. |
| 29. Webhook dedupe | `WebhookEvent`, V8 `webhook_events`; bank controller's in-memory set | Reusable durable inbox exists, but bank controller does not use it. |
| 30. Transaction boundaries | Spring `@Transactional`; risk/manual-audit `REQUIRES_NEW` | Local consistency exists; network I/O and nested locks must be separated. |
| 31. Events/outbox | `RiskDomainEvent`, `RiskSignalObserved`, `RiskEventService`, `RiskWorker` | Risk queue is durable and specialized. No generic transactional outbox found. |

Required capability check:

| Capability | Already exists? | Exact limit |
|---|---|---|
| Idempotency key | YES, partial | Payment/settlement keys and V8 `idempotency_keys`; orchestration keys optional/global, no payload comparison. No active general key-table service found. |
| Correlation ID | PARTIAL | Setu provider creates local request IDs; no end-to-end persisted financial/engine correlation contract. |
| Immutable financial event history | PARTIAL | Journals/escrow/audit history exist, but no general DB update/delete protection. V18 protects risk history/factors and versioned risk assessments only; V22 invoice history is append-only by usage, not DB trigger. |
| Settlement uniqueness | PARTIAL | V13 settlement reference and nullable idempotency key unique. Milestone index is nonunique. |
| Milestone release uniqueness | YES, narrow | V4 unique `(milestone_id, transaction_type)` on escrow ledger protects RELEASE, but also restricts FUND and is not bank payout uniqueness. |
| Transaction locking | YES, partial | Pessimistic deal/milestone/escrow/legacy-payment/ledger-account/invoice/reminder locks; risk subject locks. Not all writers use them. |
| Beneficiary snapshot | NO | Settlement stores account FK, not immutable verified beneficiary/version/payload. |
| Rule versioning | YES, domain-specific | Risk rules/version labels and invoice policy snapshots exist; no common immutable registry. |
| Engine decision persistence | PARTIAL | Risk assessments/history and invoice assessments exist; no common EngineDecision or release decision snapshot. |
| Outbox table | NO | Risk events are a scoring queue, not a multi-consumer financial outbox. |
| Bank provider interface | YES | Reuse `BankingProvider`; add a real adapter later rather than another provider hierarchy. |

## 5. REUSE / EXTEND / NEW / LEGACY Matrix

Classification is future treatment; **everything except this document is DO NOT TOUCH during this audit**. REUSE retains authority and behavior; EXTEND adds focused controls; NEW fills a proven gap; LEGACY stays outside new engine dependencies.

| Component group | Classification | Intended treatment |
|---|---|---|
| Deal, agreement, milestone, deliverable entities/repositories | EXTEND | Keep IDs and tables; add validated organization ownership, revision/fingerprint and state guards. |
| RiskRuleEngine, scoring/evidence/policy/events/history/reviews | REUSE + EXTEND | Retain implementation, improve provenance/freshness and provide common adapter. |
| Invoice normalization/matching/document validation/assessments/reviews | REUSE + EXTEND | Preserve existing tests and policy semantics; extend extraction provenance and approval invalidation. |
| Verification profiles, consent, events/checks, name matching | REUSE + EXTEND | Reuse eligibility inputs and consolidate duplicate bank-account workflows. |
| PM invitations and assignment | REUSE | Initial participant/assignment source for enterprise approvals. |
| Milestone approval and invoice reviews | EXTEND | Keep domain semantics; connect to revision-bound approval stages. |
| BankingProvider and BankingDtos | EXTEND | Add capability/identity/idempotency semantics and real provider implementation. |
| MockBankingProvider | REUSE, sandbox only | Improve contract tests; cannot prove real financial finality. |
| PaymentOrchestrationService | EXTEND | Secure confirmation, durable ingestion, proper financial references, short transactions. |
| SettlementAuthorizationService | EXTEND | Separate decision, authorization, dispatch and confirmed accounting. |
| LedgerService, journal tables, escrow projection | REUSE + EXTEND | Event uniqueness, immutability, currency checks, consistent locking; no replacement ledger. |
| ReconciliationService and runs/items | REUSE + EXTEND | Durable feed, full matching, scope, exception lifecycle; no second reconciliation service. |
| DisputeService, disputes, audit history | EXTEND | Evidence references and decision support; preserve human resolution. |
| AuditService, notifications, reminder engine | EXTEND | Correlation, tenant context, reliable dispatch and failure visibility. |
| Auth/security/org membership | EXTEND | Close privileged registration and object/organization authorization gaps. |
| V8 webhook inbox and idempotency tables | EXTEND | Activate and harden existing infrastructure rather than duplicate it. |
| Common decision contract, Trust/Fraud/MilestoneRules/Approval/Dispute/Release adapters | NEW | Small additions around domain sources, not nine new data silos. |
| Release instructions, execution attempts, generic outbox | NEW | Missing durable boundary and recovery records. |
| Razorpay, on/off-ramp DTO remnants, blockchain, disabled Node/Prisma | LEGACY | Quarantine execution and retain historical provenance; see section 16. |
| V1-V22 including Java V18/V19, historical records, existing unrelated work | DO NOT TOUCH | New migration sequence only; preserve history and user changes. |

## 6. Risk Engine Assessment

Retain `RiskRuleEngine.calculate`, buyer/vendor policies, `RiskEvidenceService`, `RiskScoringService`, `RiskEventService`, `RiskEventProcessor`, `RiskWorker`, and the alert/review/history services. The current rule engine caps categories, weights impacts, reports missing signals, calculates factor changes, and produces LOW through CRITICAL levels and ALLOW/MONITOR/VERIFY/MANUAL_REVIEW/TEMPORARY_HOLD actions. `RiskEngineService` is explicitly a deal compatibility view that recalculates buyer/vendor risk and returns the higher score; do not mistake that view for the authoritative persisted assessment.

`risk_events` deduplicates by source key and subject; processing locks event and subject, retries with backoff and dead-letters after ten failures. V18 prevents alteration of protected PostgreSQL risk history. `RiskPolicyService` checks expected version, logs before/after policy and schedules refresh through version mismatch. `RiskActionService.payoutReviews` commits cases in REQUIRES_NEW so a blocked settlement does not erase its review.

Extend with canonical source-event/correlation linkage, bank instruction/transaction/settlement evidence, policy content hash or archived policy reference, freshness and missing-evidence rules, and tenant-bound queries. Preserve old `payments` evidence as explicitly legacy so importing bank equivalents cannot double-count volume. Current risk review-required threshold and payout gating are not identical: payout policy can allow some scores while requiring review only for critical/high-value/restricted cases. Map the **effective configured restriction** into the common decision; do not translate every high score into BLOCK without an explicit policy change.

Avoid recalculating through REQUIRES_NEW while holding financial and subject locks. Establish one lock protocol or precompute then validate versions. Keep risk computation and review persistence independent of bank execution. Do not create replacement risk score, event, assessment, factor, alert, or review tables.

## 7. Trust Engine Plan

Trust is verified identity, signing authority, beneficiary confidence, and demonstrated transaction performance. It is not `100 - riskScore` and a high trust score cannot override a missing KYB or bank check.

Reuse `organizations`, active `organization_members`, KYC/KYB profiles with expiry, authorized representatives, beneficial owners, verification checks/events/consents, bank account verification, agreement signatures, completed milestones, bank-confirmed settlement history and resolved disputes. Fix `OrganizationService.getVerifiedBusinessProfile` before reuse: completed-deal metrics compare organization IDs to user IDs; milestone and dispute counts are global.

Evaluate per organization and purpose, with separate component results, coverage/confidence, observed-at and expiry. A new organization has insufficient history, not fabricated high trust. Required verification missing/expired produces a non-ALLOW result; distinguish unresolved evidence (REVIEW) from a known prohibition (BLOCK). Preserve the exact applicable verification policy.

Required new trust-specific tables: **none initially**. Persist TRUST results in `engine_decisions`, referencing authoritative verification/history rows. Add an optional `trust_profiles` current projection only when read-volume justifies it; unique organization/policy-purpose, pointing to latest decision, never a second identity master. Verification/status, membership/authority, beneficiary changes, settlement finality and dispute outcomes trigger reevaluation through the common outbox; no separate trust event bus/table.

## 8. Milestone Rules Engine Plan

Reuse deal amount/currency/parties/status; agreement ID/version/hash/signatures; milestone sequence/amount/currency/status/deadlines/invoiceRequired; deliverable submissions/reviews; approved current invoice assessment; dispute status; escrow confirmed funds and reservations.

Implement deterministic, versioned checks in the existing milestone domain:

- Explicit allowed deal/milestone transitions; positive amounts; deal, milestone, invoice, ledger and bank currency equality.
- Sum of noncancelled contractual milestones equals approved agreement/deal value at activation/funding; allocation changes require a new signed revision. Existing create-time upper bound is not a complete contract invariant.
- Required deliverables present and approved for the current revision; deadlines use the existing clock and an explicit grace/late policy.
- Latest required invoice human-approved, bound to current evidence. Automatic matching is not human approval.
- Active disputes/holds block; incomplete approval stages review; previously executed milestone blocks additional release.
- Available confirmed funds minus pending reservations cover the exact amount. Define dependency/sequence requirements explicitly; a sequence number alone does not establish prerequisite enforcement.

Persist MILESTONE_RULES in common decisions. No duplicate milestones table. Add an optional dependency relation only if the product requires a dependency graph; default sequential rules can use existing fields. New revision/version fields must be advanced by every relevant writer, including deadline and agreement changes.

## 9. Approval Engine Plan

Reuse PM invitations/assignment, `MilestoneService.approveMilestone` and `rejectMilestone`, deliverable reviewer/timestamp, invoice review records, risk review records, and audit history. Milestone approval accepts buyer, assigned PM, or admin; invoice review accepts assigned PM or admin. These are different existing policies and must not silently collapse into one permission.

Invoice review already locks the invoice, binds to latest assessment, rejects changed fingerprints and superseded revisions, and prevents overwriting a decided review. General milestone approval does not have comparable revision-bound approval history or multi-stage controls. PM invitation optimistic locking protects one invitation, not necessarily competing invitations assigning one deal.

Add enterprise policies with ordered/parallel stages, thresholds by currency, quorum, eligible organization roles, assignment/delegation expiry, reject/request-information outcomes, escalation timeouts and separation of duties. A requester cannot approve their own release; admin override needs an explicit separate policy and audit reason. Revoked membership or PM reassignment invalidates remaining authority.

New `approval_workflows`, `approval_steps`, `approval_actions` represent the workflow instance, instantiated requirements and append-only human actions. Versioned templates live in common policy versions. Existing invoice/risk review decisions remain authoritative evidence referenced by steps; do not copy their case history into a second review system. Every approval binds subject revision, amount/currency, agreement hash, invoice assessment, beneficiary version and policy version. Any material change requires reapproval. APPROVAL ALLOW means required stages are satisfied, not permission to execute payment.

## 10. Fraud Engine Plan

Reuse risk velocity/failure/reversal metrics, auth-failure observations, KYB/name-match checks, bank account change signals and invoice duplicate/mismatch signals. `InvoiceService.recordRisk` already sends structured invoice observations through `RiskSignalObserved`. `RiskEventService` maps bank-account changes, including special handling of a first account as KYB-related activity.

Invoice duplicate matching already examines byte hash, normalized invoice number, vendor identity and amount-related candidates; preserve its reason codes and access-controlled duplicate references. It is detection, not a global database prohibition against resubmission. Simultaneous uploads to different milestones can race candidate reads, so approval/release must recheck canonical duplicate identity. Do not add a global unique file hash that destroys legitimate revision/evidence handling.

Add deterministic fraud policies for recent beneficiary changes followed by release, repeated retries, abnormal payment amounts/velocity, mismatched bank instruction/account/currency, reuse of bank references, and suspicious document reuse. Record event time, signal provenance and investigation outcome; avoid double-counting the same invoice signal through both direct observation and audit bridge.

Use common FRAUD decisions and existing risk evidence/alerts/reviews initially. Dedicated fraud cases are deferred until investigators need a distinct workflow; do not introduce a duplicate risk event or score store. A fraud result may recommend a hold; only authorized release controls enforce it. Bank change events need old/new account identifiers and verification versions, not raw account details.

## 11. Document Intelligence Plan

Extend `InvoiceExtractionProvider`, `DefaultInvoiceExtractionProvider`, `InvoiceNormalizationService`, `InvoiceMatchingEngine`, `InvoiceDocumentService` and `InvoiceService`. Reuse `invoices`, `invoice_documents`, `invoice_extracted_fields`, `invoice_checks`, `invoice_assessments`, and `invoice_reviews`.

Current extraction is **manual-entry copying**, not implemented OCR. The default provider assigns confidence 1 to supplied fields; preserve provenance so this cannot be read as independent machine verification. Matching covers business identity, totals/currency, bank evidence, documents/duplicates and milestone evidence. V22 stores private document bytes, result/policy JSON and fingerprints. Upload validation checks actual bytes, bounded size, extension/MIME and server-side SHA-256; it deliberately avoids remote URL fetching. This is useful validation, not a malware scanner or full document parser.

Future extraction adapter can add OCR with field-level source/page/confidence, extraction/provider/model versions, safe error handling and human correction history. Run expensive extraction outside a locked financial transaction through the shared job/outbox infrastructure, then commit version-bound results. Low confidence/provider failure stays REVIEW. Add scanning/quarantine before parsing, controlled document access and retention. Deliverables need equivalent protected evidence handling; do not assume their file URLs share invoice protections.

Adapt each invoice assessment to DOCUMENT EngineDecision, retaining the source assessment ID and matching policy snapshot. Revalidate approval fingerprints at release and execution preparation, not only at the moment the PM clicks approve. No new invoice, matching, or invoice review tables are warranted. General document tables should wait for a concrete non-invoice document requirement.

## 12. Reconciliation Engine Assessment

Keep `ReconciliationService`, `ReconciliationRun`, `ReconciliationItem`, repositories and APIs. Existing code fetches a 30-day bank window, loads all internal payment transactions/settlements, matches inbound references/UTRs and compares inbound amounts; it records matched, amount mismatch, missing external/internal results.

Current deficiencies are specific:

- Settlement match checks reference/UTR existence then writes bank amount into both amount fields; it does not compare against settlement amount.
- No loop checks for every internal settlement missing externally. Inbound matching does not validate currency, direction, destination account or final status.
- `findAll()` internal history is compared against a bounded bank window, producing false missing records for older transactions; matching can reuse a bank row.
- Feed records are not durably imported with statement identity, cursor, timezone, value date or balance controls. Mock feed is memory-only.
- Run COMPLETED denotes computation completion, not zero exceptions. There is no implemented nightly scheduler and no complete exception resolution/aging workflow.
- APIs are authenticated but lack specific finance/admin or organization scope checks.

Extend the same service with durable `bank_statement_imports` and `bank_statement_lines`, bank/account/date scope, paginated ingestion, unique provider row identity, one-to-one or explicitly modeled partial matching, currency/direction/account/status comparison, fees/refunds/reversals, opening/closing balance checks and exception ownership. Extend existing items with links, resolved-by/time/reason and source IDs; do not create competing reconciliation runs/items tables.

RECONCILIATION decisions reflect current verified funding and relevant unresolved exceptions. Before a new payout, require reconciled/confirmed available funding according to policy; do not require the future payout's own bank statement row, which would create a circular prerequisite. After dispatch, use reconciliation to recover unknown outcomes and detect missing finality, never to invent cash from an internal match alone.

## 13. Dispute Intelligence Plan

Reuse `disputes` and `DisputeService`, deal/milestone links, reasons, resolver/notes/time, agreement audit events, deliverable reviews, invoice assessments/documents, payment/settlement evidence, notifications and risk history. Existing creation validates milestone/deal relationship and party access and locks the deal; resolution is admin-only. Deal-specific read access lacks a party check. Resolution restores ACCEPTED rather than a versioned prior financial/workflow state.

Add `dispute_evidence` as links with immutable content hash/version, submitter, event time and access scope; reuse documents rather than copying bytes. Persist DISPUTE decisions with timelines, missing evidence and recommended next actions. Human adjudication remains authoritative. Open disputes block eligible release scope; closed disputes do not automatically restore stale approval or retry a failed payout. Lock/revision controls must serialize open/resolve operations against release preparation. Do not create a replacement dispute case table.

## 14. Release Decision Engine Plan

The engine evaluates an immutable input snapshot and returns/persists a recommendation. It has no `BankingProvider`, `BankingExecutionService`, ledger-posting or escrow-mutation dependency. It never invokes the existing combined settlement method. An application coordinator may collect current evidence and persist the snapshot; evaluation itself does not perform financial writes.

Dependencies: RISK for both counterparties; TRUST for identity/authority/beneficiary; MILESTONE_RULES for contractual eligibility; APPROVAL for required human actions; FRAUD; DOCUMENT; RECONCILIATION for available verified funding; DISPUTE. Version the dependency policy and model nonapplicability explicitly with an ALLOW reason plus policy justification. Missing/time-out/stale mandatory evidence is never implicit ALLOW.

Common model evaluation: introduce `DecisionStatus = ALLOW | REVIEW | BLOCK` and `EngineType = RISK | TRUST | MILESTONE_RULES | APPROVAL | FRAUD | DOCUMENT | RECONCILIATION | DISPUTE | RELEASE`. Retain detailed native statuses in evidence. BLOCK dominates REVIEW; REVIEW dominates ALLOW; all required current decisions must ALLOW for an ALLOW release. Expiry/revocation can still prevent authorization or dispatch after an earlier ALLOW.

`EngineDecision` should include all requested fields: `id`, `organizationId`, `engineType`, `subjectType`, `subjectId`, `decision`, optional `score`, `reasonCodes`, `explanation`, `evidenceReferences`, `ruleVersion`, `correlationId`, `evaluatedAt`. Add `evaluationId`, input fingerprint/revision, `validUntil`, policy content hash/reference, evidence observed-at times, source assessment ID, schema/engine implementation version, and evaluation outcome/error code. Errors should produce REVIEW plus an explicit failure reason; the decision enum is not a job lifecycle enum. Score range and direction are engine-specific, never averaged blindly.

For shared buyer/vendor deals, `organizationId` must identify the evaluation owner, not pretend there is only one participant. Derive visibility from the deal's authorized parties and platform permissions; keep counterparty-private evidence redacted. New decisions require validated organization binding; legacy unbound deals remain migration-blocked. Do not duplicate risk/invoice evidence into unrestricted JSON.

Release snapshot must freeze: deal and milestone IDs/revisions; both organization IDs; amount, currency and rounding; signed agreement ID/version/hash; deliverable IDs/hashes/review versions; invoice revision/assessment/policy and approval IDs; approval actors/stages/authority versions; KYC/KYB verification IDs/status/expiry; selected beneficiary account/provider token, verification and version/hash; current risk assessments/restrictions; fraud/dispute/reconciliation decision IDs; funding events, balance/reservation version; all engine decisions and reasons; effective policy bundle; evaluation time/expiry and correlation/causation IDs. Persist both the canonical snapshot and its hash.

Do not create a separate `release_decisions` table alongside common decisions: RELEASE is an `engine_decisions` row. A NEW `release_instructions` record references that decision and carries a distinct authorization/execution lifecycle.

## 15. Settlement Separation Plan

Required boundary:

```text
ReleaseDecisionEngine -> EngineDecision(RELEASE)
  -> ReleaseInstruction -> Authorization
  -> BankingExecutionService -> BankingProvider (Bank Adapter)
  -> trusted bank outcome -> existing LedgerService + EscrowService projection
```

1. **Evaluate:** persist immutable component decisions and release snapshot. REVIEW/BLOCK create no executable instruction.
2. **Propose instruction:** application coordinator creates an idempotent, amount/currency/beneficiary-bound release instruction referencing the ALLOW decision. It has no bank side effect.
3. **Authorize:** refactored `SettlementAuthorizationService` validates current actor and active organization authority, separation of duties, decision expiry and source versions. In one short transaction, acquire ordered locks, recheck funds/holds, reserve the amount, create/link the existing settlement instruction, record authorization and outbox event, then commit. No bank call.
4. **Dispatch:** `BankingExecutionService` durably claims the authorized work with a lease/fencing version. Revalidate critical changes before dispatch, persist an attempt and stable provider idempotency/reference, commit; call the adapter outside database locks. The API caller cannot choose arbitrary execution credentials or beneficiaries.
5. **Observe:** distinguish accepted/processing from settled. Record a bank response, verified callback or status-query result durably. Unknown/time-out stays UNKNOWN/PENDING_RECONCILIATION; do not assume failure and create a fresh payout.
6. **Finalize:** on trusted final success, atomically update settlement status, post exactly one existing double-entry journal, consume reservation, update escrow and milestone projection, append audit and outbox notification. Duplicate callback/poll results reuse the same financial event identity. On definitive failure release the reservation; a retry needs a policy-valid instruction and cannot coexist with unresolved prior execution.

Suggested release lifecycle: PROPOSED -> AUTHORIZED -> DISPATCHING -> SUBMITTED -> SETTLED; alternatives EXPIRED, CANCELLED before dispatch, FAILED only for proven failure, and UNKNOWN after uncertain external effects. Existing settlement statuses must be extended consistently rather than silently mapped to SETTLED. Keep release approval state separate from bank state and reconciliation state.

Store beneficiary snapshot in `release_instructions` (immutable fields plus verification/version reference); execution resolves the provider beneficiary token or securely stored account inside the adapter boundary. A masked account is not executable banking information. Reuse existing encrypted verification data only through restricted service access; do not copy raw bank details into engine explanations.

Beneficiary changes and other critical mutations must invalidate unexecuted instructions under the same synchronization protocol. Once dispatch has started, changes cannot retarget it; retain the original snapshot and resolve/cancel with the bank where supported. A post-dispatch dispute creates an operational hold/escalation, not fictional rollback of an external transfer.

Reuse `settlement_instructions` as the bank lifecycle record. New execution attempts record retries and unknown outcomes. Keep funds reservation on the release instruction initially; under escrow/deal locks sum active reservations or maintain a checked reserved projection. A separate reservation table is unnecessary unless partial/multiple release allocations are approved requirements.

## 16. Razorpay Migration / Legacy Plan

These labels describe future work only. Nothing is deleted in this audit.

| Component | Razorpay treatment | General classification / rationale |
|---|---|---|
| Active `J/payment/service/RazorpayService.java`, `J/payment/config/RazorpayProperties.java` | QUARANTINE | LEGACY. Actual order/signature implementation remains active; isolate behind an explicitly legacy profile and disable new order creation after caller migration. |
| `PaymentController` create-order/verify/config and `PaymentService` provider execution | QUARANTINE | LEGACY. Remove from bank-engine dependency graph; use explicit capability response, no fallback to Razorpay. |
| Existing Razorpay webhook for historical in-flight payments | KEEP TEMPORARILY | LEGACY. Only if outstanding provider obligations exist; strict signatures, reconciliation, durable dedupe and sunset owner/date. No new business intake. |
| `Payment`, `FundingRail`, provider fields, old payment DTOs and read views | LEGACY | Preserve historical rail/provider and audit lineage. Do not relabel historical Razorpay success as bank confirmation. |
| Shared ownership/idempotency checks and escrow funding logic inside `PaymentService` | ADAPT | EXTEND shared validated financial-event posting only; do not adapt Razorpay API semantics into the bank contract. |
| `WebhookEvent`/repository and V8 infrastructure | ADAPT | EXTEND provider-neutral inbox for banking; reuse table, not weak legacy processing assumptions. |
| `frontend/lib/razorpay.ts`, corporate milestone payment checkout page | REMOVE | LEGACY future removal after bank funding UI/API works and historical navigation is retained. |
| `frontend/services/payment.service.ts`, payment type aliases, corporate/vendor/finance payment pages | ADAPT | EXTEND bank-oriented clients and retain clearly labelled historical views where required. |
| `legacy/backend-razorpay/*`, disabled Node Razorpay routes/controllers | LEGACY | Keep archived/non-runnable; remove runnable duplicate packaging only after reference checks. |
| Active Razorpay tests and fixtures | KEEP TEMPORARILY | DO NOT TOUCH baseline now; retain historical callback/security coverage until obligations end, then isolate legacy suite. |
| Razorpay config/documentation descriptions and obsolete checkout-only artifacts | REMOVE | Future cleanup after reference census; historical migrations remain DO NOT TOUCH. |

Cutover sequence: enumerate outstanding orders/callbacks and consumers; stop new legacy intake; route new bank funding only through secured orchestration; reconcile old balances and provenance; isolate historical reads/callbacks; validate no legacy execution references from engines; then retire obsolete runtime/UI pieces. Never bulk-delete historical `payments`, escrow entries or V3/V8 rail defaults. Blockchain contracts, on/off-ramp remnants and UI routes receive the same legacy separation, without creating replacement wallet/chain concepts.

## 17. Proposed Package Structure

Retain existing `com.finx` domain packages. Proposed additions are responsibilities, not implementation files created by this audit:

```text
com.finx.engine.model          DecisionStatus, EngineType, EngineDecision, evaluation context
com.finx.engine.service        decision persistence, policy resolution, evidence freshness
com.finx.engine.repository     common decisions and immutable policy versions
com.finx.risk                  existing risk + common decision adapter
com.finx.trust                 trust evaluator over verification and performance
com.finx.milestone.rules       deterministic contractual evaluator
com.finx.approval              workflow instances, stages, actions, authorization policy
com.finx.fraud                 signal evaluator using existing risk/invoice evidence
com.finx.invoice               existing extraction/matching + document decision adapter
com.finx.reconciliation        existing service + statement ingestion / decision adapter
com.finx.dispute               existing case service + evidence / intelligence adapter
com.finx.release               ReleaseDecisionEngine, snapshot and instruction coordination
com.finx.paymentorchestration  authorization, bank lifecycle, BankingExecutionService
com.finx.banking               existing BankingProvider and isolated provider adapters
com.finx.events                transactional outbox and durable consumer delivery
```

Dependency direction: domain evaluators depend on read-only evidence contracts; release depends on those contracts/common decisions; execution depends on authorized instructions and banking; common model depends on neither controllers nor providers. Enforce this with architecture tests. Avoid a universal mutable service that owns all nine domains.

## 18. Proposed Database Tables

New-table list is deliberately limited. All are future proposals, not DDL. Use UUID primary keys, UTC timestamps, explicit organization/subject scope, indexed foreign keys, exact decimal financial amounts consistent with currency policy, and immutable decision/evidence payloads. Use JSONB for structured snapshots only when indexed query needs justify it; scalar keys and frequently filtered fields remain typed columns.

| New table | Required purpose / principal constraints |
|---|---|
| `engine_policy_versions` | Immutable policy bundle/content/hash, engine type, tenant/platform scope, version, activation and author. Unique scope/type/policy-key/version with explicit handling of platform scope. Archive referenced risk/invoice policy content without replacing their authoritative configuration services. |
| `engine_decisions` | All nine result envelopes, subject scope, input hash, correlation, version and expiry, evidence references and release snapshot. Append-only; unique evaluation/engine/subject identity; indexes by org/subject/time and correlation. |
| `approval_workflows` | Instance pinned to subject revision and policy version; one active applicable workflow per scope/revision/purpose. |
| `approval_steps` | Immutable instantiated stages/quorum/eligible-role requirements plus versioned current state; unique workflow/stage identifier. |
| `approval_actions` | Append-only actor decisions bound to step/evidence revision; prevent duplicate effective votes by actor and idempotency key; preserve superseded actions. |
| `release_instructions` | Immutable proposed amount/currency/beneficiary/decision plus versioned lifecycle/authorization/reservation. Unique org/operation/idempotency key; at most one active or successful full release per milestone. |
| `bank_execution_attempts` | Durable attempt/request hash, instruction FK, provider reference, stable provider key, lease/fencing metadata and response/outcome history. Unique instruction/attempt number; no new provider key for an uncertain retry. |
| `outbox_events` | Domain-event envelope saved with source mutation: event ID, org, aggregate revision, schema, correlation/causation, safe payload and creation time. Immutable payload. |
| `outbox_deliveries` | Per-event/per-consumer delivery state, lease, attempts/retryAt/dead-letter; unique event/consumer. Required for independent risk/trust/fraud/notification delivery without treating one consumer's success as everyone's. |
| `bank_statement_imports` | Provider/account/window/cursor, source hash, ingestion status and statement balance metadata; idempotent import identity. |
| `bank_statement_lines` | Durable provider line/reference, amount/currency/direction/account/value date/status; unique provider/account/line identity; links used by existing reconciliation items. |
| `dispute_evidence` | Existing dispute FK, protected document/evidence reference, immutable revision/hash, contributor and time. No duplicate case or document bytes. |

Optional later: `trust_profiles` as a latest-decision projection. Not required for first delivery. No new risk, invoice, ledger, reconciliation-run/item, KYB/KYC, generic fraud case, or duplicate webhook/idempotency tables.

Extend existing tables instead:

- `webhook_events`: protected payload/reference, provider-account scope, safe authentication metadata, retry/lease/error fields; keep provider event identity and detect same ID/different hash. Bank confirmation must actually use it.
- `idempotency_keys`: explicit organization/operation scope, request hash, in-progress/completed status, stable response reference; migrate global PK safely rather than shadowing it with another table.
- `settlement_instructions`: release instruction FK, version and honest provider lifecycle. Unique provider/account/settlement identity; unique nonnull release-instruction link. Partial milestone uniqueness for active/successful releases, consistent with instruction state.
- `payment_instructions`/`payment_transactions`: organization ownership, provider account and immutable bank event identity, locking/version, amount/currency validation. Do not assume UTR alone is globally unique across providers.
- `escrow_ledger`: add explicit bank instruction/confirmed transaction reference. Existing `payment_id` references legacy `payments` in V3 and must stay valid for historical rows. Define mutually exclusive source references and dedupe by confirmed event. Refine V4's overly broad milestone/type FUND uniqueness only after defining partial funding semantics; retain release uniqueness.
- Journal entries/lines: source event uniqueness, protected posted history, reversal linkage, account/entry currency consistency, full line-total validation; header debit=credit alone does not prove line sums match.
- Deal/milestone/agreement/bank account/dispute: revision fields and critical-change invalidation; organization binding and membership enforcement. Add one-primary-account uniqueness with a partial index after resolving duplicates.
- Existing reconciliation items/runs: source links, bounded scope, matching-policy version, failure/resolution/ownership fields. Existing audit/verification/invoice histories: correlation and stronger append-only protection where appropriate.

Do not force all historical rows into fabricated organizations or beneficiary snapshots. Mark unmappable legacy records explicitly and deny new execution until remediated. For tenant defense in depth, design RLS with transaction-local trusted scope and a non-bypass runtime role, including multi-party deals and background workers; test pool context reset before activation. Application checks remain required.

## 19. Flyway Migration Sequence

Provisional versions assume V22 remains the highest when implementation starts. Recheck branch and deployed Flyway history first. **Never modify historical SQL or Java migrations.** No migrations are created by this audit.

| Order | Proposed migration | Scope and prerequisite |
|---|---|---|
| V23 | `financial_source_reference_and_tenant_columns` | Add explicit bank source FKs and nullable ownership/revision/correlation fields. Preserve legacy payment FK/data. |
| V24 | `financial_reference_backfill_and_constraints` | Verified lineage/org backfill; duplicate inventory; event uniqueness, safe FUND/RELEASE constraints, primary beneficiary rules. Abort/report ambiguity rather than delete records. |
| V25 | `durable_banking_inbox_and_idempotency_scope` | Extend V8 infrastructure and provider identity indexes; migration-compatible old/new key handling. |
| V26 | `transactional_outbox_and_deliveries` | New event and per-consumer state; unique delivery and pending-work indexes. |
| V27 | `engine_policy_versions_and_decisions` | Immutable common results, policy bundles, tenant/query indexes and evidence references. |
| V28 | `enterprise_approval_workflows` | Workflow, step, action tables and revision-bound uniqueness. |
| V29 | `release_instructions_and_bank_attempts` | Release snapshot/instruction/reservation and attempts; link settlements, enforce one active/successful full release. |
| V30 | `bank_statement_ingestion_and_reconciliation_extensions` | Durable feed, existing runs/items extensions and deterministic matching references. |
| V31 | `dispute_evidence_and_decision_invalidation` | Evidence links and remaining cross-domain invalidation/revision fields. |
| V32 | `financial_history_and_tenant_enforcement` | Append-only posted history and tested tenant/RLS enforcement, stronger NOT NULL/composite relationships after validated backfill. |

Keep each stage expand -> deploy compatible writers -> validate/backfill -> enforce; split a version further if online index creation/backfill size needs separate deployment steps. Financial source fixes and tenant checks precede common framework integration. Use production-shaped PostgreSQL snapshots for upgrade rehearsal; test clean V1-to-latest too. Feature rollback disables new writes/dispatch and keeps evidence; it must not drop committed financial history or re-enable the bypass.

## 20. API Changes

| Existing / proposed surface | Future contract |
|---|---|
| `POST /api/v1/payments/instructions/{id}/confirm` | Remove public financial assertion authority. Only authenticated provider ingestion/internal operations with verified evidence may confirm. Any sandbox simulator must be explicitly sandbox-only and separately authorized. |
| Banking webhook `/api/v1/webhooks/banking/{provider}` | Required provider-specific authentication and event identity, strict malformed/replay checks, durable inbox acknowledgement; verify instruction/account/amount/currency. |
| Instruction/settlement/dispute reads by deal | Enforce party/organization scope in service and repository, including replay responses and referenced evidence. |
| `POST /api/v1/milestones/{id}/release-decisions` (new) | Evaluate and persist recommendation with correlation; no financial effect; return reasons, evidence versions and expiry. |
| `POST /api/v1/release-instructions` (new) | Propose instruction from current ALLOW decision; mandatory idempotency and request hash. Server derives amount and beneficiary snapshot. |
| `POST /api/v1/release-instructions/{id}/authorize` (new) | Authorized actor, expected revision and separation-of-duties checks; queues execution, normally 202, never promises immediate disbursement. |
| `GET /api/v1/release-instructions/{id}` (new) | Scoped honest lifecycle and linked settlement/outcome; no raw bank payload. |
| Current `/settlements`, `/settlements/authorize` | Deprecate or adapt to the same instruction/authorization path; prohibit an alternate synchronous execution branch. |
| `POST /api/escrow/{milestoneId}/release` | Retire public direct projection mutation; compatibility route must use the new governed path. Internal projection updates only after trusted final outcome. |
| Approval actions / engine decision history (new) | Scoped queries and expected-version writes; append-only actions with required reasons/evidence. Avoid exposing one unrestricted arbitrary-subject engine endpoint. |
| Reconciliation trigger/runs/items | Platform finance/admin or explicitly tenant-scoped finance permissions; bounded dates/provider account, pagination, durable job/exception status. |
| Legacy payment reads/callbacks | Explicit legacy surface/provenance, temporary historical support only. |

Use 403/404 consistently to avoid resource enumeration, 409 for idempotency payload conflicts/stale revisions, and structured REVIEW/BLOCK results for valid evaluation requests. Add correlation to responses. Existing frontend `b2b.service.ts` expects some unimplemented routes such as instruction-by-ID/simulate-confirmation/settlement-by-ID; reconcile actual mappings before UI migration rather than documenting them as existing APIs.

## 21. Event Architecture

Keep risk's durable queue and processors. `AuditService` publishes `RiskDomainEvent` synchronously in-process; the listener persists risk work. That is useful local behavior, not an external durable multi-consumer bus. Audit action-string mapping must be tested for every originating domain and entity type.

Introduce a transactional outbox envelope: eventId, schemaVersion, organization/authorized subject scope, aggregateType/id/revision, eventType, occurredAt, recordedAt, correlationId, causationId, source evidence reference and redacted payload. Save it in the same transaction as the authoritative change. Publish at least once, with independently durable consumer delivery/claim/retry state. No exactly-once network promise.

Events include verification/authority changed, beneficiary added/verified/changed, agreement revised/signed, deliverable submitted/reviewed, invoice assessed/reviewed, milestone revised/approved, dispute opened/resolved, bank credit confirmed/reversed, release evaluated/authorized/invalidated, settlement submitted/unknown/settled/failed, reconciliation exception opened/resolved and policy activated. Reuse existing source IDs.

Risk consumes canonical source identity using its existing unique source key; bridge gradually so audit plus outbox cannot double-count. Trust and fraud consume the same facts. Notifications use existing service/channels with event-recipient-template dedupe. Corrections/reversals are new events, not destructive history edits. Support bounded retries, dead-letter inspection/replay, event lag and delivery metrics. Engines must not recursively trigger reevaluation indefinitely from their own derived events.

## 22. Idempotency Strategy

Existing protection is material but incomplete: V13 unique references/nullable keys; V4 legacy provider-order, escrow payment and milestone/type uniqueness; V8 webhook event uniqueness and unused general key table; V17 risk source uniqueness. Bank controller's memory set is lost on restart, not shared across replicas, accepts blank IDs and records IDs before successful processing.

Require organization + operation + client key and canonical request hash for commands. Authenticate and authorize the referenced resource **before** returning a stored response. The same key/same authorized request returns the original result; changed payload produces conflict. Retain financial operation identity after a short API response-cache TTL expires. Different client keys must still be prevented from releasing the same milestone twice.

Reuse/extend V8 tables. Inbox insert and business confirmation become atomic or recoverably staged; unique provider/account/event ID plus payload hash detects conflicting duplicates. Concurrent identical arrivals must converge through a database constraint, not read-then-insert alone. A provider event ID is not the API user's idempotency key.

Create one stable provider idempotency key from the durable settlement/release identity **before** any call. Retries and timeout recovery use that identity and status lookup. Journal/source event and escrow projection uniqueness are independent final safeguards. Replace process-local AtomicLong financial references with durable database/UUID-derived identifiers. Do not reuse a new key merely because the original HTTP response was lost.

## 23. Concurrency Strategy

Existing locks are useful: settlement/release lock milestone then deal; funding locks deal; ledger accounts lock in request line order; risk locks subjects/events; invoice upload locks milestone and review locks invoice. Optimistic versions exist on escrow, invitations, invoices and selected risk entities, but not consistently on all release inputs.

Adopt and test one global protocol. Prefer sorted organization/authority dependencies when needed, then deal, milestone(s), evidence/instruction rows in defined order, escrow/reservation, and sorted ledger accounts. All writers that invalidate release evidence must participate. Do not mix old milestone-first and new deal-first writers during cutover. For high-contention evidence, collect a versioned snapshot and revalidate in a short critical section instead of holding many locks during scoring.

Specific hazards:

- Deposit posts ledger then acquires deal/escrow locks; settlement holds deal then posts ledger. Deposit and settlement ledger line order is also reversed. This creates deadlock potential under concurrent workloads.
- Milestone approval/rejection use ordinary reads and no milestone optimistic version; concurrent decisions can overwrite each other. Agreement editing/signing has similar revision race exposure.
- Optional key checks and SETTLED-only checks cannot guard an external side effect that occurred before database rollback.
- Risk REQUIRES_NEW while caller holds locks needs explicit contention tests; avoid nested transactions that need locks held by the suspended transaction.
- Bank-account primary changes and account verification selection need atomicity and deterministic beneficiary choice; an Optional query may fail when multiple verified accounts exist.
- Catching a unique violation and continuing in the same transaction (escrow account creation fallback) may leave the transaction rollback-only; use safe conflict handling or retry a fresh transaction.

Use short worker claims with `SKIP LOCKED`/lease/fencing for queued work; never hold a DB transaction across bank/OCR calls. Persist state transitions with expected version. On deadlock/serialization failure retry boundedly **before external side effects**; after dispatch, reconcile identity first. Atomic reservation under escrow/deal lock prevents two distinct milestones overspending the same balance. Database uniqueness complements locks; PostgreSQL race tests with multiple connections are required.

## 24. Security Findings

Findings below are source-confirmed unless marked otherwise. They were not exploited against a running service. Severity describes architectural impact, not a measured production incident.

| ID / priority | Evidence and trigger | Impact / required remediation |
|---|---|---|
| S01 CRITICAL | `PaymentOrchestrationController.confirmPayment` -> `PaymentOrchestrationService.confirmPayment`; only generic authenticated route rule, no role/party/bank proof check | Caller knowing an instruction ID can assert SUCCESS and internal funding. Restrict to trusted bank ingestion and validate actual credit facts. Database reference errors may abort a particular deployment; that is not an authorization control. |
| S02 CRITICAL | `BankingWebhookController`: signature checked only when present; timestamp optional and malformed numeric value ignored; route public in `SecurityConfig` | Unsigned events can invoke confirmation. Require authentication, registered provider binding, strict timestamp/nonce semantics, event ID, account/amount/currency validation and durable replay protection. |
| S03 CRITICAL | `SettlementAuthorizationService.authorizeAndInitiateSettlement`: bank call precedes save; sets SETTLED regardless of result.status | External payout may survive rollback; retry may duplicate; pending/failed bank result can complete internal accounting. Implement section 15 before real adapter activation. |
| S04 HIGH | `EscrowController.releaseEscrow` -> `EscrowService.releaseEscrow` | Buyer/admin can bypass invoice, KYB, bank and risk gates and mark money/milestone released internally. Govern all release paths through one authorization/execution lifecycle. |
| S05 HIGH | `AuthService.register` rejects only ADMIN; `RegisterRequest` accepts FINANCE; `SecurityConfig` admits FINANCE to global finance routes; `FinanceController` uses `findAll()` | Self-registration grants privileged platform financial visibility. Restrict public roles and make organization finance distinct from platform finance. |
| S06 HIGH | `getInstructionsForDeal`, `getSettlementsForDeal` ignore principal; `DisputeService.getDisputesForDeal` has no principal; reconciliation reads/triggers have no specific role scope | Cross-deal IDOR and platform data/operation access for ordinary authenticated users. Enforce service-level subject access and scoped repositories. |
| S07 HIGH | Settlement global idempotency lookup returns before buyer/admin check; funding global lookup can return another deal's key result after checking only requested deal | Known key can leak a different resource or mismatch the intended command. Authorize stored resource and scope/hash keys. |
| S08 HIGH | `DealService.createDeal` copies supplied buyer/seller organization IDs without membership/party binding; `OrganizationService.validateOrgMembership/Role` ignores member status | Unrelated organization evidence can be attached; inactive membership can retain authority. Validate active binding on every command and migrate existing bad links. |
| S09 HIGH | Settlement retains beneficiary account FK only, selects any VERIFIED account, sends masked number; older org verification also uses masked data | No immutable beneficiary guarantee or production executable target; change/reselection can invalidate prior evidence. Consolidate verification, pin version/token, enforce change invalidation and deterministic selection. |
| S10 HIGH | Milestone approval has no revision snapshot; settlement checks invoice status but does not rerun assessment fingerprint; agreement/beneficiary/verification can change | Stale approval can authorize changed facts. Bind and revalidate snapshot at authorization/dispatch; invoice review's existing freshness checks are a pattern to reuse. |
| S11 HIGH | V13 no milestone settlement uniqueness; no provider-effect uniqueness; V12 journal reference_id only indexed; optional keys | Duplicate attempts or replays can have distinct references. Add business-effect uniqueness, reservation, provider identity and confirmed-event accounting. |
| S12 HIGH | V3 `escrow_ledger.payment_id` FK -> payments; bank `confirmPayment` passes PaymentInstruction ID to `fundEscrow`; no later FK correction found | Flyway schema and bank service disagree. Expected FK failure on migrated PostgreSQL; standard H2/JPA tests do not validate this path. Add explicit bank source FK and migration-backed integration test. |
| S13 HIGH | `AgreementService.signAgreement` and deal/settlement flows do not call available eligibility validation methods; repository search found definitions without callers | Existing KYC/signatory/production-readiness helpers do not enforce those operations. Wire purpose-specific authority/expiry checks; do not count method existence as a gate. |
| S14 HIGH | Reconciliation treats a matching settlement reference as matched without checking internal amount; incomplete outbound and currency/status checks | False financial assurance. Extend existing matcher, preserve raw bank facts and test adversarial discrepancies. |
| S15 MEDIUM/HIGH | Lock inversions, ordinary approval reads, REQUIRES_NEW nesting; section 23 | Deadlocks, stale writes and lost approval/authority changes. Stable lock order, expected revisions, short transactions and multi-connection tests. Runtime incidence not measured. |
| S16 MEDIUM | `OrganizationService.getVerifiedBusinessProfile` mixes org/user identifiers and global counts | Misleading trust metrics and cross-tenant aggregates. Replace with properly scoped source queries before Trust Engine reuse. |
| S17 MEDIUM | Financial/audit/invoice history lacks general DB mutation guard; risk V18 guard is narrower | Append-only intent can be bypassed through alternate writes. Restricted privileges, immutable evidence and compensating reversals; no deletion as correction. |
| S18 MEDIUM | Manual extraction reports default confidence 1; deliverables accept URLs; invoice magic-byte checks limited | False confidence and inconsistent evidence integrity. Explicit provenance, scanner/parser safeguards and protected deliverable storage. |
| S19 HIGH readiness | Mock banking is the only adapter and default Render configuration; mock signature accepts fixed test value and does not cryptographically bind body | Mock success cannot prove production readiness. Production execution must reject sandbox providers and require tested real adapter capabilities. |

Positive controls to preserve: buyer/admin checks in create instruction and settlement, signed agreement and dispute gates, pessimistic release locks, V4 escrow uniqueness/nonnegative balance, balanced journal validation, active-member check on invoice upload, assigned-PM invoice access/review checks, byte-derived invoice hashes, stale invoice review protection, admin-only dispute resolution, user-state checks in JWT filter, risk history triggers, and fail-closed financial audit persistence.

## 25. Recommended Implementation Order

| Phase | Deliverable / exit gate |
|---|---|
| 0. Baseline and containment | Reproduce supplied baseline on the exact working tree; secure S01/S02/S04/S05/S06/S08; inventory live providers/legacy obligations; prove all execution entry points are contained. Preserve existing unrelated changes. |
| 1. Financial/schema integrity | Correct bank-source FK mismatch, tenant binding, business-effect uniqueness, scoped idempotency/inbox, currency/account semantics and lock order; PostgreSQL migration + race tests pass. |
| 2. Durable events and common decision contract | Outbox/delivery, policy archive and common envelope; adapt existing Risk and Document in shadow/read-only mode. No live release integration; prove no bank or accounting dependency. |
| 3. Trust, milestone rules and approvals | Scoped trust inputs, deterministic rules and revision-bound enterprise workflow. Preserve single-stage existing behavior as explicit initial policy; evaluate differences in shadow mode. |
| 4. Fraud, reconciliation and dispute extensions | Canonical signal provenance, durable statements and robust existing reconciliation, evidence-linked dispute recommendations. No automatic adjudication. |
| 5. Release and authorization separation | Immutable release snapshot, instruction/reservation, explicit authority and stale-evidence checks; replace every public release bypass with the governed path. Test entirely with sandbox/fake adapter. |
| 6. Banking execution and recovery | Durable attempts, real adapter contract/certification, strict callback ingestion, polling/unknown recovery, exactly-once internal effect, reversals/reconciliation and operational controls. No rollout until all critical findings closed. |
| 7. Product and legacy cutover | Bank-oriented UI/API parity, historical legacy reads, drain old obligations, isolate/remove obsolete checkout and blockchain paths, correct CI/docs, staged monitored rollout. |

A small common model can be developed in an isolated follow-up after Phase 0/1 contracts are fixed. The current audit verdict does not authorize treating the framework as a safe live-release wrapper. Deploy behind disabled execution flags, compare shadow results, and enable per-provider/organization only after exit gates. Rollback stops dispatch while retaining in-flight recovery and historical evidence.

## 26. Files Expected to Change

This is a future change inventory, not changes performed here. Paths grouped by owning subsystem include their entities/DTOs/repositories/tests where the corresponding schema or contract changes.

| Future phase | Existing files/packages likely touched |
|---|---|
| Containment/auth | `J/security/config/SecurityConfig.java`, `J/auth/service/AuthService.java`, `J/auth/dto/request/RegisterRequest.java`, `J/common/enums/Role.java`, finance/org/deal/dispute/reconciliation controllers and services. |
| Financial boundaries | `J/paymentorchestration/service/{PaymentOrchestrationService,SettlementAuthorizationService}.java`, controller/DTO/entity/repository packages; `J/banking/controller/BankingWebhookController.java`, `J/banking/provider/BankingProvider.java`, `J/banking/dto/BankingDtos.java`. |
| Accounting/concurrency | `J/ledger/service/LedgerService.java`, ledger repositories/entities, `J/escrow/service/EscrowService.java`, `J/escrow/controller/EscrowController.java`, escrow repositories/entities; milestone/deal locks and revision writers. |
| Evidence and identity | `J/verification/service/{VerificationService,VerificationEligibilityService}.java`, org bank account/service/repository, `J/agreement/service/AgreementService.java`, `J/milestone/service/MilestoneService.java`, PM assignment service; `J/invoice/service/InvoiceService.java` and extraction/matching adapters. |
| Risk/event integration | `J/risk/service/{RiskEventService,RiskEventProcessor,RiskScoringService,RiskEvidenceService,RiskPolicyService,RiskActionService}.java`, worker/events, `J/audit/service/AuditService.java`, notification/reminder integration; preserve core scoring. |
| Reconciliation/dispute | `J/reconciliation/service/ReconciliationService.java`, existing run/item entities/repositories/controller; dispute service/controller/evidence extension. |
| New additions | New packages in section 17 and only new Flyway files starting after current latest; no modification of V1-V22 or Java V18/V19. |
| Legacy/product phase | Active `J/payment` execution/config and legacy tests; `frontend/services/{b2b,payment,escrow,invoice,milestone}.service.ts`, `frontend/types/{index,b2b}.ts`, `frontend/lib/razorpay.ts`, corporate milestone payment and project pages, vendor project/payment pages, PM reviews, finance/admin views, legacy escrow components/routes. |
| Delivery/docs | `.github/workflows/ci.yml`, relevant runtime profiles and provider readiness configuration, `README.md`, `docs/{ARCHITECTURE,BANKING_ARCHITECTURE,LEDGER,FINANCIAL_STATE_MACHINE}.md`; new test coverage under existing `T/` domains. |

Before implementation reconcile the pre-existing modified `InvoiceService.java`, `frontend/tsconfig.json`, invoice verification script and test artifacts with their owner. This audit leaves those changes intact. No environment file needs editing to deliver this document.

## 27. Tests Required for Each Phase

| Phase | Required tests and evidence |
|---|---|
| 0 | Preserve/reproduce 325-test supplied baseline; add HTTP negative tests for ordinary user confirmation, missing/invalid webhook authentication, FINANCE self-registration, cross-deal reads, inactive org membership and forged organization IDs; verify direct release cannot bypass gates. Tests must fail on the current gaps. |
| 1 | Real PostgreSQL clean and upgrade Flyway tests, including Java V18; bank deposit creates correct source FK and ledger; multiple instruction credits/partial funding; same/different keys concurrently; duplicate provider event; one full release per milestone; two milestones compete for one balance; currency mismatch and rollback invariants. |
| 2 | Existing RiskRuleEngine/Scoring/Integration and InvoiceMatching/Service/Document tests remain; common mapping preserves native policy, missing evidence yields REVIEW, immutable results/policies, tenant-redacted evidence, outbox rollback/restart/multiple consumers/dead-letter replay and no duplicated risk signal. Architecture test forbids engine -> banking/ledger mutation dependencies. |
| 3 | Trust expiry/revoked authority/new organization; scoped metrics; contract sum/sequence/date/invoice rules; multi-stage quorum/threshold/delegation; requester cannot approve; reassigned PM/inactive member; approve vs reject race; material input changes invalidate all relevant approval paths. |
| 4 | Cross-milestone duplicate invoices under race; bank-change/fraud velocity provenance; reconciliation wrong amount/currency/direction/account/status, missing outbound, duplicate/partial feed rows, bounded windows, fees/reversals, unmatched inbound, import retry, statement balance; dispute evidence privacy/open-resolve-release races. |
| 5 | ALLOW never directly calls provider; REVIEW/BLOCK never generate executable work; snapshot expiry or changed beneficiary/agreement/invoice/risk/dispute prevents authorization; reservations atomic; obsolete escrow/settlement endpoints cannot create side paths. |
| 6 | Adapter sandbox contract plus provider-supported integration tests: accepted != settled; crash before call, after bank acceptance before DB commit, during finalization; unknown response; callback before HTTP response; callback/poll race; reordered/replayed/conflicting events; provider idempotency across restart; same payout never executed twice; reversal accounting; lease expiry/fencing. |
| 7 | Browser API parity for corporate/vendor/PM/finance/admin; truthful pending/unknown/final UI; legacy history and in-flight callback drain; no new Razorpay/blockchain intake; CI no obsolete path; read-only shadow comparison and staged operational acceptance. |

Existing suites to extend include `T/paymentorchestration/SettlementAuthorizationServiceTest`, `T/e2e/{BankIntegratedEndToEndTest,TransactionEngineEndToEndTest,SecurityHardenedTest}`, `T/ledger/LedgerServiceTest`, `T/reconciliation/ReconciliationServiceTest`, `T/milestone/MilestoneServiceTest`, `T/projectmanager/ProjectManagerInvitationServiceTest`, existing risk/invoice/verification suites, and `T/FlywayCleanMigrationTest`. Preserve legacy payment tests until explicit retirement; do not inflate coverage with tests that merely mirror getters or new enums.

Verification in this audit is static source/schema/call-path review and document completeness checks only. No fresh test pass, production security proof or adapter certification is claimed.

## 28. Risks and Blockers

Immediate blockers: S01-S13 control/data-integrity defects; mock-only bank integration; no immutable release/beneficiary/approval binding; no safe handling of uncertain payout outcomes; missing business-effect uniqueness; production-shaped PostgreSQL tests absent from the observed baseline. A framework layered over those paths would preserve their bypasses.

Decisions required before implementation integration: ownership model for buyer/vendor shared deals and platform finance; whether release is strictly once per milestone or permits partial releases (this plan assumes one full release); available bank idempotency/status/reversal semantics; verification expiry and policy override authority; organization mapping for legacy deals; disposition of historical Razorpay obligations; INR-only initial execution versus full multicurrency accounting. Until selected, reject unsupported financial behavior rather than infer it from a generic interface.

Operational blockers: statement feed contract and durable provider beneficiary identity; evidence retention and access model; bounded worker retries, queue lag/unknown-settlement alerting and operator recovery process; transition strategy for existing in-flight settlements; CI obsolete blockchain job. No live provider/deployment/database state was inspected, so outstanding balances, migrations applied, RLS outside Flyway, and provider configuration remain unverified.

Risk/reconciliation/invoice systems are assets to retain. The fail verdict means the **current integrated architecture is not ready to safely connect the common engine framework to financial execution**; it does not mean the audit was incomplete or that those systems should be rewritten. Re-audit after containment, data-integrity fixes, durable execution boundaries and PostgreSQL negative/race tests. Only then promote shadow decisions into authorization inputs.

ARCHITECTURE AUDIT: FAIL

SAFE TO IMPLEMENT COMMON ENGINE FRAMEWORK: NO
