# FinX blockchain migration engineering report

Verified locally on 2026-09-19. No changes were committed or deployed. No real funds, user private keys or production database were used by the chain E2E harness.

## A. Repository Audit

The authoritative backend was Spring Boot, with existing JWT/RBAC, deals as projects, milestones/deliverables, PM assignments, disputes, fiat payments and an internal escrow ledger. Next.js provided role dashboards and Razorpay checkout. Flyway V1-V9 and existing Foundry escrow/factory/asset-registry contracts were retained. Earlier Node backend and synthetic settlement artifacts were not treated as settlement evidence. See [the pre-implementation audit](blockchain-migration-audit.md).

## B. Architecture Implemented

PaymentRailPolicy selects the default BLOCKCHAIN rail or explicit RAZORPAY fallback. BlockchainEscrowRail constructs authorized, persistent transaction intents. Browser wallets sign; the backend does not hold a custody private key. Web3j polls canonical receipts and confirmed contract snapshots, stores events and projects token accounting into the application. Reorgs quarantine affected records for review. See [architecture](blockchain-architecture.md) and [state/workflow specification](blockchain-escrow-flow.md).

## C. Razorpay Status

Razorpay services, dependencies, controllers, verification/webhook code, historical records and tests remain. PAYMENT_RAIL=BLOCKCHAIN rejects fiat create/verify/webhook/fund/release mutations before provider use, and normal funding screens do not load checkout. PAYMENT_RAIL=RAZORPAY enables the existing rail with its credentials, but projects already assigned blockchain escrow still cannot acquire fiat funding or release. This is not an automatic migration of open positions.

Unit tests assert zero interactions with the mocked Razorpay provider at guarded boundaries. The real local chain E2E runs with empty Razorpay credentials, checks rejected fiat order creation and verifies no fiat payment records. Its report does not invent a network-interception invocation counter.

## D. Smart Contract

Reused `blockchain/contracts/src/FinXEscrow.sol`, `FinXEscrowFactory.sol` and `SupportedAssetRegistry.sol`. Existing creation, two-party acceptance, full funding, milestone submission/approval/release, voluntary refund, dispute arbitration and cancellation remain. SafeERC20, ReentrancyGuard, fixed recipients, asset restrictions, council roles and pause controls are retained. Normal milestone release now additionally rejects any project with an unresolved on-chain dispute.

Existing immutable deployments do not receive this change automatically. `test/LocalCouncil.sol` is an owner-executed local fixture, not production governance.

## E. Blockchain Configuration

Explicit chain ID, RPC, factory, token/decimals, creator, arbitrator, confirmations, start block, explorer, wallet domain and testnet label are documented in env examples. Amoy is the intended public testnet; the verified E2E used chain 31337 and a newly deployed six-decimal test token. RPC chain identity, token decimals, factory version and escrow terms are checked. No real chain configuration was applied. See [deployment](blockchain-deployment.md).

## F. Database Changes

Added V10 only: `blockchain_wallets`, `blockchain_escrows`, `blockchain_allocations`, `blockchain_transactions`, `blockchain_events`, `blockchain_funding_intents`. Atomic amounts use NUMERIC(78,0) and string JSON representation. Unique operation, idempotency, transaction and event constraints plus row locks protect repeated work. Transaction replacement preserves the previous record. Indexed topics/data, canonical checkpoint and audit records support investigation.

V1-V10 migrated successfully on clean H2 in PostgreSQL compatibility mode. Real PostgreSQL migration/locking validation remains outstanding. No existing live database was altered.

## G. Backend Changes

Added wallet ownership challenges, scoped APIs, immutable token schedules, funding intents, exact calldata construction, intent/hash tracking, verified same-nonce replacement, confirmation polling, bounded event backfill, reconciliation, admin health and transaction views. Workflow guards require confirmed funding and keep dispute resolution tied to chain outcomes. Application dispute creation and on-chain freeze remain distinct asynchronous actions.

Existing Onramper signing is reused for a wallet-bound, Ed25519-signed sandbox URL. Signed webhook inbox events are deduplicated signals, never settlement proof. An off-ramp interface and explicitly simulated/unavailable adapters do not claim bank settlement.

## H. Frontend Changes

Buyer/vendor project and escrow screens now show token allocations, exact totals, wallet actions, hashes, confirmation counts and pending/confirmed/reverted state. Admin can inspect reconciliation and recent transaction states, and prepare fixed-outcome council proposals. PM/finance can inspect scoped chain state without acquiring signing authority. Legacy fiat UI remains behind the rail selector; fiat history is labeled separately. Eight desktop/mobile role cases were tested with fixture APIs; real wallet extension popups were not automated.

