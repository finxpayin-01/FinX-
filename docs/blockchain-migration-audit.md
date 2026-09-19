# Blockchain migration audit

Baseline: `4c923a2`; clean main worktree. Source audit performed 2026-09-19 before implementation. No live database or secret files read.

## Current architecture

- Next.js 16.3 / React 19 App Router, Axios JWT client, corporate/vendor/PM/admin/finance dashboards. Existing payment checkout directly calls Razorpay; escrow screens read fiat ledger totals.
- Spring Boot 3.4.3, Java 17 source target, Web3j 4.12.3, JPA, PostgreSQL, Flyway V1-V9. Render Docker builds Java. Legacy Node/Prisma server immediately throws and remains disabled.
- `Deal` is the project aggregate. Milestones, deliverables (attachment URLs), disputes, PM invitations, notifications and audit records are reusable. There is no separate Project or wallet entity. JWT authentication and assigned-PM review exist; Redis is used for rate limiting, not financial correctness.
- `PaymentService.createPaymentOrder`, `verifyPayment`, and `processWebhook` are Razorpay entry points. Verification credits `EscrowService.fundEscrow`. `releaseEscrow` subtracts database balance and completes milestones without an external payout. Preserve these as explicitly selected legacy fiat behavior.
- V8 adds on/off-ramp records and webhook inbox tables, but OnRampController has TODO persistence/ownership checks and ignores its idempotency key. Provider status is hardcoded completed. BlockchainSettlementVerifier ignores expected asset/amount and treats any successful receipt as settlement.
- Existing Foundry contracts: FinXEscrowFactory, FinXEscrow, SupportedAssetRegistry. Fixed buyer/vendor/token and allocations, both-party acceptance, full funding, buyer approval, party release, vendor refund, contract arbitrator, emergency pause, SafeERC20 and reentrancy guards. Factory creation requires CREATOR_ROLE. Direct ERC20 donations are not funding.
- Existing contract suites cover lifecycle, adversarial tokens, boundaries and invariants. Existing JS reconciliation script uses synthetic fixtures; historical report claims are not proof of application-chain integration.

## Findings and conflicts

1. Critical migration boundary: database-only release must be inaccessible in blockchain mode, including old endpoints.
2. Receipt status is insufficient: require correct chain, contract, canonical block, confirmation depth, immutable terms and exact event/state accounting.
3. No durable indexer, transaction intents, wallet ownership or blockchain read model currently exists.
4. INR deal amounts cannot silently become USDC. Freeze a separately accepted token allocation schedule; retain original commercial currency/value.
5. A PM review does not grant buyer wallet authority. Factory creator cannot release funds. Preserve these boundaries.
6. Dispute collection/deal reads permit cross-user access; requested milestone is not checked against the deal. Database disputes do not freeze on-chain custody until a chain dispute is confirmed.
7. AuditService swallows persistence errors. FlywayConfig automatically repairs/deletes migration history. Both conflict with durable financial evidence.
8. Browser JWT localStorage, incomplete provider onboarding and lack of independent security review remain production risks.
9. Existing per-milestone contract dispute isolation differs from requested whole-project release freeze; normal release should block while any allocation is disputed.

## Migration strategy and change inventory

1. Add rail policy and pause legacy mutations while keeping history/services/tests. Fail closed with missing blockchain configuration.
2. Reuse contracts, tighten project dispute release lock and extend Foundry tests. Retain factory CREATOR_ROLE: buyer creates application intent; authorized operator deploys immutable terms, parties separately accept.
3. Add V10 blockchain escrows, allocations, verified wallets, transaction intents, indexed events and funding intents. UUID ownership, numeric(78,0) atomic amounts, unique operation/event keys and transactional locks. Never rewrite V1-V9 or fiat balances.
4. Add Spring `blockchain` module for configuration, ABI construction, wallet proofs, authorized intents, receipt verification, bounded restartable indexing and reconciliation. Wallet signing stays outside backend; no plaintext platform custody key introduced.
5. Integrate existing milestone/dispute workflows and add blockchain views to project/funding and admin screens. Preserve hidden Razorpay checkout. Show submitted/pending/confirmed/reverted distinctly.
6. Reuse OnRampProvider only through persisted, owned funding intents; provider completion never credits escrow. Off-ramp remains explicitly unavailable without a real adapter.
7. Run backend, contract and frontend checks, plus local-chain E2E with a secret-free JSON report. Document all untested or externally blocked paths honestly.

Planned files: existing payment/escrow/milestone/dispute services and controllers; new `backend/src/main/java/com/finx/blockchain/*`; additive Flyway V10; application configuration and env templates; existing contracts/tests; frontend escrow component/service and role pages; scripts/blockchain; README and requested docs. Deployment configuration only as needed for environment wiring. No auth rewrite, legacy deletion, real-fund transfer or production deployment.
