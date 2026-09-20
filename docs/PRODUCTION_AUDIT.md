# FinX production audit

Baseline: `070147a`, 2026-09-20. Clean working tree verified before creating
`production-hardening`. This is a source and local-test audit, not an independent
security certification or evidence of live infrastructure configuration.

## Inventory and execution plan

The deployed path in `render.yaml` is Next.js 16/React 19 -> Spring Boot 3.4.3 ->
PostgreSQL/Flyway, with Redis and externally signed EVM transactions. Maven targets
Java 17 while Docker and CI use Java 21. The older Express/Prisma backend is not
the Render backend; preserve it but do not deploy it as a substitute.

1. Record architecture, verified risks, baseline failures and launch gates.
2. Harden project access and financial state transitions with regression tests.
3. Add an append-oriented chain ledger and reconcile it against finalized state.
4. Reject unsafe testnet deployment configurations and verify contract invariants.
5. Harden API/UX, CI, deployment configuration and operational documentation.
6. Run full available validation, retain failures and distinguish external gates.
7. Commit related changes separately; no push or production deployment.

## Findings

| ID / priority | Problem and impact | Affected files / evidence | Recommended fix | Status |
|---|---|---|---|---|
| TEN-01 / P0 | No organization membership model; FINANCE has global project access. Company isolation cannot be claimed. | `FinanceController`, `FinanceProjectController`: `findAll`; `DealController.getMyDeals`, `BlockchainEscrowRail.access`, `MilestoneService.validatePartyAccess`: FINANCE bypass. | Scope finance through explicit project grants; add organization model and verified backfill before multi-company launch. | Open |
| CHAIN-01 / P0 | Chain state is reconciled but has no independent accounting entries. | `ChainIndexer.reconcile` writes events and mutable totals, never a ledger. | Append validated financial event entries, enforce uniqueness and compare sums with chain totals. | Open |
| DEP-01 / P0 | Testnet script warns and continues on unsupported chains. | `script/DeployAmoy.s.sol`: chain mismatch only logs a warning. | Revert before broadcast; test mainnet rejection. | Open |
| DEP-02 / P1 | Old Amoy deployment installs empty dispute/pause contracts and a local fixture. Funds can become unresolvable. | `script/DeployPolygonAmoy.s.sol`: `Empty` councils. | Route legacy entry point through tested functional Amoy deployment. | Open |
| STATE-01 / P1 | Concurrent milestone reviews use unlocked reads; cancelled milestones can be resubmitted; review lacks active-deal check. | `MilestoneService.approveMilestone/rejectMilestone/submitDeliverable`. | Serialize state changes; explicit source-state validation; regression tests. | Open |
| STATE-02 / P1 | Disputes can reopen terminal projects; repeated resolution can overwrite history. | `DisputeService.createDispute/resolveDispute`. | Validate lifecycle, lock project and dispute, idempotent repeat resolution. | Open |
| STATE-03 / P1 | Generic cancellation can cancel funded projects without chain settlement. | `DealService.cancelDeal`. | Restrict generic cancellation to unfunded, uncommitted invitations. | Open |
| MONEY-01 / P1 | Milestone currency can differ from deal currency while amounts are summed directly. | `MilestoneService.createMilestone`. | Reject mismatched currencies; retain explicit token allocations separate from fiat. | Open |
| FIN-01 / P1 | Fiat reconciliation matches deposits by amount instead of payment reference; missing milestone cases are skipped. | `FinanceProjectController.getReconciliation`. | Match payment ID, account and amount, flag missing references. | Open |
| OPS-01 / P1 | CI deploy hook runs on pushes; Render auto-deploy is not disabled; AWS placeholder uses account credentials on push. | `.github/workflows`, `render.yaml`. | Manual deployment with protected environment; disable automatic deployment. | Open |
| OPS-02 / P1 | Public health always returns UP independently of DB; no demonstrated restore, alert routing or load benchmark. | `HealthController`, deployment files. | Add readiness; document and validate operational gates in staging. | Open |
| BUILD-01 / P1 | Windows Maven wrapper fails before Maven starts on non-symlink `.m2`. | `mvnw.cmd`: indexes null `.Target[0]`; baseline run reproduced. | Null-safe target resolution. | Open |
| BUILD-02 / P2 | Missing forge-std submodule and CRLF formatting fail contract baseline. | Actual format/build/test run. | Initialize pinned submodule; enforce LF; rerun. | Submodule initialized |
| UX-01 / P1 | Legacy screens contain misleading settlement/upload messages; external deliverable URLs have no private-storage lifecycle. | `frontend/app/admin/escrow`, vendor deliverables, `SubmitDeliverableRequest`. | Distinguish accounting from settlement; validate links; private scanned storage remains launch work. | Open |
| SCALE-01 / P2 | Finance/audit lists unbounded; project enrichment and milestone deliverables use per-row queries. | Finance controllers, `AuditService`, `DealService`, `MilestoneService`. | Bound pagination and measure PostgreSQL plans/load before launch. | Open |
| AUTH-REVIEW / P0 | Credential/authentication review and redacted history scan require unavailable repository-mandated skill. | Root `AGENTS.md` requires `aws-secrets-manager`; searched installed skill locations, no callable `retrieve_skill`. | Supply skill or explicitly authorize local review without it. | Blocked, user clarification pending |

## Existing controls verified in source

Contracts use SafeERC20, reentrancy guards, fixed parties, token balance-delta
checks, distinct contract councils, delayed default-admin transfer, explicit
milestone states, full funding, and a 64-milestone bound. Global disputes block
normal release. Direct token donations are intentionally not recoverable.

The chain rail stores operation/idempotency keys and intent payload hashes,
validates sender/destination/calldata, waits for confirmation depth, compares
immutable terms, polls bounded block ranges, persists checkpoints, and quarantines
changed checkpoint hashes. These controls need adversarial testing and external
audit; a confirmation threshold is not an unconditional finality guarantee.

Fiat money uses BigDecimal/NUMERIC. V4 adds financial integrity constraints.
V1-V10 migration files will not be edited. Audit failure aborts financial work.

## Coverage limits and external gates

No production database, provider account, wallet, infrastructure or ownership was
modified. No real funds were used. Supabase Data API grants/RLS, provider approval,
backup restoration, key custody, independent contract audit, penetration testing,
and legal/provider responsibilities require evidence outside the repository.
Do not infer legal compliance, production settlement or tested recovery from a
successful build. Final local results and remaining work are recorded in
`PRODUCTION_READINESS_REPORT.md`.
