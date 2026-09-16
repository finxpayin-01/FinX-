# FinX contracts - Phase 1

PRODUCTION READINESS: NOT YET APPROVED. No deployment, provider integration or independent audit is included.

From this directory (Node 20+):

```sh
npm ci --ignore-scripts
npm run build
npm test
npm run format:check
npm run test:gas
```

Pins: Foundry 1.7.1, OpenZeppelin 5.6.1, Solidity 0.8.24, Paris EVM, optimizer 200. No chain ID, token, RPC, deployed address or private key is embedded. npm lockfile includes integrity-pinned platform binaries. `.npmrc` disables dependency lifecycle scripts because Foundry's npm postinstall uses Unix shell syntax. The launcher runs the platform binary directly and propagates failure: upstream 1.7.1's wrapper can mask failed tests. Do not use its bare `forge` shim for CI.

## Contracts

- SupportedAssetRegistry: initially empty chain-local allowlist with decimal/amount checks and independent deposit/withdraw controls.
- FinXEscrowFactory: role-limited creation, unique escrow/deal hash, version 1 and global pause. Delayed two-step admin transfer. Never upgrades existing escrows.
- FinXEscrow: immutable party/asset/allocation terms, both-party acceptance, atomic full funding, buyer approval, fixed-vendor release, voluntary vendor refunds, disputed-only arbitration, local pause and accounting events.

All business operations use random internal hashes; documents and personal data remain off-chain. Amounts are integer base units, including tokens with six decimals. All state-changing escrow operations have reentrancy protection. Factory creator and ordinary application ADMIN cannot release funds. Governance, arbitrator and emergency council must be distinct reviewed multisig contracts in deployment; checking contract code does not verify that governance arrangement. TestCouncil/TestToken exist only under test/ and are not production examples.

## Explicit constraints

At most 64 milestones, no partial funding/split settlement/fees/delegated project-manager signing/recipient replacement/upgrade. Arbitrator resolves whole disputed allocations only. Both parties can cancel only before funding. Pause can halt exits; token issuer freeze/rebase/depeg remain risks. Immutable key loss requires a reviewed custody recovery model before deployment.

Only `fundEscrow()` recognizes custody: a raw ERC20 transfer is an uncredited donation. Direct transfers and wrong tokens are unrecoverable in v1. Ramps must deliver to buyer wallet, then buyer approves the exact total and funds. Exit goes to fixed vendor wallet, requiring a separate vendor-authorized off-ramp transfer. Nothing here marks INR bank settlement.

Tests cover lifecycle, role/state violations, replay, donation exclusion, false/no-return/fee/callback tokens, asset pauses, boundaries and delayed governance. Fuzz uses 512 amounts/outcomes; invariant handler uses 128 runs x 64 steps and checks actual balances plus per-milestone totals. Expected invalid actions are caught by the handler; unexpected handler reverts fail the suite. These are initial tests, not an independent audit or full Phase 2 completion.

Read [contract design](../docs/blockchain/CONTRACT_DESIGN.md), [architecture](../docs/blockchain/TARGET_ARCHITECTURE.md), [threat model](../docs/blockchain/THREAT_MODEL.md) and [phase report](../docs/blockchain/PHASE_1_REPORT.md). Public testnet deployment belongs to Phase 3 after test/security review and actual external configuration.
