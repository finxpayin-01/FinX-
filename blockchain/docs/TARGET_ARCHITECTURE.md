# Target architecture

Target design; only isolated contracts are implemented in this checkpoint.

Next.js -> Spring Boot API (JWT + deal authorization) -> Escrow Orchestrator -> Blockchain Gateway / SigningService -> EVM RPC -> versioned factory / immutable FinXEscrow.

INR -> approved on-ramp -> supported stablecoin in buyer wallet -> approve + fundEscrow -> milestone release to fixed vendor wallet -> separately authorized off-ramp -> verified INR bank settlement.

## Domains and trust boundaries

- Application: existing users/deals/milestones/private documents. Immutable accepted agreement version binds amounts/parties. Only random identifiers/hashes on-chain; hashing low-entropy identity data is not anonymization.
- Blockchain: custody source of truth. Factory creator cannot move funds; parties accept deployed terms before funding. No upgrade/sweep. Governance, arbitrator and emergency council require reviewed multisig contracts and separate operators. Code existence does not prove a multisig threshold.
- Web3 infrastructure: replaceable RPC, WalletService and SigningService. Local signer only in development; managed custody/KMS/HSM use key references and operation/chain/contract/amount policy. No arbitrary client-supplied transaction endpoints. Persist signed hash/nonce before broadcast; retries query or rebroadcast same bytes, replacements retain intent identity.
- Gateways: OnRampProvider, OffRampProvider, BankPayoutProvider. Webhooks authenticate messages, not custody or bank settlement. Verify raw bytes/signature/timestamp/event ID and transaction attributes; store encrypted inbox with retention controls.
- Compliance/risk: KYC/KYB/sanctions/monitoring decisions, limits and holds. Missing/expired decision blocks funding. Ordinary ADMIN is not a signing authority.

PostgreSQL owns durable intents, outbox, journal and confirmed read models. Redis only accelerates rate limits/locks/jobs; losing Redis must not duplicate money movement. Notifications derive from finality and separate verified payout status.

## Indexing and recovery

Poll bounded RPC log ranges from persisted per-chain cursors; WebSockets are optional hints. Validate registered factory/emitter, chain/token/amount, receipt success, canonical block hash and configured confirmation threshold. Commit event identity (chain, tx hash, log index), projection/journal and cursor atomically. Retain canonical observation history.

Before finality, invalidate/replay reorged observations. Deep reorg after finality halts dependent payouts, opens manual review and appends compensating journals. Never delete financial history. Payout deduplication uses immutable settlement intent, not a log hash. Provider confirmation completes conversion state; only finalized contract funding events prove custody.

## APIs, precision and scale

Preserve /api fiat conventions. Proposed /api/v1/escrows, funding-session, milestone release/dispute, settlements, webhooks and admin/reconciliation return ApiResponse. Mutations require tenant/deal authorization and Idempotency-Key scoped by actor/operation with canonical request hash and stored response. Reusing a key with changed payload conflicts.

Base units use BigInteger and numeric strings; fiat uses BigDecimal. Chain/token identity, decimals, limits, RPC/explorer/finality are configuration. No asset enabled by default. Dashboards use indexed paginated DB read models, never repeated RPC calls. Million-user capacity requires measured SLO/load evidence.

Source references: [OpenZeppelin primitives](https://docs.openzeppelin.com/contracts/5.x/api/utils), [Foundry installation](https://getfoundry.sh/getting-started/installation).