## I. End-to-End Money Flow

Fiat -> On-ramp -> Stablecoin -> Escrow -> Milestone lock -> Approval -> On-chain release -> Vendor -> Off-ramp -> Fiat.

| Segment | Verified status |
| --- | --- |
| Fiat purchase / Onramper | Signed sandbox adapter and unit tests; no live credentials, checkout or purchase exercised |
| Stablecoin wallet funding | Locally minted test ERC20 only; no fiat conversion |
| Escrow funding / approval / release | Actual Anvil EVM transactions and receipt-confirmed backend projections |
| Vendor receipt | Actual local token balance checked after 2.5 TEST release |
| Dispute refund | Council-executed local transaction; 2.5 TEST returned to original buyer |
| Restart / indexing | Persistent temporary database reopened; no duplicate credit |
| Off-ramp / bank | Interface plus explicit local simulation; not configured or settled |
| Public Amoy | Not deployed or tested |

Onramper sandbox does not settle testnet tokens. The widget and local token minting are independent test paths, not one completed fiat payment.

## J. Tests

| Check | Result |
| --- | --- |
| Maven test | PASS: 184 tests, 0 failures, 0 errors, 0 skipped |
| Maven clean package | PASS, including the full regression suite |
| Clean Flyway migration | PASS: V1-V10 on H2 |
| Frontend npm install | PASS; no reported vulnerabilities |
| Frontend lint | PASS |
| Frontend production build | PASS; 48 static pages generated |
| Foundry build | PASS; contract sizes below deployment limits |
| Foundry tests | PASS: 33 tests; 512 fuzz cases; invariant 128 runs / 8192 calls |
| Local application/chain E2E | PASS: 24 checks |
| Playwright role/viewport smoke | PASS: 8 cases, 1440x1000 and 390x844, Edge |
| git diff --check | PASS |

Maven used the repository's cached 3.9.9 executable because the Windows wrapper cache lookup failed. Early E2E attempts exposed a malformed build artifact and Spring repository proxy field access; clean packaging and method-based JDBC access resolved them. The Windows launcher now terminates its owned child process trees. Final build/test commands above exited successfully.

Evidence: root `e2e_blockchain_report.json`, `e2e_blockchain_ui_report.json`, `backend/target/surefire-reports/`, and `scratch/blockchain-ui/`. Generated reports/screenshots are ignored by Git. UI fixtures are not blockchain settlement evidence. Render/Docker deployment and browser-wallet signing were not exercised.

## K. Security Findings

Fixed whole-project dispute release exposure, receipt-only false verification, hardcoded provider completion, unguarded fiat mutation in blockchain mode, mixed-rail project accounting, mutable allocation schedules, dispute read/milestone scope gaps, unsafe Flyway repair/history deletion, swallowed audit persistence failures and database-URI error leakage. Idempotency, precision, membership, concurrency, confirmed receipt mismatch/revert and reorg quarantine have targeted tests.

Residual risks include external smart-contract audit, true PostgreSQL contention, trusted RPC behavior, bytecode release allowlists, manual deep-reorg recovery, wallet rotation/EIP-1271, audit database immutability, stronger privileged authentication and existing JWT localStorage exposure. A submitted application dispute cannot prevent a direct chain release until its chain freeze is mined. See [security limits](blockchain-security.md).

## L. User Action Required

Nothing external is needed to rerun the local chain tests. For public Amoy, provide approved RPC access and authorize deployment/governance and party wallets with test gas; record the resulting reviewed factory/token/council addresses. For provider sandbox testing, obtain the provider-issued publishable test key, registered signing credentials, supported asset identifier and webhook secret. Supply secrets through secret management, not source control or chat.

Production provider onboarding, bank relationships and any managed custody/signer arrangement require separate external approval and credentials. They are not assumed or fabricated here.

## M. Production Blockers

**LOCAL SANDBOX MVP: verified. TESTNET MVP COMPLETE: not claimed, because public Amoy has not been exercised. PRODUCTION FINANCIAL INFRASTRUCTURE: not ready.**

Remaining gates: independent contract/application audits; production custody/signing and council governance; RPC redundancy; provider KYB/KYC and AML controls; legal/regulatory/tax review; off-ramp and bank reconciliation; real PostgreSQL validation; monitoring/alerts; incident response; disaster recovery; key recovery/rotation; external immutable audit retention; financial accounting and public-network end-to-end tests. Technical escrow does not establish regulated escrow or licensed custody status.
