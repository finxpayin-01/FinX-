# FinX blockchain

PRODUCTION READINESS: NOT YET APPROVED.

This folder contains the blockchain-specific FinX work.

- `contracts/`: Solidity escrow contracts, Foundry configuration, pinned npm tooling and contract tests.
- `docs/`: Phase 0 audit/design documents and the Phase 1 validation report.

From `contracts/`:

```sh
npm ci --ignore-scripts
npm run build
npm test
npm run format:check
```

Start with [docs/PHASE_1_REPORT.md](docs/PHASE_1_REPORT.md) for completed scope, validation evidence and remaining production blockers.
