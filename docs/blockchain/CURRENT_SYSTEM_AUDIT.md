# Current system audit

Baseline: b8f896b. Review: 2026-09-16. Scope: source/configuration, frontend routes/services/types, both backends, Flyway V1-V7, tests, deployment and CI. No live database, provider or custody configuration was inspected. Untracked scratch/ is user work and excluded.

PRODUCTION READINESS: NOT YET APPROVED

| Component / evidence | Disposition | Action |
| --- | --- | --- |
| Next.js 16 / React 19 / Tailwind 4, frontend/app and components | KEEP | Preserve layouts, role navigation, forms and routes |
| frontend/types/index.ts, services, escrow/payment/timeline UI | MODIFY | Decimal-string money; independent custody and INR settlement states |
| Spring Boot 3.4.3 / Java 21, backend/pom.xml | KEEP | Authoritative API: render.yaml/Dockerfile build Java |
| backend/src/server.ts, TS controllers, Prisma schemas | REMOVE (planned) | Server immediately throws to disable legacy Node; keep disabled until reference inventory; do not migrate legacy split databases |
| JWT, BCrypt, refresh-token hashes/rotation, Google OAuth | KEEP / MODIFY | Preserve flows; localStorage tokens need stronger XSS/session protection and privileged step-up |
| SecurityConfig / role checks | MODIFY | Tenant/project assignment, financial authorization; CORS omits Idempotency-Key |
| DealService / deals / project IDs | MODIFY | Reuse deals as projects; immutable agreement versions and rail; ADMIN can currently accept for vendor |
| MilestoneService / milestones / deliverables | MODIFY | Preserve evidence/work flow; freeze allocation; separate work from custody status |
| PaymentService / RazorpayService / payments | MODIFY | Explicit fiat fallback only; never credit blockchain custody |
| EscrowService / escrow_accounts | MODIFY | Preserve fiat model; isolated blockchain read model |
| escrow_ledger | REPLACE for new accounting | Preserve history; currently single movement/balance log, not balanced double entry |
| DisputeService / disputes | MODIFY | Reuse cases, add authorized on-chain adjudication |
| AuditService / audit_logs | MODIFY | Correlation, redaction, retention, append-only enforcement |
| Notifications / deal email listener | KEEP / MODIFY | Settlement messages behind durable outbox and finality |
| Finance/Admin controllers and dashboards | MODIFY | Paginated indexed read models and reconciliation |
| frontend/app/kyc/page.tsx | REPLACE verification behavior | Local step state, no provider decision; cannot authorize funding |
| PostgreSQL / JPA / Flyway V1-V7 | KEEP / MODIFY | Additive migrations, never rewrite applied files |
| FlywayConfig | REPLACE automatic repair (planned) | Startup repair/history deletion requires reviewed recovery instead |
| Redis / Compose | KEEP | Ephemeral coordination only |
| Render / Docker deployment | KEEP / MODIFY | Preserve; separate workers and contract releases later |
| .github/workflows/ci.yml / tests | MODIFY | Keep Java/frontend gates; add contract validation, no auto-deployment |
| Root scripts/demo reports/logs/database.json | MODIFY / REMOVE (planned) | Inventory generated data; demo artifacts are not settlement proof; no deletion now |
| Contracts/gateway/indexer/signers/ramps/compliance/journals/reconciliation | NEW | Missing from authoritative backend |

## Blocking findings

1. PaymentService.verifyPayment/processWebhook call EscrowService.fundEscrow after Razorpay verification. They update DB custody without chain evidence. A rail discriminator and finalized-event projector must precede blockchain activation.
2. EscrowService.releaseEscrow subtracts DB balance, appends RELEASE, marks milestone COMPLETED and notifies vendor. It executes no token transfer or bank payout. These records do not prove settlement.
3. Fiat release checks deal aggregate balance, not protected funding for that milestone. New contracts must enforce allocation isolation.
4. DisputeController GET collection/deal routes have no party/admin authorization beyond authentication. MilestoneService permits any PROJECT_MANAGER to review without assignment. Fix before integrating signer or private evidence workflows.
5. Webhook signature validation exists; durable event-ID deduplication/timestamp replay handling does not. Amount/currency validation permits absent values. Never reuse this as chain settlement evidence.
6. DisputeService.resolveDispute restoration condition excludes DISPUTED, leaving such deals disputed after case closure. Closing a case must never itself move chain funds.
7. No true double-entry accounting, finality/reorg handling, nonce recovery, durable financial queue, ramp settlement, verified KYC gating or provider reconciliation found.
8. FlywayConfig calls repair and can delete history; V7 reconstructs balances without mismatch review. Do not repeat for blockchain accounting.
9. Admin/finance use findAll aggregation; frontend monetary types use number/Number. Retain Java BigDecimal, add integer base-unit handling.
10. Tests primarily use H2/mocks. Test profile disables Flyway; separate clean migration test uses H2 PostgreSQL mode. This does not prove PostgreSQL locking, RLS or real Redis/provider operation.

No independent audit, legal approval, secret-history scan, load SLOs or custody proof supplied. Config files are deployment intent, not live evidence. Baseline execution outcomes are recorded in PHASE_1_REPORT.md.
