# Blockchain E2E testing

## Commands

Backend: `cd backend`, then `mvn test` and `mvn clean package` (or the repository wrapper). On this Windows workspace the wrapper could not resolve its cache; the cached Maven 3.9.9 `bin/mvn.cmd` with `-Dmaven.repo.local=.m2/repository` was used. See the final migration report for actual outcomes.

Frontend: `cd frontend`, `npm install`, `npm run lint`, `npm run build`.

Contracts: `cd blockchain/contracts`, `npm ci`, `npm run build`, `npm test`.

Application/chain E2E: after backend packaging and contract compilation, run `npm run test:e2e` from `blockchain/contracts`. Requires Java and Node plus the pinned Anvil npm binary. The launcher hides child windows and stops its own processes on completion.

Browser smoke test: run a local frontend with a localhost API URL, then `node scripts/ui-smoke.mjs` from `blockchain/contracts`. Playwright uses installed Edge by default (`UI_BROWSER` and `UI_URL` override browser channel and URL). All API responses are local fixtures; external requests are blocked. Desktop/mobile screenshots go to `scratch/blockchain-ui/` and assertions to `e2e_blockchain_ui_report.json`. This verifies rendering and state labels, not real wallet or provider settlement.

## What the E2E actually runs

The harness creates a new mnemonic in memory, starts a localhost-only Anvil chain, deploys test ERC20/registry/factory and local test council contracts, and starts the packaged Spring application with an isolated temporary H2 database. It explicitly disables `.env` import, external provider credentials, email and Razorpay. It does not use the user's configured database or real wallet keys.

It registers buyer/vendor, logs in the generated-password administrator, proves wallet ownership, creates a deal/milestones, proposes token allocations, deploys escrow, accepts terms, mints test tokens, approves ERC20 spending, funds, submits work, approves, releases, disputes/refunds and restarts the backend. Assertions compare actual token balances, API read model, canonical confirmations, audit records, IDOR checks, unauthorized/double-release reverts and the absence of fiat payment records.

`e2e_blockchain_report.json` is generated at repository root with explicit environment and pass/fail checks. It contains no wallet seed/private keys, JWTs, generated passwords or provider secrets. The existing gitignore excludes runtime reports; inspect the local report directly.

## Scope and gaps

This is a local EVM application E2E, not an Amoy transaction, a live on-ramp payment, a bank payout or proof of production compliance. Backend tests also cover token precision, duplicate requests/events, receipt mismatch, revert/finality, reorg quarantine, provider signature verification and signal-only webhooks. Foundry tests include adversarial tokens, reentrancy, access control and fuzz/invariant cases.

Real PostgreSQL contention, provider dashboard acceptance of signed URLs, browser extension wallet popups, public-network fee behavior, managed signing and deep-reorg disaster recovery need separate environment validation. Never replace a failed test with a mocked successful blockchain receipt in the integration report.
