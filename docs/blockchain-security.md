# Blockchain security and limits

This is technical smart-contract escrow, not proof of regulated escrow, licensed custody or payment authorization. Local tests do not establish production readiness.

## Controls implemented

- Existing SafeERC20 and ReentrancyGuard, exact token balance deltas, positive allocations, fixed recipients, asset registry, contract councils, delayed governance and local/factory pause retained.
- All normal releases now block while any allocation is disputed. No owner sweep, arbitrary payment destination or upgrade of funded contracts added.
- Legacy fiat mutation paths fail closed under blockchain mode. Chain amounts never enter the Razorpay ledger.
- Exact atomic amounts, immutable schedule, on-chain recipient/amount/state reconciliation, canonical receipts and confirmation depth.
- Wallet challenge proofs, project membership checks, PM review without signing delegation, unique operation/event constraints, row locking and replay-safe projection.
- Dispute reads now require admin or project access. Cross-project milestone disputes reject. Case closure requires confirmed chain settlement for blockchain escrow.
- Audit persistence failure aborts financial mutation. Startup no longer repairs/deletes Flyway history. Database connection parse errors no longer echo URI credentials.
- Provider sandbox URLs are server-signed using standard Java Ed25519. Webhooks are signature-checked and deduplicated, but are only signals. Receipt-only legacy verifier can no longer return VERIFIED without recipient evidence.

## Remaining risks and production blockers

1. Independent contract audit and backend/frontend assessment, including real PostgreSQL concurrency and migrations. H2 tests do not prove PostgreSQL production behavior.
2. Public Amoy deployment validation and then a separately approved production deployment; reviewed artifact hashes, governance/council multisigs, thresholds and key recovery.
3. Noncustodial wallet identity binding, wallet rotation and EIP-1271 support; MPC/KMS/HSM integration if adopting managed custody. No plaintext backend custody key is supported.
4. RPC redundancy, chain/provider disagreement, deep reorg restoration, transaction replacement when the original hash is unavailable, dropped transaction operator procedures and alerting.
5. Provider production onboarding, verified asset/network capability, KYB/KYC, AML and sanctions controls, incident response, customer recovery and fiat off-ramp settlement controls. Sandbox widget credentials alone do not satisfy these.
6. Independent legal/regulatory/tax and custody review, banking relationships, retention rules and financial accounting. Token balances are not bank deposits and do not imply INR convertibility.
7. Database role permissions, immutable audit retention/external audit sink, disaster recovery, backups, performance/load testing and stronger privileged authentication. Existing AuditLog API is append-oriented; database superusers are not prevented from modifying it.
8. Existing JWT localStorage exposure to XSS, existing broader application authorization and rate-limit coverage. Financial wallet signing is a second boundary, not a cure for application compromise.
9. A database dispute and its on-chain transaction are asynchronous. Only a mined dispute can stop a direct on-chain release. Fixed recipients and contract ordering still apply.
10. Factory bytecode/version allowlisting and signed deployment manifests require independent release controls. RPC is a trusted configured service; no user-submitted RPC URL is accepted.

## Emergency procedure

Use the separately controlled emergency council/factory pauser to pause affected contracts. Preserve DB and chain evidence. Stop new funding/operations, reconcile using independent RPCs, review canonical checkpoints and proposed corrective actions. Do not delete events, alter historical amounts, repair Flyway checksums or insert successful receipts. Resume only after incident review and governance authorization.
