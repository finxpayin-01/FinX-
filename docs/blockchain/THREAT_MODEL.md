# Threat model

Assets: escrow funds, settlement destinations, agreement integrity, signing authority, append-only accounting and confidential evidence.

| Threat | Control / required integration | Residual risk |
| --- | --- | --- |
| Compromised admin/backend creator | No sweep/upgrade/arbitrary recipient; both parties accept immutable terms | Compromised party key can exercise its authority |
| Governance compromise | Config/pause authority only, cannot rewrite recipients | Can censor funding/exit; multisig review required |
| Arbitrator compromise | Only disputed allocation, whole amount to buyer OR vendor | Can choose unfairly; independent reviewed multisig/legal process |
| Reentrancy/malicious token | SafeERC20, nonReentrant money paths, effects before transfer, exact balance deltas | Only approved non-fee/non-rebasing assets; issuer freeze/depeg/proxy changes remain |
| Double release/refund race | Terminal transitions and immutable milestone allocations | Dispute vs release follows chain ordering; no retrospective clawback |
| Wrong wallet/terms/token | Immutable terms, party acceptance, asset allowlist | Wallet/provider identity verification remains off-chain |
| Partial funding/direct donation | Full funding only, no top-up; raw transfers uncredited | Donations/wrong tokens unrecoverable in v1; UX must prevent |
| Gas DoS | Maximum 64 positive, unique milestones; bounded loops | Profile on selected network |
| Forged/replayed webhook | Future raw signature/timestamp/unique inbox | Not implemented by contracts; existing fiat gaps remain |
| RPC timeout/reorg/lies | Future durable intents/canonical receipts/finality/reconciliation | Deep reorg halts payout for review |
| DB tampering/unbalanced ledger | Future restricted append-only journals and balanced posting | Existing ledger is not double entry; owner remains trusted |
| PII/auth bypass | Private evidence, random hashes, tenant authorization | Existing dispute reads/PM scope/localStorage need remediation |
| Supply chain | Exact compiler/dependency pins and lockfile | Tooling is not independent audit |
| Provider insolvency/bank rejection | Separate released vs INR settled, provider recovery | Software cannot guarantee redemption/legal authorization |

Pause blocks funding/submission/approval/release/refund/resolution. Parties can still raise disputes. Unpause is explicit; no time-based unilateral refund. Loss of immutable keys or permanent token freeze can strand funds; reviewed wallet recovery is a deployment blocker, not a reason to add admin bypass.

Review multisig thresholds/ownership, ERC20 behavior, ordering/front-running, denial of service, compiler advisories and transaction simulation. PRODUCTION READINESS: NOT YET APPROVED.
