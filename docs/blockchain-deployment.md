# Blockchain deployment

## Configuration

`backend/.env.example` and root `.env.example` contain empty network/address fields. No live configuration is changed by the migration. Backend defaults to BLOCKCHAIN and fails closed until configured.

| Setting | Purpose |
| --- | --- |
| PAYMENT_RAIL | BLOCKCHAIN default; RAZORPAY explicitly enables preserved fiat mutations |
| BLOCKCHAIN_ENABLED | Enable chain integration |
| BLOCKCHAIN_CHAIN_ID / BLOCKCHAIN_RPC_URL | Explicit EVM chain and trusted HTTP RPC |
| ESCROW_FACTORY_ADDRESS | Deployed FinXEscrowFactory, not a per-project escrow address |
| BLOCKCHAIN_CREATOR_ADDRESS | Wallet granted factory CREATOR_ROLE; used by authorized deployment operator |
| BLOCKCHAIN_ARBITRATOR_ADDRESS | Existing immutable contract council for dispute execution |
| USDC_CONTRACT_ADDRESS | Approved test ERC20 address, despite legacy variable name |
| BLOCKCHAIN_TOKEN_SYMBOL / BLOCKCHAIN_TOKEN_DECIMALS | Explicit display symbol and verified on-chain decimals |
| BLOCKCHAIN_CONFIRMATIONS | Required canonical receipt depth, minimum 1 |
| BLOCKCHAIN_START_BLOCK | Deployment block; avoid scanning from genesis in public environments |
| BLOCKCHAIN_NETWORK_NAME / BLOCKCHAIN_TESTNET | User-facing network and test-fund label |
| BLOCKCHAIN_EXPLORER_URL | Trusted HTTPS explorer origin; no credentials/path/query |
| BLOCKCHAIN_WALLET_DOMAIN | Application domain included in ownership challenges |
| BLOCKCHAIN_POLL_MS | Indexer delay, default 10000 ms |

Polygon documents Amoy chain ID as `80002`; configure it explicitly, select an RPC provider and obtain test gas. See [Polygon's Amoy guide](https://support.polygon.technology/support/solutions/articles/82000907114-how-to-add-amoy-network-in-your-wallet-).

Build existing Foundry contracts and deploy registry/factory with reviewed contract governance, dispute council and emergency council. Configure approved token decimals and limits; grant the operator CREATOR_ROLE. Deploying an EOA as a council fails contract validation. Test LocalCouncil is deliberately only a local fixture and must not be deployed as production governance.

Run Flyway V10 using a reviewed database deployment. Existing V1-V9 and fiat records are untouched. Startup now fails on migration checksum/history problems; it does not silently repair them. Take backups and verify against real PostgreSQL before deployment.

Render Blueprint and Docker Compose pass blockchain environment settings to the backend. No frontend RPC secret or backend custody private key is required. No Render service has been deployed as part of this work.

## Onramper sandbox

Set `ONRAMP_WIDGET_PUBLIC_KEY` to a publishable sandbox key, `ONRAMP_WIDGET_URL=https://buy.onramper.dev`, `ONRAMP_ASSET_ID` to the provider's supported asset identifier, and `ONRAMP_V2_PRIVATE_KEY` to a registered Ed25519 PKCS8 signing key through backend secret management. Existing `ONRAMP_WEBHOOK_SECRET` verifies callbacks. These are provider signing credentials, not blockchain custody keys. Do not use a secret API key as a widget publishable key.

The widget URL is locked to the verified buyer wallet and uses the existing Onramper signing service. URLs are short-lived and single-use. A repeated funding request with the same idempotency key returns the existing intent; request a fresh intent for a new checkout attempt. Banxa sandbox is the only enabled provider in this adapter. No production widget is enabled.

Onramper's sandbox does not issue testnet transactions. Use local/test tokens for chain funding independently, and do not enter real card details. See [sandbox limitations](https://docs.onramper.com/docs/testing-overview) and [V2 URL signing](https://docs.onramper.com/docs/widget-sign-a-url-v2).

## Fallback and upgrade

Setting PAYMENT_RAIL=RAZORPAY enables the preserved fiat checkout/services with valid Razorpay credentials. Projects with blockchain escrows remain blocked from fiat funding/release and schedule changes regardless of the global setting. This does not move tokens, convert historical projects, close open chain escrows or merge ledgers. Reconcile and segregate existing blockchain projects before changing the deployment-wide rail. Continue indexing previously created escrows. Do not treat a fallback configuration change as a custody migration.

Existing deployed immutable contracts do not receive the new whole-project dispute lock. New deployments must use the updated artifact. Existing funded contracts require a separately reviewed migration strategy; this code does not move them.
